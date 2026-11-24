package app.venkat.offkit.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.venkat.offkit.R
import app.venkat.offkit.core.ShareRequest
import app.venkat.offkit.ui.theme.OffkitTool
import kotlinx.coroutines.launch

/** Which screen [OffkitJobFlow] shows for a state. */
internal enum class JobFlowScreen { Options, Processing, Result, NoGain, Error }

/** The screen for [state]; a `Done` no smaller than its known original is [JobFlowScreen.NoGain] when [noGainAsError]. */
internal fun jobFlowScreen(state: JobUiState, noGainAsError: Boolean): JobFlowScreen = when (state) {
    JobUiState.Idle -> JobFlowScreen.Options
    is JobUiState.Running -> JobFlowScreen.Processing
    is JobUiState.Done -> {
        val original = state.originalBytes
        if (noGainAsError && original != null && state.bytes >= original) JobFlowScreen.NoGain else JobFlowScreen.Result
    }
    is JobUiState.Failed -> JobFlowScreen.Error
}

/**
 * Host of a tool's job flow: shows the caller's [options] screen or one of the shared Processing,
 * Result and Error screens for [state], and owns their effects.
 *
 * - [JobUiState.Idle]: [options] (the caller's own [OffkitScaffold]; no BackHandler is added).
 * - [JobUiState.Running]: [OffkitProcessingScreen] for [tool] with [title] and [inputName].
 *   Cancel (tag [TAG_JOB_CANCEL]) and system back call [onCancel] (no dialog).
 * - [JobUiState.Done]: [OffkitResultScreen]. Save launches the system "create document" dialog
 *   with `suggestedName` and passes the chosen Uri (or null on cancel) to [onSave]. Share calls
 *   [onShare]; each new [shareRequest] launches the share sheet and is reported through
 *   [onShareHandled]. A non-null [saveError] is shown as a snackbar, then [onSaveErrorShown] is
 *   called; the result stays. Close, Discard/Done (tag [TAG_JOB_RESET]) and system back leave
 *   the tool ([onReset] then [onLeave]); while the result is neither saved nor shared they first
 *   show [OffkitDiscardDialog] (Keep stays, Discard leaves). While saving, leaving is ignored.
 * - With [noGainAsError], a `Done` whose `bytes >= originalBytes` shows the no-gain card instead
 *   (`ic_warning`, `error_no_gain_*`, [inputName] and [inputBytes], primary
 *   `action_choose_another`); its primary button, Back and system back call [onReset].
 * - [JobUiState.Failed]: [OffkitErrorScreen] with [pdfErrorTitle] / [pdfErrorBody], [inputName]
 *   and [inputBytes]; the primary [errorActionLabel] (tag [TAG_JOB_RESET]), Back and system
 *   back call [onReset] (back to the options with the inputs kept).
 */
@Composable
fun OffkitJobFlow(
    tool: OffkitTool,
    title: String,
    state: JobUiState,
    inputName: String?,
    inputBytes: Long?,
    saveError: String?,
    onSaveErrorShown: () -> Unit,
    shareRequest: ShareRequest?,
    onShareHandled: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSave: (Uri?) -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    onLeave: () -> Unit,
    errorActionLabel: String,
    noGainAsError: Boolean = false,
    options: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val launchSaver = rememberPdfSaver(onSave)
    ShareRequestEffect(shareRequest, onShareHandled)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(saveError) {
        if (saveError != null) {
            // Not in this effect's coroutine: clearing the error re-keys it and would cancel the snackbar.
            scope.launch { snackbar.showSnackbar(saveError) }
            onSaveErrorShown()
        }
    }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    val screen = jobFlowScreen(state, noGainAsError)

    val leave = {
        askDiscard = false
        onReset()
        onLeave()
    }
    val requestLeave = {
        when {
            state.isSaving -> Unit
            state.hasUnsavedWork() -> askDiscard = true
            else -> leave()
        }
    }

    when (screen) {
        JobFlowScreen.Options -> options()
        JobFlowScreen.Processing -> {
            val running = state as JobUiState.Running
            BackHandler(onBack = onCancel)
            OffkitProcessingScreen(tool, title, running.done, running.total, inputName, onCancel)
        }
        JobFlowScreen.Result -> {
            val done = state as JobUiState.Done
            BackHandler(onBack = requestLeave)
            OffkitResultScreen(
                title = title,
                state = done,
                onClose = requestLeave,
                onSave = { if (!done.saving) launchSaver(done.suggestedName) },
                onShare = onShare,
                onFinish = requestLeave,
                onOpen = { uri -> openSavedPdf(context, uri) },
                snackbarHostState = snackbar,
            )
            if (askDiscard) OffkitDiscardDialog(onKeep = { askDiscard = false }, onDiscard = leave)
        }
        JobFlowScreen.NoGain -> {
            BackHandler(onBack = onReset)
            OffkitErrorScreen(
                title = title,
                icon = AppIcons.warning,
                errorTitle = stringResource(R.string.error_no_gain_title),
                errorBody = stringResource(R.string.error_no_gain_body),
                fileName = inputName,
                fileBytes = inputBytes,
                actionLabel = stringResource(R.string.action_choose_another),
                onAction = onReset,
                onBack = onReset,
            )
        }
        JobFlowScreen.Error -> {
            BackHandler(onBack = onReset)
            OffkitJobErrorScreen(
                title = title,
                error = (state as JobUiState.Failed).error,
                fileName = inputName,
                fileBytes = inputBytes,
                actionLabel = errorActionLabel,
                onAction = onReset,
                onBack = onReset,
            )
        }
    }
    // A dialog left open when the state moves on (e.g. reset elsewhere) must not reappear later.
    LaunchedEffect(screen) { if (screen != JobFlowScreen.Result) askDiscard = false }
}
