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
import app.venkat.offkit.core.JobInput
import app.venkat.offkit.core.JobOutcome
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.engine.PdfError
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

    private val outDir get() = File(app.cacheDir, "save-out").apply { mkdirs() }

    /** A reader whose save destinations are plain files (`file:` Uris), optionally failing mid-write. */
    private fun fileReader(failAfterBytes: Int? = null) = PdfReader(
        app,
        PdfJobs(app, engine, Dispatchers.IO, Dispatchers.Unconfined) { _, uri ->
            val out = java.io.FileOutputStream(requireNotNull(uri.path))
            if (failAfterBytes == null) {
                out
            } else {
                object : java.io.OutputStream() {
                    var n = 0
                    override fun write(b: Int) {
                        if (n++ >= failAfterBytes) throw java.io.IOException("disk full")
                        out.write(b)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) write(b[i].toInt()) }
                    override fun close() = out.close()
                }
            }
        },
    )

    private fun dest(name: String): Pair<File, Uri> {
        val f = File(outDir, name).apply { delete(); createNewFile() } // as the system dialog leaves it
        return f to Uri.fromFile(f)
    }

    @Test
    fun saveACopyWritesThePrivateCopyByteForByteAndSaysSaved() = runBlocking {
        val r = fileReader()
        val doc = (r.open(CoreTestSupport.contentUri(app, "Report", bytes)) as ReaderOpenResult.Opened).document
        val actions = ReaderActions(r, CoroutineScope(Dispatchers.Unconfined)) { doc }
        assertEquals("Report.pdf", actions.beginSaveCopy())
        assertTrue(actions.savingCopy.value)
        assertNull("no second dialog while one is out", actions.beginSaveCopy())
        val (file, uri) = dest("copy.pdf")
        actions.saveCopyTo(uri)
        assertEquals(ReaderActionEvent.Message(R.string.reader_saved), withTimeout(5_000) { actions.events.first() })
        assertArrayEquals(doc.file.readBytes(), file.readBytes())
        assertArrayEquals(bytes, file.readBytes())
        withTimeout(5_000) { actions.savingCopy.first { !it } }
        Unit
    }

    @Test
    fun aProtectedDocumentIsSavedAsItIsStillProtected() = runBlocking {
        val r = fileReader()
        val plain = (r.open(CoreTestSupport.contentUri(app, "locked.pdf", bytes)) as ReaderOpenResult.Opened).document
        // The same private copy, marked encrypted as the engine reports a protected PDF.
        val doc = ReaderDocument(plain.name, plain.pages, plain.dir, plain.file, isEncrypted = true)
        val actions = ReaderActions(r, CoroutineScope(Dispatchers.Unconfined)) { doc }
        actions.beginSaveCopy()
        val (file, uri) = dest("locked-copy.pdf")
        actions.saveCopyTo(uri)
        assertEquals(ReaderActionEvent.Message(R.string.reader_saved), withTimeout(5_000) { actions.events.first() })
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun compressRefusesAProtectedDocumentWithAMessageAndHandsOffAPlainOne() = runBlocking {
        val plain = open("plain.pdf")
        val ok = ReaderActions(reader, CoroutineScope(Dispatchers.Unconfined)) { plain }.compressSource()!!
        assertEquals("plain.pdf", ok.name)
        assertEquals(plain.file, ok.file)
        val locked = ReaderDocument(plain.name, plain.pages, plain.dir, plain.file, isEncrypted = true)
        val actions = ReaderActions(reader, CoroutineScope(Dispatchers.Unconfined)) { locked }
        assertNull(actions.compressSource())
        assertEquals(ReaderActionEvent.Message(R.string.reader_compress_protected), withTimeout(5_000) { actions.events.first() })
    }

    @Test
    fun aJobCopiesTheReaderCopyAndRejectsAFileOutsideAReaderFolderOrAMissingOne() = runBlocking {
        val jobs = PdfJobs(app, engine, Dispatchers.IO, Dispatchers.Unconfined)
        val doc = open("job.pdf")
        var seen: ByteArray? = null
        val ok = jobs.run(listOf(JobInput.ReaderCopy(doc.file)), { _, _ -> }) { _, files, out, _ ->
            seen = files.single().readBytes()
            out.writeBytes(seen!!)
            PdfResult.Ok(1, seen!!.size.toLong())
        }
        assertTrue(ok is JobOutcome.Ready)
        assertArrayEquals(bytes, seen)

        val outside = File(app.cacheDir, "jobs/elsewhere/document.pdf").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val sneaky = File(doc.dir, "../elsewhere/document.pdf")
        val missing = File(doc.dir, "gone.pdf")
        for (f in listOf(outside, sneaky, missing, File(app.filesDir, "document.pdf"))) {
            val r = jobs.run(listOf(JobInput.ReaderCopy(f)), { _, _ -> }) { _, _, _, _ ->
                error("engine must not run for $f")
            }
            assertTrue("$f rejected: $r", r is JobOutcome.Failed && r.error is PdfError.Io)
        }
    }

    @Test
    fun cancellingTheDialogDoesNothingAndAllowsANewSave() = runBlocking {
        val doc = open("a.pdf")
        val actions = ReaderActions(reader, CoroutineScope(Dispatchers.Unconfined)) { doc }
        assertTrue(actions.beginSaveCopy() != null)
        actions.saveCopyTo(null)
        assertTrue(!actions.savingCopy.value)
        assertNull(withTimeoutOrNull(300) { actions.events.first() })
        assertTrue(actions.beginSaveCopy() != null)
    }

    @Test
    fun aDocumentReplacedBeforeTheDialogReturnsWritesNothingAndRemovesTheEmptyFile() = runBlocking {
        val r = fileReader()
        val first = (r.open(CoreTestSupport.contentUri(app, "first.pdf", bytes)) as ReaderOpenResult.Opened).document
        val second = (r.open(CoreTestSupport.contentUri(app, "second.pdf", "%PDF-1.7\nother\n".toByteArray())) as ReaderOpenResult.Opened).document
        var open = first
        val actions = ReaderActions(r, CoroutineScope(Dispatchers.Unconfined)) { open }
        actions.beginSaveCopy()
        open = second
        val (file, uri) = dest("first.pdf")
        actions.saveCopyTo(uri)
        assertEquals(ReaderActionEvent.Message(R.string.reader_save_closed), withTimeout(5_000) { actions.events.first() })
        CoreTestSupport.idleUntil(message = "empty file removed") { !file.exists() }
        assertTrue(!actions.savingCopy.value)
    }

    @Test
    fun aCopyStillRunningWhenTheReaderLeavesIsWrittenToTheEnd() = runBlocking {
        val big = ByteArray(1_000_000) { (it % 253).toByte() }
        val firstWrite = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // A slow destination: the first write waits until the reader has gone.
        val r = PdfReader(
            app,
            PdfJobs(app, engine, Dispatchers.IO, Dispatchers.Unconfined) { _, uri ->
                val out = java.io.FileOutputStream(requireNotNull(uri.path))
                object : java.io.OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        firstWrite.countDown()
                        release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        out.write(b, off, len)
                    }
                    override fun close() = out.close()
                }
            },
        )
        val doc = (r.open(CoreTestSupport.contentUri(app, "big.pdf", big)) as ReaderOpenResult.Opened).document
        val readerScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        val actions = ReaderActions(r, readerScope) { doc }
        actions.beginSaveCopy()
        val (file, uri) = dest("big-copy.pdf")
        actions.saveCopyTo(uri)
        assertTrue("copy started", firstWrite.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // The reader leaves: its view model scope is cancelled and the document closed.
        readerScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        r.close(doc)
        CoreTestSupport.idleUntil(message = "reader folder deleted") { !doc.dir.exists() }
        release.countDown()
        withTimeout(10_000) { actions.savingCopy.first { !it } }
        assertTrue("destination kept", file.exists())
        assertArrayEquals(big, file.readBytes())
    }

    @Test
    fun aFailedWriteDeletesThePartialFileAndSaysSo() = runBlocking {
        val r = fileReader(failAfterBytes = 4)
        val doc = (r.open(CoreTestSupport.contentUri(app, "big.pdf", bytes)) as ReaderOpenResult.Opened).document
        val actions = ReaderActions(r, CoroutineScope(Dispatchers.Unconfined)) { doc }
        actions.beginSaveCopy()
        val (file, uri) = dest("big.pdf")
        actions.saveCopyTo(uri)
        assertEquals(ReaderActionEvent.Message(R.string.reader_save_failed), withTimeout(5_000) { actions.events.first() })
        assertTrue("partial file deleted", !file.exists())
        withTimeout(5_000) { actions.savingCopy.first { !it } }
        Unit
    }

    @Test
    fun shareBusyIsTrueFromTheTapUntilTheCopyEnds() = runBlocking {
        val doc = open("busy.pdf")
        val gate = java.util.concurrent.CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        pool.execute { gate.await() }
        val actions = ReaderActions(reader, CoroutineScope(pool.asCoroutineDispatcher())) { doc }
        assertTrue(!actions.shareBusy.value)
        actions.share()
        assertTrue("busy as soon as Share is tapped", actions.shareBusy.value)
        actions.share() // ignored while busy
        gate.countDown()
        withTimeout(5_000) { actions.events.first() } as ReaderActionEvent.Share
        withTimeout(5_000) { actions.shareBusy.first { !it } }
        assertEquals(1, sharedFiles().size)
        pool.shutdown()
    }

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
