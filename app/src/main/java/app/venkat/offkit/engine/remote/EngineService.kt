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
 */
class EngineService : Service() {
    private lateinit var server: EngineServer

    override fun onCreate() {
        super.onCreate()
        server = EngineServer({ MuPdfEngine() }, File(cacheDir, JOBS_DIR))
    }

    override fun onBind(intent: Intent?): IBinder = server

    companion object {
        /** Must match `PdfJobs`'s job root (`cacheDir/jobs`). */
        const val JOBS_DIR = "jobs"

        /** Suffix of the engine process name (`android:process` in the manifest). */
        const val PROCESS_SUFFIX = ":engine"
    }
}
