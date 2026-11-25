package app.venkat.offkit.engine.ops

import app.venkat.offkit.engine.EngineStack
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfInputException
import app.venkat.offkit.engine.PdfInputs
import app.venkat.offkit.engine.PdfPageInfo
import app.venkat.offkit.engine.PdfRender
import app.venkat.offkit.engine.RenderLimits
import app.venkat.offkit.engine.RenderRequest
import app.venkat.offkit.engine.classifyFailure
import app.venkat.offkit.engine.isOutOfMemoryFailure
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Rect
import java.io.File
import java.io.FileOutputStream

/** Displayed size of a page from its `Page.getBounds()` (rotation applied), or null if unusable. */
internal fun pageDimsOf(bounds: Rect): PageDims? {
    val w = bounds.x1 - bounds.x0
    val h = bounds.y1 - bounds.y0
    return if (w.isFinite() && h.isFinite() && w > 0f && h > 0f) PageDims(w, h) else null
}

/** A non-OOM MuPDF failure while loading a page or reading its bounds means the page is unusable. */
private inline fun <T> loadingPage(block: () -> T): T = try {
    block()
} catch (e: RuntimeException) {
    if (e is PdfInputException || isOutOfMemoryFailure(e)) throw e
    throw PdfInputException(PdfError.Corrupt, e)
}

/**
 * Implementation of [app.venkat.offkit.engine.PdfPageRenderer.pageInfo].
 *
 * Opens [pdf] with [PdfInputs.open] (size / object-stream pre-checks, Encrypted and Corrupt
 * classification, zero pages → Corrupt). More than [RenderLimits.MAX_PAGES] pages is
 * `Err(OutOfMemory)`, decided from the page count before any page is loaded. Then each page is
 * loaded, its bounds read and the page destroyed at once, so only one `Page` is alive at a time.
 * A page that fails to load, or whose size is not finite and > 0, is `Err(Corrupt)`.
 * Cancellation is polled before every page; progress is reported every [PROGRESS_EVERY] pages.
 */
class PageInfoOp {
    fun run(pdf: File, job: JobControl): PdfPageInfo = guarded {
        if (job.isCancelled()) return@guarded PdfPageInfo.Err(PdfError.Cancelled)
        val doc = PdfInputs.open(pdf)
        try {
            val count = doc.countPages()
            if (count > RenderLimits.MAX_PAGES) return@guarded PdfPageInfo.Err(PdfError.OutOfMemory)
            if (count <= 0) return@guarded PdfPageInfo.Err(PdfError.Corrupt)
            val dims = ArrayList<PageDims>(count)
            for (i in 0 until count) {
                if (job.isCancelled()) return@guarded PdfPageInfo.Err(PdfError.Cancelled)
                val d = loadingPage {
                    val page = doc.loadPage(i)
                    try { pageDimsOf(page.bounds) } finally { page.destroy() }
                } ?: return@guarded PdfPageInfo.Err(PdfError.Corrupt)
                dims.add(d)
                if ((i + 1) % PROGRESS_EVERY == 0 || i + 1 == count) job.progress(i + 1, count)
            }
            PdfPageInfo.Ok(dims)
        } finally {
            doc.destroy()
        }
    }

    private inline fun guarded(crossinline block: () -> PdfPageInfo): PdfPageInfo = try {
        EngineStack.run {
            try {
                block()
            } catch (e: PdfInputException) {
                PdfPageInfo.Err(e.error)
            }
        }
    } catch (t: Throwable) {
        PdfPageInfo.Err(classifyFailure(t))
    }

    private companion object {
        const val PROGRESS_EVERY = 100
    }
}

/**
 * Implementation of [app.venkat.offkit.engine.PdfPageRenderer.renderPage].
 *
 * Validates the request with [RenderLimits.check] before opening and again with the page's
 * [PageDims] (same [pageDimsOf] as [PageInfoOp]) after loading it; an invalid request or a page
 * past the end is `Err(Io(IllegalArgumentException))`. The document is opened per call (MuPDF
 * objects live on the [EngineStack] thread of this call only).
 *
 * Geometry: the page is drawn with `ctm = [s 0 0 s (-x0*s - left) (-y0*s - top)]`, where
 * `(x0, y0)` is the top-left of `Page.getBounds()` (rotation applied by MuPDF), into a pixmap of
 * exactly `width x height` device-RGB pixels cleared to white. So the output's (0, 0) is the
 * scaled page's pixel (left, top) whatever the page's MediaBox/CropBox origin or /Rotate.
 * `Page.run` draws the contents, annotations and form widgets, as a viewer shows them.
 *
 * Memory: one native RGB pixmap (3 bytes per pixel, 24 MB at [RenderLimits.MAX_PIXELS]) plus its
 * Java copy from `getSamples()` (another 24 MB), and a 64 KB write buffer; RGB is expanded to
 * opaque RGBA while streaming to [out]. Peak about 48 MB for a maximal tile, plus MuPDF's page
 * resources (decoded images, fonts).
 *
 * Cancellation: polled before opening, after opening and before writing; during `Page.run` a
 * watcher thread polls [JobControl.isCancelled] every [WATCH_MS] ms and calls `Cookie.abort()`
 * (which only sets the cookie's abort flag, safe from another thread). MuPDF checks the flag
 * between content-stream operators, so one very expensive single operator (a huge image or path)
 * is not interrupted. A cancelled or aborted render never writes [out].
 *
 * On any non-Ok outcome (including a thrown exception) [out] is deleted. [out] is written in place
 * (no temporary sibling that a watchdog kill could orphan); the client deletes it on any failure.
 */
class RenderPageOp {
    fun run(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender {
        val result = try {
            out.delete()
            EngineStack.run {
                try {
                    render(pdf, request, out, job)
                } catch (e: PdfInputException) {
                    PdfRender.Err(e.error)
                }
            }
        } catch (t: Throwable) {
            PdfRender.Err(classifyRenderFailure(t))
        }
        if (result !is PdfRender.Ok) {
            try {
                out.delete()
            } catch (ignored: Throwable) {
                // Best effort: the error result is what matters.
            }
        }
        return result
    }

    private fun invalid(reason: String) = PdfRender.Err(PdfError.Io(IllegalArgumentException(reason)))

    private fun render(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender {
        RenderLimits.check(request, null)?.let { return invalid(it) }
        if (job.isCancelled()) return PdfRender.Err(PdfError.Cancelled)
        val doc = PdfInputs.open(pdf)
        try {
            if (job.isCancelled()) return PdfRender.Err(PdfError.Cancelled)
            val count = doc.countPages()
            if (request.page >= count) return invalid("page ${request.page} past the end ($count pages)")
            val page = loadingPage { doc.loadPage(request.page) }
            try {
                val bounds = loadingPage { page.bounds }
                val dims = pageDimsOf(bounds) ?: return PdfRender.Err(PdfError.Corrupt)
                RenderLimits.check(request, dims)?.let { return invalid(it) }
                val samples = draw(page, bounds, request, job) ?: return PdfRender.Err(PdfError.Cancelled)
                if (job.isCancelled()) return PdfRender.Err(PdfError.Cancelled)
                writeRgba(samples, request.width, request.height, out)
                return PdfRender.Ok(request.width, request.height)
            } finally {
                page.destroy()
            }
        } finally {
            doc.destroy()
        }
    }

    /** Draws the requested tile; returns the pixmap's packed RGB samples, or null if cancelled. */
    private fun draw(page: Page, b: Rect, r: RenderRequest, job: JobControl): Samples? {
        val s = r.scale
        val ctm = Matrix(s, 0f, 0f, s, -b.x0 * s - r.left, -b.y0 * s - r.top)
        val pix = Pixmap(ColorSpace.DeviceRGB, 0, 0, r.width, r.height, false)
        try {
            pix.clear(255)
            val cookie = Cookie()
            try {
                val watcher = CancelWatcher(job, cookie)
                watcher.start()
                try {
                    val dev = DrawDevice(pix)
                    try {
                        page.run(dev, ctm, cookie)
                        dev.close()
                    } finally {
                        dev.destroy()
                    }
                } catch (e: RuntimeException) {
                    // An aborted run may throw; a cancel is reported as such, not as a failure.
                    if (job.isCancelled()) return null
                    throw e
                } finally {
                    watcher.stopAndJoin() // before cookie.destroy(): the watcher may still call abort()
                }
                if (job.isCancelled() || watcher.aborted) return null
            } finally {
                cookie.destroy()
            }
            val n = pix.numberOfComponents
            val stride = pix.stride
            check(n == 3 && pix.width == r.width && pix.height == r.height && stride >= r.width * 3) {
                "unexpected pixmap layout n=$n stride=$stride ${pix.width}x${pix.height}"
            }
            return Samples(pix.samples, stride)
        } finally {
            pix.destroy()
        }
    }

    private class Samples(val bytes: ByteArray, val stride: Int)

    /** Expands packed RGB rows to opaque RGBA and writes exactly `width * height * 4` bytes. */
    private fun writeRgba(s: Samples, width: Int, height: Int, out: File) {
        val rowBytes = width * 4
        val buf = ByteArray(maxOf(rowBytes, WRITE_BUFFER))
        FileOutputStream(out).use { os ->
            var fill = 0
            for (y in 0 until height) {
                if (fill + rowBytes > buf.size) {
                    os.write(buf, 0, fill)
                    fill = 0
                }
                var src = y * s.stride
                for (x in 0 until width) {
                    buf[fill] = s.bytes[src]
                    buf[fill + 1] = s.bytes[src + 1]
                    buf[fill + 2] = s.bytes[src + 2]
                    buf[fill + 3] = OPAQUE
                    fill += 4
                    src += 3
                }
            }
            if (fill > 0) os.write(buf, 0, fill)
        }
    }

    /** Polls [job] and aborts [cookie] when it is cancelled. Runs only while `Page.run` runs. */
    private class CancelWatcher(private val job: JobControl, private val cookie: Cookie) {
        @Volatile private var stop = false
        @Volatile var aborted = false
            private set
        private val thread = Thread({
            try {
                while (!stop) {
                    if (job.isCancelled()) {
                        cookie.abort()
                        aborted = true
                        return@Thread
                    }
                    Thread.sleep(WATCH_MS)
                }
            } catch (ignored: InterruptedException) {
                // Asked to stop.
            }
        }, "offkit-render-cancel").apply { isDaemon = true }

        fun start() = thread.start()

        fun stopAndJoin() {
            stop = true
            thread.interrupt()
            var interrupted = false
            while (true) {
                try {
                    thread.join()
                    break
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val WATCH_MS = 50L
        const val WRITE_BUFFER = 64 * 1024
        const val OPAQUE: Byte = -1 // 0xFF
    }
}

/**
 * [classifyFailure], plus the binding's `getSamples()` failure to allocate its Java array
 * ("cannot create byte array"), which is an out-of-memory condition.
 */
internal fun classifyRenderFailure(t: Throwable): PdfError =
    if (t is RuntimeException && t.message.orEmpty().contains("cannot create byte array")) {
        PdfError.OutOfMemory
    } else {
        classifyFailure(t)
    }
