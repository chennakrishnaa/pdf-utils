package app.venkat.filetools.feature.compress

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import app.venkat.filetools.R
import app.venkat.filetools.engine.CompressionLevel
import app.venkat.filetools.ui.components.AppIcons
import app.venkat.filetools.ui.components.ChoiceCard
import app.venkat.filetools.ui.components.ChoiceGroup
import app.venkat.filetools.ui.components.InfoNote
import app.venkat.filetools.ui.components.JobPanel
import app.venkat.filetools.ui.components.JobUiState
import app.venkat.filetools.ui.components.SecondaryActionButton
import app.venkat.filetools.ui.components.SectionHeader
import app.venkat.filetools.ui.components.SelectedFileCard
import app.venkat.filetools.ui.components.ToolScaffold
import app.venkat.filetools.ui.components.formatBytes
import app.venkat.filetools.ui.components.hasUnsavedWork
import app.venkat.filetools.ui.components.rememberPdfPicker
import app.venkat.filetools.ui.components.rememberPdfSaver

/** Test tag of the root content column. */
const val TAG_COMPRESS_ROOT = "compress_root"

/** Test tag of the "Choose PDF" button. */
const val TAG_COMPRESS_CHOOSE = "compress_choose"

/** Test tag of the card showing the chosen PDF (or the "no PDF chosen" placeholder). */
const val TAG_COMPRESS_FILE = "compress_file"

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

/** Subtitle of [level]'s card; takes the level's maximum image edge in px. */
@StringRes
private fun levelDetail(level: CompressionLevel): Int = when (level) {
    CompressionLevel.LOW -> R.string.compress_level_low_detail
    CompressionLevel.MEDIUM -> R.string.compress_level_medium_detail
    CompressionLevel.HIGH -> R.string.compress_level_high_detail
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
    // Inputs and options are locked while the job runs and while its result is being saved.
    val editable = state !is JobUiState.Running && state !is JobUiState.Saving

    val picker = rememberPdfPicker(multiple = false) { uris -> uris.firstOrNull()?.let(vm::choose) }
    val saver = rememberPdfSaver { uri -> vm.save(uri) }

    ToolScaffold(
        title = stringResource(R.string.tool_compress_title),
        onBack = onBack,
        confirmLeave = state.hasUnsavedWork(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag(TAG_COMPRESS_ROOT),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(stringResource(R.string.compress_file_header))
            val chosen = file
            SelectedFileCard(
                name = chosen?.name ?: stringResource(R.string.compress_empty),
                detail = chosen?.sizeBytes?.let(::formatBytes),
                testTag = TAG_COMPRESS_FILE,
            )
            SecondaryActionButton(
                text = stringResource(R.string.compress_choose),
                onClick = picker,
                enabled = editable,
                testTag = TAG_COMPRESS_CHOOSE,
                icon = AppIcons.pdf,
            )

            SectionHeader(stringResource(R.string.compress_level_header))
            ChoiceGroup {
                CompressionLevel.entries.forEach { option ->
                    ChoiceCard(
                        title = stringResource(levelLabel(option)),
                        subtitle = stringResource(levelDetail(option), option.maxLongEdgePx),
                        selected = option == level,
                        onClick = { vm.setLevel(option) },
                        enabled = editable,
                        testTag = compressLevelTag(option),
                    )
                }
            }
            InfoNote(text = stringResource(R.string.compress_note), testTag = TAG_COMPRESS_NOTE)

            JobPanel(
                state = state,
                runLabel = stringResource(R.string.compress_run),
                runEnabled = file != null,
                onRun = vm::run,
                onCancel = vm::cancel,
                onSave = { saver(vm.suggestedFileName()) },
                onReset = vm::reset,
                runIcon = AppIcons.compress,
                modifier = Modifier.padding(top = 8.dp),
            )
            // Shown in any state (e.g. "result no longer available" arrives in Idle); the
            // ViewModel clears it when a run starts, a save succeeds or the inputs change.
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
}
