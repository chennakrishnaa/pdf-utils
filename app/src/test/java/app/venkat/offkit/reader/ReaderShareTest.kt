package app.venkat.offkit.reader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.R
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.ReaderDocument
import app.venkat.offkit.core.ReaderOpenResult
import app.venkat.offkit.feature.reader.ReaderActionEvent
import app.venkat.offkit.feature.reader.ReaderActions
import app.venkat.offkit.ui.components.sharePdfIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderShareTest {
    private lateinit var app: Application
    private lateinit var engine: ExecutorCoroutineDispatcher
    private lateinit var reader: PdfReader
    private val bytes = "%PDF-1.7\nshared bytes\n".toByteArray()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        (app as PdfUtilApp).engine = FakePdfEngine()
        CoreTestSupport.clearFileProviderCache()
        engine = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        reader = PdfReader(app, PdfJobs(app, engine, Dispatchers.IO, Dispatchers.Unconfined))
    }

    @After
    fun tearDown() = engine.close()

    private fun open(name: String): ReaderDocument = runBlocking {
        (reader.open(CoreTestSupport.contentUri(app, name, bytes)) as ReaderOpenResult.Opened).document
    }

    private fun sharedFiles() = File(app.cacheDir, "shared").walkTopDown().filter { it.isFile }.toList()

    @Test
    fun shareCopiesTheExactBytesAndBuildsTheResultScreensIntent() = runBlocking {
        val doc = open("Quarterly report.pdf")
        val actions = ReaderActions(reader, CoroutineScope(Dispatchers.Unconfined)) { doc }
        actions.share()
        val event = withTimeout(5_000) { actions.events.first() } as ReaderActionEvent.Share
        assertEquals("Quarterly report.pdf", event.request.name)
        val copy = sharedFiles().single()
        assertEquals("Quarterly report.pdf", copy.name)
        assertArrayEquals(bytes, copy.readBytes())
        assertArrayEquals(bytes, app.contentResolver.openInputStream(event.request.uri)!!.use { it.readBytes() })

        val chooser = sharePdfIntent(event.request.uri, event.request.name)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val stream = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("content", stream.scheme)
        assertEquals(PdfJobs.shareAuthority(app), stream.authority)
        assertEquals(event.request.uri, stream)
    }

    @Test
    fun namesAreSanitisedAsForJobResults() {
        assertEquals("notes.pdf", PdfJobs.shareFileName("notes"))
        assertEquals("Scan.PDF", PdfJobs.shareFileName("Scan.PDF"))
        assertTrue(!PdfJobs.shareFileName("../../etc/passwd").contains('/'))
        assertEquals("document.pdf", PdfJobs.shareFileName("   "))
        val doc = open("no extension")
        val request = runBlocking { reader.prepareShare(doc) }
        assertEquals("no extension.pdf", request.name)
        assertEquals("no extension.pdf", sharedFiles().single().name)
    }

    @Test
    fun aDocumentReplacedDuringTheCopyOpensNoSheetAndAClosedOneShowsAMessage() = runBlocking {
        val doc = open("a.pdf")
        // The first call (start) sees the document; the check after the copy sees another one.
        var calls = 0
        val replaced = ReaderActions(reader, CoroutineScope(Dispatchers.Unconfined)) { if (calls++ == 0) doc else null }
        replaced.share()
        assertNull(withTimeoutOrNull(500) { replaced.events.first() })
        assertTrue(!replaced.sharing)

        // Closed while the user taps Share: a short message, nothing left in shared/ for it.
        val closed = open("b.pdf")
        reader.close(closed)
        withContext(engine) {}
        val actions = ReaderActions(reader, CoroutineScope(Dispatchers.Unconfined)) { closed }
        actions.share()
        assertEquals(ReaderActionEvent.Message(R.string.reader_share_failed), withTimeout(5_000) { actions.events.first() })
        assertTrue(sharedFiles().none { it.name == "b.pdf" })
        // The closed document's private folder is gone.
        assertEquals(1, File(app.cacheDir, "jobs").listFiles().orEmpty().count { it.name.startsWith("reader-") })
        reader.close(doc)
        withContext(engine) {}
        assertTrue(File(app.cacheDir, "jobs").listFiles().orEmpty().none { it.name.startsWith("reader-") })
    }
}
