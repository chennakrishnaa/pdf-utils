package app.venkat.offkit.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.venkat.offkit.R
import app.venkat.offkit.core.RecentFile
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.nav.Routes
import app.venkat.offkit.ui.support.HOUSE_PROMOS
import app.venkat.offkit.ui.support.HomeSupportCards
import app.venkat.offkit.ui.support.HousePromo
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitLink
import app.venkat.offkit.ui.theme.OffkitOnSuccessContainer
import app.venkat.offkit.ui.theme.OffkitTheme
import app.venkat.offkit.ui.theme.OffkitTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Test tag of the About action in the home app bar. */
const val TAG_ACTION_ABOUT = "action_about"

/**
 * Test tag of the privacy line under the wordmark ("Works offline. …"). It keeps the tag of the
 * old home footer, which it replaces, so tests that use it as a "Home is shown" marker still work.
 */
const val TAG_HOME_PRIVACY = "home_privacy"

/** Test tag of the recent-files section. */
const val TAG_RECENT_SECTION = "recent_section"

/** Test tag of the "Files you save show up here." hint. */
const val TAG_RECENT_EMPTY = "recent_empty"

/** Test tag of the "Clear" action of the recent-files section. */
const val TAG_RECENT_CLEAR = "recent_clear"

/** Test tags of the clear-confirmation dialog buttons. */
const val TAG_RECENT_CLEAR_CONFIRM = "recent_clear_confirm"
const val TAG_RECENT_CLEAR_CANCEL = "recent_clear_cancel"

/** Test tag of recent row [index] (0-based, newest first); tapping it opens the file. */
fun recentRowTag(index: Int): String = "recent_row_$index"

/** Test tag of the share button of recent row [index]. */
fun recentRowShareTag(index: Int): String = "recent_row_${index}_share"

/** Test tag of the remove-from-list button of recent row [index]. */
fun recentRowRemoveTag(index: Int): String = "recent_row_${index}_remove"

/** Test tag of the full-width "Read a PDF" card below the tool grid. */
const val TAG_TOOL_READ = "tool_read"

/** A tool card of the home grid: the design's [OffkitTool], the route it opens and its test tag. */
private data class HomeTool(val tool: OffkitTool, val route: String, val testTag: String)

private val homeTools = listOf(
    HomeTool(OffkitTool.IMAGES, Routes.IMAGES_TO_PDF, "tool_images_to_pdf"),
    HomeTool(OffkitTool.COMPRESS, Routes.COMPRESS, "tool_compress"),
    HomeTool(OffkitTool.MERGE, Routes.MERGE, "tool_merge"),
    HomeTool(OffkitTool.ADD_PAGES, Routes.ADD_IMAGES, "tool_add_images"),
)

/**
 * Home screen: the wordmark and privacy line, a 2×2 grid of tool cards, the recently saved files,
 * then (unless hidden) one house promo card from [promos] and the "Support the app" card, which
 * calls [onOpenSupport]. [onOpenTool] receives the tool's route from [Routes]. Everything below
 * the top bar scrolls as one column.
 */
@Composable
fun HomeScreen(
    onOpenTool: (route: String) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSupport: () -> Unit = {},
    onOpenDocument: (Uri) -> Unit = {},
    promos: List<HousePromo> = HOUSE_PROMOS,
) {
    val vm: HomeViewModel = viewModel {
        HomeViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]))
    }
    val recents by vm.recents.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val msgUnavailable = stringResource(R.string.recent_unavailable)
    val msgNoShare = stringResource(R.string.recent_no_share)

    HomeContent(
        recents = recents,
        snackbar = snackbar,
        onOpenTool = onOpenTool,
        onOpenAbout = onOpenAbout,
        onOpen = { file ->
            scope.launch {
                if (vm.checkAvailable(file)) {
                    onOpenDocument(file.uri)
                } else {
                    showMessage(scope, snackbar, msgUnavailable)
                }
            }
        },
        onShare = { file ->
            scope.launch {
                if (!tryStart(context, vm.shareIntent(file))) {
                    showMessage(scope, snackbar, msgNoShare)
                }
            }
        },
        onRemove = vm::remove,
        onClear = vm::clear,
        bottomCards = { HomeSupportCards(onOpenSupport = onOpenSupport, promos = promos) },
    )
}

/** Shows [message] without suspending the caller (a snackbar suspends until dismissed). */
private fun showMessage(scope: CoroutineScope, host: SnackbarHostState, message: String) {
    scope.launch { host.showSnackbar(message) }
}

private fun tryStart(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    // The target app may not accept the grant; treat it like "no app".
    false
}

/** The stateless home layout; [bottomCards] are emitted into the scrolling column after Recent. */
@Composable
private fun HomeContent(
    recents: List<RecentFile>?,
    snackbar: SnackbarHostState,
    onOpenTool: (route: String) -> Unit,
    onOpenAbout: () -> Unit,
    onOpen: (RecentFile) -> Unit,
    onShare: (RecentFile) -> Unit,
    onRemove: (RecentFile) -> Unit,
    onClear: () -> Unit,
    bottomCards: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        // Insets are applied by hand: status bar in the top bar, navigation bar inside the scroll.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding()) },
        topBar = { HomeTopBar(onOpenAbout) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OffkitDimens.screenPadding),
        ) {
            PrivacyLine()
            Spacer(Modifier.height(20.dp))
            ToolGrid(onOpenTool)
            Spacer(Modifier.height(OffkitDimens.itemGap))
            ReadCard(onClick = { onOpenTool(Routes.READER) })
            Spacer(Modifier.height(OffkitDimens.sectionGap))
            RecentSection(recents, onOpen, onShare, onRemove, onClear)
            Spacer(Modifier.height(OffkitDimens.itemGap))
            bottomCards()
            Spacer(Modifier.height(OffkitDimens.sectionGap))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun HomeTopBar(onOpenAbout: () -> Unit) {
    val appName = stringResource(R.string.app_name)
    val wordmark = buildAnnotatedString {
        append(appName)
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
            append(stringResource(R.string.home_wordmark_dot))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .heightIn(min = 64.dp)
            .padding(start = OffkitDimens.contentInset, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = wordmark,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            // The brand dot is decoration: accessibility services and tests see just the name.
            modifier = Modifier.weight(1f).clearAndSetSemantics {
                heading()
                text = AnnotatedString(appName)
            },
        )
        IconButton(
            onClick = onOpenAbout,
            modifier = Modifier.size(OffkitDimens.touchTarget).testTag(TAG_ACTION_ABOUT),
        ) {
            Icon(
                painterResource(R.drawable.ic_info),
                contentDescription = stringResource(R.string.home_about),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PrivacyLine() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = OffkitDimens.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_shield),
            contentDescription = null,
            tint = OffkitOnSuccessContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(OffkitDimens.itemGap))
        Text(
            text = stringResource(R.string.home_privacy_line),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TAG_HOME_PRIVACY),
        )
    }
}

/** A white card with a 1dp outlineVariant border and 20dp corners (SPEC §0 "Cards and lists"). */
@Composable
private fun Modifier.homeCard(): Modifier = this
    .clip(MaterialTheme.shapes.medium)
    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.medium)

@Composable
private fun ToolGrid(onOpenTool: (route: String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OffkitDimens.itemGap)) {
        homeTools.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(OffkitDimens.itemGap),
            ) {
                pair.forEach { tool ->
                    ToolCard(tool, onClick = { onOpenTool(tool.route) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ToolCard(tool: HomeTool, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = 128.dp)
            .homeCard()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tool.testTag)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Image(
            painterResource(tool.tool.badge),
            contentDescription = null,
            modifier = Modifier.size(OffkitDimens.iconContainer),
        )
        Column(modifier = Modifier.padding(top = 16.dp)) {
            Text(
                text = stringResource(tool.tool.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(tool.tool.description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The full-width "Read a PDF" card: the tool cards' look, laid out as one row. */
@Composable
private fun ReadCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .homeCard()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(TAG_TOOL_READ)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(R.drawable.ic_tool_read_badge),
            contentDescription = null,
            modifier = Modifier.size(OffkitDimens.iconContainer),
        )
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(
                text = stringResource(R.string.tool_read_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.tool_read_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentSection(
    recents: List<RecentFile>?,
    onOpen: (RecentFile) -> Unit,
    onShare: (RecentFile) -> Unit,
    onRemove: (RecentFile) -> Unit,
    onClear: () -> Unit,
) {
    var askClear by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(TAG_RECENT_SECTION),
        verticalArrangement = Arrangement.spacedBy(OffkitDimens.itemGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = OffkitDimens.itemGap),
        ) {
            Icon(
                painterResource(R.drawable.ic_history),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(OffkitDimens.iconSizeSmall),
            )
            Spacer(Modifier.width(OffkitDimens.itemGap))
            Text(
                text = stringResource(R.string.recent_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (!recents.isNullOrEmpty()) {
                TextButton(
                    onClick = { askClear = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = OffkitLink),
                    modifier = Modifier.heightIn(min = OffkitDimens.touchTarget).testTag(TAG_RECENT_CLEAR),
                ) {
                    Text(stringResource(R.string.recent_clear), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        when {
            recents == null -> Unit // Still loading (a few milliseconds); show nothing.
            recents.isEmpty() -> Box(modifier = Modifier.fillMaxWidth().homeCard().padding(24.dp)) {
                Text(
                    text = stringResource(R.string.recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_RECENT_EMPTY),
                )
            }
            else -> {
                val now = remember(recents) { System.currentTimeMillis() }
                Column(modifier = Modifier.fillMaxWidth().homeCard()) {
                    recents.forEachIndexed { index, file ->
                        key(file.uri.toString()) {
                            if (index > 0) {
                                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            RecentRow(index, file, now, onOpen, onShare, onRemove)
                        }
                    }
                }
            }
        }
    }
    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text(stringResource(R.string.recent_clear_title)) },
            text = { Text(stringResource(R.string.recent_clear_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askClear = false
                        onClear()
                    },
                    modifier = Modifier.testTag(TAG_RECENT_CLEAR_CONFIRM),
                ) { Text(stringResource(R.string.recent_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askClear = false }, modifier = Modifier.testTag(TAG_RECENT_CLEAR_CANCEL)) {
                    Text(stringResource(R.string.recent_clear_cancel))
                }
            },
        )
    }
}

/** "tool · size · time", leaving out the tool or size when unknown. */
@Composable
private fun recentMeta(file: RecentFile, now: Long): String {
    val context = LocalContext.current
    val parts = listOfNotNull(
        toolTitle(file.tool)?.let { stringResource(it) },
        file.sizeBytes?.let(::formatBytes),
        relativeTime(context, file.savedAtMillis, now),
    )
    return when (parts.size) {
        3 -> stringResource(R.string.recent_meta, parts[0], parts[1], parts[2])
        2 -> stringResource(R.string.recent_meta_two, parts[0], parts[1])
        else -> parts[0]
    }
}

@Composable
private fun RecentRow(
    index: Int,
    file: RecentFile,
    now: Long,
    onOpen: (RecentFile) -> Unit,
    onShare: (RecentFile) -> Unit,
    onRemove: (RecentFile) -> Unit,
) {
    val openLabel = stringResource(R.string.cd_open_file, file.name)
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button) { onOpen(file) }
                .semantics(mergeDescendants = true) { contentDescription = openLabel }
                .testTag(recentRowTag(index))
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    painterResource(R.drawable.ic_pdf_file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(OffkitDimens.iconSizeSmall),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = recentMeta(file, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        IconButton(
            onClick = { onShare(file) },
            modifier = Modifier.size(OffkitDimens.touchTarget).testTag(recentRowShareTag(index)),
        ) {
            Icon(
                painterResource(R.drawable.ic_share),
                contentDescription = stringResource(R.string.cd_share_file, file.name),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(OffkitDimens.iconSizeSmall),
            )
        }
        IconButton(
            onClick = { onRemove(file) },
            modifier = Modifier.size(OffkitDimens.touchTarget).testTag(recentRowRemoveTag(index)),
        ) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.cd_remove_recent, file.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(OffkitDimens.iconSizeSmall),
            )
        }
    }
}

private fun previewRecents(now: Long): List<RecentFile> = listOf(
    RecentFile("content://preview/a".toUri(), "merged-compressed-with-images.pdf", 1_200_000, now - 60_000, "add_images"),
    RecentFile("content://preview/b".toUri(), "airtel-Nov-Dec-compressed.pdf", 565_500, now - 120_000, "compress"),
    RecentFile("content://preview/c".toUri(), "merged.pdf", 2_300_000, now - 120_000, "merge"),
    RecentFile("content://preview/d".toUri(), "Photos-24-Nov.pdf", 405_200, now - 86_400_000, "images_to_pdf"),
)

@Composable
private fun HomePreview(recents: List<RecentFile>) {
    OffkitTheme {
        HomeContent(
            recents = recents,
            snackbar = remember { SnackbarHostState() },
            onOpenTool = {},
            onOpenAbout = {},
            onOpen = {},
            onShare = {},
            onRemove = {},
            onClear = {},
            bottomCards = {},
        )
    }
}

@Preview(name = "Home", widthDp = 412, heightDp = 915, showBackground = true)
@Composable
private fun HomePopulatedPreview() {
    HomePreview(previewRecents(System.currentTimeMillis()))
}

@Preview(name = "Home, no recent files", widthDp = 412, heightDp = 915, showBackground = true)
@Composable
private fun HomeEmptyPreview() {
    HomePreview(emptyList())
}
