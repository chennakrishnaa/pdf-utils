package app.venkat.filetools

import android.app.Application
import app.venkat.filetools.engine.MuPdfEngine
import app.venkat.filetools.engine.PdfEngine

class PdfUtilApp : Application() {
    @Volatile
    private var engineOrNull: PdfEngine? = null

    /**
     * The app-wide [PdfEngine]. Defaults lazily to [MuPdfEngine] on first read, so creating
     * the application does not load MuPDF. Tests may replace it before first use.
     */
    var engine: PdfEngine
        get() = engineOrNull ?: synchronized(this) {
            engineOrNull ?: MuPdfEngine().also { engineOrNull = it }
        }
        set(value) {
            engineOrNull = value
        }
}
