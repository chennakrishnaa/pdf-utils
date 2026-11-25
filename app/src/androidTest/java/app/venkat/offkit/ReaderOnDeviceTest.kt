package app.venkat.offkit

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.remote.RemoteEngine
import app.venkat.offkit.feature.reader.ItemRect
import app.venkat.offkit.feature.reader.ReaderDocState
import app.venkat.offkit.feature.reader.ReaderPageState
import app.venkat.offkit.feature.reader.ReaderViewModel
import app.venkat.offkit.feature.reader.VisiblePage
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The in-app reader end to end on a device: a PDF built here is opened through a real `content:`
 * Uri (the app's own share `FileProvider`) by [ReaderViewModel] with the production [PdfReader] and
 * the real MuPDF engine in the `:engine` process (no fake engine). Its image page goes through the
 * render-time image gate (`RenderImageGate`) and MuPDF's JPEG decoder on the Android build.
 *
 * Timings are logged under tag [TAG].
 */
@RunWith(AndroidJUnit4::class)
class ReaderOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val jobs: PdfJobs get() = PdfJobs.get(context)
    private val jobsRoot: File get() = File(context.cacheDir, "jobs")
    private lateinit var sharedDir: File
    private lateinit var scratch: File
    private var vm: ReaderViewModel? = null

    @Before
    fun setUp() {
        assertTrue("engine is ${app().engine}", app().engine is RemoteEngine)
        // The first PdfJobs.get of the process wipes cacheDir/jobs and cacheDir/shared: do it before
        // placing the input under shared/.
        jobs.wipeAll()
        sharedDir = File(context.cacheDir, "shared/reader-ondevice-${System.nanoTime()}").apply { mkdirs() }
        scratch = File(context.cacheDir, "reader-ondevice-scratch-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        vm?.let { model -> onMain { model.close() } }
        sharedDir.deleteRecursively()
        scratch.deleteRecursively()
    }

    // ---------------------------------------------------------------- helpers

    private fun app(): PdfUtilApp = context.applicationContext as PdfUtilApp

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Polls [condition] every 10 ms until true; fails after [timeoutMs] with [state] in the message. */
    private fun waitFor(what: String, timeoutMs: Long = 60_000, state: () -> String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > end) fail("timed out after $timeoutMs ms waiting for $what; state: ${state()}")
            Thread.sleep(10)
        }
    }

    /** The share-provider `content:` Uri of [file] (which must be under [sharedDir]). */
    private fun contentUri(file: File): Uri {
        val uri = FileProvider.getUriForFile(context, PdfJobs.shareAuthority(context), file)
        assertEquals("content", uri.scheme)
        return uri
    }

    private fun quadrantJpeg(): ByteArray =
        Fixtures.jpegWithExifOrientation(scratch, "q.jpg", IMAGE_W, IMAGE_H, exifOrientation = 1).readBytes()

    /** Opens [file] (under [sharedDir]) in a new view model; returns it and the open time in ms. */
    private fun openInViewModel(file: File): Pair<ReaderViewModel, Long> {
        val uri = contentUri(file)
        val model = onMain { ReaderViewModel(context.applicationContext as Application) }
        vm = model
        val t0 = SystemClock.elapsedRealtime()
        onMain { model.open(uri) }
        waitFor("document to open", state = { "${model.document.value}" }) {
            model.document.value.let { it is ReaderDocState.Open || it is ReaderDocState.Failed }
        }
        val openMs = SystemClock.elapsedRealtime() - t0
        val doc = model.document.value
        if (doc !is ReaderDocState.Open) fail("expected Open, got $doc")
        doc as ReaderDocState.Open
        assertEquals(file.name, doc.name)
        assertEquals(listOf(PageDims(PAGE_W, PAGE_H), PageDims(PAGE_W, PAGE_H)), doc.pages)
        val readerDirs = readerDirs()
        assertEquals("one reader dir while open: $readerDirs", 1, readerDirs.size)
        return model to openMs
    }

    private fun readerDirs(): List<File> =
        jobsRoot.listFiles()?.filter { it.name.startsWith(PdfReader.READER_DIR_PREFIX) }.orEmpty()

    private fun waitForBase(model: ReaderViewModel, page: StateFlow<ReaderPageState>, label: String): Bitmap {
        waitFor("$label base", state = { "doc=${model.document.value} page=${page.value}" }) {
            page.value.base != null || page.value.error != null
        }
        val state = page.value
        assertNull("$label failed: ${state.error}", state.error)
        val bmp = state.base!!
        assertEquals("$label config", Bitmap.Config.ARGB_8888, bmp.config)
        return bmp
    }

    /** Colour (0xRRGGBB) of [bmp] at item pixel ([ix], [iy]) for a bitmap covering [rect]. */
    private fun colorAt(bmp: Bitmap, rect: ItemRect, ix: Float, iy: Float): Int {
        val bx = ((ix - rect.left) / rect.width * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val by = ((iy - rect.top) / rect.height * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val argb = bmp.getPixel(bx, by)
        assertEquals("alpha at ($bx,$by)", 0xFF, argb ushr 24)
        return argb and 0xFFFFFF
    }

    private fun assertColor(label: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue(
            "$label: got #%06X, want #%06X (±$tolerance)".format(actual, expected),
            Fixtures.colorNear(actual, expected, tolerance),
        )
    }

    /** Quadrant colour of the image page at item pixel ([ix], [iy]) (item [ITEM_W] x [ITEM_H]). */
    private fun quadrantColor(ix: Float, iy: Float): Int {
        val left = ix < ITEM_W / 2f
        val top = iy < ITEM_H / 2f
        return when {
            top && left -> Fixtures.RED
            top -> Fixtures.GREEN
            left -> Fixtures.BLUE
            else -> Fixtures.YELLOW
        }
    }

    private fun assertPlainPage(bmp: Bitmap) {
        val full = ItemRect(0f, 0f, ITEM_W.toFloat(), ITEM_H.toFloat())
        assertColor("plain centre", Fixtures.BLUE, colorAt(bmp, full, ITEM_W / 2f, ITEM_H / 2f), 8)
        assertColor("plain top-left", 0xFFFFFF, colorAt(bmp, full, 100f, 100f), 8)
        assertColor("plain bottom-right", 0xFFFFFF, colorAt(bmp, full, ITEM_W - 100f, ITEM_H - 100f), 8)
    }

    private fun enginePid(): Int? {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.runningAppProcesses.orEmpty().firstOrNull { it.processName == "${context.packageName}:engine" }?.pid
    }

    private fun closeAndAssertClean(model: ReaderViewModel) {
        onMain { model.close() }
        assertEquals(ReaderDocState.Idle, model.document.value)
        // PdfReader.close deletes the directory on the engine thread, asynchronously.
        waitFor("reader dirs to be deleted", timeoutMs = 10_000, state = { "${readerDirs()}" }) { readerDirs().isEmpty() }
        vm = null
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun reader_realEngine_jpegPageBaseAndDetail_plainPage() {
        val file = Fixtures.jpegThenPlainPdf(sharedDir, "photo-and-plain.pdf", quadrantJpeg(), IMAGE_W, IMAGE_H, PAGE_W, PAGE_H)
        val (model, openMs) = openInViewModel(file)
        val page0 = onMain { model.page(0) }
        val page1 = onMain { model.page(1) }

        // Zoom 1, a phone-like item width, the whole image page on screen: its base, then page 1 as a neighbour.
        val full = ItemRect(0f, 0f, ITEM_W.toFloat(), ITEM_H.toFloat())
        val t1 = SystemClock.elapsedRealtime()
        onMain { model.onViewport(ITEM_W, 1f, listOf(VisiblePage(0, full))) }
        val base = waitForBase(model, page0, "image page")
        val baseMs = SystemClock.elapsedRealtime() - t1
        assertTrue("base ${base.width}x${base.height}", abs(base.width - ITEM_W) <= 1 && abs(base.height - ITEM_H) <= 1)
        assertNull("no detail at zoom 1 with a sharp base", page0.value.detail)
        for ((ix, iy) in listOf(270f to 200f, 810f to 200f, 270f to 610f, 810f to 610f)) {
            assertColor("base ($ix,$iy)", quadrantColor(ix, iy), colorAt(base, full, ix, iy), JPEG_TOLERANCE)
        }

        val plain = waitForBase(model, page1, "plain page")
        assertTrue("plain ${plain.width}x${plain.height}", abs(plain.width - ITEM_W) <= 1 && abs(plain.height - ITEM_H) <= 1)
        assertPlainPage(plain)

        // Zoom 3 over the centre of the image page (a 1080x1920 screen shows 360x640 item px).
        val visible = ItemRect(360f, 85f, 720f, 725f)
        val t2 = SystemClock.elapsedRealtime()
        onMain { model.onViewport(ITEM_W, 3f, listOf(VisiblePage(0, visible))) }
        waitFor("image page detail", state = { "doc=${model.document.value} page=${page0.value}" }) {
            page0.value.detail != null || page0.value.error != null
        }
        val detailMs = SystemClock.elapsedRealtime() - t2
        val state = page0.value
        assertNull("detail failed: ${state.error}", state.error)
        val detail = state.detail!!
        assertEquals(ITEM_W, detail.itemWidthPx)
        assertTrue("detail ${detail.rect} covers $visible", detail.rect.contains(visible))
        val bmp = detail.bitmap
        assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
        val detailPxPerItemPx = bmp.width / detail.rect.width
        val basePxPerItemPx = base.width.toFloat() / ITEM_W
        assertTrue("detail scale $detailPxPerItemPx vs base $basePxPerItemPx", detailPxPerItemPx > 2.5f * basePxPerItemPx)
        assertTrue("detail scale vertical", bmp.height / detail.rect.height > 2.5f * basePxPerItemPx)
        var checked = 0
        val seen = HashSet<Int>()
        for (gx in 1..19) for (gy in 1..19) {
            val ix = detail.rect.left + detail.rect.width * gx / 20f
            val iy = detail.rect.top + detail.rect.height * gy / 20f
            // Keep clear of the quadrant boundaries (JPEG chroma blur, upscaling).
            if (abs(ix - ITEM_W / 2f) < EDGE_MARGIN || abs(iy - ITEM_H / 2f) < EDGE_MARGIN) continue
            val want = quadrantColor(ix, iy)
            assertColor("detail ($ix,$iy)", want, colorAt(bmp, detail.rect, ix, iy), JPEG_TOLERANCE)
            seen += want
            checked++
        }
        assertEquals("all four quadrants in the detail", setOf(Fixtures.RED, Fixtures.GREEN, Fixtures.BLUE, Fixtures.YELLOW), seen)
        assertTrue("checked $checked", checked > 100)
        assertEquals("document still open", ReaderDocState.Open::class, model.document.value::class)

        Log.i(
            TAG,
            "jpeg page: open=${openMs}ms base=${baseMs}ms (${base.width}x${base.height}) " +
                "detail=${detailMs}ms (${bmp.width}x${bmp.height} over ${detail.rect})",
        )
        closeAndAssertClean(model)
    }

    @Test
    fun reader_realEngine_jpegWithHugeSofIsRefusedByGate_otherPageRenders() {
        val jpeg = quadrantJpeg()
        // The dictionary says 400x300 but the JPEG frame header says 30000x30000 (2.7 GB decoded).
        val hostile = listOf(0xC0, 0xC1, 0xC2).firstNotNullOfOrNull { marker ->
            runCatching { Fixtures.patchSof(jpeg, marker, HUGE, HUGE) }.getOrNull()
        } ?: error("no SOF in the fixture JPEG")
        val file = Fixtures.jpegThenPlainPdf(sharedDir, "hostile.pdf", hostile, IMAGE_W, IMAGE_H, PAGE_W, PAGE_H)
        val (model, openMs) = openInViewModel(file)
        val pidBefore = enginePid()
        assertNotNull("no :engine process after open", pidBefore)
        val page0 = onMain { model.page(0) }
        val page1 = onMain { model.page(1) }

        val full = ItemRect(0f, 0f, ITEM_W.toFloat(), ITEM_H.toFloat())
        val t1 = SystemClock.elapsedRealtime()
        onMain { model.onViewport(ITEM_W, 1f, listOf(VisiblePage(0, full))) }
        waitFor("hostile page result", state = { "doc=${model.document.value} page=${page0.value}" }) {
            page0.value.base != null || page0.value.error != null
        }
        val refuseMs = SystemClock.elapsedRealtime() - t1
        assertEquals(PdfError.OutOfMemory, page0.value.error)
        assertNull(page0.value.base)

        // The neighbour still renders, the document stays open, the engine process was not replaced.
        assertPlainPage(waitForBase(model, page1, "plain page"))
        assertEquals("document still open", ReaderDocState.Open::class, model.document.value::class)
        assertEquals("engine pid unchanged", pidBefore, enginePid())

        Log.i(TAG, "hostile jpeg page: open=${openMs}ms refused=${refuseMs}ms engine pid $pidBefore unchanged")
        closeAndAssertClean(model)
    }

    private companion object {
        const val TAG = "ReaderOnDevice"
        const val IMAGE_W = 400
        const val IMAGE_H = 300
        const val PAGE_W = 400f
        const val PAGE_H = 300f
        /** Phone-like item width; the 400x300 pt page is then 1080x810 px (2.7 px/pt). */
        const val ITEM_W = 1080
        const val ITEM_H = 810
        const val JPEG_TOLERANCE = 60
        const val EDGE_MARGIN = 30f
        const val HUGE = 30_000
    }
}
