package app.venkat.offkit.core

import android.content.Context
import android.content.Intent
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
import app.venkat.offkit.engine.PdfPageText
import app.venkat.offkit.engine.PageText
import app.venkat.offkit.engine.PageTextFormat
import app.venkat.offkit.engine.PageTextParse
import app.venkat.offkit.engine.RenderLimits
import app.venkat.offkit.engine.RenderRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.venkat.offkit.engine.ReaderPassword
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
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
 * @property isEncrypted the PDF uses encryption (it needed a password, or has an owner password only).
 * @property canPrint / [canCopy] the PDF's permission bits as the engine reported them (true for an
 *   unencrypted PDF and for one opened with its owner password). Advisory: nothing enforces them.
 *
 * The password the document was opened with, if any, is kept in memory here for the document's
 * lifetime (MuPDF reopens the file on every engine call, so every info, render and text call sends
 * it); it is never written to disk, logged, saved in a `SavedStateHandle` or part of [toString].
 */
class ReaderDocument internal constructor(
    val name: String,
    val pages: List<PageDims>,
    internal val dir: File,
    internal val file: File,
    internal val password: String? = null,
    val isEncrypted: Boolean = false,
    val canPrint: Boolean = true,
    val canCopy: Boolean = true,
) {
    override fun toString() = "ReaderDocument(name=$name, pages=${pages.size}, encrypted=$isEncrypted)"

    /** Set once by [PdfReader.close]; read by the engine (as `job.isCancelled()`) on its own thread. */
    @Volatile
    internal var closed: Boolean = false
        private set

    internal fun markClosed() {
        closed = true
    }

    private val tileCounter = AtomicInteger()

    internal fun newTileFile(): File = File(dir, "tile-${tileCounter.incrementAndGet()}.rgba")

    internal fun newTextFile(): File = File(dir, "text-${tileCounter.incrementAndGet()}.bin")

    /** The last copy [PdfReader.prepareShare] made of this document (under `cacheDir/shared/`), or null. */
    @Volatile
    internal var sharedCopy: SharedCopy? = null

    /**
     * True if the document needs a password to open ([isEncrypted] and opened with a non-empty
     * one). A PDF with only an owner password opens without one and is not protected here.
     */
    val needsPassword: Boolean get() = isEncrypted && !password.isNullOrEmpty()
}

/**
 * The private copy of a password-protected PDF, kept between password attempts so a retry does not
 * read the Uri again (the transient grant may be gone). From [ReaderOpenResult.Failed.locked]; give
 * it to [PdfReader.unlock] with a password, or to [PdfReader.discard] when the user gives up (cancels
 * the password prompt, leaves the reader or picks another PDF). Holds no password.
 */
class LockedPdf internal constructor(val name: String, internal val dir: File, internal val file: File) {
    /** Set once by [PdfReader.discard] or when [PdfReader.unlock] hands the copy to a document or deletes it. */
    @Volatile
    internal var consumed: Boolean = false

    override fun toString() = "LockedPdf(name=$name)"
}

/** Result of [PdfReader.open] and [PdfReader.unlock]. */
sealed interface ReaderOpenResult {
    data class Opened(val document: ReaderDocument) : ReaderOpenResult

    /**
     * Nothing is left on disk, except when [locked] is not null: [error] is [PdfError.Encrypted]
     * (needs a password) or [PdfError.WrongPassword], the caller asked to keep the copy
     * (`keepLockedCopy`, or [PdfReader.unlock]), and the copy waits in [locked] for the next attempt.
     */
    data class Failed(val error: PdfError, val locked: LockedPdf? = null) : ReaderOpenResult
}

/** Result of [PdfReader.render]. */
sealed interface ReaderTile {
    /** A fresh `ARGB_8888` bitmap of exactly the requested size, not shared with anyone. */
    data class Ok(val bitmap: Bitmap) : ReaderTile

    data class Err(val error: PdfError) : ReaderTile
}

/** Result of [PdfReader.text]. */
sealed interface ReaderText {
    /** The page's text and geometry (zero length for a page without text). */
    data class Ok(val text: PageText) : ReaderText

    data class Err(val error: PdfError) : ReaderText
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

    /** How long [open] waits for the provider to name a Uri's type (see `checkType`). Lowered in tests. */
    @Volatile
    internal var typeTimeoutMillis: Long = TYPE_TIMEOUT_MILLIS

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
     *
     * With [checkType] (an "Open with" intent that named no type), the provider is first asked
     * for the Uri's type on the IO dispatcher, with a [typeTimeoutMillis] limit; anything but
     * `application/pdf` (another type, none, an exception, no answer in time) is `Io`, before
     * any copy is made.
     *
     * Passwords: [password] (null or empty: none) is sent with the page-info call; a PDF with only
     * an owner password opens without one. With [keepLockedCopy], a PDF that needs a password
     * (`Failed(Encrypted)`) or got a wrong one (`Failed(WrongPassword)`) keeps its copy in
     * [ReaderOpenResult.Failed.locked] for [unlock]; the caller must then [unlock] or [discard] it.
     * Without it the copy is deleted as for any failure.
     */
    suspend fun open(
        uri: Uri,
        checkType: Boolean = false,
        password: String? = null,
        keepLockedCopy: Boolean = false,
    ): ReaderOpenResult {
        if (!UriIo.isContentUri(uri)) return ReaderOpenResult.Failed(PdfError.Io(IOException("Not a content Uri")))
        password?.let { p -> ReaderPassword.check(p)?.let { return ReaderOpenResult.Failed(PdfError.Io(IllegalArgumentException(it))) } }
        val dir = File(jobs.jobsRoot, READER_DIR_PREFIX + UUID.randomUUID())
        val file = File(dir, DOCUMENT_NAME)
        var keep = false
        try {
            if (checkType && !providerSaysPdf(uri)) throw IOException("Not a PDF")
            val name = withContext(jobs.ioDispatcher) {
                if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
                val ctx = currentCoroutineContext()
                val picked = UriIo.describe(appContext, uri)
                // Larger than the engine accepts: fail before copying (as PdfInputs does, OutOfMemory).
                if ((picked.sizeBytes ?: 0L) > maxInputBytes) throw UriIo.InputTooLargeException(maxInputBytes)
                UriIo.copyToFile(appContext, uri, file, isCancelled = { !ctx.isActive }, maxBytes = maxInputBytes)
                picked.name
            }
            val result = readInfo(name, dir, file, password, if (keepLockedCopy) LockedPdf(name, dir, file) else null)
            keep = result is ReaderOpenResult.Opened || (result is ReaderOpenResult.Failed && result.locked != null)
            return result
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
     * Tries [password] on [locked] (from a `Failed` of [open] with `keepLockedCopy`, or of an earlier
     * [unlock]) without copying the file again. `Opened`: the copy now belongs to the document
     * (close it with [close]); `Failed(WrongPassword or Encrypted, locked)`: the same copy waits for
     * another attempt; any other `Failed`: the copy is deleted (`locked` null). A [locked] already
     * discarded or used gives `Failed(Cancelled)`. A [password] that fails [ReaderPassword.check]
     * gives `Failed(Io(IllegalArgumentException), locked)` without calling the engine.
     * Cancelling the caller deletes the copy and rethrows.
     */
    suspend fun unlock(locked: LockedPdf, password: String): ReaderOpenResult {
        if (locked.consumed) return ReaderOpenResult.Failed(PdfError.Cancelled)
        ReaderPassword.check(password)?.let { return ReaderOpenResult.Failed(PdfError.Io(IllegalArgumentException(it)), locked) }
        var keep = false
        try {
            val result = readInfo(locked.name, locked.dir, locked.file, password, locked)
            keep = result is ReaderOpenResult.Opened || (result is ReaderOpenResult.Failed && result.locked != null)
            if (result is ReaderOpenResult.Opened) locked.consumed = true
            return result
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            return ReaderOpenResult.Failed(PdfError.Cancelled)
        } catch (e: OutOfMemoryError) {
            return ReaderOpenResult.Failed(PdfError.OutOfMemory)
        } catch (e: Exception) {
            return ReaderOpenResult.Failed(PdfError.Io(e))
        } finally {
            if (!keep) discard(locked)
        }
    }

    /**
     * The user gave up on [locked] (cancelled the password prompt, left the reader, opened another
     * PDF): its copy is deleted on the engine thread. Safe to call more than once; never blocks.
     */
    fun discard(locked: LockedPdf) {
        locked.consumed = true
        jobs.processScope.launch(jobs.engineDispatcher) {
            try {
                locked.dir.deleteRecursively()
            } catch (e: Exception) {
                // Best effort; PdfJobs.get wipes cacheDir/jobs in the next process.
            }
        }
    }

    /**
     * Page info of the copy [file] with [password]; `Failed(.., lockedOut)` for a password error
     * when [lockedOut] is not null. Throws what [open] catches.
     */
    private suspend fun readInfo(name: String, dir: File, file: File, password: String?, lockedOut: LockedPdf?): ReaderOpenResult {
        val engine = renderer() ?: return ReaderOpenResult.Failed(PdfError.Io(IOException("Engine cannot render pages")))
        val pw = password?.takeIf { it.isNotEmpty() }
        val info = withContext(jobs.engineDispatcher) {
            val job = coroutineContext.job
            if (pw == null) engine.pageInfo(file, cancelWhen { !job.isActive }) else engine.pageInfo(file, pw, cancelWhen { !job.isActive })
        }
        currentCoroutineContext().ensureActive()
        return when (info) {
            is PdfPageInfo.Err -> {
                val passwordError = info.error == PdfError.Encrypted || info.error == PdfError.WrongPassword
                ReaderOpenResult.Failed(info.error, if (passwordError) lockedOut else null)
            }
            is PdfPageInfo.Ok -> {
                val pages = info.pages.map { sanePageDims(it) }
                if (pages.isEmpty() || pages.size > RenderLimits.MAX_PAGES || pages.any { it == null }) {
                    ReaderOpenResult.Failed(PdfError.Corrupt)
                } else {
                    ReaderOpenResult.Opened(
                        ReaderDocument(
                            name, pages.filterNotNull(), dir, file,
                            password = if (info.encrypted) pw else null,
                            isEncrypted = info.encrypted,
                            canPrint = info.canPrint,
                            canCopy = info.canCopy,
                        ),
                    )
                }
            }
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
                    document.password.let { pw ->
                        // Without a password, the plain call (as before K1).
                        if (pw == null) engine.renderPage(document.file, request, out, cancelWhen { document.closed })
                        else engine.renderPage(document.file, request, out, pw, cancelWhen { document.closed })
                    }
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
            // Only this call's own file: the folder is deleted on the engine thread alone
            // (two concurrent deleteRecursively walks of one folder can fail).
            withContext(NonCancellable + jobs.ioDispatcher) { out.delete() }
            if (document.closed) deleteFolder(document)
        }
    }

    /**
     * The text of page [page] of [document] with every character's box, in the space of
     * [ReaderDocument.pages] (see [PageText] for the hit-test, word, highlight and copy helpers).
     * Runs on the engine thread like [render] (never concurrently with it); the engine's file is
     * parsed with [PageTextFormat.read] on the IO dispatcher and deleted in every case. A malformed
     * file or a reply whose length disagrees with the file is `Err(Io)`; a closed document
     * `Err(Cancelled)`. Cancelling the caller behaves as for [render].
     */
    suspend fun text(document: ReaderDocument, page: Int): ReaderText {
        if (document.closed) return ReaderText.Err(PdfError.Cancelled)
        if (page !in document.pages.indices) {
            return ReaderText.Err(PdfError.Io(IllegalArgumentException("page $page out of range")))
        }
        val engine = renderer() ?: return ReaderText.Err(PdfError.Io(IOException("Engine cannot read text")))
        val out = document.newTextFile()
        try {
            val result = withContext(jobs.engineDispatcher) {
                if (document.closed) {
                    PdfPageText.Err(PdfError.Cancelled)
                } else {
                    document.password.let { pw ->
                        if (pw == null) engine.pageText(document.file, page, out, cancelWhen { document.closed })
                        else engine.pageText(document.file, page, out, pw, cancelWhen { document.closed })
                    }
                }
            }
            return when (result) {
                is PdfPageText.Err -> ReaderText.Err(result.error)
                is PdfPageText.Ok -> if (document.closed) {
                    ReaderText.Err(PdfError.Cancelled)
                } else {
                    when (val p = withContext(jobs.ioDispatcher) { PageTextFormat.read(out) }) {
                        is PageTextParse.Err -> ReaderText.Err(PdfError.Io(IOException("Bad page text: ${p.reason}")))
                        is PageTextParse.Ok ->
                            if (p.text.length != result.units || p.text.truncated != result.truncated) {
                                ReaderText.Err(PdfError.Io(IOException("Page text does not match the reply")))
                            } else {
                                ReaderText.Ok(p.text)
                            }
                    }
                }
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            return ReaderText.Err(PdfError.Cancelled)
        } catch (e: OutOfMemoryError) {
            return ReaderText.Err(PdfError.OutOfMemory)
        } catch (e: Exception) {
            return ReaderText.Err(PdfError.Io(e))
        } finally {
            // Only this call's own file: the folder is deleted on the engine thread alone
            // (two concurrent deleteRecursively walks of one folder can fail).
            withContext(NonCancellable + jobs.ioDispatcher) { out.delete() }
            if (document.closed) deleteFolder(document)
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
        deleteFolder(document)
    }

    /**
     * Opens [document]'s private copy (the original bytes, unchanged) for reading. Throws
     * [IOException] if the document is closed or its copy is gone. The returned channel keeps the
     * bytes readable after [close] deletes the folder (the file is only unlinked), so a reader of
     * it never races the deletion; the caller closes it. Blocking: call off the main thread.
     */
    fun openSource(document: ReaderDocument): FileChannel {
        if (document.closed) throw IOException("Document closed")
        return FileInputStream(document.file).channel
    }

    /**
     * Copies [document]'s private copy, byte for byte, to `cacheDir/shared/<random id>/<name>` (name:
     * [PdfJobs.shareFileName] of the display name) on the IO dispatcher and returns its share Uri.
     * [onProgress] gets (copied, total) bytes on the IO dispatcher. Nothing else under
     * `cacheDir/shared/` is touched (a job result's shared copy stays); the copy lives until the
     * next job start or process start ([PdfJobs.wipeShared], [PdfJobs.get]). Sharing the same
     * document again reuses its copy while it still exists. Throws [IOException] (the partial copy
     * is deleted) or [CancellationException].
     */
    suspend fun prepareShare(document: ReaderDocument, onProgress: (Long, Long) -> Unit = { _, _ -> }): ShareRequest =
        withContext(jobs.ioDispatcher) {
            val name = PdfJobs.shareFileName(document.name)
            val source = openSource(document)
            source.use { channel ->
                val total = channel.size()
                document.sharedCopy?.let { copy ->
                    if (copy.request.name == name && copy.file.isFile && copy.file.length() == total) {
                        return@withContext copy.request
                    }
                }
                jobs.copyToShared(Channels.newInputStream(channel), name, total, onProgress).also {
                    document.sharedCopy = it
                }.request
            }
        }

    /**
     * Deletes [document]'s folder on the engine thread, so deletions never run concurrently with
     * each other or with an engine call writing into the folder. Queued, never blocks. Engine
     * calls for a closed document are refused before they start, so nothing is written after.
     */
    private fun deleteFolder(document: ReaderDocument) {
        jobs.processScope.launch(jobs.engineDispatcher) {
            try {
                document.dir.deleteRecursively()
            } catch (e: Exception) {
                // Best effort; PdfJobs.get wipes cacheDir/jobs in the next process.
            }
        }
    }

    /**
     * True if the provider reports [uri]'s type as `application/pdf`. Asked on the IO dispatcher,
     * never the main thread; a provider that throws or does not answer within [typeTimeoutMillis]
     * counts as "not a PDF". The call runs detached, as a blocked binder call cannot be
     * interrupted: a provider that never answers pins one IO thread until the system's provider
     * timeout, and its late answer is dropped.
     */
    private suspend fun providerSaysPdf(uri: Uri): Boolean = withContext(jobs.ioDispatcher) {
        val type = jobs.processScope.async(jobs.ioDispatcher) {
            try {
                appContext.contentResolver.getType(uri)
            } catch (e: Exception) {
                null
            }
        }
        val resolved = withTimeoutOrNull(typeTimeoutMillis) { type.await() }
        type.cancel()
        Intent.normalizeMimeType(resolved) == MIME_PDF
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
        private const val MIME_PDF = "application/pdf"

        /** Default for [typeTimeoutMillis]. */
        const val TYPE_TIMEOUT_MILLIS = 3_000L

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
