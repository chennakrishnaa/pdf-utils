package app.venkat.offkit.feature.merge

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.FileRow
import app.venkat.offkit.ui.components.OffkitBottomSummary
import app.venkat.offkit.ui.components.OffkitInfoNote
import app.venkat.offkit.ui.components.OffkitJobFlow
import app.venkat.offkit.ui.components.OffkitNavIcon
import app.venkat.offkit.ui.components.OffkitPrimaryButton
import app.venkat.offkit.ui.components.OffkitScaffold
import app.venkat.offkit.ui.components.OffkitSectionHeader
import app.venkat.offkit.ui.components.OffkitSelectableFileList
import app.venkat.offkit.ui.components.TAG_FILE_LIST_EMPTY
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.rememberPdfPicker
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme
import app.venkat.offkit.ui.theme.OffkitTool

/** Test tag of the options screen's content column. */
const val TAG_MERGE_ROOT = "merge_root"

/** Test tag of the "Add PDFs" button. */
const val TAG_MERGE_ADD = "merge_add"

/** Test tag of the note about what is and is not carried over. */
const val TAG_MERGE_NOTE = "merge_note"

/** Fewest PDFs a merge needs. */
private const val MIN_FILES = 2

/**
 * "Merge PDFs" (SPEC §8). The options screen lists the chosen PDFs in output order; the shared
 * Processing, Result and Error screens of [OffkitJobFlow] cover the job.
 */
@Composable
fun MergeScreen(onBack: () -> Unit) {
    // Explicit initializer: the default factory caches the first Application process-wide
    // (AndroidViewModelFactory.getInstance), which goes stale when tests create a fresh one.
    val vm: MergeViewModel = viewModel {
        MergeViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]))
    }
    val items by vm.items.collectAsState()
    val state by vm.jobState.collectAsState()
    val saveError by vm.saveError.collectAsState()
    val shareRequest by vm.shareRequest.collectAsState()
    // Screen state: the selected row, by index. Cleared on add and remove; follows a moved row.
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val picker = rememberPdfPicker(multiple = true) { uris ->
        selected = null
        vm.add(uris)
    }

    OffkitJobFlow(
        tool = OffkitTool.MERGE,
        title = stringResource(R.string.screen_merge),
        state = state,
        inputName = null,
        inputBytes = null,
        saveError = saveError,
        onSaveErrorShown = vm::clearSaveError,
        shareRequest = shareRequest,
        onShareHandled = vm::onShareHandled,
        onCancel = vm::cancel,
        onSave = vm::save,
        onShare = vm::share,
        onReset = vm::reset,
        onLeave = onBack,
        errorActionLabel = stringResource(R.string.job_try_again),
    ) {
        MergeOptions(
            rows = items.map { item ->
                FileRow(
                    key = item.key.toString(),
                    title = item.file.name,
                    subtitle = item.file.sizeBytes?.let(::formatBytes),
                )
            },
            selectedIndex = selected?.takeIf { it in items.indices },
            onSelect = { selected = it },
            onMoveUp = { index ->
                vm.moveUp(index)
                selected = index - 1
            },
            onMoveDown = { index ->
                vm.moveDown(index)
                selected = index + 1
            },
            onRemove = { index ->
                vm.remove(index)
                selected = null
            },
            onAdd = picker,
            onRun = vm::merge,
            onBack = onBack,
        )
    }
}

/** The stateless options screen: the list in output order, the note and the Merge bar. */
@Composable
private fun MergeOptions(
    rows: List<FileRow>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    onRun: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val canRun = rows.size >= MIN_FILES
    OffkitScaffold(
        title = stringResource(R.string.screen_merge),
        navIcon = OffkitNavIcon.Back,
        onNavigate = onBack,
        modifier = Modifier.testTag(TAG_MERGE_ROOT),
        bottomBar = {
            OffkitBottomSummary(
                title = pluralStringResource(R.plurals.files_count, rows.size, rows.size),
                subtitle = stringResource(if (canRun) R.string.merge_order_hint else R.string.merge_need_two),
            ) { modifier ->
                OffkitPrimaryButton(
                    text = stringResource(R.string.action_merge),
                    onClick = onRun,
                    modifier = modifier,
                    enabled = canRun,
                    icon = painterResource(R.drawable.ic_tool_merge),
                    testTag = TAG_JOB_RUN,
                )
            }
        },
    ) {
        OffkitSectionHeader(
            text = stringResource(R.string.merge_section_count, rows.size),
            actionLabel = stringResource(R.string.add_pdfs),
            onAction = onAdd,
            actionIcon = AppIcons.add,
            actionTestTag = TAG_MERGE_ADD,
        )
        Text(
            text = stringResource(R.string.merge_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
        )
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.merge_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(TAG_FILE_LIST_EMPTY),
            )
        } else {
            OffkitSelectableFileList(
                rows = rows,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove,
            )
        }
        OffkitInfoNote(
            text = stringResource(R.string.merge_note),
            modifier = Modifier.padding(top = OffkitDimens.screenPadding),
            testTag = TAG_MERGE_NOTE,
        )
    }
}

private val previewRows = listOf(
    FileRow(key = "1", title = "Invoice-Oct.pdf", subtitle = "180 KB"),
    FileRow(key = "2", title = "Statement-Q3.pdf", subtitle = "1.4 MB"),
    FileRow(key = "3", title = "Contract-signed.pdf", subtitle = "720 KB"),
)

@Composable
private fun MergeOptionsPreview(rows: List<FileRow>, selectedIndex: Int?) {
    OffkitTheme { MergeOptions(rows, selectedIndex, {}, {}, {}, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun MergeEmptyPreview() = MergeOptionsPreview(emptyList(), null)

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun MergeOneFilePreview() = MergeOptionsPreview(previewRows.take(1), null)

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun MergeThreeSelectedPreview() = MergeOptionsPreview(previewRows, 1)
