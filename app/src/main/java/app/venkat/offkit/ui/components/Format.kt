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
    PdfError.ImageTooLarge -> R.string.core_error_image_too_large
    PdfError.Cancelled -> R.string.error_cancelled
    PdfError.NotImplemented -> R.string.error_not_implemented
    PdfError.EngineCrashed -> R.string.error_engine_crashed
    PdfError.EngineUnavailable -> R.string.error_engine_unavailable
    PdfError.TimedOut -> R.string.error_timed_out
    is PdfError.Io -> R.string.error_generic
}

/** String resource id of the short title of the Error screen for [error] (SPEC §6). */
@StringRes
fun pdfErrorTitle(error: PdfError): Int = when (error) {
    PdfError.Encrypted -> R.string.error_locked_title
    PdfError.Corrupt -> R.string.error_unreadable_title
    PdfError.UnsupportedImage -> R.string.error_unsupported_image_title
    PdfError.OutOfMemory -> R.string.error_out_of_memory_title
    PdfError.ImageTooLarge -> R.string.error_image_too_large_title
    PdfError.EngineCrashed -> R.string.error_engine_crashed_title
    PdfError.TimedOut -> R.string.error_timed_out_title
    PdfError.EngineUnavailable -> R.string.error_engine_unavailable_title
    PdfError.NotImplemented -> R.string.job_failed_title
    PdfError.Cancelled -> R.string.job_failed_title
    is PdfError.Io -> R.string.job_failed_title
}

/** String resource id of the explanation under [pdfErrorTitle] on the Error screen (SPEC §6). */
@StringRes
fun pdfErrorBody(error: PdfError): Int = when (error) {
    PdfError.Encrypted -> R.string.error_locked_body
    PdfError.Corrupt -> R.string.error_unreadable_body
    PdfError.UnsupportedImage -> R.string.error_unsupported_image
    PdfError.OutOfMemory -> R.string.error_out_of_memory_body
    PdfError.ImageTooLarge -> R.string.core_error_image_too_large
    PdfError.EngineCrashed -> R.string.error_engine_crashed
    PdfError.TimedOut -> R.string.error_timed_out
    PdfError.EngineUnavailable -> R.string.error_engine_unavailable_body
    PdfError.NotImplemented -> R.string.error_not_implemented
    PdfError.Cancelled -> R.string.error_cancelled
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
