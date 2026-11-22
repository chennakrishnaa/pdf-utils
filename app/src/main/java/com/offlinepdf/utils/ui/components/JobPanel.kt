package com.offlinepdf.utils.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.offlinepdf.utils.R

/** UI state of one tool job, rendered by [JobPanel]. */
sealed interface JobUiState {
    /** Nothing running; the run button is shown. */
    data object Idle : JobUiState

    /** Working: [done] of [total] units complete. `total == 0` means progress is unknown. */
    data class Running(val done: Int, val total: Int) : JobUiState

    /** Output produced (in a temp file) and waiting for the user to choose where to save it. */
    data class ReadyToSave(val summary: String) : JobUiState

    /**
     * The output is being copied to the chosen location. Nothing can be started, reset or
     * cancelled until it ends in `Saved` or back in `ReadyToSave` (with a save error).
     */
    data class Saving(val summary: String) : JobUiState

    /** Output saved to the user's chosen location. */
    data class Saved(val summary: String) : JobUiState

    /** Job failed; [message] is user-facing text (see [pdfErrorMessage]). */
    data class Failed(val message: String) : JobUiState
}

/**
 * True when leaving the screen now would lose something: a job in progress, a result not yet
 * saved, or a save in progress. Pass it to `ToolScaffold(confirmLeave = state.hasUnsavedWork())`.
 */
fun JobUiState.hasUnsavedWork(): Boolean =
    this is JobUiState.Running || this is JobUiState.ReadyToSave || this is JobUiState.Saving

const val TAG_JOB_RUN = "job_run"
const val TAG_JOB_CANCEL = "job_cancel"
const val TAG_JOB_SAVE = "job_save"
const val TAG_JOB_RESET = "job_reset"
const val TAG_JOB_ERROR = "job_error"
const val TAG_JOB_PROGRESS = "job_progress"
const val TAG_JOB_SUMMARY = "job_summary"
const val TAG_JOB_SAVING = "job_saving"

/**
 * Run / progress / save / result panel for a tool. Stateless: the caller owns [state].
 *
 * - [JobUiState.Idle]: primary button [runLabel] ([TAG_JOB_RUN]), enabled per [runEnabled], calls [onRun].
 * - [JobUiState.Running]: progress bar ([TAG_JOB_PROGRESS]; indeterminate when total == 0),
 *   "done of total" text, and Cancel ([TAG_JOB_CANCEL]) calling [onCancel].
 * - [JobUiState.ReadyToSave]: summary ([TAG_JOB_SUMMARY]), "Save as…" ([TAG_JOB_SAVE]) calling
 *   [onSave], and "Discard" ([TAG_JOB_RESET]) calling [onReset].
 * - [JobUiState.Saving]: summary ([TAG_JOB_SUMMARY]), an indeterminate progress bar and
 *   "Saving…" ([TAG_JOB_SAVING]); no buttons.
 * - [JobUiState.Saved]: success summary ([TAG_JOB_SUMMARY]) and "Start over" ([TAG_JOB_RESET])
 *   calling [onReset].
 * - [JobUiState.Failed]: error message ([TAG_JOB_ERROR]) and "Try again" ([TAG_JOB_RESET])
 *   calling [onReset].
 */
@Composable
fun JobPanel(
    state: JobUiState,
    runLabel: String,
    runEnabled: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            JobUiState.Idle -> {
                PrimaryActionButton(text = runLabel, onClick = onRun, enabled = runEnabled, testTag = TAG_JOB_RUN)
            }

            is JobUiState.Running -> {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().testTag(TAG_JOB_PROGRESS),
                    )
                    Text(
                        text = stringResource(R.string.job_progress, state.done, state.total),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag(TAG_JOB_PROGRESS))
                    Text(text = stringResource(R.string.job_working), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.testTag(TAG_JOB_CANCEL)) {
                    Text(stringResource(R.string.job_cancel))
                }
            }

            is JobUiState.ReadyToSave -> {
                Text(
                    text = state.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(TAG_JOB_SUMMARY),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSave, modifier = Modifier.testTag(TAG_JOB_SAVE)) {
                        Text(stringResource(R.string.job_save))
                    }
                    OutlinedButton(onClick = onReset, modifier = Modifier.testTag(TAG_JOB_RESET)) {
                        Text(stringResource(R.string.job_discard))
                    }
                }
            }

            is JobUiState.Saving -> {
                Text(
                    text = state.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(TAG_JOB_SUMMARY),
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.core_saving),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TAG_JOB_SAVING),
                )
            }

            is JobUiState.Saved -> {
                Text(
                    text = stringResource(R.string.job_saved_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = state.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(TAG_JOB_SUMMARY),
                )
                OutlinedButton(onClick = onReset, modifier = Modifier.testTag(TAG_JOB_RESET)) {
                    Text(stringResource(R.string.job_start_over))
                }
            }

            is JobUiState.Failed -> {
                Text(
                    text = stringResource(R.string.job_failed_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TAG_JOB_ERROR),
                )
                OutlinedButton(onClick = onReset, modifier = Modifier.testTag(TAG_JOB_RESET)) {
                    Text(stringResource(R.string.job_try_again))
                }
            }
        }
    }
}
