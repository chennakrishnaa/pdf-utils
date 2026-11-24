package app.venkat.offkit

import android.app.Application
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.remote.BoundServiceConnector
import app.venkat.offkit.engine.remote.EngineProcess
import app.venkat.offkit.engine.remote.RemoteEngine

/**
 * The application. Also instantiated in the `:engine` process (EngineService), so it must stay
 * inert at creation: no `PdfJobs.get()`, no job-directory wiping, no engine binding here.
 */
class PdfUtilApp : Application() {
    @Volatile
    private var engineOrNull: PdfEngine? = null

    /**
     * The app-wide [PdfEngine]. Defaults lazily, on first read, to a [RemoteEngine] that runs MuPDF
     * in the separate `:engine` process; creating it does not bind or load anything. Reading the
     * default in the engine process throws (it would bind to itself). Tests may replace it before
     * first use.
     */
    var engine: PdfEngine
        get() = engineOrNull ?: synchronized(this) {
            engineOrNull ?: run {
                EngineProcess.checkNotEngineProcess("PdfUtilApp.engine")
                RemoteEngine(BoundServiceConnector.forService(this))
            }.also { engineOrNull = it }
        }
        set(value) {
            engineOrNull = value
        }
}
