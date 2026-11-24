package app.venkat.offkit.engine.remote

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import app.venkat.offkit.core.ImagePreparer
import app.venkat.offkit.core.InProcessImagePreparer
import app.venkat.offkit.core.PreparedImage
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfResult
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The binder served by [EngineService] in the `:engine` process. Plain Kotlin apart from the
 * AIDL stub, so JVM tests can run it in-process with a fake [PdfEngine].
 *
 * Each request runs on [worker] (one thread, so the engine is never used concurrently). Before
 * running, every path of the request must resolve (canonical path) inside [jobsRoot]; otherwise
 * the result is `Err(Io(SecurityException))` and the engine is not called. Progress and the
 * result are sent from the worker thread through the request's own callback binder, so they
 * arrive in order.
 *
 * **Client gone (FR-2).** The watchdog lives in the client (UI) process, so an engine whose
 * client is gone must not keep working unwatched, nor be shared with a new client's job. While
 * a request is queued or running, the server links to the death of its callback binder. If that
 * binder dies, a callback throws [RemoteException], or [onUnbound] is called (the service was
 * unbound or destroyed), it deletes the `out` of every pending request (best effort) and calls
 * [killProcess]. An unbind with nothing pending does not kill. [EngineService] hands out one
 * server per process, so two jobs never run in MuPDF at once.
 *
 * @param engine created on first use, on the worker thread.
 * @param jobsRoot `cacheDir/jobs` of this app.
 * @param killProcess ends this process; the default kills it with `Process.killProcess`.
 * @param linkDeath links `onDeath` to the death of a client binder and returns how to unlink it,
 *   or null if the binder is already dead.
 */
class EngineServer(
    engine: () -> PdfEngine,
    private val jobsRoot: File,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "offkit-engine-worker").apply { isDaemon = true }
    },
    private val myPid: () -> Int = { Process.myPid() },
    private val preparer: ImagePreparer = InProcessImagePreparer,
    private val killProcess: () -> Unit = { Process.killProcess(Process.myPid()) },
    private val linkDeath: (IBinder, () -> Unit) -> (() -> Unit)? = ::linkToDeath,
) : IEngineService.Stub() {
    private val engine by lazy(engine)

    /** A request that was accepted and has not sent its result yet. */
    private class Pending(val out: File?, val unlink: () -> Unit)

    /** Queued or running requests by id. */
    private val pending = ConcurrentHashMap<Long, Pending>()

    /** True while any request is queued or running. */
    val busy: Boolean get() = pending.isNotEmpty()

    /** Id of the request asked to cancel (only that request observes it). */
    private val cancelledId = AtomicLong(Long.MIN_VALUE)

    override fun pid(): Int = myPid()

    override fun run(requestId: Long, request: Bundle?, callback: IEngineCallback?) {
        if (callback == null) return
        val trusted = Binder.getCallingUid() == Process.myUid()
        val out = try {
            request?.let { EngineWire.decodeRequest(it).out }?.let(::File)
        } catch (e: Exception) {
            null
        }
        val unlink = linkDeath(callback.asBinder()) { onClientGone() }
        if (unlink == null) {
            // The client died before its request arrived: do not run it; end any older ones.
            onClientGone()
            return
        }
        pending[requestId] = Pending(out, unlink)
        worker.execute { handle(requestId, request, callback, trusted) }
    }

    /**
     * The service was unbound or destroyed. If a request is still queued or running, its client
     * is gone: [onClientGone]. Otherwise nothing happens.
     */
    fun onUnbound() {
        if (busy) onClientGone()
    }

    /** Deletes the partial output of every pending request, then kills this process. */
    private fun onClientGone() {
        synchronized(pending) {
            if (pending.isEmpty()) return
            for (p in pending.values) {
                try {
                    p.out?.delete()
                } catch (e: SecurityException) {
                    // Best effort.
                }
            }
            killProcess()
        }
    }

    /** Forgets [id] before its result is sent, so an unbind right after a success kills nothing. */
    private fun finished(id: Long) {
        pending.remove(id)?.let { it.unlink() }
    }

    override fun cancel(requestId: Long) {
        cancelledId.set(requestId)
    }

    private fun handle(id: Long, bundle: Bundle?, callback: IEngineCallback, trusted: Boolean) {
        var clientGone = false
        var decoded: EngineRequest? = null
        val reply: Bundle = try {
            if (!trusted) throw SecurityException("caller is not this app")
            val request = EngineWire.decodeRequest(bundle ?: throw IllegalArgumentException("no request"))
            decoded = request
            checkPaths(jobsRoot, request.paths)
            val job = object : JobControl {
                override fun progress(done: Int, total: Int) {
                    if (clientGone) return
                    try {
                        callback.progress(id, done, total)
                    } catch (e: RemoteException) {
                        clientGone = true
                        onClientGone()
                    }
                }

                override fun isCancelled(): Boolean = clientGone || cancelledId.get() == id
            }
            val out = request.out?.let(::File)
            try {
                when (val value = request.execute({ engine }, preparer, job)) {
                    is PreparedImage -> EngineWire.encodePrepared(checkPrepared(value), myPid())
                    else -> EngineWire.encodeAny(value)
                }
            } catch (e: OutOfMemoryError) {
                out?.delete()
                errorFor(request, PdfError.OutOfMemory)
            } catch (e: Throwable) {
                out?.delete()
                errorFor(request, PdfError.Io(e))
            }
        } catch (e: Exception) {
            decoded?.let { errorFor(it, PdfError.Io(e)) } ?: EngineWire.encodeResult(PdfResult.Err(PdfError.Io(e)))
        }
        finished(id)
        try {
            callback.result(id, reply)
        } catch (e: RemoteException) {
            // Client gone: nothing to report to.
        }
    }

    private fun errorFor(request: EngineRequest, error: PdfError): Bundle =
        if (request is EngineRequest.Estimate) {
            EngineWire.encodeEstimate(app.venkat.offkit.engine.PdfEstimate.Err(error))
        } else {
            EngineWire.encodeResult(PdfResult.Err(error))
        }

    /** A prepared image must also lie inside [jobsRoot]; otherwise it is deleted and rejected. */
    private fun checkPrepared(p: PreparedImage): PreparedImage {
        if (p !is PreparedImage.Ok) return p
        return try {
            checkPaths(jobsRoot, listOf(p.file.path))
            p
        } catch (e: SecurityException) {
            p.file.delete()
            PreparedImage.Err(PdfError.Io(e))
        }
    }

    companion object {
        private fun linkToDeath(binder: IBinder, onDeath: () -> Unit): (() -> Unit)? {
            val recipient = IBinder.DeathRecipient { onDeath() }
            try {
                binder.linkToDeath(recipient, 0)
            } catch (e: RemoteException) {
                return null
            }
            return {
                try {
                    binder.unlinkToDeath(recipient, 0)
                } catch (e: RuntimeException) {
                    // Already dead or unlinked.
                }
            }
        }

        /**
         * Throws [SecurityException] unless every path in [paths] resolves, after
         * canonicalisation (symlinks and `..` resolved), strictly inside [jobsRoot].
         */
        fun checkPaths(jobsRoot: File, paths: List<String>) {
            val root = try {
                jobsRoot.canonicalPath
            } catch (e: IOException) {
                throw SecurityException("cannot resolve jobs directory")
            }
            val prefix = root + File.separator
            for (p in paths) {
                val canonical = try {
                    File(p).canonicalPath
                } catch (e: IOException) {
                    throw SecurityException("cannot resolve path")
                }
                if (!File(p).isAbsolute || !canonical.startsWith(prefix)) {
                    throw SecurityException("path outside the jobs directory")
                }
            }
        }
    }
}
