package app.venkat.offkit.ui.components

import android.net.Uri
import app.venkat.offkit.engine.PdfError

/** UI state of one tool job, rendered by [OffkitJobFlow]. */
sealed interface JobUiState {
    /** Nothing running; the run button is shown. */
    data object Idle : JobUiState

    /** Working: [done] of [total] units complete. `total == 0` means progress is unknown. */
    data class Running(val done: Int, val total: Int) : JobUiState

    /**
     * Output produced (kept in a temp file until discarded, invalidated or replaced by a new job),
     * ready to be saved (any number of times) or shared.
     *
     * @property pages page count of the output.
     * @property bytes output size in bytes.
     * @property originalBytes size of the input the output replaces, when the tool knows it
     *   (Compress); null otherwise.
     * @property suggestedName file name pre-filled in the save dialog and used for sharing.
     * @property saving true while the output is being copied to a chosen location. Nothing can be
     *   started, reset, invalidated, shared or cancelled meanwhile.
     * @property savedName display name the provider reports for the latest successful save, if any.
     * @property savedUri destination of the latest successful save, or null if never saved.
     * @property shared true once a share sheet was launched for this output.
     */
    data class Done(
        val pages: Int,
        val bytes: Long,
        val originalBytes: Long?,
        val suggestedName: String,
        val saving: Boolean = false,
        val savedName: String? = null,
        val savedUri: Uri? = null,
        val shared: Boolean = false,
    ) : JobUiState

    /** Job failed with [error]; map it to text with [pdfErrorMessage]. */
    data class Failed(val error: PdfError) : JobUiState
}

/** True while a result is being saved (`Done` with `saving`). */
val JobUiState.isSaving: Boolean get() = this is JobUiState.Done && saving

/**
 * True when leaving the screen now would lose something: a job in progress, a save in progress,
 * or a result that was neither saved nor shared. The job flow uses it to decide whether leaving
 * asks for confirmation.
 */
fun JobUiState.hasUnsavedWork(): Boolean = when (this) {
    is JobUiState.Running -> true
    is JobUiState.Done -> saving || (savedUri == null && !shared)
    else -> false
}

const val TAG_JOB_RUN = "job_run"
const val TAG_JOB_CANCEL = "job_cancel"
const val TAG_JOB_SAVE = "job_save"
const val TAG_JOB_RESET = "job_reset"
const val TAG_JOB_ERROR = "job_error"
const val TAG_JOB_PROGRESS = "job_progress"
const val TAG_JOB_SUMMARY = "job_summary"
const val TAG_JOB_SAVING = "job_saving"
const val TAG_JOB_SHARE = "job_share"
