package app.venkat.offkit.reader

import android.app.Application
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentInfo
import android.print.RecordingPrintCallbacks
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.R
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.core.ReaderDocument
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.feature.reader.ReaderActionEvent
import app.venkat.offkit.feature.reader.ReaderActions
import app.venkat.offkit.feature.reader.ReaderPrintAdapter
import app.venkat.offkit.feature.reader.ReaderPrintJob
import app.venkat.offkit.feature.reader.startPrint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderPrintTest {
    private lateinit var app: Application
    private lateinit var dir: File
    private lateinit var source: File
    private val bytes = Random(5).nextBytes(200_000)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        dir = File(app.cacheDir, "jobs/reader-test").apply { mkdirs() }
        source = File(dir, "document.pdf").apply { writeBytes(bytes) }
    }

    private fun job(open: () -> FileChannel = { FileInputStream(source).channel }) = ReaderPrintJob("My report", 7, open)

    private fun destination(): Pair<File, ParcelFileDescriptor> {
        val out = File(app.cacheDir, "print-out.pdf")
        return out to ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    }

    private fun write(adapter: ReaderPrintAdapter, signal: CancellationSignal = CancellationSignal()): Pair<RecordingPrintCallbacks.Write, File> {
        val (out, pfd) = destination()
        val callback = RecordingPrintCallbacks.Write()
        adapter.onWrite(arrayOf(PageRange(1, 2)), pfd, signal, callback)
        assertTrue("write callback", callback.done.await(5, TimeUnit.SECONDS))
        // The adapter never closes the framework's descriptor.
        assertTrue(pfd.fileDescriptor.valid())
        pfd.close()
        return callback to out
    }

    @Test
    fun layoutReportsADocumentWithThePageCount() {
        val adapter = ReaderPrintAdapter(job())
        val callback = RecordingPrintCallbacks.Layout()
        adapter.onLayout(null, PrintAttributes.Builder().build(), CancellationSignal(), callback, null)
        assertEquals("finished", callback.result)
        assertEquals(7, callback.info.pageCount)
        assertEquals(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT, callback.info.contentType)
        assertEquals("My report.pdf", callback.info.name)
        assertTrue(callback.changed)
        val again = RecordingPrintCallbacks.Layout()
        adapter.onLayout(PrintAttributes.Builder().build(), PrintAttributes.Builder().build(), CancellationSignal(), again, null)
        assertFalse(again.changed)
        val cancelled = RecordingPrintCallbacks.Layout()
        adapter.onLayout(null, PrintAttributes.Builder().build(), CancellationSignal().apply { cancel() }, cancelled, null)
        assertEquals("cancelled", cancelled.result)
    }

    @Test
    fun writeCopiesTheExactBytesAndReportsAllPagesThenFinishClosesTheSource() {
        val adapter = ReaderPrintAdapter(job())
        val (callback, out) = write(adapter)
        assertEquals("finished", callback.result)
        assertArrayEquals(arrayOf(PageRange.ALL_PAGES), callback.pages)
        assertArrayEquals(bytes, out.readBytes())
        // A second write (another preview or the final job) reads from the start again.
        val (second, out2) = write(adapter)
        assertEquals("finished", second.result)
        assertArrayEquals(bytes, out2.readBytes())
        assertTrue(adapter.sourceOpen)
        adapter.onFinish()
        assertFalse(adapter.sourceOpen)
    }

    @Test
    fun aDocumentClosedAfterTheFirstWriteStillPrintsFromTheOpenSource() {
        val adapter = ReaderPrintAdapter(job())
        write(adapter)
        dir.deleteRecursively() // PdfReader.close deletes the folder
        val (callback, out) = write(adapter)
        assertEquals("finished", callback.result)
        assertArrayEquals(bytes, out.readBytes())
        adapter.onFinish()
    }

    @Test
    fun cancelDuringTheWriteCallsOnWriteCancelledAndFinishClosesTheSource() {
        val signal = CancellationSignal()
        val adapter = ReaderPrintAdapter(job { FileInputStream(source).channel.also { signal.cancel() } })
        val (callback, _) = write(adapter, signal)
        assertEquals("cancelled", callback.result)
        adapter.onFinish()
        assertFalse(adapter.sourceOpen)
    }

    @Test
    fun aDeletedSourceCallsOnWriteFailed() {
        source.delete()
        val adapter = ReaderPrintAdapter(job())
        assertEquals("failed", write(adapter).first.result)
        val closed = ReaderPrintAdapter(job { throw IOException("Document closed") })
        assertEquals("failed", write(closed).first.result)
        // After onFinish nothing is opened again.
        val finished = ReaderPrintAdapter(job())
        finished.onFinish()
        assertEquals("failed", write(finished).first.result)
        assertFalse(finished.sourceOpen)
    }

    @Test
    fun withoutAnActivityPrintingExplainsInsteadOfCrashing() {
        assertEquals(R.string.reader_print_unavailable, startPrint(app, job()))
    }

    private fun doc(password: String?, encrypted: Boolean) =
        ReaderDocument("p.pdf", List(2) { PageDims(595f, 842f) }, dir, source, password, encrypted)

    private fun printEvent(document: ReaderDocument): ReaderActionEvent = runBlocking {
        val reader = PdfReader(app, PdfJobs(app, Dispatchers.IO, Dispatchers.IO, Dispatchers.Unconfined))
        val actions = ReaderActions(reader, CoroutineScope(Dispatchers.Unconfined)) { document }
        actions.print()
        withTimeout(5_000) { actions.events.first() }
    }

    @Test
    fun aProtectedDocumentShowsTheMessageAndNeverReachesThePrintManager() {
        val event = printEvent(doc(password = "secret", encrypted = true))
        assertEquals(ReaderActionEvent.Message(R.string.reader_print_protected), event)
        assertEquals("Protected PDFs can't be printed from Offkit yet.", app.getString(R.string.reader_print_protected))
    }

    @Test
    fun plainAndOwnerPasswordOnlyDocumentsArePrinted() {
        for (document in listOf(doc(null, false), doc(null, true), doc("", true))) {
            val event = printEvent(document) as ReaderActionEvent.Print
            assertEquals(2, event.job.pageCount)
            event.job.openSource().use { assertEquals(bytes.size.toLong(), it.size()) }
        }
    }
}
