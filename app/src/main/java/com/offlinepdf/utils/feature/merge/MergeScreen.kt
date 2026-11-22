package com.offlinepdf.utils.feature.merge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinepdf.utils.R
import com.offlinepdf.utils.ui.components.FileList
import com.offlinepdf.utils.ui.components.FileRow
import com.offlinepdf.utils.ui.components.JobPanel
import com.offlinepdf.utils.ui.components.JobUiState
import com.offlinepdf.utils.ui.components.SectionHeader
import com.offlinepdf.utils.ui.components.ToolScaffold
import com.offlinepdf.utils.ui.components.formatBytes
import com.offlinepdf.utils.ui.components.rememberPdfPicker
import com.offlinepdf.utils.ui.components.rememberPdfSaver

/** Test tag of the "Add PDFs" button. */
const val TAG_MERGE_ADD = "merge_add"

/** Test tag of the note about what is and is not carried over. */
const val TAG_MERGE_NOTE = "merge_note"

/** Test tag of the inline save-error text. */
const val TAG_MERGE_SAVE_ERROR = "merge_save_error"

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
    val running = state is JobUiState.Running

    val picker = rememberPdfPicker(multiple = true) { uris -> vm.add(uris) }
    val saver = rememberPdfSaver { uri -> vm.save(uri) }

    ToolScaffold(title = stringResource(R.string.tool_merge_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(stringResource(R.string.merge_files_header))
            FileList(
                rows = items.map { item ->
                    FileRow(
                        key = item.key.toString(),
                        title = item.file.name,
                        subtitle = item.file.sizeBytes?.let(::formatBytes),
                    )
                },
                onMoveUp = if (running) null else vm::moveUp,
                onMoveDown = if (running) null else vm::moveDown,
                onRemove = if (running) null else vm::remove,
                emptyText = stringResource(R.string.merge_empty),
            )
            OutlinedButton(
                onClick = picker,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag(TAG_MERGE_ADD),
            ) {
                Text(stringResource(R.string.merge_add))
            }
            Text(
                text = stringResource(R.string.merge_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_MERGE_NOTE),
            )
            JobPanel(
                state = state,
                runLabel = stringResource(R.string.merge_run),
                runEnabled = items.size >= 2,
                onRun = vm::merge,
                onCancel = vm::cancel,
                onSave = {
                    vm.clearSaveError()
                    saver(vm.suggestedFileName())
                },
                onReset = vm::reset,
            )
            saveError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TAG_MERGE_SAVE_ERROR),
                )
            }
        }
    }
}
