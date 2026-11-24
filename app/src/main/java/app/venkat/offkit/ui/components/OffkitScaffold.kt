package app.venkat.offkit.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.venkat.offkit.R
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme

/** Test tag of the Back navigation icon of [OffkitScaffold]. */
const val TAG_ACTION_BACK = "action_back"

/** Test tag of the Close navigation icon of [OffkitScaffold]. (Back uses [TAG_ACTION_BACK].) */
const val TAG_ACTION_CLOSE = "action_close"

/** Which navigation icon the top app bar of [OffkitScaffold] shows. */
enum class OffkitNavIcon {
    /** `ic_back`, content description `action_back`, tag [TAG_ACTION_BACK]. Mirrored in RTL. */
    Back,

    /** `ic_close`, content description `cd_close`, tag [TAG_ACTION_CLOSE]. */
    Close,

    /** No navigation icon; the title starts at the screen's 16dp edge. */
    None,
}

/** A Modifier that mirrors directional icons (back, move earlier/later) in right-to-left layouts. */
@Composable
internal fun Modifier.mirrorInRtl(): Modifier =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) this.scale(scaleX = -1f, scaleY = 1f) else this

/**
 * Screen frame of the redesign (SPEC §0 "Screen skeleton").
 *
 * Renders, top to bottom, on the `background` colour:
 * - a top app bar: a row of at least 64dp, padded by the status-bar insets, with a 48dp navigation
 *   icon button chosen by [navIcon] (Back: `ic_back`, "Back", tag [TAG_ACTION_BACK]; Close:
 *   `ic_close`, "Close", tag [TAG_ACTION_CLOSE]; None) that calls [onNavigate], then [title] in
 *   `titleLarge`, one line, ellipsised, marked as a heading;
 * - [content], in a vertically scrolling column ([scrollState]) with 16dp side padding, 8dp top
 *   and 24dp bottom padding. Do not put another vertical scroller inside it;
 * - the [snackbarHostState]'s snackbars, if given, just above the bottom bar;
 * - [bottomBar], if given, in a fixed bar: `surfaceContainerLowest`, 1dp `outlineVariant` top
 *   border, 16dp side padding, 12dp top padding, bottom padding = navigation-bar inset + 12dp.
 *   Put a full-width [OffkitPrimaryButton] or an [OffkitBottomSummary] in it. Without a bottom
 *   bar the content column ends with the navigation-bar inset.
 *
 * The frame provides `LocalContentColor = onSurface` to its slots.
 * The whole frame applies `imePadding()`, so the bottom bar rises above the keyboard, and the
 * horizontal safe-drawing insets (landscape cut-outs). Call `enableEdgeToEdge()` in the Activity.
 * It has no leave confirmation and no BackHandler: system back is left to navigation.
 */
@Composable
fun OffkitScaffold(
    title: String,
    navIcon: OffkitNavIcon,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: (@Composable () -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    // Untinted Text / Icons in the slots take onSurface, not the default black.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .imePadding(),
        ) {
            OffkitTopBar(title = title, navIcon = navIcon, onNavigate = onNavigate)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(start = OffkitDimens.screenPadding, end = OffkitDimens.screenPadding, top = 8.dp, bottom = 24.dp),
            ) {
                content()
                if (bottomBar == null) Spacer(Modifier.navigationBarsPadding())
            }
            if (snackbarHostState != null) {
                SnackbarHost(snackbarHostState, modifier = Modifier.fillMaxWidth())
            }
            if (bottomBar != null) OffkitBottomBar(content = bottomBar)
        }
    }
}

/** Top app bar of [OffkitScaffold]. */
@Composable
private fun OffkitTopBar(title: String, navIcon: OffkitNavIcon, onNavigate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp)
            .padding(start = if (navIcon == OffkitNavIcon.None) OffkitDimens.screenPadding else 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (navIcon) {
            OffkitNavIcon.Back -> IconButton(onClick = onNavigate, modifier = Modifier.testTag(TAG_ACTION_BACK)) {
                Icon(AppIcons.back, stringResource(R.string.action_back), modifier = Modifier.mirrorInRtl())
            }
            OffkitNavIcon.Close -> IconButton(onClick = onNavigate, modifier = Modifier.testTag(TAG_ACTION_CLOSE)) {
                Icon(AppIcons.close, stringResource(R.string.cd_close))
            }
            OffkitNavIcon.None -> Unit
        }
        if (navIcon != OffkitNavIcon.None) Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

/** The fixed bottom action bar of [OffkitScaffold]. */
@Composable
private fun OffkitBottomBar(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = OffkitDimens.screenPadding, vertical = OffkitDimens.bottomBarPaddingTop),
            ) { content() }
        }
    }
}

@Preview(widthDp = 412, heightDp = 600)
@Composable
private fun OffkitScaffoldPreview() {
    OffkitTheme {
        OffkitScaffold(
            title = stringResource(R.string.screen_compress),
            navIcon = OffkitNavIcon.Back,
            onNavigate = {},
            bottomBar = {
                OffkitPrimaryButton(
                    text = stringResource(R.string.choose_pdf_first),
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            OffkitSectionHeader(stringResource(R.string.section_pdf))
            OffkitFileSlot(stringResource(R.string.choose_pdf), stringResource(R.string.choose_pdf_hint), onClick = {})
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun OffkitTopBarClosePreview() {
    OffkitTheme {
        Column(Modifier.background(MaterialTheme.colorScheme.background), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OffkitTopBar(stringResource(R.string.screen_merge), OffkitNavIcon.Close) {}
            OffkitTopBar(stringResource(R.string.screen_images), OffkitNavIcon.None) {}
        }
    }
}
