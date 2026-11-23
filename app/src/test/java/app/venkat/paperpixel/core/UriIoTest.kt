package app.venkat.paperpixel.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import app.venkat.paperpixel.FakePdfEngine
import app.venkat.paperpixel.PdfUtilApp
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
        assertEquals(PickedFile(uri, "Holiday photo.jpg", 12345L), UriIo.describe(context, uri))
        val noSize = Uri.parse("content://named.test/doc/nosize")
        assertEquals(PickedFile(noSize, "Report.pdf", null), UriIo.describe(context, noSize))
    }

    @Test
    fun describeFallsBackToLastPathSegment() {
        val uri = Uri.parse("content://unknown.authority/tree/primary%3ADocs%2Fscan.pdf")
        val picked = UriIo.describe(context, uri)
        assertEquals("scan.pdf", picked.name)
        assertNull(picked.sizeBytes)

        val bare = Uri.parse("content://unknown.authority")
        assertEquals(UriIo.FALLBACK_NAME, UriIo.describe(context, bare).name)

        val file = tmp.newFile("local.pdf").apply { writeBytes(ByteArray(7)) }
        val fromFile = UriIo.describe(context, Uri.fromFile(file))
        assertEquals("local.pdf", fromFile.name)
        assertEquals(7L, fromFile.sizeBytes)
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
            UriIo.copyToFile(context, uri, dest) { ++polls > 2 }
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
