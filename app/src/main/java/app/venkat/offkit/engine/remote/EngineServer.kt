package app.venkat.offkit.engine.remote

import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.RemoteException
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfResult
import java.io.File
import java.io.IOException
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
 * arrive in order. A callback that throws [RemoteException] (client gone) cancels the request.
 *
 * @param engine created on first use, on the worker thread.
 * @param jobsRoot `cacheDir/jobs` of this app.
 */
class EngineServer(
    engine: () -> PdfEngine,
    private val jobsRoot: File,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "offkit-engine-worker").apply { isDaemon = true }
    },
    private val myPid: () -> Int = { Process.myPid() },
) : IEngineService.Stub() {
    private val engine by lazy(engine)

    /** Id of the request asked to cancel (only that request observes it). */
    private val cancelledId = AtomicLong(Long.MIN_VALUE)

    override fun pid(): Int = myPid()

    override fun run(requestId: Long, request: Bundle?, callback: IEngineCallback?) {
        if (callback == null) return
        val trusted = Binder.getCallingUid() == Process.myUid()
        worker.execute { handle(requestId, request, callback, trusted) }
    }

    override fun cancel(requestId: Long) {
        cancelledId.set(requestId)
    }

    private fun handle(id: Long, bundle: Bundle?, callback: IEngineCallback, trusted: Boolean) {
        var clientGone = false
        val reply: Bundle = try {
            if (!trusted) throw SecurityException("caller is not this app")
            val request = EngineWire.decodeRequest(bundle ?: throw IllegalArgumentException("no request"))
            checkPaths(jobsRoot, request.paths)
            val job = object : JobControl {
                override fun progress(done: Int, total: Int) {
                    if (clientGone) return
                    try {
                        callback.progress(id, done, total)
                    } catch (e: RemoteException) {
                        clientGone = true
                    }
                }

                override fun isCancelled(): Boolean = clientGone || cancelledId.get() == id
            }
            val out = request.out?.let(::File)
            try {
                EngineWire.encodeAny(request.execute(engine, job))
            } catch (e: OutOfMemoryError) {
                out?.delete()
                errorFor(request, PdfError.OutOfMemory)
            } catch (e: Throwable) {
                out?.delete()
                errorFor(request, PdfError.Io(e))
            }
        } catch (e: Exception) {
            EngineWire.encodeResult(PdfResult.Err(PdfError.Io(e)))
        }
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

    companion object {
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
