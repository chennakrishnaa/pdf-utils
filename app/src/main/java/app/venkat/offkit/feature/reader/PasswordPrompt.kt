package app.venkat.offkit.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.theme.OffkitDimens

/** Test tags of the password prompt. */
const val TAG_PASSWORD_DIALOG = "reader_password_dialog"
const val TAG_PASSWORD_NAME = "reader_password_name"
const val TAG_PASSWORD_FIELD = "reader_password_field"
const val TAG_PASSWORD_TOGGLE = "reader_password_toggle"
const val TAG_PASSWORD_PROBLEM = "reader_password_problem"
const val TAG_PASSWORD_PROGRESS = "reader_password_progress"
const val TAG_PASSWORD_UNLOCK = "reader_password_unlock"
const val TAG_PASSWORD_CANCEL = "reader_password_cancel"

/**
 * Asks for the password of [state]'s PDF: its name, a hidden password field (show/hide toggle, no
 * suggestions, the keyboard's Done = Unlock), Cancel and Unlock. Unlock is enabled for a non-empty
 * field; while [ReaderDocState.Locked.busy] a progress bar shows and both buttons (and dismissing)
 * are disabled. Each wrong attempt ([ReaderDocState.Locked.attempt]) clears and refocuses the field.
 *
 * The typed password lives only in this dialog's plain `remember` state: it is not saved (rotation
 * gives an empty field), and is handed to [onUnlock] only. Cancel, Back and a tap outside call
 * [onCancel].
 */
@Composable
internal fun PasswordPrompt(state: ReaderDocState.Locked, onUnlock: (String) -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Plain remember, never rememberSaveable: the password must not reach the saved state.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val unlock = { if (password.isNotEmpty() && !state.busy) onUnlock(password) }
    val title = stringResource(R.string.reader_password_title)
    AlertDialog(
        onDismissRequest = { if (!state.busy) onCancel() },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = colors.surfaceContainerLowest,
        modifier = Modifier.testTag(TAG_PASSWORD_DIALOG),
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(Modifier.semantics { paneTitle = title }) {
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(TAG_PASSWORD_NAME),
                )
                Text(
                    text = stringResource(R.string.reader_password_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { if (!state.busy) password = it },
                    label = { Text(stringResource(R.string.reader_password_field)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { unlock() }),
                    trailingIcon = {
                        val description = stringResource(
                            if (visible) R.string.reader_password_hide_description else R.string.reader_password_show_description,
                        )
                        TextButton(
                            onClick = { visible = !visible },
                            modifier = Modifier.semantics { contentDescription = description }.testTag(TAG_PASSWORD_TOGGLE),
                        ) {
                            Text(stringResource(if (visible) R.string.reader_password_hide else R.string.reader_password_show))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(TAG_PASSWORD_FIELD),
                )
                // In the dialog window's composition, so the field is attached when focus is asked for.
                // A wrong attempt clears the field and puts the cursor back in it.
                LaunchedEffect(state.attempt) {
                    if (state.attempt > 0) password = ""
                    runCatching { focus.requestFocus() }
                }
                val problem = when (state.problem) {
                    PasswordProblem.WRONG -> R.string.reader_password_wrong
                    PasswordProblem.INVALID -> R.string.reader_password_invalid
                    null -> null
                }
                if (problem != null && !state.busy) {
                    Text(
                        text = stringResource(problem),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Assertive }
                            .testTag(TAG_PASSWORD_PROBLEM),
                    )
                }
                if (state.busy) {
                    val checking = stringResource(R.string.reader_password_checking)
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .semantics { contentDescription = checking }
                            .testTag(TAG_PASSWORD_PROGRESS),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = unlock,
                enabled = password.isNotEmpty() && !state.busy,
                colors = ButtonDefaults.textButtonColors(contentColor = colorResource(R.color.offkit_link)),
                modifier = Modifier.heightIn(min = OffkitDimens.touchTarget).testTag(TAG_PASSWORD_UNLOCK),
            ) { Text(stringResource(R.string.reader_password_unlock), style = MaterialTheme.typography.labelMedium) }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !state.busy,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                modifier = Modifier.heightIn(min = OffkitDimens.touchTarget).testTag(TAG_PASSWORD_CANCEL),
            ) { Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelMedium) }
        },
    )
}
