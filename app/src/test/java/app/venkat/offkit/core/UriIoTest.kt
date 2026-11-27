package app.venkat.offkit.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UriIoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context

    /** Provider answering every query with one row of display name and size. */
    class NamedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
            val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            if (uri.lastPathSegment == "nosize") c.addRow(arrayOf<Any?>("Report.pdf", null))
            else c.addRow(arrayOf<Any?>("Holiday photo.jpg", 12345L))
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        (context as PdfUtilApp).engine = FakePdfEngine()
    }

    @Test
    fun describeReadsNameAndSizeFromProvider() {
        Robolectric.setupContentProvider(NamedProvider::class.java, "named.test")
        val uri = Uri.parse("content://named.test/doc/42")
        assertEquals(PickedFile(uri, "Holiday photo.jpg", 12345L, nameReported = true), UriIo.describe(context, uri))
        val noSize = Uri.parse("content://named.test/doc/nosize")
        assertEquals(PickedFile(noSize, "Report.pdf", null, nameReported = true), UriIo.describe(context, noSize))
    }

    /**
     * W7: refuses a projection that names `_size` (throws, or answers with no row) unless the
     * Uri's first segment is `rows`, where it answers with a size row but no name for that
     * projection. The name alone, or a null projection, gets the name.
     */
    class PickyProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
            val asksSize = projection?.contains(OpenableColumns.SIZE) == true
            return when {
                !asksSize -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf<Any?>("Real name.pdf")) }
                uri.pathSegments.first() == "throws" -> throw IllegalArgumentException("Invalid column _size")
                uri.pathSegments.first() == "rows" -> MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(777L)) }
                else -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test
    fun describeAsksForTheNameAloneWhenTheSizeColumnIsRefused() {
        Robolectric.setupContentProvider(PickyProvider::class.java, "picky.test")
        val thrown = Uri.parse("content://picky.test/throws/42")
        assertEquals(PickedFile(thrown, "Real name.pdf", null, nameReported = true), UriIo.describe(context, thrown))
        val empty = Uri.parse("content://picky.test/empty/42")
        assertEquals(PickedFile(empty, "Real name.pdf", null, nameReported = true), UriIo.describe(context, empty))
        // A size from the first query is kept when the name only comes from a later one.
        val sized = Uri.parse("content://picky.test/rows/42")
        assertEquals(PickedFile(sized, "Real name.pdf", 777L, nameReported = true), UriIo.describe(context, sized))
    }

    @Test
    fun describeFallsBackToLastPathSegment() {
        val uri = Uri.parse("content://unknown.authority/tree/primary%3ADocs%2Fscan.pdf")
        val picked = UriIo.describe(context, uri)
        assertEquals("scan.pdf", picked.name)
        assertNull(picked.sizeBytes)
        assertFalse("a name guessed from the Uri is not reported", picked.nameReported)

        val bare = Uri.parse("content://unknown.authority")
        assertEquals(UriIo.FALLBACK_NAME, UriIo.describe(context, bare).name)
        assertFalse(UriIo.describe(context, bare).nameReported)

        // A file: Uri is never touched on disk: name from the Uri only, size unknown.
        val file = tmp.newFile("local.pdf").apply { writeBytes(ByteArray(7)) }
        val fromFile = UriIo.describe(context, Uri.fromFile(file))
        assertEquals("local.pdf", fromFile.name)
        assertNull(fromFile.sizeBytes)
        assertTrue("a file: Uri's own name counts as reported", fromFile.nameReported)
    }

    @Test
    fun describeSanitisesHostileProviderNames() {
        Robolectric.setupContentProvider(HostileProvider::class.java, "hostile.test")
        val spoof = UriIo.describe(context, Uri.parse("content://hostile.test/doc/spoof"))
        assertEquals("invoicefdp.exe", spoof.name)
        val long = UriIo.describe(context, Uri.parse("content://hostile.test/doc/long"))
        assertEquals(MAX_DISPLAY_NAME_LENGTH, long.name.length)
        assertTrue(long.name.endsWith(".pdf"))
        val path = UriIo.describe(context, Uri.parse("content://hostile.test/doc/path"))
        assertEquals(".._.._etc_passwd", path.name)
        // Only control characters: falls back to the (sanitised) last path segment.
        val blank = UriIo.describe(context, Uri.parse("content://hostile.test/doc/blank"))
        assertEquals("blank", blank.name)
        assertFalse(blank.nameReported)
        assertTrue(spoof.nameReported)
    }

    @Test
    fun onlyContentUrisAreAccepted() {
        assertTrue(UriIo.isContentUri(Uri.parse("content://x/1")))
        assertFalse(UriIo.isContentUri(Uri.fromFile(tmp.root)))
        assertFalse(UriIo.isContentUri(Uri.parse("android.resource://app.venkat.offkit/raw/x")))
        assertFalse(UriIo.isContentUri(Uri.parse("relative/path.pdf")))
        assertFalse(UriIo.isContentUri(null))
    }

    class HostileProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
            val name = when (uri.lastPathSegment) {
                "spoof" -> "invoice\u202Efdp.exe"
                "long" -> "L".repeat(10_000) + ".pdf"
                "path" -> "../../etc/passwd"
                else -> "\u0000\n\u202E"
            }
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf<Any?>(name)) }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test
    fun copyToFileCopiesBytes() {
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        val uri = CoreTestSupport.contentUri(context, "big.bin", bytes)
        val dest = tmp.root.resolve("copy.bin")
        assertEquals(bytes.size.toLong(), UriIo.copyToFile(context, uri, dest))
        assertArrayEquals(bytes, dest.readBytes())
    }

    @Test
    fun copyToFileHonoursCancellation() {
        val uri = CoreTestSupport.contentUri(context, "big.bin", ByteArray(500_000))
        val dest = tmp.root.resolve("copy.bin")
        var polls = 0
        try {
            UriIo.copyToFile(context, uri, dest, isCancelled = { ++polls > 2 })
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        assertFalse(dest.exists())
    }

    @Test
    fun copyToFileMissingUriThrowsIOException() {
        val missing = Uri.fromFile(tmp.root.resolve("does-not-exist.pdf"))
        val dest = tmp.root.resolve("copy.bin")
        try {
            UriIo.copyToFile(context, missing, dest)
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertFalse(dest.exists())
    }

    @Test
    fun copyToUriWritesBytes() {
        val src = tmp.newFile("src.pdf").apply { writeBytes("hello pdf".toByteArray()) }
        val sink = ByteArrayOutputStream()
        val dest = Uri.parse("content://dest.test/out.pdf")
        shadowOf(context.contentResolver).registerOutputStream(dest, sink)
        assertEquals(9L, UriIo.copyToUri(context, src, dest))
        assertArrayEquals("hello pdf".toByteArray(), sink.toByteArray())
    }

    @Test
    fun copyToUriWriteFailureThrowsIOException() {
        val src = tmp.newFile("src.pdf").apply { writeBytes(ByteArray(10)) }
        val dest = Uri.parse("content://dest.test/broken.pdf")
        shadowOf(context.contentResolver).registerOutputStream(
            dest,
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
            },
        )
        try {
            UriIo.copyToUri(context, src, dest)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(true)
        }
    }
}
