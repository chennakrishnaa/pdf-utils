package app.venkat.offkit.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.venkat.offkit.R
import app.venkat.offkit.core.RecentFile
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.nav.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Test tag of the About action in the home app bar. */
const val TAG_ACTION_ABOUT = "action_about"

/** Test tag of the recent-files section. */
const val TAG_RECENT_SECTION = "recent_section"

/** Test tag of the "Files you save will appear here" hint. */
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

private data class Tool(
    val route: String,
    val testTag: String,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
)

private val tools = listOf(
    Tool(Routes.IMAGES_TO_PDF, "tool_images_to_pdf", R.string.tool_images_to_pdf_title, R.string.tool_images_to_pdf_desc),
    Tool(Routes.COMPRESS, "tool_compress", R.string.tool_compress_title, R.string.tool_compress_desc),
    Tool(Routes.MERGE, "tool_merge", R.string.tool_merge_title, R.string.tool_merge_desc),
    Tool(Routes.ADD_IMAGES, "tool_add_images", R.string.tool_add_images_title, R.string.tool_add_images_desc),
)

@Composable
private fun toolIcon(route: String): Painter = when (route) {
    Routes.IMAGES_TO_PDF -> AppIcons.image
    Routes.COMPRESS -> AppIcons.compress
    Routes.MERGE -> AppIcons.merge
    else -> AppIcons.addToPdf
}

/**
 * Home screen: one card per tool, then the recently saved files. [onOpenTool] receives the
 * tool's route from [Routes]. The whole content scrolls as one column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenTool: (route: String) -> Unit, onOpenAbout: () -> Unit) {
    val vm: HomeViewModel = viewModel {
        HomeViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]))
    }
    val recents by vm.recents.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val msgUnavailable = stringResource(R.string.recent_unavailable)
    val msgNoViewer = stringResource(R.string.recent_no_viewer)
    val msgNoShare = stringResource(R.string.recent_no_share)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenAbout, modifier = Modifier.testTag(TAG_ACTION_ABOUT)) {
                        Icon(AppIcons.info, contentDescription = stringResource(R.string.home_about))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeSectionTitle(stringResource(R.string.recent_tools_title))
            tools.forEach { tool -> ToolCard(tool, onClick = { onOpenTool(tool.route) }) }

            RecentSection(
                recents = recents,
                onOpen = { file ->
                    scope.launch {
                        val intent = vm.viewIntentIfAvailable(file)
                        if (intent == null) {
                            showMessage(scope, snackbar, msgUnavailable)
                        } else if (!tryStart(context, intent)) {
                            showMessage(scope, snackbar, msgNoViewer)
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
            )

            Text(
                text = stringResource(R.string.home_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("home_footer"),
            )
        }
    }
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

@Composable
private fun HomeSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = 4.dp).semantics { heading() },
    )
}

@Composable
private fun ToolCard(tool: Tool, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().testTag(tool.testTag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(toolIcon(tool.route), contentDescription = null, modifier = Modifier.padding(13.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(tool.title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(tool.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                AppIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(TAG_RECENT_SECTION),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(
                AppIcons.history,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            HomeSectionTitle(stringResource(R.string.recent_title), Modifier.weight(1f).padding(bottom = 4.dp))
            if (!recents.isNullOrEmpty()) {
                TextButton(onClick = { askClear = true }, modifier = Modifier.testTag(TAG_RECENT_CLEAR)) {
                    Text(stringResource(R.string.recent_clear))
                }
            }
        }
        when {
            recents == null -> Unit // Still loading (a few milliseconds); show nothing.
            recents.isEmpty() -> Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp).testTag(TAG_RECENT_EMPTY),
                )
            }
            else -> {
                val now = remember(recents) { System.currentTimeMillis() }
                recents.forEachIndexed { index, file ->
                    androidx.compose.runtime.key(file.uri.toString()) {
                        RecentRow(index, file, now, onOpen, onShare, onRemove)
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

@Composable
private fun RecentRow(
    index: Int,
    file: RecentFile,
    now: Long,
    onOpen: (RecentFile) -> Unit,
    onShare: (RecentFile) -> Unit,
    onRemove: (RecentFile) -> Unit,
) {
    val context = LocalContext.current
    val meta = listOfNotNull(
        toolTitle(file.tool)?.let { stringResource(it) },
        file.sizeBytes?.let(::formatBytes),
        relativeTime(context, file.savedAtMillis, now),
    ).joinToString(" · ")
    Card(
        onClick = { onOpen(file) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().testTag(recentRowTag(index)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(AppIcons.pdf, contentDescription = null, modifier = Modifier.padding(9.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onShare(file) }, modifier = Modifier.testTag(recentRowShareTag(index))) {
                Icon(AppIcons.share, contentDescription = stringResource(R.string.recent_share))
            }
            IconButton(onClick = { onRemove(file) }, modifier = Modifier.testTag(recentRowRemoveTag(index))) {
                Icon(AppIcons.close, contentDescription = stringResource(R.string.recent_remove))
            }
        }
    }
}
