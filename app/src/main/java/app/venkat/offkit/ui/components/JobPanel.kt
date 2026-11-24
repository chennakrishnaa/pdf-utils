package app.venkat.offkit.ui.components

import android.net.Uri
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.engine.PdfError

/** UI state of one tool job, rendered by [JobPanel]. */
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
 * or a result that was neither saved nor shared. Pass it to
 * `ToolScaffold(confirmLeave = state.hasUnsavedWork())`.
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

/**
 * Run / progress / save / result panel for a tool. Stateless: the caller owns [state].
 *
 * - [JobUiState.Idle]: a [PrimaryActionButton] [runLabel] ([TAG_JOB_RUN]) with optional
 *   [runIcon], enabled per [runEnabled], calls [onRun]. No card around it.
 * - Every other state is shown inside a card:
 * - [JobUiState.Running]: "done of total" text with a percentage, progress bar
 *   ([TAG_JOB_PROGRESS]; indeterminate with "Working…" when total == 0), and an outlined
 *   Cancel ([TAG_JOB_CANCEL]) calling [onCancel].
 * - [JobUiState.Done]: a "Saved" badge once saved, the [summary] text ([TAG_JOB_SUMMARY]); while
 *   saving an indeterminate bar and "Saving…" ([TAG_JOB_SAVING]) and no buttons; otherwise a row
 *   with a text "Discard"/"Start over" ([TAG_JOB_RESET]) calling [onReset] and the primary
 *   "Save as…" ([TAG_JOB_SAVE]) calling [onSave], then an outlined "Share" ([TAG_JOB_SHARE])
 *   calling [onShare].
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
    onShare: () -> Unit = {},
    summary: @Composable (JobUiState.Done) -> String = { defaultSummary(it) },
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

        is JobUiState.Done -> PanelCard(modifier) {
            if (state.savedUri != null) StatusHeading(icon = AppIcons.check, text = stringResource(R.string.job_saved_title))
            Summary(summary(state))
            if (state.saving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.core_saving),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.testTag(TAG_JOB_SAVING),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onReset,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_JOB_RESET),
                    ) {
                        Text(stringResource(if (state.savedUri != null) R.string.job_start_over else R.string.job_discard))
                    }
                    PrimaryActionButton(
                        text = stringResource(R.string.job_save),
                        onClick = onSave,
                        enabled = true,
                        testTag = TAG_JOB_SAVE,
                        modifier = Modifier.weight(1f),
                        icon = AppIcons.save,
                    )
                }
                OutlinedButton(
                    onClick = onShare,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TAG_JOB_SHARE),
                ) {
                    Text(stringResource(R.string.recent_share))
                }
            }
        }

        is JobUiState.Failed -> PanelCard(modifier, container = colors.errorContainer, content = colors.onErrorContainer) {
            Text(
                text = stringResource(R.string.job_failed_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onErrorContainer,
            )
            Text(
                text = stringResource(pdfErrorMessage(state.error)),
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

/** Fallback summary: "N pages · size". */
@Composable
private fun defaultSummary(done: JobUiState.Done): String =
    pluralStringResource(R.plurals.merge_summary, done.pages, done.pages, formatBytes(done.bytes))

@Composable
private fun Summary(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(TAG_JOB_SUMMARY),
    )
}

