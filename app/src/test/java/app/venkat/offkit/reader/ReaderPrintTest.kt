package app.venkat.offkit.reader

import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.FakePdfEngine
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

    private class Env(val fake: FakePdfEngine, val engine: ExecutorCoroutineDispatcher, val reader: PdfReader)

    private fun <T> withEnv(block: suspend Env.() -> T): T {
        val fake = FakePdfEngine().apply { readerPages = List(2) { PageDims(595f, 842f) } }
        (app as PdfUtilApp).engine = fake
        val engine = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            return runBlocking { Env(fake, engine, PdfReader(app, PdfJobs(app, engine, Dispatchers.IO, Dispatchers.Unconfined))).block() }
        } finally {
            engine.close()
        }
    }

    private suspend fun Env.printEvent(document: ReaderDocument): ReaderActionEvent {
        val actions = ReaderActions(reader, CoroutineScope(Dispatchers.Unconfined)) { document }
        actions.print()
        return withTimeout(5_000) { actions.events.first() }
    }

    private val decrypted get() = FakePdfEngine.DECRYPTED_PREFIX + bytes

    @Test
    fun aProtectedDocumentPrintsTheDecryptedCopyAndSharesTheOriginal() = withEnv {
        fake.readerPassword = "secret"
        val document = doc(password = "secret", encrypted = true)
        val event = printEvent(document) as ReaderActionEvent.Print
        assertEquals(2, event.job.pageCount)
        val adapter = ReaderPrintAdapter(event.job)
        val (callback, out) = write(adapter)
        assertEquals("finished", callback.result)
        assertArrayEquals(decrypted, out.readBytes())
        adapter.onFinish()
        // Share sends the original, still protected bytes.
        val share = ReaderActions(reader, CoroutineScope(Dispatchers.Unconfined)) { document }
        share.share()
        val request = (withTimeout(5_000) { share.events.first() } as ReaderActionEvent.Share).request
        assertArrayEquals(bytes, app.contentResolver.openInputStream(request.uri)!!.use { it.readBytes() })
    }

    @Test
    fun ownerPasswordOnlyDocumentsPrintTheDecryptedCopyAndPlainOnesTheOriginal() = withEnv {
        for (document in listOf(doc(null, true), doc("", true))) {
            val event = printEvent(document) as ReaderActionEvent.Print
            val (callback, out) = write(ReaderPrintAdapter(event.job))
            assertEquals("finished", callback.result)
            assertArrayEquals(decrypted, out.readBytes())
        }
        val plain = printEvent(doc(null, false)) as ReaderActionEvent.Print
        assertArrayEquals(bytes, write(ReaderPrintAdapter(plain.job)).second.readBytes())
        assertEquals(2, fake.decryptCalls.size)
    }

    @Test
    fun printIsNotStartedTwiceWhilePreparing() = withEnv {
        val release = CountDownLatch(1)
        fake.beforeDecrypt = { release.await(5, TimeUnit.SECONDS) }
        val document = doc(null, true)
        val actions = ReaderActions(reader, CoroutineScope(Dispatchers.Default)) { document }
        actions.print()
        withTimeout(5_000) { actions.printPreparing.first { it } }
        assertTrue(actions.printing)
        actions.print()
        actions.print()
        release.countDown()
        assertTrue(withTimeout(5_000) { actions.events.first() } is ReaderActionEvent.Print)
        withTimeout(5_000) { actions.printPreparing.first { !it } }
        assertNull(withTimeoutOrNull(300) { actions.events.first() })
        assertEquals(1, fake.decryptCalls.size)
    }

    @Test
    fun leavingOrOpeningAnotherDocumentDuringPreparationPrintsNothing() = withEnv {
        val document = doc(null, true)
        var current: ReaderDocument? = document
        // Closing the document (another PDF opened, or the reader left) stops the engine call.
        fake.beforeDecrypt = { reader.close(document); current = null }
        val actions = ReaderActions(reader, CoroutineScope(Dispatchers.Unconfined)) { current }
        actions.print()
        assertNull(withTimeoutOrNull(500) { actions.events.first() })
        assertFalse(actions.printPreparing.value)
        withContext(engine) {}
        assertFalse(dir.exists())
    }

    @Test
    fun aFailedCopyShowsAMessage() = withEnv {
        fake.decryptError = PdfError.OutOfMemory
        assertEquals(ReaderActionEvent.Message(R.string.reader_print_failed), printEvent(doc(null, true)))
        assertEquals("Couldn't prepare this PDF for printing. Check free space and try again.", app.getString(R.string.reader_print_failed))
    }

    @Test
    fun aDocumentClosedWhileThePrintDialogIsOpen() = withEnv {
        // Closed after the first write (the preview): later writes still print from the open file.
        val first = doc(null, true)
        val adapter = ReaderPrintAdapter((printEvent(first) as ReaderActionEvent.Print).job)
        write(adapter)
        reader.close(first)
        withContext(engine) {}
        assertFalse(dir.exists())
        val (again, out) = write(adapter)
        assertEquals("finished", again.result)
        assertArrayEquals(decrypted, out.readBytes())
        adapter.onFinish()

        // Closed before any write: the write fails cleanly.
        dir.mkdirs()
        source.writeBytes(bytes)
        val second = doc(null, true)
        val late = ReaderPrintAdapter((printEvent(second) as ReaderActionEvent.Print).job)
        reader.close(second)
        withContext(engine) {}
        assertEquals("failed", write(late).first.result)
        late.onFinish()
    }
}
