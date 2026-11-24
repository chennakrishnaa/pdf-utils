package app.venkat.offkit.engine.remote

import android.app.Application
import android.os.Build
import java.io.File

/** Tells whether the current process is the `:engine` process ([EngineService]). */
object EngineProcess {
    @Volatile
    private var cached: Boolean? = null

    fun isEngineProcess(): Boolean = cached ?: (currentProcessName()?.endsWith(EngineService.PROCESS_SUFFIX) == true)
        .also { cached = it }

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            try {
                File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
            } catch (e: Exception) {
                null
            }
        }

    /** Throws [IllegalStateException] in the engine process: [what] belongs to the UI process. */
    fun checkNotEngineProcess(what: String) {
        check(!isEngineProcess()) { "$what must not be used in the engine process" }
    }
}
