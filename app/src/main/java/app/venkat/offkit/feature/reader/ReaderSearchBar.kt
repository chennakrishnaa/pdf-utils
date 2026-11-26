package app.venkat.offkit.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.AppIcons
import java.text.NumberFormat

/** Test tags of the search bar and its parts. */
const val TAG_READER_SEARCH = "reader_search"
const val TAG_READER_SEARCH_BAR = "reader_search_bar"
const val TAG_READER_SEARCH_FIELD = "reader_search_field"
const val TAG_READER_SEARCH_COUNT = "reader_search_count"
const val TAG_READER_SEARCH_PREVIOUS = "reader_search_previous"
const val TAG_READER_SEARCH_NEXT = "reader_search_next"
const val TAG_READER_SEARCH_CLOSE = "reader_search_close"
const val TAG_READER_SEARCH_SKIPPED = "reader_search_skipped"

/**
 * The top bar while searching: Close and the query field on the first row (the field takes focus
 * and opens the keyboard); the count, the skipped-pages note, the page indicator ([indicator]) and
 * Previous / Next on the second. The count is a polite live region that changes only when the
 * scan ends (or the selected hit changes after it), not on every page scanned.
 */
@Composable
internal fun ReaderSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchNow: () -> Unit,
    state: ReaderSearchState?,
    indicator: @Composable () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    val count = searchCountText(state)
    // Stays "Searching…" while the scan runs, so TalkBack is not told every page's count.
    val spoken = if (state?.scanning == true) stringResource(R.string.reader_search_scanning) else count
    val hint = stringResource(R.string.reader_search_hint)
    val hasHits = !state?.hits.isNullOrEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 4.dp, end = 4.dp)
            .testTag(TAG_READER_SEARCH_BAR),
    ) {
        Row(modifier = Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag(TAG_READER_SEARCH_CLOSE)) {
                Icon(AppIcons.close, stringResource(R.string.reader_search_close_cd))
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f).padding(end = 12.dp)) {
                val style = MaterialTheme.typography.bodyLarge
                if (query.isEmpty()) {
                    Text(hint, style = style, color = colors.onSurfaceVariant, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = style.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSearchNow()
                        keyboard?.hide()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = hint }
                        .testTag(TAG_READER_SEARCH_FIELD),
                )
            }
        }
        Row(modifier = Modifier.heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clearAndSetSemantics {
                            contentDescription = spoken
                            liveRegion = LiveRegionMode.Polite
                        }
                        .testTag(TAG_READER_SEARCH_COUNT),
                )
                if (state != null && !state.scanning && state.skipped > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.reader_search_skipped, state.skipped, state.skipped),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.testTag(TAG_READER_SEARCH_SKIPPED),
                    )
                }
            }
            indicator()
            IconButton(onClick = onPrevious, enabled = hasHits, modifier = Modifier.testTag(TAG_READER_SEARCH_PREVIOUS)) {
                Icon(painterResource(R.drawable.ic_search_previous), stringResource(R.string.reader_search_previous_cd))
            }
            IconButton(onClick = onNext, enabled = hasHits, modifier = Modifier.testTag(TAG_READER_SEARCH_NEXT)) {
                Icon(painterResource(R.drawable.ic_search_next), stringResource(R.string.reader_search_next_cd))
            }
        }
    }
}

/** "3 of 17", "Searching… 12 so far", "No results", or nothing before a search starts. */
@Composable
private fun searchCountText(state: ReaderSearchState?): String {
    if (state == null) return ""
    val numbers = NumberFormat.getIntegerInstance()
    val total = numbers.format(state.hits.size).let { if (state.capped) stringResource(R.string.reader_search_capped, it) else it }
    return when {
        state.scanning && state.hits.isEmpty() -> stringResource(R.string.reader_search_scanning)
        state.scanning -> stringResource(R.string.reader_search_so_far, total)
        state.hits.isEmpty() -> stringResource(R.string.reader_search_none)
        else -> stringResource(R.string.reader_search_position, state.currentIndex + 1, total)
    }
}
