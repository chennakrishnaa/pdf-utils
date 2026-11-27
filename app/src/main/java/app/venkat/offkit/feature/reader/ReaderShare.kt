package app.venkat.offkit.feature.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.venkat.offkit.R
import android.net.Uri
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.core.ReaderCopyHandoff
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.ReaderDocument
import app.venkat.offkit.core.ReaderPrintFile
import app.venkat.offkit.engine.PdfError
import androidx.compose.material3.TextButton
import app.venkat.offkit.core.ShareRequest
import app.venkat.offkit.ui.components.launchOrExplain
import app.venkat.offkit.ui.components.sharePdfIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Test tags of the Share and Print menu entries and of the share progress dialog. */
const val TAG_READER_SHARE = "reader_share"
const val TAG_READER_PRINT = "reader_print"
const val TAG_READER_SHARE_PROGRESS = "reader_share_progress"
const val TAG_READER_PRINT_PROGRESS = "reader_print_progress"

/** What the screen must do for a Share or Print request ([ReaderActions.events]). */
sealed interface ReaderActionEvent {
    /** Open the share sheet for [request] (as the Result screen does). */
    data class Share(val request: ShareRequest) : ReaderActionEvent

    /** Hand [job] to the system print framework ([startPrint]). */
    data class Print(val job: ReaderPrintJob) : ReaderActionEvent

    /** Show [text] briefly. */
    data class Message(@StringRes val text: Int) : ReaderActionEvent
}

/**
 * Share, Print, Save a copy and the hand-off to Compress for the open document, owned by
 * [ReaderViewModel] (main thread only). [current] returns the open document or null. A share copy
 * runs in [scope] (cancelled with the view model, which deletes the partial copy); if the document
 * changes while it runs, the copy is kept but no sheet opens. Share copies are wiped with the rest
 * of `cacheDir/shared/` when a tool job starts from a file (not from a reader's copy, whose reader
 * is still alive below the tool) and at the next process start. Save a copy runs in the process
 * scope ([PdfReader.saveCopyDetached]).
 */
class ReaderActions(
    private val reader: PdfReader,
    private val scope: CoroutineScope,
    private val current: () -> ReaderDocument?,
) {
    private val _events = Channel<ReaderActionEvent>(Channel.BUFFERED)

    /** Collect once, from the screen ([ReaderActionsEffect]). */
    val events: Flow<ReaderActionEvent> = _events.receiveAsFlow()

    private val _shareProgress = MutableStateFlow<Float?>(null)

    /** Fraction copied of a large file being shared, or null (nothing shown). */
    val shareProgress: StateFlow<Float?> = _shareProgress.asStateFlow()

    private var shareJob: Job? = null

    private val _shareBusy = MutableStateFlow(false)

    /** True while a share copy runs: the Share button is disabled. */
    val shareBusy: StateFlow<Boolean> = _shareBusy.asStateFlow()

    /** True while a share copy runs (see [shareBusy]). */
    val sharing: Boolean get() = shareJob?.isActive == true

    /** Copies the open document into `cacheDir/shared/` and asks for the share sheet. */
    fun share() {
        val doc = current() ?: return
        if (sharing) return
        val problem = shareBlockReason(doc)
        if (problem != null) {
            _events.trySend(ReaderActionEvent.Message(problem))
            return
        }
        _shareBusy.value = true
        shareJob = scope.launch {
            try {
                val request = reader.prepareShare(doc) { done, total ->
                    if (total >= LARGE_SHARE_BYTES) _shareProgress.value = done.toFloat() / total
                }
                if (current() === doc) _events.trySend(ReaderActionEvent.Share(request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current() === doc) _events.trySend(ReaderActionEvent.Message(R.string.reader_share_failed))
            } finally {
                _shareProgress.value = null
                _shareBusy.value = false
            }
        }
    }

    /** The document a "Save a copy" dialog was opened for (memory only), until its result arrives. */
    private var saveCopyDoc: ReaderDocument? = null
    private val _savingCopy = MutableStateFlow(false)

    /** True from the "Save a copy" tap until the copy is written or abandoned (the entry is disabled). */
    val savingCopy: StateFlow<Boolean> = _savingCopy.asStateFlow()

    /**
     * Starts "Save a copy" for the open document: returns the name to suggest in the system save
     * dialog, or null (no document, or a dialog or a save is already under way: no second dialog).
     * The caller opens the dialog and passes its result to [saveCopyTo], or calls
     * [saveCopyNotLaunched] if it could not open.
     */
    fun beginSaveCopy(): String? {
        val doc = current() ?: return null
        if (_savingCopy.value) return null
        saveCopyDoc = doc
        _savingCopy.value = true
        return saveCopyName(doc.name)
    }

    /** The save dialog could not be opened: Save a copy is available again. */
    fun saveCopyNotLaunched() {
        saveCopyDoc = null
        _savingCopy.value = false
    }

    /**
     * The save dialog's result: null if cancelled (nothing happens). If the document it was
     * opened for is no longer the open one (replaced, closed, or the process was recreated
     * meanwhile), nothing is written: the empty file the dialog created is deleted and a failure
     * message shown. Otherwise the private copy is written byte for byte
     * ([PdfReader.saveCopyDetached]), then "Saved" or a failure message is shown. The copy runs to
     * completion even if the reader leaves meanwhile (the message then has no screen to show on;
     * the recent files still list the copy).
     */
    fun saveCopyTo(dest: Uri?) {
        val doc = saveCopyDoc
        saveCopyDoc = null
        if (dest == null) {
            _savingCopy.value = false
            return
        }
        if (doc == null || current() !== doc) {
            _savingCopy.value = false
            reader.discardDestination(dest)
            _events.trySend(ReaderActionEvent.Message(R.string.reader_save_failed))
            return
        }
        reader.saveCopyDetached(doc, dest) { result ->
            _savingCopy.value = false
            _events.trySend(ReaderActionEvent.Message(if (result.isSuccess) R.string.reader_saved else R.string.reader_save_failed))
        }
    }

    /**
     * The open document for the reader's "Compress", or null: none open, or it is protected
     * (tools refuse encrypted PDFs; a message explains it and nothing navigates).
     */
    fun compressSource(): ReaderCopyHandoff? {
        val doc = current() ?: return null
        if (doc.isEncrypted) {
            _events.trySend(ReaderActionEvent.Message(R.string.reader_compress_protected))
            return null
        }
        return ReaderCopyHandoff(doc.file, doc.name)
    }

    private var printJob: Job? = null
    private var printDoc: ReaderDocument? = null
    private val _printPreparing = MutableStateFlow(false)

    /** True while the unencrypted copy of an encrypted document is made for printing. */
    val printPreparing: StateFlow<Boolean> = _printPreparing.asStateFlow()

    /** True while a print copy of the open document is being made (Print does nothing then). */
    val printing: Boolean get() = printJob?.isActive == true && printDoc === current()

    /**
     * Asks the screen to print the open document, or explains why it cannot ([printBlockReason]).
     * An unencrypted document prints its private copy at once. An encrypted one (with a user
     * password, or an owner password only) first gets an unencrypted copy from the engine
     * ([PdfReader.preparePrint], [printPreparing] meanwhile); a second Print for the same document
     * meanwhile is ignored. If the document is no longer the open one when the copy is ready (the
     * user opened another PDF or left; closing it also stops the engine), nothing is printed.
     * The share copy is unaffected: Share always sends the original, still protected file.
     */
    fun print() {
        val doc = current() ?: return
        val problem = printBlockReason(doc)
        if (problem != null) {
            _events.trySend(ReaderActionEvent.Message(problem))
            return
        }
        if (!doc.isEncrypted) {
            _events.trySend(ReaderActionEvent.Print(ReaderPrintJob(doc.name, doc.pages.size) { reader.openSource(doc) }))
            return
        }
        if (printing) return
        printJob?.cancel()
        printDoc = doc
        printJob = scope.launch {
            _printPreparing.value = true
            try {
                val result = reader.preparePrint(doc)
                if (current() !== doc) return@launch
                when (result) {
                    is ReaderPrintFile.Ok -> _events.trySend(
                        ReaderActionEvent.Print(ReaderPrintJob(doc.name, doc.pages.size) { reader.openPrintSource(doc, result.file) }),
                    )
                    is ReaderPrintFile.Err ->
                        if (result.error != PdfError.Cancelled) _events.trySend(ReaderActionEvent.Message(R.string.reader_print_failed))
                }
            } finally {
                if (printDoc === doc) _printPreparing.value = false
            }
        }
    }

    /**
     * Stops waiting for a print copy (the user dismissed the progress dialog). The engine call is
     * let finish; its copy is kept for the next Print of the same document.
     */
    fun cancelPrint() {
        printJob?.cancel()
        printJob = null
        printDoc = null
        _printPreparing.value = false
    }

    companion object {
        /** Copies at least this large show a progress dialog. */
        const val LARGE_SHARE_BYTES = 8L * 1024 * 1024
    }
}

/**
 * Why [doc] may not be shared, or null. The one place a `canCopy` check would go (not enforced:
 * owner decision pending). A protected document is shared as it is, still protected.
 */
@StringRes
internal fun shareBlockReason(@Suppress("UNUSED_PARAMETER") doc: ReaderDocument): Int? = null

/**
 * The name "Save a copy" suggests for a document named [name]: sanitised like a shared copy's
 * ([PdfJobs.shareFileName]), with `.pdf` added if missing. Only a suggestion, never a path.
 */
internal fun saveCopyName(name: String): String {
    val clean = PdfJobs.shareFileName(name)
    return if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
}

/** The Activity behind [context], or null (`PrintManager.print` needs one). */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Carries out [actions]' events for the screen: the share sheet (guarded like the Result screen's),
 * printing, and messages in [snackbar]. Shows a progress dialog while a large file is copied.
 */
@Composable
internal fun ReaderActionsEffect(actions: ReaderActions, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val res by rememberUpdatedState(LocalResources.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(actions) {
        actions.events.collect { event ->
            val message = when (event) {
                is ReaderActionEvent.Share -> {
                    // Only from the screen on top: a copy that ends while Compress (or another
                    // app) is in front opens no share sheet later, unprompted.
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        launchOrExplain(context, R.string.core_no_share_target) {
                            context.startActivity(sharePdfIntent(event.request.uri, event.request.name))
                        }
                    }
                    null
                }
                is ReaderActionEvent.Print -> startPrint(context, event.job)
                is ReaderActionEvent.Message -> event.text
            }
            if (message != null) {
                snackbar.currentSnackbarData?.dismiss()
                launch { snackbar.showSnackbar(res.getString(message)) }
            }
        }
    }
    val preparingPrint by actions.printPreparing.collectAsState()
    if (preparingPrint) {
        AlertDialog(
            onDismissRequest = { actions.cancelPrint() },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actions.cancelPrint() }) { Text(stringResource(android.R.string.cancel)) }
            },
            text = {
                Column {
                    Text(stringResource(R.string.reader_print_preparing))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                }
            },
            modifier = Modifier.testTag(TAG_READER_PRINT_PROGRESS),
        )
    }
    val progress by actions.shareProgress.collectAsState()
    progress?.let { fraction ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Column {
                    Text(stringResource(R.string.reader_share_preparing))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
            },
            modifier = Modifier.testTag(TAG_READER_SHARE_PROGRESS),
        )
    }
}
