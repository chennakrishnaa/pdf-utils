package app.venkat.offkit.engine.remote

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import app.venkat.offkit.core.ImagePreparer
import app.venkat.offkit.core.PreparedImage
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfEstimate
import app.venkat.offkit.engine.PdfResult
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [PdfEngine] that runs every call in the `:engine` process ([EngineService]), so a file that
 * crashes MuPDF or exhausts its memory kills only that process.
 *
 * Each call binds if needed ([EngineConnector.connect]), sends the request (paths only) and blocks
 * the calling thread until the result arrives or the engine dies. While blocked it delivers
 * progress to `job.progress` on the calling thread, in order and before returning, and polls
 * `job.isCancelled()` about every [pollMs]. On cancel it asks the service to cancel; if no result
 * arrives within [cancelGraceMs] it kills the engine process and returns `Err(Cancelled)`.
 *
 * If the engine dies, disconnects, or cannot be bound within the connector's timeout, the call
 * returns `Err(EngineCrashed)` (estimates: `PdfEstimate.Err(EngineCrashed)`) and deletes a partial
 * `out`; the next call binds a fresh engine. It never falls back to running MuPDF in this process.
 *
 * It is also the app's [ImagePreparer]: image normalisation (platform codecs) runs in the engine
 * process too. A death during it gives `PreparedImage.Err(EngineCrashed)`, a hard cancel
 * `PreparedImage.Err(Cancelled)`; the raw file is deleted in every case.
 *
 * **Watchdog** ([limits]): MuPDF calls cannot be interrupted, so while waiting the client also
 * kills the engine and returns `Err(TimedOut)` when a PDF operation goes [EngineLimits.stallMs]
 * without a progress event or result, or runs past [EngineLimits.totalMs] (image estimate or
 * normalisation: [EngineLimits.imageTotalMs]); and returns `Err(OutOfMemory)` when the engine's
 * resident memory, sampled about every [EngineLimits.memorySampleMs] through [rssBytes], exceeds
 * [EngineLimits.memoryBytes]. If [rssBytes] cannot read it, the memory check is skipped (logged
 * once). A user cancel takes precedence over the watchdog and still returns `Cancelled`. After a
 * watchdog kill, partial `out` is deleted and the next call binds a new engine, as for a hard cancel.
 *
 * Calls are serialised (one request at a time). Do not call on the main thread.
 *
 * @param limits watchdog limits; the app passes the default times and a memory limit from
 *   [EngineLimits.memoryLimitFor]. Tests pass small ones.
 * @param rssBytes resident set size in bytes of an engine pid, or null if unknown.
 * @param nanoTime monotonic clock for every deadline here (polling itself waits in real time).
 * @param log where the one-time "cannot read the engine's memory" warning goes.
 */
class RemoteEngine(
    private val connector: EngineConnector,
    private val pollMs: Long = 100,
    private val cancelGraceMs: Long = 2_000,
    private val deathWaitMs: Long = 1_000,
    private val limits: EngineLimits = EngineLimits(),
    private val rssBytes: (pid: Int) -> Long? = ProcRss::bytes,
    private val nanoTime: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : PdfEngine, ImagePreparer {
    private sealed interface Event {
        data class Progress(val done: Int, val total: Int) : Event
        data class Result(val bundle: Bundle) : Event
        data object Lost : Event
    }

    /** One binding of the engine service. */
    private class Session {
        @Volatile var service: IEngineService? = null
        @Volatile var pid: Int = 0
        @Volatile var lost = false
        @Volatile var events: LinkedBlockingQueue<Event>? = null

        fun markLost() {
            lost = true
            events?.offer(Event.Lost)
        }
    }

    private sealed interface Reply {
        data class Done(val bundle: Bundle) : Reply

        /**
         * No result: the engine died ([PdfError.EngineCrashed]) or was killed for a hard cancel
         * ([PdfError.Cancelled]) or by the watchdog ([PdfError.TimedOut], [PdfError.OutOfMemory]).
         */
        data class Failed(val error: PdfError) : Reply
    }

    private val crashed = Reply.Failed(PdfError.EngineCrashed)

    @Volatile
    private var rssUnreadableLogged = false

    private val lock = Any()
    private var session: Session? = null
    private var nextId = 1L

    override fun imagesToPdf(
        images: List<File>,
        pageSize: PageSize,
        out: File,
        job: JobControl,
        compression: CompressionLevel?,
    ): PdfResult = result(EngineRequest.ImagesToPdf(images.map { it.path }, pageSize, out.path, compression), out, job)

    override fun estimateImage(image: File): PdfEstimate =
        when (val r = call(EngineRequest.Estimate(image.path), JobControl.None)) {
            is Reply.Done -> EngineWire.decodeEstimate(r.bundle)
            is Reply.Failed -> PdfEstimate.Err(r.error)
        }

    override fun compress(pdf: File, level: CompressionLevel, out: File, job: JobControl): PdfResult =
        result(EngineRequest.Compress(pdf.path, level, out.path), out, job)

    override fun merge(pdfs: List<File>, out: File, job: JobControl): PdfResult =
        result(EngineRequest.Merge(pdfs.map { it.path }, out.path), out, job)

    override fun appendImages(pdf: File, images: List<File>, out: File, job: JobControl): PdfResult =
        result(EngineRequest.AppendImages(pdf.path, images.map { it.path }, out.path), out, job)

    /** Process id of the engine that last normalised an image successfully (0: none yet). For tests. */
    @Volatile
    var lastImagePreparedByPid: Int = 0
        private set

    override fun prepareImage(raw: File, destDir: File, index: Int, job: JobControl): PreparedImage = try {
        when (val r = call(EngineRequest.NormalizeImage(raw.path, destDir.path, index), job)) {
            is Reply.Done -> EngineWire.decodePrepared(r.bundle, destDir, index).also {
                if (it is PreparedImage.Ok) lastImagePreparedByPid = EngineWire.preparedPid(r.bundle)
            }
            is Reply.Failed -> PreparedImage.Err(r.error)
        }
    } finally {
        raw.delete()
    }

    private fun result(request: EngineRequest, out: File, job: JobControl): PdfResult =
        when (val r = call(request, job)) {
            is Reply.Done -> EngineWire.decodeResult(r.bundle).also { if (it is PdfResult.Err) out.delete() }
            is Reply.Failed -> PdfResult.Err(r.error).also { out.delete() }
        }

    /** The live session, binding a new one if needed; null if binding failed. */
    private fun connected(): Session? {
        session?.let { if (!it.lost) return it else drop(it) }
        val s = Session()
        val service = connector.connect { s.markLost() } ?: return null
        s.service = service
        try {
            s.pid = service.pid()
        } catch (e: RemoteException) {
            s.lost = true
        } catch (e: RuntimeException) {
            s.lost = true
        }
        if (s.lost) {
            connector.disconnect()
            return null
        }
        session = s
        return s
    }

    private fun drop(s: Session) {
        s.events = null
        if (session === s) {
            session = null
            connector.disconnect()
        }
    }

    /**
     * Sends [request] and waits for its reply. If the request could not even be handed to the
     * engine (the binder was already dead, e.g. the engine was killed while idle and its death
     * notification had not arrived yet), it was never started, so it is sent once more on a
     * freshly bound engine.
     */
    private fun call(request: EngineRequest, job: JobControl): Reply = synchronized(lock) {
        attempt(request, job) ?: attempt(request, job) ?: crashed
    }

    /** One send-and-wait; null if the request never reached the engine. Holds [lock]. */
    private fun attempt(request: EngineRequest, job: JobControl): Reply? {
        val s = connected() ?: return crashed
        val service = s.service!!
        val id = nextId++
        val events = LinkedBlockingQueue<Event>()
        s.events = events
        if (s.lost) {
            drop(s)
            return null
        }
        val callback = object : IEngineCallback.Stub() {
            override fun progress(requestId: Long, done: Int, total: Int) {
                if (requestId == id) events.offer(Event.Progress(done, total))
            }

            override fun result(requestId: Long, result: Bundle?) {
                if (requestId == id && result != null) events.offer(Event.Result(result))
            }
        }
        try {
            service.run(id, EngineWire.encodeRequest(request), callback)
        } catch (e: RemoteException) {
            drop(s)
            return null
        }

        val sentAt = nanoTime()
        val totalNs = TimeUnit.MILLISECONDS.toNanos(if (request.isImageRequest()) limits.imageTotalMs else limits.totalMs)
        val stallNs = TimeUnit.MILLISECONDS.toNanos(limits.stallMs)
        var lastActivity = sentAt
        var nextRssSample = sentAt
        var cancelDeadline = 0L
        var interrupted = false
        try {
            while (true) {
                val event = try {
                    events.poll(pollMs, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    interrupted = true
                    null
                }
                when (event) {
                    is Event.Progress -> {
                        lastActivity = nanoTime()
                        job.progress(event.done, event.total)
                    }
                    is Event.Result -> {
                        s.events = null
                        return Reply.Done(event.bundle)
                    }
                    Event.Lost -> {
                        drop(s)
                        return crashed
                    }
                    null -> Unit
                }
                if (cancelDeadline == 0L && (interrupted || job.isCancelled())) {
                    cancelDeadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(cancelGraceMs)
                    try {
                        service.cancel(id)
                    } catch (e: RemoteException) {
                        // Death is reported through the session.
                    }
                }
                if (cancelDeadline != 0L) {
                    // A user cancel wins over the watchdog: it ends as Cancelled either way.
                    if (nanoTime() - cancelDeadline >= 0) {
                        killAndWait(s)
                        return Reply.Failed(PdfError.Cancelled)
                    }
                    continue
                }
                val now = nanoTime()
                if (now - lastActivity >= stallNs || now - sentAt >= totalNs) {
                    killAndWait(s)
                    return Reply.Failed(PdfError.TimedOut)
                }
                if (limits.memoryBytes != Long.MAX_VALUE && s.pid > 0 && now - nextRssSample >= 0) {
                    nextRssSample = now + TimeUnit.MILLISECONDS.toNanos(limits.memorySampleMs)
                    if (rssOverLimit(s.pid)) {
                        killAndWait(s)
                        return Reply.Failed(PdfError.OutOfMemory)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** True if [pid]'s resident memory exceeds the limit. Unreadable: false (fail open), logged once. */
    private fun rssOverLimit(pid: Int): Boolean {
        val rss = try {
            rssBytes(pid)
        } catch (e: RuntimeException) {
            null
        }
        if (rss == null) {
            if (!rssUnreadableLogged) {
                rssUnreadableLogged = true
                log("Cannot read the memory use of engine pid $pid; the engine memory limit is not enforced")
            }
            return false
        }
        return rss > limits.memoryBytes
    }

    private fun EngineRequest.isImageRequest() =
        this is EngineRequest.Estimate || this is EngineRequest.NormalizeImage

    /**
     * Kills the stuck engine and waits up to [deathWaitMs] for its death notification, so the next
     * call binds a new process instead of the dying one.
     */
    private fun killAndWait(s: Session) {
        connector.kill(s.pid)
        val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(deathWaitMs)
        while (!s.lost && nanoTime() - deadline < 0) {
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        s.lost = true
        drop(s)
    }

    private companion object {
        const val TAG = "OffkitEngine"
    }
}
