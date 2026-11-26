package app.venkat.offkit

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PageTextFormat
import app.venkat.offkit.engine.PageTextParse
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfPageInfo
import app.venkat.offkit.engine.PdfPageText
import app.venkat.offkit.engine.PdfRender
import app.venkat.offkit.engine.RenderRequest
import app.venkat.offkit.engine.remote.RemoteEngine
import app.venkat.offkit.feature.reader.ItemRect
import app.venkat.offkit.feature.reader.PasswordProblem
import app.venkat.offkit.feature.reader.ReaderDocState
import app.venkat.offkit.feature.reader.ReaderViewModel
import app.venkat.offkit.feature.reader.VisiblePage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Password-protected PDFs on a device: the password crosses the real binder to the `:engine`
 * process and meets the Android build of MuPDF, and the pre-checks run on encrypted input. The
 * encrypted files are [EncryptedFixtures] (base64 made on the JVM), so MuPDF does not run in this
 * (instrumentation) process to build them.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val jobs: PdfJobs get() = PdfJobs.get(context)
    private val jobsRoot: File get() = File(context.cacheDir, "jobs")
    private val remote: RemoteEngine get() = (context.applicationContext as PdfUtilApp).engine as RemoteEngine
    private lateinit var engineDir: File
    private lateinit var sharedDir: File
    private var vm: ReaderViewModel? = null

    @Before
    fun setUp() {
        assertTrue("engine is ${(context.applicationContext as PdfUtilApp).engine}", (context.applicationContext as PdfUtilApp).engine is RemoteEngine)
        jobs.wipeAll()
        // EngineServer only accepts paths inside cacheDir/jobs.
        engineDir = File(jobsRoot, "encrypted-ondevice-${System.nanoTime()}").apply { mkdirs() }
        sharedDir = File(context.cacheDir, "shared/encrypted-ondevice-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        vm?.let { model -> onMain { model.close() } }
        engineDir.deleteRecursively()
        sharedDir.deleteRecursively()
    }

    // ---------------------------------------------------------------- helpers

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitFor(what: String, timeoutMs: Long = 60_000, state: () -> String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > end) fail("timed out after $timeoutMs ms waiting for $what; state: ${state()}")
            Thread.sleep(10)
        }
    }

    private fun enginePid(): Int? {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.runningAppProcesses.orEmpty().firstOrNull { it.processName == "${context.packageName}:engine" }?.pid
    }

    private fun readerDirs(): List<File> =
        jobsRoot.listFiles()?.filter { it.name.startsWith(PdfReader.READER_DIR_PREFIX) }.orEmpty()

    private fun contentUri(file: File): Uri =
        FileProvider.getUriForFile(context, PdfJobs.shareAuthority(context), file)

    private val onePage = listOf(PageDims(EncryptedFixtures.PAGE_PT, EncryptedFixtures.PAGE_PT))

    /** pageText through the engine process; returns the page's text. */
    private fun remoteText(pdf: File, password: String?): String {
        val out = File(engineDir, "text.bin").also { it.delete() }
        val r = remote.pageText(pdf, 0, out, password, JobControl.None)
        assertTrue("pageText: $r", r is PdfPageText.Ok)
        val parsed = PageTextFormat.read(out)
        assertTrue("$parsed", parsed is PageTextParse.Ok)
        return (parsed as PageTextParse.Ok).text.text
    }

    /** No password, a wrong one and [user] through the engine process; text holds the known word. */
    private fun assertPasswordFlow(pdf: File, user: String, wrong: String) {
        assertEquals(PdfPageInfo.Err(PdfError.Encrypted), remote.pageInfo(pdf, null, JobControl.None))
        assertEquals(PdfPageInfo.Err(PdfError.WrongPassword), remote.pageInfo(pdf, wrong, JobControl.None))
        val ok = remote.pageInfo(pdf, user, JobControl.None)
        assertTrue("pageInfo with the password: $ok", ok is PdfPageInfo.Ok)
        ok as PdfPageInfo.Ok
        assertEquals(onePage, ok.pages)
        assertTrue("encrypted flag", ok.encrypted)
        val text = remoteText(pdf, user)
        assertTrue(text, text.contains(EncryptedFixtures.WORD))
    }

    // ---------------------------------------------------------------- engine process

    @Test
    fun encrypted_aes256NonAsciiPasswordThroughEngineProcess() {
        val pdf = EncryptedFixtures.write(engineDir, "aes.pdf", EncryptedFixtures.AES256_B64)
        val pidBefore = enginePid()
        assertPasswordFlow(pdf, EncryptedFixtures.AES256_USER, wrong = "passwörd-é")
        val pid = enginePid()
        assertNotNull("no :engine process", pid)
        if (pidBefore != null) assertEquals("engine pid unchanged", pidBefore, pid)

        // Render the whole page at scale 1: 200x200 px.
        val out = File(engineDir, "tile.rgba")
        val req = RenderRequest(page = 0, scale = 1f, left = 0, top = 0, width = 200, height = 200)
        assertEquals(PdfRender.Err(PdfError.Encrypted), remote.renderPage(pdf, req, out, null, JobControl.None))
        assertEquals(PdfRender.Err(PdfError.WrongPassword), remote.renderPage(pdf, req, out, "nope", JobControl.None))
        assertFalse("no output after a refused render", out.exists())
        assertEquals(PdfRender.Ok(200, 200), remote.renderPage(pdf, req, out, EncryptedFixtures.AES256_USER, JobControl.None))
        val bytes = out.readBytes()
        assertEquals(200 * 200 * 4, bytes.size)
        fun px(x: Int, y: Int): List<Int> = (0 until 4).map { bytes[(y * 200 + x) * 4 + it].toInt() and 0xFF }
        fun assertPx(x: Int, y: Int, r: Int, g: Int, b: Int) {
            val p = px(x, y)
            val ok = abs(p[0] - r) <= 2 && abs(p[1] - g) <= 2 && abs(p[2] - b) <= 2 && p[3] == 255
            assertTrue("pixel ($x,$y) = $p, want [$r, $g, $b, 255]", ok)
        }
        // The black square is PDF (20..80, 20..80): displayed x 20..80, y 120..180.
        assertPx(50, 150, 0, 0, 0)
        assertPx(190, 10, 255, 255, 255)
        assertPx(150, 150, 255, 255, 255)

        assertEquals("engine process still alive, not replaced", pid, enginePid())
    }

    @Test
    fun encrypted_rc4AsciiPasswordThroughEngineProcess() {
        val pdf = EncryptedFixtures.write(engineDir, "rc4.pdf", EncryptedFixtures.RC4_B64)
        assertPasswordFlow(pdf, EncryptedFixtures.RC4_USER, wrong = "rc4-secreT")
        assertNotNull("no :engine process", enginePid())
    }

    @Test
    fun encrypted_ownerPasswordOnlyOpensWithoutPasswordThroughEngineProcess() {
        val pdf = EncryptedFixtures.write(engineDir, "owner.pdf", EncryptedFixtures.OWNER_ONLY_B64)
        val ok = remote.pageInfo(pdf, null, JobControl.None)
        assertTrue("pageInfo: $ok", ok is PdfPageInfo.Ok)
        ok as PdfPageInfo.Ok
        assertEquals(onePage, ok.pages)
        assertTrue("encrypted flag", ok.encrypted)
        val text = remoteText(pdf, null)
        assertTrue(text, text.contains(EncryptedFixtures.WORD))
    }

    // ---------------------------------------------------------------- reader view model

    @Test
    fun encrypted_readerViewModel_lockedWrongRightSearchAndCancel() {
        val aes = EncryptedFixtures.write(sharedDir, "aes.pdf", EncryptedFixtures.AES256_B64)
        val rc4 = EncryptedFixtures.write(sharedDir, "rc4.pdf", EncryptedFixtures.RC4_B64)
        val model = onMain { ReaderViewModel(context.applicationContext as Application) }
        vm = model
        val doc = { "${model.document.value}" }

        onMain { model.open(contentUri(aes)) }
        waitFor("Locked", state = doc) { model.document.value.let { it is ReaderDocState.Locked || it is ReaderDocState.Failed || it is ReaderDocState.Open } }
        assertEquals(ReaderDocState.Locked("aes.pdf"), model.document.value)

        onMain { model.unlock("passwörd-é") }
        waitFor("wrong password result", state = doc) { (model.document.value as? ReaderDocState.Locked)?.attempt == 1 || model.document.value !is ReaderDocState.Locked }
        assertEquals(ReaderDocState.Locked("aes.pdf", attempt = 1, problem = PasswordProblem.WRONG), model.document.value)

        onMain { model.unlock(EncryptedFixtures.AES256_USER) }
        waitFor("Open", state = doc) { model.document.value.let { it is ReaderDocState.Open || it is ReaderDocState.Failed || (it is ReaderDocState.Locked && it.attempt > 1) } }
        assertEquals(ReaderDocState.Open("aes.pdf", onePage), model.document.value)
        val openDirs = readerDirs()
        assertEquals("one reader dir while open: $openDirs", 1, openDirs.size)

        // Page 1 renders (its base bitmap, with the black square and white paper).
        val page0 = onMain { model.page(0) }
        val itemW = 600
        val full = ItemRect(0f, 0f, itemW.toFloat(), itemW.toFloat())
        onMain { model.onViewport(itemW, 1f, listOf(VisiblePage(0, full))) }
        waitFor("page 1 base", state = { "doc=${model.document.value} page=${page0.value}" }) {
            page0.value.base != null || page0.value.error != null
        }
        assertNull("page 1 failed: ${page0.value.error}", page0.value.error)
        val bmp = page0.value.base!!
        assertTrue("base ${bmp.width}x${bmp.height}", abs(bmp.width - itemW) <= 1 && abs(bmp.height - itemW) <= 1)
        assertEquals("black square", 0x000000, bmp.getPixel(bmp.width / 4, bmp.height * 3 / 4) and 0xFFFFFF)
        assertEquals("white paper", 0xFFFFFF, bmp.getPixel(bmp.width * 3 / 4, bmp.height * 3 / 4) and 0xFFFFFF)

        // Search for the known word: one hit, on page 1.
        onMain { model.search(EncryptedFixtures.WORD.lowercase(), 0) }
        waitFor("search", state = { "${model.search.value}" }) { model.search.value.let { it != null && !it.scanning && it.hits.isNotEmpty() } }
        val search = model.search.value!!
        assertEquals("hits: ${search.hits}", 1, search.hits.size)
        assertEquals(0, search.hits[0].page)
        assertEquals(0, search.skipped)
        onMain { model.closeSearch() }

        // A second locked open, then cancel: the shown document comes back and the locked copy goes.
        onMain { model.open(contentUri(rc4)) }
        waitFor("second Locked", state = doc) { model.document.value.let { it is ReaderDocState.Locked || it is ReaderDocState.Failed } }
        assertEquals(ReaderDocState.Locked("rc4.pdf"), model.document.value)
        assertEquals("open document plus the locked copy: ${readerDirs()}", 2, readerDirs().size)
        onMain { model.cancelPassword() }
        assertEquals(ReaderDocState.Open("aes.pdf", onePage), model.document.value)
        waitFor("locked copy deleted", timeoutMs = 10_000, state = { "${readerDirs()}" }) { readerDirs().size == 1 }
        assertEquals("only the open document's dir is left", openDirs, readerDirs())

        // Closing removes the last one.
        onMain { model.close() }
        assertEquals(ReaderDocState.Idle, model.document.value)
        waitFor("reader dirs deleted", timeoutMs = 10_000, state = { "${readerDirs()}" }) { readerDirs().isEmpty() }
        vm = null
    }
}
