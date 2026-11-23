package app.venkat.filetools.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.venkat.filetools.R

/** Test tag of the back button in [ToolScaffold]. */
const val TAG_ACTION_BACK = "action_back"

/** Test tag of the "Stay" button in the leave-confirmation dialog of [ToolScaffold]. */
const val TAG_LEAVE_STAY = "leave_stay"

/** Test tag of the "Leave" button in the leave-confirmation dialog of [ToolScaffold]. */
const val TAG_LEAVE_CONFIRM = "leave_confirm"

/**
 * Standard screen frame for a tool: a top app bar with [title] and a back arrow
 * (tag [TAG_ACTION_BACK]) that calls [onBack].
 *
 * When [confirmLeave] is true (e.g. `state.hasUnsavedWork()`), both the back arrow and the
 * system back gesture first show a "Leave this screen?" dialog; only its "Leave" button
 * ([TAG_LEAVE_CONFIRM]) calls [onBack], "Stay" ([TAG_LEAVE_STAY]) just closes the dialog.
 * When false, the back arrow calls [onBack] directly and system back is left to navigation.
 *
 * [content] receives the inner padding, which already includes the app bar and the system bar
 * insets; apply it to the root of the content. The content area itself does not scroll, so the
 * caller chooses: `Column(Modifier.padding(it).verticalScroll(...))` or a `LazyColumn` with
 * `contentPadding = it`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    confirmLeave: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    var askLeave by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = confirmLeave) { askLeave = true }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (confirmLeave) askLeave = true else onBack() },
                        modifier = Modifier.testTag(TAG_ACTION_BACK),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        content = content,
    )
    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(R.string.core_leave_title)) },
            text = { Text(stringResource(R.string.core_leave_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askLeave = false
                        onBack()
                    },
                    modifier = Modifier.testTag(TAG_LEAVE_CONFIRM),
                ) { Text(stringResource(R.string.core_leave_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askLeave = false }, modifier = Modifier.testTag(TAG_LEAVE_STAY)) {
                    Text(stringResource(R.string.core_leave_stay))
                }
            },
        )
    }
}
