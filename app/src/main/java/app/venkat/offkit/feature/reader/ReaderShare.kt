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
import app.venkat.offkit.R
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.ReaderDocument
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
 * Share and Print for the open document, owned by [ReaderViewModel] (main thread only).
 * [current] returns the open document or null. A share copy runs in [scope] (cancelled with the
 * view model, which deletes the partial copy); if the document changes while it runs, the copy is
 * kept (wiped with the rest of `cacheDir/shared/` at the next job start) but no sheet opens.
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

    /** True while a share copy runs (the Share entry is disabled). */
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
            }
        }
    }

    /** Asks the screen to print the open document, or explains why it cannot ([printBlockReason]). */
    fun print() {
        val doc = current() ?: return
        val problem = printBlockReason(doc)
        _events.trySend(
            if (problem != null) {
                ReaderActionEvent.Message(problem)
            } else {
                ReaderActionEvent.Print(ReaderPrintJob(doc.name, doc.pages.size) { reader.openSource(doc) })
            },
        )
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
    LaunchedEffect(actions) {
        actions.events.collect { event ->
            val message = when (event) {
                is ReaderActionEvent.Share -> {
                    launchOrExplain(context, R.string.core_no_share_target) {
                        context.startActivity(sharePdfIntent(event.request.uri, event.request.name))
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
