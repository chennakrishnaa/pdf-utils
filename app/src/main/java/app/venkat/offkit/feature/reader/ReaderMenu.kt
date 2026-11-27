package app.venkat.offkit.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.AppIcons

/** Test tag of the overflow (three dots) button in the reader's top bar. */
const val TAG_READER_MENU = "reader_menu"

/** Test tag of the "Go to page" menu entry. */
const val TAG_READER_GO_TO_PAGE = "reader_go_to_page"

/**
 * One entry of the reader's overflow menu. [tag] is its test tag (and stable id), [label] its text
 * and spoken description; a disabled entry is shown greyed out and cannot be clicked.
 */
data class ReaderMenuItem(
    val tag: String,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The reader's overflow menu entries, in order: Go to page, Print, Open another PDF. Search and
 * Share are buttons of the top bar ([ReaderTopBarButtons]), not entries. Add a new entry here
 * (and its callback as a parameter, wired in `ReaderDocument`); the layout in
 * [ReaderOverflowMenu] does not change. Only features that exist are listed: no placeholders.
 */
@Composable
internal fun readerMenuItems(
    onGoToPage: () -> Unit,
    onOpenAnother: () -> Unit,
    actions: ReaderActions? = null,
): List<ReaderMenuItem> = listOfNotNull(
    ReaderMenuItem(TAG_READER_GO_TO_PAGE, stringResource(R.string.reader_go_to_page), onClick = onGoToPage),
    actions?.let { ReaderMenuItem(TAG_READER_PRINT, stringResource(R.string.reader_print), onClick = it::print) },
    ReaderMenuItem(TAG_READER_OPEN_ANOTHER, stringResource(R.string.reader_open_another), onClick = onOpenAnother),
)

/**
 * The fixed buttons of the reader's top bar, before the overflow menu: Search, then Share
 * (disabled, greyed out and not clickable, while [shareEnabled] is false: a share copy is being
 * made). Each is a 48dp touch target, tagged [TAG_READER_SEARCH] and [TAG_READER_SHARE].
 */
@Composable
internal fun ReaderTopBarButtons(onSearch: () -> Unit, onShare: () -> Unit, shareEnabled: Boolean) {
    IconButton(onClick = onSearch, modifier = Modifier.testTag(TAG_READER_SEARCH)) {
        Icon(AppIcons.search, stringResource(R.string.reader_search))
    }
    IconButton(onClick = onShare, enabled = shareEnabled, modifier = Modifier.testTag(TAG_READER_SHARE)) {
        Icon(AppIcons.share, stringResource(R.string.reader_share))
    }
}

/** The three dots button and its drop-down of [items]. A chosen entry closes the menu first. */
@Composable
internal fun ReaderOverflowMenu(items: List<ReaderMenuItem>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag(TAG_READER_MENU)) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.reader_more_cd))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            for (item in items) {
                DropdownMenuItem(
                    text = { Text(item.label, style = MaterialTheme.typography.bodyLarge) },
                    enabled = item.enabled,
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                    modifier = Modifier.semantics { contentDescription = item.label }.testTag(item.tag),
                )
            }
        }
    }
}
