package com.offlinepdf.utils.feature.compress

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinepdf.utils.R
import com.offlinepdf.utils.engine.CompressionLevel
import com.offlinepdf.utils.ui.components.FileList
import com.offlinepdf.utils.ui.components.FileRow
import com.offlinepdf.utils.ui.components.JobPanel
import com.offlinepdf.utils.ui.components.JobUiState
import com.offlinepdf.utils.ui.components.SectionHeader
import com.offlinepdf.utils.ui.components.ToolScaffold
import com.offlinepdf.utils.ui.components.formatBytes
import com.offlinepdf.utils.ui.components.rememberPdfPicker
import com.offlinepdf.utils.ui.components.rememberPdfSaver

/** Test tag of the root content column. */
const val TAG_COMPRESS_ROOT = "compress_root"

/** Test tag of the "Choose PDF" button. */
const val TAG_COMPRESS_CHOOSE = "compress_choose"

/** Test tag of the explanation below the level choice. */
const val TAG_COMPRESS_NOTE = "compress_note"

/** Test tag of the inline save-error text. */
const val TAG_COMPRESS_SAVE_ERROR = "compress_save_error"

/** Test tag of the option for [level]: `compress_level_low`, `compress_level_medium`, `compress_level_high`. */
fun compressLevelTag(level: CompressionLevel): String = "compress_level_${level.name.lowercase()}"

@StringRes
private fun levelLabel(level: CompressionLevel): Int = when (level) {
    CompressionLevel.LOW -> R.string.compress_level_low
    CompressionLevel.MEDIUM -> R.string.compress_level_medium
    CompressionLevel.HIGH -> R.string.compress_level_high
}

@Composable
fun CompressScreen(onBack: () -> Unit) {
    // Explicit initializer: the default factory caches the first Application process-wide
    // (AndroidViewModelFactory.getInstance), which goes stale when tests create a fresh one.
    val vm: CompressViewModel = viewModel {
        CompressViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]))
    }
    val file by vm.file.collectAsState()
    val level by vm.level.collectAsState()
    val state by vm.jobState.collectAsState()
    val saveError by vm.saveError.collectAsState()
    val editable = state !is JobUiState.Running

    val picker = rememberPdfPicker(multiple = false) { uris -> uris.firstOrNull()?.let(vm::choose) }
    val saver = rememberPdfSaver { uri -> vm.save(uri) }

    ToolScaffold(title = stringResource(R.string.tool_compress_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag(TAG_COMPRESS_ROOT),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(stringResource(R.string.compress_file_header))
            FileList(
                rows = listOfNotNull(file).map { f ->
                    FileRow(key = f.uri.toString(), title = f.name, subtitle = f.sizeBytes?.let(::formatBytes))
                },
                onMoveUp = null,
                onMoveDown = null,
                onRemove = null,
                emptyText = stringResource(R.string.compress_empty),
            )
            OutlinedButton(
                onClick = picker,
                enabled = editable,
                modifier = Modifier.fillMaxWidth().testTag(TAG_COMPRESS_CHOOSE),
            ) {
                Text(stringResource(R.string.compress_choose))
            }

            SectionHeader(stringResource(R.string.compress_level_header))
            Column(modifier = Modifier.selectableGroup()) {
                CompressionLevel.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == level,
                                enabled = editable,
                                role = Role.RadioButton,
                                onClick = { vm.setLevel(option) },
                            )
                            .padding(vertical = 4.dp)
                            .testTag(compressLevelTag(option)),
                    ) {
                        RadioButton(selected = option == level, onClick = null, enabled = editable)
                        Text(
                            text = stringResource(levelLabel(option)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.compress_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_COMPRESS_NOTE),
            )

            JobPanel(
                state = state,
                runLabel = stringResource(R.string.compress_run),
                runEnabled = file != null,
                onRun = vm::run,
                onCancel = vm::cancel,
                onSave = { saver(vm.suggestedFileName()) },
                onReset = vm::reset,
                modifier = Modifier.padding(top = 8.dp),
            )
            saveError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TAG_COMPRESS_SAVE_ERROR),
                )
            }
        }
    }
    // Keep the save error visible until the user acts again; clear it when the state moves on.
    LaunchedEffect(state) {
        if (state !is JobUiState.ReadyToSave) vm.clearSaveError()
    }
}
