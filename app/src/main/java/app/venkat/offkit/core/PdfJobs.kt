package app.venkat.offkit.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.VisibleForTesting
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfResult
import app.venkat.offkit.engine.remote.EngineProcess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

/** One input of a job, in the order the engine should receive it. */
sealed interface JobInput {
    /** A PDF document; copied verbatim into the job directory. */
    data class Pdf(val uri: Uri) : JobInput

    /** An image; normalised to JPEG/PNG by [ImageNormalizer]. */
    data class Image(val uri: Uri) : JobInput
}

/**
 * A finished output waiting in the app cache for the user to pick a destination. Hand it to
 * [PdfJobs.save] or [PdfJobs.discard]; both remove it from the cache (save only on success).
 *
 * @property file the output PDF (inside `cacheDir/jobs/<id>/`).
 * @property pages page count reported by the engine.
 * @property bytes output size in bytes as reported by the engine (`PdfResult.Ok.bytes`).
 */
class PendingOutput internal constructor(
    val file: File,
    val pages: Int,
    val bytes: Long,
    internal val jobDir: File,
)

/** Result of [PdfJobs.run]. */
sealed interface JobOutcome {
    /** The engine succeeded; [output] is pending until saved or discarded. */
    data class Ready(val output: PendingOutput) : JobOutcome

    /** The job failed; nothing is left in the cache. */
    data class Failed(val error: PdfError) : JobOutcome
}

/**
 * Runs [PdfEngine] operations on content Uris. Obtain the process-wide instance with
 * [PdfJobs.get]. Scratch files live under `cacheDir/jobs/<uuid>/` and are deleted on success
 * (inputs), failure and cancellation (everything).
 *
 * Every engine call in the process runs on one dedicated thread owned by this class
 * ([ENGINE_DISPATCHER]), as the [PdfEngine] threading contract requires.
 */
class PdfJobs @VisibleForTesting internal constructor(
    context: Context,
    private val engineDispatcher: CoroutineDispatcher = ENGINE_DISPATCHER,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val openDestination: (Context, Uri) -> OutputStream? = { ctx, uri ->
        ctx.contentResolver.openOutputStream(uri, "wt")
    },
) {
    private val appContext: Context = context.applicationContext ?: context

    /**
     * Process-lifetime scope on the main dispatcher for work that must finish even when the
     * screen that started it goes away (saving a result). Never cancelled; its children must
     * catch their own exceptions.
     */
    val processScope: CoroutineScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    /** `cacheDir/jobs`, the root of all job directories. */
    @VisibleForTesting
    internal val jobsRoot: File get() = File(appContext.cacheDir, JOBS_DIR)

    /**
     * Prepares [inputs] and runs [block] on the engine thread.
     *
     * 1. Creates `cacheDir/jobs/<uuid>/`; copies each [JobInput.Pdf] and normalises each
     *    [JobInput.Image] (on the IO dispatcher), in input order.
     * 2. Calls [block] on the engine thread with the app's current [PdfEngine] (read now, not
     *    at construction), the prepared files in the same order as [inputs], the output file to
     *    write, and a [JobControl] whose `isCancelled()` is true once the calling coroutine is
     *    cancelled and whose `progress` is forwarded to [onProgress] on the main dispatcher.
     *    All progress callbacks have been delivered by the time `run` returns. [block] must be
     *    plain blocking code that only calls the engine; it must not keep the files.
     * 3. On `PdfResult.Ok` returns [JobOutcome.Ready] (input copies are deleted, only the output
     *    stays). Otherwise deletes the whole job directory and returns [JobOutcome.Failed]:
     *    the engine's error; `UnsupportedImage` for undecodable images; `Io` for unreadable
     *    inputs or any exception; `OutOfMemory` for [OutOfMemoryError].
     *
     * Cancellation: if the calling coroutine is cancelled, `run` waits for the engine call (if
     * one is in progress) to return, deletes the job directory, then rethrows the
     * [CancellationException] (structured concurrency). If the engine itself returns
     * `Err(Cancelled)` while the caller is still active, the result is `Failed(Cancelled)`.
     */
    suspend fun run(
        inputs: List<JobInput>,
        onProgress: (done: Int, total: Int) -> Unit,
        block: (engine: PdfEngine, files: List<File>, out: File, job: JobControl) -> PdfResult,
    ): JobOutcome {
        val jobDir = File(jobsRoot, UUID.randomUUID().toString())
        val inDir = File(jobDir, "in")
        val out = File(jobDir, OUTPUT_NAME)
        var keep = false
        try {
            val files = prepareInputs(inputs, inDir)

            val result = runOnEngine(files, out, onProgress, block)

            return if (result is PdfResult.Ok) {
                withContext(NonCancellable + ioDispatcher) { inDir.deleteRecursively() }
                if (!out.isFile) {
                    JobOutcome.Failed(PdfError.Io(IOException("Engine reported success but wrote no output")))
                } else {
                    keep = true
                    JobOutcome.Ready(PendingOutput(out, result.pages, result.bytes, jobDir))
                }
            } else {
                JobOutcome.Failed((result as PdfResult.Err).error)
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            return JobOutcome.Failed(PdfError.Cancelled)
        } catch (e: UnsupportedImageException) {
            return JobOutcome.Failed(PdfError.UnsupportedImage)
        } catch (e: OutOfMemoryError) {
            return JobOutcome.Failed(PdfError.OutOfMemory)
        } catch (e: Exception) {
            return JobOutcome.Failed(PdfError.Io(e))
        } finally {
            if (!keep) withContext(NonCancellable + ioDispatcher) { jobDir.deleteRecursively() }
        }
    }

    /**
     * Copies each [JobInput.Pdf] and normalises each [JobInput.Image] into [inDir] (created if
     * needed) on the IO dispatcher, in input order. Throws [IOException],
     * [UnsupportedImageException] or [CancellationException]; the caller deletes [inDir].
     */
    private suspend fun prepareInputs(inputs: List<JobInput>, inDir: File): List<File> = withContext(ioDispatcher) {
        if (!inDir.mkdirs() && !inDir.isDirectory) throw IOException("Cannot create $inDir")
        val ctx = currentCoroutineContext()
        val cancelled = { !ctx.isActive }
        inputs.mapIndexed { index, input ->
            ctx.ensureActive()
            when (input) {
                is JobInput.Pdf -> File(inDir, String.format(Locale.ROOT, "in_%03d.pdf", index)).also {
                    UriIo.copyToFile(appContext, input.uri, it, cancelled)
                }
                is JobInput.Image -> ImageNormalizer.normalize(appContext, input.uri, inDir, index, cancelled)
            }
        }
    }

    /**
     * Estimates the image data size of the image at [uri] for `PdfEngine.imagesToPdf` (see
     * [PdfEngine.estimateImage]).
     *
     * The image is prepared exactly like a [JobInput.Image] of [run] (copied and normalised by
     * [ImageNormalizer] into a private `cacheDir/jobs/<uuid>/` directory), then
     * `engine.estimateImage(file)` runs on the same single engine thread as [run]. The directory is
     * always deleted before this returns or throws. Undecodable image: `Err(UnsupportedImage)`;
     * unreadable Uri or any other exception: `Err(Io)`; [OutOfMemoryError]: `Err(OutOfMemory)`.
     *
     * Cancellation: cancelling the caller while copying or while waiting for the engine thread
     * stops the work; an engine call already in progress is waited for (it is not interruptible),
     * then the directory is deleted and the [CancellationException] is rethrown.
     */
    suspend fun estimateImage(uri: Uri): PdfEstimate {
        val jobDir = File(jobsRoot, UUID.randomUUID().toString())
        try {
            val file = prepareInputs(listOf(JobInput.Image(uri)), File(jobDir, "in")).single()
            return withContext(engineDispatcher) { (appContext as PdfUtilApp).engine.estimateImage(file) }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            return PdfEstimate.Err(PdfError.Cancelled)
        } catch (e: UnsupportedImageException) {
            return PdfEstimate.Err(PdfError.UnsupportedImage)
        } catch (e: OutOfMemoryError) {
            return PdfEstimate.Err(PdfError.OutOfMemory)
        } catch (e: Exception) {
            return PdfEstimate.Err(PdfError.Io(e))
        } finally {
            withContext(NonCancellable + ioDispatcher) { jobDir.deleteRecursively() }
        }
    }

    private suspend fun runOnEngine(
        files: List<File>,
        out: File,
        onProgress: (Int, Int) -> Unit,
        block: (PdfEngine, List<File>, File, JobControl) -> PdfResult,
    ): PdfResult = coroutineScope {
        val progress = Channel<Pair<Int, Int>>(Channel.CONFLATED)
        val forwarder = launch(mainDispatcher) {
            for ((done, total) in progress) onProgress(done, total)
        }
        try {
            withContext(engineDispatcher) {
                val engineJob = coroutineContext.job
                val control = object : JobControl {
                    override fun progress(done: Int, total: Int) {
                        progress.trySend(done to total)
                    }

                    override fun isCancelled(): Boolean = !engineJob.isActive
                }
                val engine = (appContext as PdfUtilApp).engine
                block(engine, files, out, control)
            }
        } finally {
            progress.close()
            forwarder.join()
        }
    }

    /**
     * Copies [output] to [dest] (truncating it) on the IO dispatcher. Success is reported only
     * after the data has been written, flushed and the destination closed without error; the
     * job directory is then deleted.
     *
     * On any failure after the destination was opened (including a cancellation of the caller)
     * the destination document is deleted best-effort (see [deleteDestination]) so no truncated
     * file is left behind; a failure before that deletes it only if it is empty
     * ([deleteDestinationIfEmpty]). [output] is kept
     * so the caller can retry with another [dest]. Failures are returned as
     * `Result.failure`; a cancellation is rethrown after the cleanup.
     */
    suspend fun save(output: PendingOutput, dest: Uri): Result<Unit> = withContext(ioDispatcher) {
        var opened = false
        try {
            if (!output.file.isFile) throw IOException("Output no longer exists")
            val ctx = currentCoroutineContext()
            val stream = openDestination(appContext, dest) ?: throw IOException("Cannot open destination")
            opened = true
            stream.use { out ->
                UriIo.copyToStream(output.file, out) { !ctx.isActive }
                if (out is FileOutputStream) {
                    try {
                        out.fd.sync()
                    } catch (e: IOException) {
                        // Best effort: pipes and some providers cannot sync.
                    }
                }
            }
            output.jobDir.deleteRecursively()
            Result.success(Unit)
        } catch (e: Throwable) {
            // Once opened with "wt" the destination holds our partial data: always delete it.
            // Before that it may be an existing file the user chose to replace: delete only if empty.
            withContext(NonCancellable) { if (opened) deleteDestination(dest) else deleteDestinationIfEmpty(dest) }
            when (e) {
                is CancellationException -> throw e
                is Exception -> Result.failure(e)
                else -> Result.failure(IOException("Save failed", e))
            }
        }
    }

    /**
     * Deletes the document at [dest] best-effort: `DocumentsContract.deleteDocument` for
     * document Uris, [File.delete] for `file:` Uris; anything else and every failure is
     * ignored. Blocking; call off the main thread. Only for documents this app just created.
     */
    fun deleteDestination(dest: Uri) {
        try {
            when {
                dest.scheme == ContentResolver.SCHEME_FILE -> dest.path?.let { File(it).delete() }
                DocumentsContract.isDocumentUri(appContext, dest) ->
                    DocumentsContract.deleteDocument(appContext.contentResolver, dest)
            }
        } catch (e: Exception) {
            // Best effort.
        }
    }

    /**
     * Deletes [dest] like [deleteDestination], but only if it is known to be empty (0 bytes), so
     * an existing document the user chose to replace is never removed unless it was already
     * overwritten. Blocking; never throws.
     */
    fun deleteDestinationIfEmpty(dest: Uri) {
        val empty = try {
            if (dest.scheme == ContentResolver.SCHEME_FILE) {
                dest.path?.let(::File)?.let { it.isFile && it.length() == 0L } == true
            } else {
                UriIo.describe(appContext, dest).sizeBytes == 0L
            }
        } catch (e: Exception) {
            false
        }
        if (empty) deleteDestination(dest)
    }

    /** Deletes [output] and its job directory. Safe to call more than once. */
    fun discard(output: PendingOutput) {
        output.jobDir.deleteRecursively()
    }

    /** Deletes everything under `cacheDir/jobs/`. Do not call while a job is running. */
    fun wipeAll() {
        jobsRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        private const val JOBS_DIR = "jobs"
        private const val OUTPUT_NAME = "output.pdf"

        /** The single thread on which every engine call in the process runs. */
        private val ENGINE_DISPATCHER: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pdf-engine").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        @Volatile
        private var instance: PdfJobs? = null

        /**
         * The process-wide instance, bound to the application context. The first call wipes
         * `cacheDir/jobs/` (leftovers of a previous process). Cheap; may be called on any thread.
         */
        fun get(context: Context): PdfJobs {
            val app = context.applicationContext ?: context
            instance?.let { if (it.appContext === app) return it }
            // The engine process shares cacheDir/jobs with the UI process: it must never wipe it.
            EngineProcess.checkNotEngineProcess("PdfJobs")
            return synchronized(this) {
                instance?.takeIf { it.appContext === app }
                    ?: PdfJobs(app).also {
                        it.wipeAll()
                        instance = it
                    }
            }
        }

        /** Forgets the singleton so the next [get] creates (and wipes) a fresh one. */
        @VisibleForTesting
        internal fun resetForTesting() {
            synchronized(this) { instance = null }
        }
    }
}
