package app.venkat.offkit.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.createBitmap
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.engine.ImageLimits
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfPageInfo
import app.venkat.offkit.engine.PdfPageRenderer
import app.venkat.offkit.engine.PdfRender
import app.venkat.offkit.engine.RenderLimits
import app.venkat.offkit.engine.RenderRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * An open reader document: a private copy of the picked PDF under `cacheDir/jobs/reader-<uuid>/`.
 * Obtained from [PdfReader.open]; give it back to [PdfReader.close] when done (the copy and every
 * tile are deleted then). The picked Uri is not kept.
 *
 * @property name display name of the picked file (sanitised, from [UriIo.describe]).
 * @property pages size of every page, in page order (at least one).
 */
class ReaderDocument internal constructor(
    val name: String,
    val pages: List<PageDims>,
    internal val dir: File,
    internal val file: File,
) {
    /** Set once by [PdfReader.close]; read by the engine (as `job.isCancelled()`) on its own thread. */
    @Volatile
    internal var closed: Boolean = false
        private set

    internal fun markClosed() {
        closed = true
    }

    private val tileCounter = AtomicInteger()

    internal fun newTileFile(): File = File(dir, "tile-${tileCounter.incrementAndGet()}.rgba")
}

/** Result of [PdfReader.open]. */
sealed interface ReaderOpenResult {
    data class Opened(val document: ReaderDocument) : ReaderOpenResult

    /** Nothing is left on disk. */
    data class Failed(val error: PdfError) : ReaderOpenResult
}

/** Result of [PdfReader.render]. */
sealed interface ReaderTile {
    /** A fresh `ARGB_8888` bitmap of exactly the requested size, not shared with anyone. */
    data class Ok(val bitmap: Bitmap) : ReaderTile

    data class Err(val error: PdfError) : ReaderTile
}

/**
 * Process-level page access for the in-app reader. Obtain it with [PdfReader.get].
 *
 * Every engine call runs on [PdfJobs]' single engine thread, so reader calls and PDF jobs are
 * serialised and at most one render runs at a time. The UI process never decodes image data: a
 * tile is the engine's raw RGBA reply, size-checked and copied into a bitmap with
 * `Bitmap.copyPixelsFromBuffer` (see [tileToBitmap]).
 *
 * Cancellation contract: an engine call is asked to stop (`job.isCancelled()`) only when its
 * document is closed. Cancelling the coroutine of a [render] does not cancel the engine call (an
 * engine that does not answer a cancel within 2 s is killed): the call is let finish and its
 * result is dropped. Cancelling an [open] does ask the engine to stop (that document is being
 * abandoned).
 */
class PdfReader internal constructor(
    context: Context,
    private val jobs: PdfJobs,
    /** Largest PDF [open] accepts (the engine's own input limit; lowered in tests). */
    private val maxInputBytes: Long = ImageLimits.MAX_INPUT_PDF_BYTES,
) {
    private val appContext: Context = context.applicationContext ?: context

    /**
     * Copies the PDF at [uri] (a `content:` Uri, read once with its transient grant) to a fresh
     * `cacheDir/jobs/reader-<uuid>/` directory on the IO dispatcher, then reads its page sizes on
     * the engine thread.
     *
     * Failures (the directory is deleted): non-`content:` or unreadable Uri: `Io`; a file larger
     * than [ImageLimits.MAX_INPUT_PDF_BYTES] (the engine's input limit): `OutOfMemory`, as in the
     * other flows, before copying when the provider reports the size, else as soon as the copy
     * passes the limit; the engine's
     * `Encrypted`, `Corrupt`, `OutOfMemory`, `EngineCrashed`, ...; a document with no pages or with
     * an invalid page size: `Corrupt`. Page sizes are passed through [sanePageDims]. Cancelling the caller deletes the directory and rethrows.
     */
    suspend fun open(uri: Uri): ReaderOpenResult {
        if (!UriIo.isContentUri(uri)) return ReaderOpenResult.Failed(PdfError.Io(IOException("Not a content Uri")))
        val dir = File(jobs.jobsRoot, READER_DIR_PREFIX + UUID.randomUUID())
        val file = File(dir, DOCUMENT_NAME)
        var keep = false
        try {
            val name = withContext(jobs.ioDispatcher) {
                if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
                val ctx = currentCoroutineContext()
                val picked = UriIo.describe(appContext, uri)
                // Larger than the engine accepts: fail before copying (as PdfInputs does, OutOfMemory).
                if ((picked.sizeBytes ?: 0L) > maxInputBytes) throw UriIo.InputTooLargeException(maxInputBytes)
                UriIo.copyToFile(appContext, uri, file, isCancelled = { !ctx.isActive }, maxBytes = maxInputBytes)
                picked.name
            }
            val engine = renderer() ?: return ReaderOpenResult.Failed(PdfError.Io(IOException("Engine cannot render pages")))
            val info = withContext(jobs.engineDispatcher) {
                val job = coroutineContext.job
                engine.pageInfo(file, cancelWhen { !job.isActive })
            }
            currentCoroutineContext().ensureActive()
            return when (info) {
                is PdfPageInfo.Err -> ReaderOpenResult.Failed(info.error)
                is PdfPageInfo.Ok -> {
                    val pages = info.pages.map { sanePageDims(it) }
                    if (pages.isEmpty() || pages.size > RenderLimits.MAX_PAGES || pages.any { it == null }) {
                        ReaderOpenResult.Failed(PdfError.Corrupt)
                    } else {
                        keep = true
                        ReaderOpenResult.Opened(ReaderDocument(name, pages.filterNotNull(), dir, file))
                    }
                }
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            return ReaderOpenResult.Failed(PdfError.Cancelled)
        } catch (e: OutOfMemoryError) {
            return ReaderOpenResult.Failed(PdfError.OutOfMemory)
        } catch (e: UriIo.InputTooLargeException) {
            return ReaderOpenResult.Failed(PdfError.OutOfMemory)
        } catch (e: Exception) {
            return ReaderOpenResult.Failed(PdfError.Io(e))
        } finally {
            if (!keep) withContext(NonCancellable + jobs.ioDispatcher) { dir.deleteRecursively() }
        }
    }

    /**
     * Renders [request] of [document] on the engine thread and returns it as a bitmap. The request
     * is checked with [RenderLimits.check] against the page's size first (`Err(Io)` if invalid).
     * The engine's reply is untrusted: its size must equal the request's and the tile file must
     * hold exactly `width * height * 4` bytes, else `Err(Io)`. The tile file is deleted in every
     * case. A closed document gives `Err(Cancelled)`.
     *
     * Cancelling the caller does not interrupt an engine call already running (see the class
     * KDoc); the call finishes, its tile is deleted, and the [CancellationException] is rethrown.
     */
    suspend fun render(document: ReaderDocument, request: RenderRequest): ReaderTile {
        if (document.closed) return ReaderTile.Err(PdfError.Cancelled)
        val dims = document.pages.getOrNull(request.page)
            ?: return ReaderTile.Err(PdfError.Io(IllegalArgumentException("page ${request.page} out of range")))
        RenderLimits.check(request, dims)?.let { return ReaderTile.Err(PdfError.Io(IllegalArgumentException(it))) }
        val engine = renderer() ?: return ReaderTile.Err(PdfError.Io(IOException("Engine cannot render pages")))
        val out = document.newTileFile()
        try {
            val result = withContext(jobs.engineDispatcher) {
                if (document.closed) {
                    PdfRender.Err(PdfError.Cancelled)
                } else {
                    engine.renderPage(document.file, request, out, cancelWhen { document.closed })
                }
            }
            return when (result) {
                is PdfRender.Err -> ReaderTile.Err(result.error)
                is PdfRender.Ok -> {
                    if (result.width != request.width || result.height != request.height) {
                        ReaderTile.Err(PdfError.Io(IOException("Engine replied ${result.width}x${result.height}")))
                    } else if (document.closed) {
                        ReaderTile.Err(PdfError.Cancelled)
                    } else {
                        withContext(jobs.ioDispatcher) { ReaderTile.Ok(tileToBitmap(out, request.width, request.height)) }
                    }
                }
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            return ReaderTile.Err(PdfError.Cancelled)
        } catch (e: OutOfMemoryError) {
            return ReaderTile.Err(PdfError.OutOfMemory)
        } catch (e: Exception) {
            return ReaderTile.Err(PdfError.Io(e))
        } finally {
            withContext(NonCancellable + jobs.ioDispatcher) {
                out.delete()
                if (document.closed) document.dir.deleteRecursively()
            }
        }
    }

    /**
     * Closes [document]: an engine call running for it is asked to stop (`job.isCancelled()`), and
     * its directory is deleted on the engine thread once that call has returned (queued behind it
     * in the process scope, so it happens even if the caller goes away). Safe to call more than
     * once and from any thread; never blocks.
     */
    fun close(document: ReaderDocument) {
        document.markClosed()
        jobs.processScope.launch(jobs.engineDispatcher) {
            try {
                document.dir.deleteRecursively()
            } catch (e: Exception) {
                // Best effort; PdfJobs.get wipes cacheDir/jobs in the next process.
            }
        }
    }

    private fun renderer(): PdfPageRenderer? = (appContext as PdfUtilApp).engine as? PdfPageRenderer

    private fun cancelWhen(cancelled: () -> Boolean) = object : JobControl {
        override fun progress(done: Int, total: Int) = Unit
        override fun isCancelled(): Boolean = cancelled()
    }

    companion object {
        /** Smallest page side kept by [sanePageDims], in PDF points. */
        const val MIN_PAGE_PT = 1f

        /** Largest page side kept by [sanePageDims], in PDF points: 200 times PDF's 14,400 pt limit (UserUnit). */
        const val MAX_PAGE_PT = 200f * 14_400f

        /**
         * The page size the reader works with for an engine-reported [dims], or null if [dims] is
         * not a size at all (a side not finite or not > 0: the reply is broken, the document is
         * `Corrupt`). Each side is clamped to `[MIN_PAGE_PT, MAX_PAGE_PT]`.
         *
         * Why clamp rather than refuse: a real document may hold one absurd page among good ones,
         * and the rest should still open. Within this range every product the reader computes
         * (scales, pixel counts, item sizes) is finite and fits a Long, whatever the reply held.
         * A side clamped down only makes requests cover part of the real page, which the engine
         * accepts; a side clamped up (under 1 pt, not a printable page) may make the engine refuse
         * that page's requests, which shows as a per-page error, never a crash.
         */
        fun sanePageDims(dims: PageDims): PageDims? {
            val w = dims.widthPt
            val h = dims.heightPt
            if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return null
            return PageDims(w.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT), h.coerceIn(MIN_PAGE_PT, MAX_PAGE_PT))
        }
        /** Prefix of reader directories under `cacheDir/jobs`. */
        const val READER_DIR_PREFIX = "reader-"
        private const val DOCUMENT_NAME = "document.pdf"

        @Volatile
        private var instance: PdfReader? = null

        /** The process-wide reader, on [PdfJobs.get] (whose first call wipes `cacheDir/jobs`). */
        fun get(context: Context): PdfReader {
            val jobs = PdfJobs.get(context)
            instance?.let { if (it.jobs === jobs) return it }
            return synchronized(this) {
                instance?.takeIf { it.jobs === jobs } ?: PdfReader(context, jobs).also { instance = it }
            }
        }

        /**
         * Turns an engine tile into a bitmap without decoding anything: checks that [file] holds
         * exactly `width * height * 4` bytes (RGBA, rows top to bottom), then copies them into a new
         * `ARGB_8888` bitmap with `copyPixelsFromBuffer` (the same byte layout). Does not delete
         * [file]. Throws [IOException] on a size mismatch or a short read.
         */
        internal fun tileToBitmap(file: File, width: Int, height: Int): Bitmap {
            if (width < 1 || height < 1 || width.toLong() * height > RenderLimits.MAX_PIXELS) {
                throw IOException("Invalid tile size ${width}x$height")
            }
            val expected = width.toLong() * height * 4
            val length = file.length()
            if (length != expected) throw IOException("Tile holds $length bytes, expected $expected")
            val buffer = ByteBuffer.allocate(expected.toInt())
            FileInputStream(file).channel.use { ch ->
                while (buffer.hasRemaining()) {
                    if (ch.read(buffer) < 0) throw IOException("Tile is shorter than $expected bytes")
                }
                if (ch.read(ByteBuffer.allocate(1)) > 0) throw IOException("Tile is longer than $expected bytes")
            }
            buffer.rewind()
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }
    }
}
