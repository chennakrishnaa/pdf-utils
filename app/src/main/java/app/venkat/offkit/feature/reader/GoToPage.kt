package app.venkat.offkit.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.theme.OffkitDimens

/** Test tags of the Go to page dialog. */
const val TAG_GO_TO_PAGE_FIELD = "go_to_page_field"
const val TAG_GO_TO_PAGE_HINT = "go_to_page_hint"
const val TAG_GO_TO_PAGE_GO = "go_to_page_go"
const val TAG_GO_TO_PAGE_CANCEL = "go_to_page_cancel"

/** Pure input rules of the Go to page dialog. */
object GoToPage {
    /** Longest value the field keeps: more digits than any page count, and still no overflow. */
    const val MAX_DIGITS = 9

    /** What the field keeps of a typed or pasted [input]: its digits only, at most [MAX_DIGITS]. */
    fun clean(input: String): String = input.filter { it in '0'..'9' }.take(MAX_DIGITS)

    /** The 1-based page [text] names in a document of [pageCount] pages, or null (empty, not a number, out of range). */
    fun parse(text: String, pageCount: Int): Int? {
        val t = text.trim()
        if (t.isEmpty() || !t.all { it in '0'..'9' }) return null
        val digits = t.trimStart('0')
        if (digits.isEmpty() || digits.length > MAX_DIGITS) return null
        val page = digits.toInt()
        return page.takeIf { it in 1..pageCount }
    }
}

/**
 * "Go to page": a number field pre-filled with [currentPage] (all selected), "of [pageCount]",
 * Cancel and Go. Go (or the keyboard's Done) calls [onGo] with a 1-based page, and is enabled only
 * for a page of the document; otherwise a one-line hint says which pages exist. The typed text
 * survives rotation.
 */
@Composable
internal fun GoToPageDialog(currentPage: Int, pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val initial = currentPage.toString()
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val page = GoToPage.parse(value.text, pageCount)
    val go = { if (page != null) onGo(page) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = colors.surfaceContainerLowest,
        title = { Text(stringResource(R.string.reader_go_to_page), style = MaterialTheme.typography.titleLarge, color = colors.onSurface) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { new ->
                            val cleaned = GoToPage.clean(new.text)
                            value = if (cleaned == new.text) {
                                new
                            } else {
                                TextFieldValue(cleaned, TextRange(cleaned.length))
                            }
                        },
                        label = { Text(stringResource(R.string.reader_go_to_page_field)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { go() }),
                        modifier = Modifier.weight(1f).focusRequester(focus).testTag(TAG_GO_TO_PAGE_FIELD),
                    )
                    Text(
                        text = stringResource(R.string.reader_go_to_page_of, pageCount),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                // In the dialog window's composition, so the field is attached when focus is asked for.
                LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
                if (page == null) {
                    Text(
                        text = stringResource(R.string.reader_go_to_page_hint, pageCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 8.dp).testTag(TAG_GO_TO_PAGE_HINT),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = go,
                enabled = page != null,
                colors = ButtonDefaults.textButtonColors(contentColor = colorResource(R.color.offkit_link)),
                modifier = Modifier.heightIn(min = OffkitDimens.touchTarget).testTag(TAG_GO_TO_PAGE_GO),
            ) { Text(stringResource(R.string.reader_go_to_page_go), style = MaterialTheme.typography.labelMedium) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                modifier = Modifier.heightIn(min = OffkitDimens.touchTarget).testTag(TAG_GO_TO_PAGE_CANCEL),
            ) { Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelMedium) }
        },
    )
}
