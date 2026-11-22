package com.offlinepdf.utils.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
 * - [JobUiState.Idle]: a [PrimaryActionButton] [runLabel] ([TAG_JOB_RUN]) with optional
 *   [runIcon], enabled per [runEnabled], calls [onRun]. No card around it.
 * - Every other state is shown inside a card:
 * - [JobUiState.Running]: "done of total" text with a percentage, progress bar
 *   ([TAG_JOB_PROGRESS]; indeterminate with "Working…" when total == 0), and an outlined
 *   Cancel ([TAG_JOB_CANCEL]) calling [onCancel].
 * - [JobUiState.ReadyToSave]: summary ([TAG_JOB_SUMMARY]), primary "Save as…" ([TAG_JOB_SAVE])
 *   calling [onSave], and a text "Discard" ([TAG_JOB_RESET]) calling [onReset].
 * - [JobUiState.Saving]: summary ([TAG_JOB_SUMMARY]), an indeterminate progress bar and
 *   "Saving…" ([TAG_JOB_SAVING]); no buttons.
 * - [JobUiState.Saved]: a check badge with "Saved", the summary ([TAG_JOB_SUMMARY]) and an
 *   outlined "Start over" ([TAG_JOB_RESET]) calling [onReset].
 * - [JobUiState.Failed]: an error-tinted card with the message ([TAG_JOB_ERROR]) and an outlined
 *   "Try again" ([TAG_JOB_RESET]) calling [onReset].
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
    runIcon: Painter? = null,
) {
    val colors = MaterialTheme.colorScheme
    when (state) {
        JobUiState.Idle -> {
            PrimaryActionButton(
                text = runLabel,
                onClick = onRun,
                enabled = runEnabled,
                testTag = TAG_JOB_RUN,
                modifier = modifier,
                icon = runIcon,
            )
        }

        is JobUiState.Running -> PanelCard(modifier) {
            if (state.total > 0) {
                val fraction = (state.done.toFloat() / state.total).coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.job_progress, state.done, state.total),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.job_percent, (fraction * 100).toInt()),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.primary,
                    )
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().testTag(TAG_JOB_PROGRESS),
                )
            } else {
                Text(text = stringResource(R.string.job_working), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag(TAG_JOB_PROGRESS))
            }
            OutlinedButton(
                onClick = onCancel,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TAG_JOB_CANCEL),
            ) {
                Icon(AppIcons.close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.job_cancel))
            }
        }

        is JobUiState.ReadyToSave -> PanelCard(modifier) {
            StatusHeading(icon = AppIcons.check, text = stringResource(R.string.job_ready_title))
            Summary(state.summary)
            PrimaryActionButton(
                text = stringResource(R.string.job_save),
                onClick = onSave,
                enabled = true,
                testTag = TAG_JOB_SAVE,
                icon = AppIcons.save,
            )
            TextButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TAG_JOB_RESET),
            ) {
                Text(stringResource(R.string.job_discard))
            }
        }

        is JobUiState.Saving -> PanelCard(modifier) {
            Summary(state.summary)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.core_saving),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_JOB_SAVING),
            )
        }

        is JobUiState.Saved -> PanelCard(modifier) {
            StatusHeading(icon = AppIcons.check, text = stringResource(R.string.job_saved_title))
            Summary(state.summary)
            OutlinedButton(
                onClick = onReset,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TAG_JOB_RESET),
            ) {
                Text(stringResource(R.string.job_start_over))
            }
        }

        is JobUiState.Failed -> PanelCard(modifier, container = colors.errorContainer, content = colors.onErrorContainer) {
            Text(
                text = stringResource(R.string.job_failed_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onErrorContainer,
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onErrorContainer,
                modifier = Modifier.testTag(TAG_JOB_ERROR),
            )
            OutlinedButton(
                onClick = onReset,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TAG_JOB_RESET),
            ) {
                Text(stringResource(R.string.job_try_again), color = colors.onErrorContainer)
            }
        }
    }
}

@Composable
private fun PanelCard(
    modifier: Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: Color = MaterialTheme.colorScheme.onSurface,
    body: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = body,
        )
    }
}

/** A small tinted round badge with [icon], followed by [text] as a title. */
@Composable
private fun StatusHeading(icon: Painter, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(5.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun Summary(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(TAG_JOB_SUMMARY),
    )
}

