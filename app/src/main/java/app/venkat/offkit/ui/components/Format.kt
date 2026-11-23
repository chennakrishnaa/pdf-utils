package app.venkat.offkit.ui.components

import androidx.annotation.StringRes
import app.venkat.offkit.R
import app.venkat.offkit.engine.PdfError
import java.util.Locale

/** String resource id with a user-facing explanation of [error]. */
@StringRes
fun pdfErrorMessage(error: PdfError): Int = when (error) {
    PdfError.Encrypted -> R.string.error_encrypted
    PdfError.Corrupt -> R.string.error_corrupt
    PdfError.UnsupportedImage -> R.string.error_unsupported_image
    PdfError.OutOfMemory -> R.string.error_out_of_memory
    PdfError.Cancelled -> R.string.error_cancelled
    PdfError.NotImplemented -> R.string.error_not_implemented
    is PdfError.Io -> R.string.error_generic
}

/**
 * Human-readable size: "0 B", "999 B", "1.5 KB", "5.0 MB". Uses 1 KB = 1024 B, one decimal for
 * KB and MB, and '.' as the decimal separator regardless of locale. Negative input counts as 0.
 */
fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    return when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", b / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024.0))
    }
}
