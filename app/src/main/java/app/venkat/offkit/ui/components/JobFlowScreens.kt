package app.venkat.offkit.ui.components

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme
import app.venkat.offkit.ui.theme.OffkitTool

/** Headline of the Processing screen for [tool] (`processing_*`). */
@StringRes
internal fun processingHeadline(tool: OffkitTool): Int = when (tool) {
    OffkitTool.COMPRESS -> R.string.processing_compress
    OffkitTool.MERGE -> R.string.processing_merge
    OffkitTool.IMAGES -> R.string.processing_images
    OffkitTool.ADD_PAGES -> R.string.processing_add_pages
}

/**
 * Processing screen (SPEC §4), shown while a job runs. [OffkitScaffold] without a navigation
 * icon, keeping [title]. A block centred in the viewport: a 72dp tile in the [tool]'s
 * `container` colour with its icon (36dp, `onContainer`), the `processing_*` headline
 * (`displaySmall`), then a progress line (`bodyLarge`, `onSurfaceVariant`):
 * `processing_progress` ([done], [total], [fileName]) or, without a file name, `job_progress`.
 * Then an 8dp `LinearProgressIndicator` (tag [TAG_JOB_PROGRESS], `primary` on
 * `offkit_disabled_container`) with the percentage and `processing_keep_open` under it.
 * When [total] is 0 the bar is indeterminate, the line reads `job_working` and no percentage
 * is shown. The progress line is a polite live region.
 *
 * The bottom bar holds an outlined `cancel` button (tag [TAG_JOB_CANCEL]) calling [onCancel].
 * It has no BackHandler: [OffkitJobFlow] maps system back to Cancel.
 */
@Composable
fun OffkitProcessingScreen(
    tool: OffkitTool,
    title: String,
    done: Int,
    total: Int,
    fileName: String?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    // Fill the viewport (minus the scaffold's 8dp + 24dp content padding) to centre the block.
    val minHeight = with(LocalDensity.current) { scroll.viewportSize.toDp() } - 32.dp
    OffkitScaffold(
        title = title,
        navIcon = OffkitNavIcon.None,
        onNavigate = {},
        modifier = modifier,
        scrollState = scroll,
        bottomBar = {
            OffkitOutlinedButton(
                text = stringResource(R.string.cancel),
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                testTag = TAG_JOB_CANCEL,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight.coerceAtLeast(0.dp))
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(OffkitDimens.sectionGap, Alignment.CenterVertically),
        ) {
            Box(
                modifier = Modifier.size(72.dp).background(tool.container, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(tool.icon), contentDescription = null, tint = tool.onContainer, modifier = Modifier.size(36.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(processingHeadline(tool)), style = MaterialTheme.typography.displaySmall, color = colors.onSurface)
                val line = when {
                    total <= 0 -> stringResource(R.string.job_working)
                    fileName != null -> stringResource(R.string.processing_progress, done, total, fileName)
                    else -> stringResource(R.string.job_progress, done, total)
                }
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val barModifier = Modifier.fillMaxWidth().height(OffkitDimens.progressHeight).testTag(TAG_JOB_PROGRESS)
                val track = colorResource(R.color.offkit_disabled_container)
                if (total > 0) {
                    val fraction = (done.toFloat() / total).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = barModifier,
                        color = colors.primary,
                        trackColor = track,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = barModifier,
                        color = colors.primary,
                        trackColor = track,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (total > 0) {
                        val percent = (done.toFloat() / total).coerceIn(0f, 1f) * 100
                        Text(
                            stringResource(R.string.job_percent, percent.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.processing_keep_open), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Result screen (SPEC §5) for a finished job [state]. Stateless: [OffkitJobFlow] supplies the
 * save launcher, share effect, discard dialog and back handling.
 *
 * - Top bar: Close icon (tag [TAG_ACTION_CLOSE]) calling [onClose], and [title].
 * - Headline (tag [TAG_JOB_SUMMARY] on the headline text): a 64dp `offkit_success_container`
 *   tile with `ic_check`; `result_smaller` with `result_sizes` under it when the output is
 *   smaller than a known `originalBytes`, otherwise `pages_count` with the size under it.
 * - File card: `suggestedName` and `result_suggested_name`.
 * - Status: `result_not_saved` (not saved, whether or not it was shared), or a green
 *   row "`result_saved_as`" with a trailing `ic_open` + `action_open` button calling [onOpen]
 *   with the saved Uri.
 * - Bottom bar: primary `action_save_as` / `action_save_copy` (tag [TAG_JOB_SAVE]) calling
 *   [onSave]; while saving it is disabled, reads `core_saving` and is tagged [TAG_JOB_SAVING].
 *   Below: tonal `action_share` (tag [TAG_JOB_SHARE]) calling [onShare], and a text button
 *   `action_discard` (not saved) or `action_done` (saved) (tag [TAG_JOB_RESET]) calling
 *   [onFinish]. Share and the text button are disabled while saving.
 * - Snackbars of [snackbarHostState] (save failures) appear above the bottom bar.
 */
@Composable
fun OffkitResultScreen(
    title: String,
    state: JobUiState.Done,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onFinish: () -> Unit,
    onOpen: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val colors = MaterialTheme.colorScheme
    val success = colorResource(R.color.offkit_success_container)
    val onSuccess = colorResource(R.color.offkit_on_success_container)
    val unsaved = state.savedUri == null
    OffkitScaffold(
        title = title,
        navIcon = OffkitNavIcon.Close,
        onNavigate = onClose,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        bottomBar = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OffkitPrimaryButton(
                    text = stringResource(
                        when {
                            state.saving -> R.string.core_saving
                            state.savedUri != null -> R.string.action_save_copy
                            else -> R.string.action_save_as
                        },
                    ),
                    onClick = onSave,
                    enabled = !state.saving,
                    icon = AppIcons.save,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = if (state.saving) TAG_JOB_SAVING else TAG_JOB_SAVE,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OffkitTonalButton(
                        text = stringResource(R.string.action_share),
                        onClick = onShare,
                        enabled = !state.saving,
                        icon = AppIcons.share,
                        modifier = Modifier.weight(1f).heightIn(min = OffkitDimens.buttonHeight),
                        testTag = TAG_JOB_SHARE,
                    )
                    TextButton(
                        onClick = onFinish,
                        enabled = !state.saving,
                        shape = CircleShape,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                        modifier = Modifier.weight(1f).heightIn(min = OffkitDimens.buttonHeight).testTag(TAG_JOB_RESET),
                    ) {
                        Text(stringResource(if (unsaved) R.string.action_discard else R.string.action_done), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(64.dp).background(success, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Icon(AppIcons.check, contentDescription = null, tint = onSuccess, modifier = Modifier.size(32.dp))
            }
            Column(Modifier.weight(1f)) {
                val original = state.knownOriginalBytes
                val smaller = original != null && state.bytes < original
                val headline = if (smaller) {
                    stringResource(R.string.result_smaller, savingPercent(original, state.bytes))
                } else {
                    pluralStringResource(R.plurals.pages_count, state.pages, state.pages)
                }
                val sub = if (smaller) {
                    stringResource(R.string.result_sizes, formatBytes(original), formatBytes(state.bytes))
                } else {
                    formatBytes(state.bytes)
                }
                Text(headline, style = MaterialTheme.typography.displaySmall, color = colors.onSurface, modifier = Modifier.testTag(TAG_JOB_SUMMARY))
                Text(sub, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
        }
        OffkitFileCard(
            name = state.suggestedName,
            detail = stringResource(R.string.result_suggested_name),
            modifier = Modifier.padding(top = OffkitDimens.sectionGap),
        )
        val savedUri = state.savedUri
        if (savedUri != null) {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .heightIn(min = OffkitDimens.buttonHeight)
                    .background(success, MaterialTheme.shapes.medium)
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(AppIcons.check, contentDescription = null, tint = onSuccess, modifier = Modifier.size(OffkitDimens.iconSize))
                Text(
                    // No guess when the provider did not report the name: the user may have renamed the file.
                    state.savedName?.let { stringResource(R.string.result_saved_as, it) } ?: stringResource(R.string.result_saved),
                    style = MaterialTheme.typography.labelMedium,
                    color = onSuccess,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onOpen(savedUri) },
                    // Not while saving again: the reader would copy the file while it is being rewritten.
                    enabled = !state.saving,
                    shape = CircleShape,
                    colors = ButtonDefaults.textButtonColors(contentColor = onSuccess),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.heightIn(min = OffkitDimens.touchTarget).testTag(TAG_JOB_OPEN),
                ) {
                    Icon(AppIcons.open, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_open), style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            Text(
                text = stringResource(R.string.result_not_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp),
            )
        }
    }
}

/** Test tag of the Open button in the saved row of [OffkitResultScreen]. */
const val TAG_JOB_OPEN = "job_open"

/** Test tag of the Keep button of [OffkitDiscardDialog]. */
const val TAG_LEAVE_STAY = "leave_stay"

/** Test tag of the Discard button of [OffkitDiscardDialog]. */
const val TAG_LEAVE_CONFIRM = "leave_confirm"

/**
 * "Discard this PDF?" dialog (SPEC §5): `discard_title` (`titleLarge`), `discard_body`, a `keep`
 * button (tag [TAG_LEAVE_STAY], `onSurface`) calling [onKeep] and an `action_discard` button
 * (tag [TAG_LEAVE_CONFIRM], `offkit_link`) calling [onDiscard]. 28dp radius, white. Dismissing
 * (outside tap or back) counts as Keep.
 */
@Composable
fun OffkitDiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onKeep,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = colors.surfaceContainerLowest,
        title = { Text(stringResource(R.string.discard_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface) },
        text = { Text(stringResource(R.string.discard_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) },
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                colors = ButtonDefaults.textButtonColors(contentColor = colorResource(R.color.offkit_link)),
                modifier = Modifier.heightIn(min = OffkitDimens.touchTarget).testTag(TAG_LEAVE_CONFIRM),
            ) { Text(stringResource(R.string.action_discard), style = MaterialTheme.typography.labelMedium) }
        },
        dismissButton = {
            TextButton(
                onClick = onKeep,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                modifier = Modifier.heightIn(min = OffkitDimens.touchTarget).testTag(TAG_LEAVE_STAY),
            ) { Text(stringResource(R.string.keep), style = MaterialTheme.typography.labelMedium) }
        },
    )
}

/**
 * Error screen (SPEC §6), also used for Compress's "no gain" result. Top bar: Back icon (tag
 * [TAG_ACTION_BACK]) calling [onBack], and [title]. A white card (1dp `outlineVariant` border,
 * 20dp radius) with a 56dp `primaryContainer` tile holding [icon] in `onPrimaryContainer`, then
 * [errorTitle] (`titleLarge`) and [errorBody] (`bodyLarge`, tag [TAG_JOB_ERROR]), then
 * [fileName] and the size [fileBytes] when given. The title and body are an assertive live
 * region (Compose has no "alert" role), so TalkBack announces them on arrival.
 * Bottom bar: an [OffkitPrimaryButton] [actionLabel] (tag [TAG_JOB_RESET]) calling [onAction].
 */
@Composable
fun OffkitErrorScreen(
    title: String,
    icon: Painter,
    errorTitle: String,
    errorBody: String,
    fileName: String?,
    fileBytes: Long?,
    actionLabel: String,
    onAction: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    OffkitScaffold(
        title = title,
        navIcon = OffkitNavIcon.Back,
        onNavigate = onBack,
        modifier = modifier,
        bottomBar = {
            OffkitPrimaryButton(actionLabel, onAction, Modifier.fillMaxWidth(), testTag = TAG_JOB_RESET)
        },
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = colors.surfaceContainerLowest,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp)) {
                Box(
                    modifier = Modifier.size(56.dp).background(colors.primaryContainer, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(OffkitDimens.iconSize))
                }
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                    Text(
                        errorTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        errorBody,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp).testTag(TAG_JOB_ERROR),
                    )
                }
                if (fileName != null || fileBytes != null) {
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(AppIcons.pdfFile, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        if (fileName != null) {
                            Text(
                                fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (fileBytes != null) {
                            Text(formatBytes(fileBytes), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** [OffkitErrorScreen] for a failed job: `ic_lock` for [PdfError.Encrypted], else `ic_warning`. */
@Composable
internal fun OffkitJobErrorScreen(
    title: String,
    error: PdfError,
    fileName: String?,
    fileBytes: Long?,
    actionLabel: String,
    onAction: () -> Unit,
    onBack: () -> Unit,
) {
    OffkitErrorScreen(
        title = title,
        icon = if (error == PdfError.Encrypted) AppIcons.lock else AppIcons.warning,
        errorTitle = stringResource(pdfErrorTitle(error)),
        errorBody = stringResource(pdfErrorBody(error)),
        fileName = fileName,
        fileBytes = fileBytes,
        actionLabel = actionLabel,
        onAction = onAction,
        onBack = onBack,
    )
}

// ---- Previews ----

private val previewDone = JobUiState.Done(pages = 24, bytes = 579_000, originalBytes = 1_572_864, suggestedName = "airtel-Nov-Dec-compressed.pdf")

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ProcessingPreview() {
    OffkitTheme { OffkitProcessingScreen(OffkitTool.COMPRESS, stringResource(R.string.tool_compress_title), 9, 24, "airtel-Nov-Dec.pdf", {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ResultUnsavedPreview() {
    OffkitTheme { OffkitResultScreen(stringResource(R.string.tool_compress_title), previewDone, {}, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ResultSavedPreview() {
    OffkitTheme {
        OffkitResultScreen(
            stringResource(R.string.tool_compress_title),
            previewDone.copy(savedName = previewDone.suggestedName, savedUri = Uri.EMPTY),
            {}, {}, {}, {}, {},
        )
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun DiscardDialogPreview() {
    OffkitTheme { OffkitDiscardDialog({}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ErrorPreview() {
    OffkitTheme {
        OffkitJobErrorScreen(stringResource(R.string.tool_compress_title), PdfError.Encrypted, "bank-statement.pdf", 2_400_000, stringResource(R.string.action_choose_another), {}, {})
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun NoGainPreview() {
    OffkitTheme {
        OffkitErrorScreen(
            title = stringResource(R.string.tool_compress_title),
            icon = AppIcons.warning,
            errorTitle = stringResource(R.string.error_no_gain_title),
            errorBody = stringResource(R.string.error_no_gain_body),
            fileName = "scan.pdf",
            fileBytes = 320_000,
            actionLabel = stringResource(R.string.action_choose_another),
            onAction = {},
            onBack = {},
        )
    }
}
