package app.venkat.offkit.reader

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.ReaderOpenResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

/**
 * W7: a PDF opened in the reader from providers that answer the name query in the ways seen in
 * the wild, then shared. For each: the name the reader shows, the name the share carries, and
 * what a receiving app reads back from the share Uri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderShareNameTest {
    private lateinit var app: Application
    private lateinit var engine: ExecutorCoroutineDispatcher
    private lateinit var reader: PdfReader
    private val bytes = "%PDF-1.7\nnamed bytes\n".toByteArray()

    /**
     * One provider, several behaviours chosen by the Uri's first path segment. Every Uri's
     * content is [source]. The real name of every document is [REAL_NAME] (or [NO_EXT_NAME]).
     */
    class WildProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
            val asked = projection?.toList()
            return when (uri.pathSegments.first()) {
                // (a) no _display_name column at all, whatever is asked.
                "noname" -> MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(SIZE)) }
                // (b1) throws when the projection names a column it does not know (here: _size).
                "strict" -> {
                    if (asked != null && asked.any { it != OpenableColumns.DISPLAY_NAME }) {
                        throw IllegalArgumentException("Invalid column ${asked.first { it != OpenableColumns.DISPLAY_NAME }}")
                    }
                    MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf<Any?>(REAL_NAME)) }
                }
                // (b2) returns no cursor when the projection names a column it does not know.
                "nullcursor" -> if (asked != null && OpenableColumns.SIZE in asked) {
                    null
                } else {
                    MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf<Any?>(REAL_NAME)) }
                }
                // (b3) returns an empty cursor when the projection names a column it does not know.
                "emptycursor" -> if (asked != null && OpenableColumns.SIZE in asked) {
                    MatrixCursor(asked.toTypedArray())
                } else {
                    MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf<Any?>(REAL_NAME)) }
                }
                // (c) ignores the projection and returns its own columns, in its own order.
                "owncolumns" -> MatrixCursor(arrayOf("_id", "mime_type", OpenableColumns.SIZE, "date_modified", OpenableColumns.DISPLAY_NAME))
                    .apply { addRow(arrayOf<Any?>(7L, "application/pdf", SIZE, 1_700_000_000L, REAL_NAME)) }
                // (d) a real name without an extension.
                "noext" -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
                    .apply { addRow(arrayOf<Any?>(NO_EXT_NAME, SIZE)) }
                // (e) no row at all, and the Uri ends in an opaque id.
                "opaque" -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
                // Control: a well-behaved provider.
                else -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
                    .apply { addRow(arrayOf<Any?>(REAL_NAME, SIZE)) }
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun getType(uri: Uri): String = "application/pdf"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

        companion object {
            const val AUTHORITY = "wild.provider.test"
            const val REAL_NAME = "Lease agreement (signed) 2024.pdf"
            const val NO_EXT_NAME = "Invoice 2024"
            const val SIZE = 21L
            lateinit var source: File
        }
    }

    /** What one open-then-share round trip produced. */
    data class Trip(val shown: String, val shared: String, val receiverName: String?, val receiverSize: Long?, val receiverType: String?, val lastSegment: String?)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        (app as PdfUtilApp).engine = FakePdfEngine()
        CoreTestSupport.clearFileProviderCache()
        engine = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        reader = PdfReader(app, PdfJobs(app, engine, Dispatchers.IO, Dispatchers.Unconfined))
        WildProvider.source = File(app.filesDir, "source.pdf").apply { writeBytes(bytes) }
        Robolectric.setupContentProvider(WildProvider::class.java, WildProvider.AUTHORITY)
    }

    @After
    fun tearDown() = engine.close()

    private fun trip(vararg segments: String): Trip = runBlocking {
        val uri = Uri.Builder().scheme("content").authority(WildProvider.AUTHORITY).apply { segments.forEach { appendPath(it) } }.build()
        val doc = (reader.open(uri) as ReaderOpenResult.Opened).document
        val request = reader.prepareShare(doc)
        // As a receiving app reads the shared Uri: a null projection and the explicit
        // {DISPLAY_NAME, SIZE} one must agree; then the type.
        var name: String? = null
        var size: Long? = null
        for (projection in listOf(null, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))) {
            app.contentResolver.query(request.uri, projection, null, null, null)!!.use { c ->
                c.moveToFirst()
                val n = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                val s = c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
                if (name != null) assertEquals("projections agree", name, n)
                name = n
                size = s
            }
        }
        val type = app.contentResolver.getType(request.uri)
        reader.close(doc)
        Trip(doc.name, request.name, name, size, type, request.uri.lastPathSegment)
    }

    private fun assertTrip(expectedName: String, trip: Trip) {
        assertEquals("shared $trip", expectedName, trip.shared)
        assertEquals("receiver DISPLAY_NAME $trip", expectedName, trip.receiverName)
        assertEquals("receiver SIZE $trip", bytes.size.toLong(), trip.receiverSize)
        assertEquals("receiver type $trip", "application/pdf", trip.receiverType)
        assertEquals("receiver last segment $trip", expectedName, trip.lastSegment)
    }

    @Test
    fun wellBehavedProviderKeepsTheName() {
        val t = trip("doc", "42")
        assertEquals(WildProvider.REAL_NAME, t.shown)
        assertTrip(WildProvider.REAL_NAME, t)
    }

    @Test
    fun providerWithoutANameColumnFallsBackToTheUri() {
        val t = trip("noname", "42")
        assertEquals("42", t.shown)
        assertTrip("42.pdf", t)
    }

    @Test
    fun providerThatThrowsOnTheSizeColumn() {
        val t = trip("strict", "42")
        assertTrip(WildProvider.REAL_NAME, t)
    }

    @Test
    fun providerThatReturnsNoCursorForTheSizeColumn() {
        val t = trip("nullcursor", "42")
        assertTrip(WildProvider.REAL_NAME, t)
    }

    @Test
    fun providerThatReturnsAnEmptyCursorForTheSizeColumn() {
        val t = trip("emptycursor", "42")
        assertTrip(WildProvider.REAL_NAME, t)
    }

    @Test
    fun providerThatIgnoresTheProjection() {
        val t = trip("owncolumns", "42")
        assertTrip(WildProvider.REAL_NAME, t)
    }

    @Test
    fun nameWithoutExtensionGetsPdf() {
        val t = trip("noext", "42")
        assertEquals(WildProvider.NO_EXT_NAME, t.shown)
        assertTrip("Invoice 2024.pdf", t)
    }

    @Test
    fun opaqueUriWithNoRow() {
        val t = trip("opaque", "8f3a2b")
        assertEquals("8f3a2b", t.shown)
        assertTrip("8f3a2b.pdf", t)
    }
}
