package app.venkat.offkit.engine.remote

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.venkat.offkit.engine.MuPdfEngine
import java.io.File

/**
 * Hosts the MuPDF engine in its own `:engine` process (see the manifest), so a hostile file that
 * exhausts memory or crashes natively only kills this process; the UI process sees the binder die
 * and reports `PdfError.EngineCrashed` ([RemoteEngine]).
 *
 * Not exported, no intent filter. Receives file paths only and accepts only paths inside this
 * app's `cacheDir/jobs/` ([EngineServer.checkPaths]). It never deletes job directories and never
 * binds to itself.
 *
 * One [EngineServer] (one worker thread, one MuPDF engine) per process: a service instance that
 * is destroyed and recreated in the same, still cached process gets the same server, so two
 * jobs can never run in MuPDF at once. When the service is unbound or destroyed while a request
 * is still queued or running, its client is gone and the server kills this process
 * ([EngineServer.onUnbound]).
 */
class EngineService : Service() {
    override fun onBind(intent: Intent?): IBinder = serverFor(this)

    override fun onUnbind(intent: Intent?): Boolean {
        shared?.onUnbound()
        return false
    }

    override fun onDestroy() {
        shared?.onUnbound()
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var shared: EngineServer? = null

        /** The process-wide server, created on first bind. */
        @Synchronized
        fun serverFor(service: Service): EngineServer =
            shared ?: EngineServer({ MuPdfEngine() }, File(service.cacheDir, JOBS_DIR)).also { shared = it }

        /** Must match `PdfJobs`'s job root (`cacheDir/jobs`). */
        const val JOBS_DIR = "jobs"

        /** Suffix of the engine process name (`android:process` in the manifest). */
        const val PROCESS_SUFFIX = ":engine"
    }
}
