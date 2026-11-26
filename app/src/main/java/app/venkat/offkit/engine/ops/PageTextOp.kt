package app.venkat.offkit.engine.ops

import app.venkat.offkit.engine.EngineStack
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageText
import app.venkat.offkit.engine.PageTextFormat
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfInputException
import app.venkat.offkit.engine.PdfInputs
import app.venkat.offkit.engine.PdfPageText
import app.venkat.offkit.engine.ScanMemo
import app.venkat.offkit.engine.isOutOfMemoryFailure
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.DisplayListDevice
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Implementation of [app.venkat.offkit.engine.PdfPageRenderer.pageText].
 *
 * Pre-checks, in the order of [RenderPageOp]: [PdfInputs.openForReader] with the [ScanMemo] key (size,
 * object-stream pre-scan, Encrypted / Corrupt), page index, [checkPageAnnots], then
 * [RenderImageGate] (memoised per page, shared with renders). The gate is needed: running a page
 * into any device but a "don't decode images" one makes the PDF interpreter load every image it
 * reaches (`pdf_load_image`: whole stream read, JPX header parsed, colour spaces / ICC loaded), even
 * though the structured-text device itself keeps no image without `preserve-images` (not used).
 *
 * Extraction: `Page.run` into a [DisplayList] with a [Cookie] that a watcher thread aborts on
 * cancel (`Page.toStructuredText` takes no cookie), then `DisplayList.toStructuredText("")` (default
 * options: no images, no dehyphenation) and a walk. Coordinates are translated by the top-left of
 * `Page.getBounds()`, as the render's ctm at scale 1, so they are in the space of [app.venkat.offkit.engine.PageDims].
 * Each char's box is the axis-aligned box of its quad (rotated text gets the enclosing box);
 * non-finite or absurd boxes are clamped to the page; invalid code points become U+FFFD.
 * Text past [maxUnits] is dropped (`truncated`). Empty lines are dropped.
 */
class PageTextOp(private val maxUnits: Int = PageTextFormat.MAX_UNITS) {
    fun run(pdf: File, page: Int, out: File, job: JobControl, password: String? = null): PdfPageText {
        val result = try {
            out.delete()
            EngineStack.run {
                try {
                    extract(pdf, page, out, job, password)
                } catch (e: PdfInputException) {
                    PdfPageText.Err(e.error)
                }
            }
        } catch (t: Throwable) {
            PdfPageText.Err(classifyRenderFailure(t))
        }
        if (result !is PdfPageText.Ok) {
            try { out.delete() } catch (ignored: Throwable) { /* best effort */ }
        }
        return result
    }

    private fun extract(pdf: File, index: Int, out: File, job: JobControl, password: String?): PdfPageText {
        if (index < 0) return invalid("page $index < 0")
        if (job.isCancelled()) return PdfPageText.Err(PdfError.Cancelled)
        val memo = ScanMemo.keyOf(pdf, password)
        val doc = PdfInputs.openForReader(pdf, password, memo)
        try {
            if (job.isCancelled()) return PdfPageText.Err(PdfError.Cancelled)
            val count = doc.countPages()
            if (index >= count) return invalid("page $index past the end ($count pages)")
            checkPageAnnots(doc, index)
            if (memo == null || !ScanMemo.pagePassed(memo, index)) {
                PdfInputs.checkGraph { RenderImageGate.checkPage(doc, index) { job.isCancelled() } }
                if (memo != null) ScanMemo.rememberPage(memo, index)
            }
            val page = loading { doc.loadPage(index) }
            try {
                val b = loading { page.bounds }
                val w = b.x1 - b.x0
                val h = b.y1 - b.y0
                if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return PdfPageText.Err(PdfError.Corrupt)
                val list = DisplayList(Rect(0f, 0f, w, h))
                try {
                    val cookie = Cookie()
                    try {
                        val watcher = CancelWatcher(job, cookie)
                        watcher.start()
                        try {
                            val dev = DisplayListDevice(list)
                            try {
                                page.run(dev, Matrix(1f, 0f, 0f, 1f, -b.x0, -b.y0), cookie)
                                dev.close()
                            } finally {
                                dev.destroy()
                            }
                        } catch (e: RuntimeException) {
                            if (job.isCancelled()) return PdfPageText.Err(PdfError.Cancelled)
                            throw e
                        } finally {
                            watcher.stopAndJoin()
                        }
                        if (job.isCancelled() || watcher.aborted) return PdfPageText.Err(PdfError.Cancelled)
                    } finally {
                        cookie.destroy()
                    }
                    val stext = list.toStructuredText("")
                    val text = try {
                        Collector(maxUnits, w, h).also { stext.walk(it) }.result()
                    } finally {
                        stext.destroy()
                    }
                    if (job.isCancelled()) return PdfPageText.Err(PdfError.Cancelled)
                    PageTextFormat.write(out, text)
                    return PdfPageText.Ok(text.length, text.truncated)
                } finally {
                    list.destroy()
                }
            } finally {
                page.destroy()
            }
        } finally {
            doc.destroy()
        }
    }

    private fun invalid(reason: String) = PdfPageText.Err(PdfError.Io(IllegalArgumentException(reason)))

    private inline fun <T> loading(block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        if (e is PdfInputException || isOutOfMemoryFailure(e)) throw e
        throw PdfInputException(PdfError.Corrupt, e)
    }

    /** Collects chars into flat arrays; never throws from a callback (JNI). */
    private class Collector(private val max: Int, private val w: Float, private val h: Float) : StructuredTextWalker {
        private val sb = StringBuilder()
        private var boxes = FloatArray(1024)
        private val starts = ArrayList<Int>()
        private val blocks = ArrayList<Int>()
        private var block = -1
        private var blockUsed = false
        private var lineStart = 0
        var truncated = false
            private set

        override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) = Unit
        override fun beginTextBlock(bbox: Rect?, justify: Int) { blockUsed = false }
        override fun endTextBlock() = Unit
        override fun beginLine(bbox: Rect?, wmode: Int, dir: Point?) { lineStart = sb.length }
        override fun endLine() {
            if (sb.length > lineStart) {
                if (!blockUsed) { block++; blockUsed = true }
                starts += lineStart
                blocks += block
            }
        }
        override fun beginStruct(standard: String?, raw: String?, index: Int) = Unit
        override fun endStruct() = Unit
        override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) = Unit

        override fun onChar(c: Int, origin: Point?, font: Font?, size: Float, q: Quad?, argb: Int, flags: Int, bidi: Int) {
            try { font?.destroy() } catch (ignored: Throwable) { /* wrapper only */ }
            if (truncated) return
            val cp = if (Character.isValidCodePoint(c) && !(c in 0xD800..0xDFFF)) c else 0xFFFD
            val units = Character.charCount(cp)
            if (sb.length + units > max) { truncated = true; return }
            var x0 = 0f; var y0 = 0f; var x1 = 0f; var y1 = 0f
            if (q != null) {
                x0 = min(min(q.ul_x, q.ur_x), min(q.ll_x, q.lr_x)); x1 = max(max(q.ul_x, q.ur_x), max(q.ll_x, q.lr_x))
                y0 = min(min(q.ul_y, q.ur_y), min(q.ll_y, q.lr_y)); y1 = max(max(q.ul_y, q.ur_y), max(q.ll_y, q.lr_y))
            }
            if (!(x0.isFinite() && y0.isFinite() && x1.isFinite() && y1.isFinite())) { x0 = 0f; y0 = 0f; x1 = 0f; y1 = 0f }
            val lim = PageTextFormat.MAX_COORD
            x0 = x0.coerceIn(-lim, lim); x1 = x1.coerceIn(-lim, lim); y0 = y0.coerceIn(-lim, lim); y1 = y1.coerceIn(-lim, lim)
            sb.appendCodePoint(cp)
            repeat(units) {
                val i = (sb.length - units + it) * 4
                if (i + 4 > boxes.size) boxes = boxes.copyOf(max(boxes.size * 2, i + 4))
                boxes[i] = x0; boxes[i + 1] = y0; boxes[i + 2] = x1; boxes[i + 3] = y1
            }
        }

        fun result(): PageText {
            // A line cut by truncation mid-way may have been left open: close it.
            if (truncated && sb.length > lineStart && (starts.isEmpty() || starts.last() != lineStart)) endLine()
            return PageText(
                sb.toString(), boxes.copyOf(sb.length * 4), starts.toIntArray(), blocks.toIntArray(), truncated,
            )
        }
    }

    /** As in [RenderPageOp]: polls [job] while `Page.run` runs and aborts [cookie] on cancel. */
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
        }, "offkit-text-cancel").apply { isDaemon = true }

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
    }
}
