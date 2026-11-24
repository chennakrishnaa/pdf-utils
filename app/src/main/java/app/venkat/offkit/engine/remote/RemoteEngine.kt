package app.venkat.offkit.engine.remote

import android.os.Bundle
import android.os.RemoteException
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
 * Calls are serialised (one request at a time). Do not call on the main thread.
 */
class RemoteEngine(
    private val connector: EngineConnector,
    private val pollMs: Long = 100,
    private val cancelGraceMs: Long = 2_000,
    private val deathWaitMs: Long = 1_000,
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
        data object Crashed : Reply
        data object Killed : Reply
    }

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
            Reply.Crashed -> PdfEstimate.Err(PdfError.EngineCrashed)
            Reply.Killed -> PdfEstimate.Err(PdfError.Cancelled)
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
            Reply.Crashed -> PreparedImage.Err(PdfError.EngineCrashed)
            Reply.Killed -> PreparedImage.Err(PdfError.Cancelled)
        }
    } finally {
        raw.delete()
    }

    private fun result(request: EngineRequest, out: File, job: JobControl): PdfResult =
        when (val r = call(request, job)) {
            is Reply.Done -> EngineWire.decodeResult(r.bundle).also { if (it is PdfResult.Err) out.delete() }
            Reply.Crashed -> PdfResult.Err(PdfError.EngineCrashed).also { out.delete() }
            Reply.Killed -> PdfResult.Err(PdfError.Cancelled).also { out.delete() }
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

    private fun call(request: EngineRequest, job: JobControl): Reply = synchronized(lock) {
        val s = connected() ?: return Reply.Crashed
        val service = s.service!!
        val id = nextId++
        val events = LinkedBlockingQueue<Event>()
        s.events = events
        if (s.lost) {
            drop(s)
            return Reply.Crashed
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
            return Reply.Crashed
        }

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
                    is Event.Progress -> job.progress(event.done, event.total)
                    is Event.Result -> {
                        s.events = null
                        return Reply.Done(event.bundle)
                    }
                    Event.Lost -> {
                        drop(s)
                        return Reply.Crashed
                    }
                    null -> Unit
                }
                if (cancelDeadline == 0L && (interrupted || job.isCancelled())) {
                    cancelDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cancelGraceMs)
                    try {
                        service.cancel(id)
                    } catch (e: RemoteException) {
                        // Death is reported through the session.
                    }
                }
                if (cancelDeadline != 0L && System.nanoTime() - cancelDeadline >= 0) {
                    killAndWait(s)
                    return Reply.Killed
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Kills the stuck engine and waits up to [deathWaitMs] for its death notification, so the next
     * call binds a new process instead of the dying one.
     */
    private fun killAndWait(s: Session) {
        connector.kill(s.pid)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deathWaitMs)
        while (!s.lost && System.nanoTime() - deadline < 0) {
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
}
