package app.venkat.offkit.feature.compress

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.venkat.offkit.R
import app.venkat.offkit.core.PickedFile
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.ui.components.OffkitFileCard
import app.venkat.offkit.ui.components.OffkitFileSlot
import app.venkat.offkit.ui.components.OffkitInfoNote
import app.venkat.offkit.ui.components.OffkitJobFlow
import app.venkat.offkit.ui.components.OffkitNavIcon
import app.venkat.offkit.ui.components.OffkitOption
import app.venkat.offkit.ui.components.OffkitPrimaryButton
import app.venkat.offkit.ui.components.OffkitRadioGroup
import app.venkat.offkit.ui.components.OffkitScaffold
import app.venkat.offkit.ui.components.OffkitSectionHeader
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.rememberPdfPicker
import app.venkat.offkit.ui.theme.OffkitTheme
import app.venkat.offkit.ui.theme.OffkitTool

/** Test tag of the options screen's root. */
const val TAG_COMPRESS_ROOT = "compress_root"

/** Test tag of the empty file slot and of the file card's Change button (both open the picker). */
const val TAG_COMPRESS_CHOOSE = "compress_choose"

/** Test tag of the card showing the chosen PDF. */
const val TAG_COMPRESS_FILE = "compress_file"

/** Test tag of the explanation below the level choice. */
const val TAG_COMPRESS_NOTE = "compress_note"

/** Test tag of the option for [level]: `compress_level_low`, `compress_level_medium`, `compress_level_high`. */
fun compressLevelTag(level: CompressionLevel): String = "compress_level_${level.name.lowercase()}"

@StringRes
private fun levelLabel(level: CompressionLevel): Int = when (level) {
    CompressionLevel.LOW -> R.string.level_low
    CompressionLevel.MEDIUM -> R.string.level_medium
    CompressionLevel.HIGH -> R.string.level_high
}

@StringRes
private fun levelDescription(level: CompressionLevel): Int = when (level) {
    CompressionLevel.LOW -> R.string.level_low_desc
    CompressionLevel.MEDIUM -> R.string.level_medium_desc
    CompressionLevel.HIGH -> R.string.level_high_desc
}

/** "Compress PDF" tool: options, then the shared Processing / Result / Error screens. */
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
    val shareRequest by vm.shareRequest.collectAsState()
    val picker = rememberPdfPicker(multiple = false) { uris -> uris.firstOrNull()?.let(vm::choose) }

    OffkitJobFlow(
        tool = OffkitTool.COMPRESS,
        title = stringResource(R.string.screen_compress),
        state = state,
        inputName = file?.name,
        inputBytes = file?.sizeBytes,
        saveError = saveError,
        onSaveErrorShown = vm::clearSaveError,
        shareRequest = shareRequest,
        onShareHandled = vm::onShareHandled,
        onCancel = vm::cancel,
        onSave = vm::save,
        onShare = vm::share,
        onReset = vm::reset,
        onLeave = onBack,
        errorActionLabel = stringResource(R.string.action_choose_another),
        noGainAsError = true,
    ) {
        CompressOptions(
            file = file,
            level = level,
            onBack = onBack,
            onChoose = picker,
            onLevel = vm::setLevel,
            onRun = vm::run,
        )
    }
}

/** The options screen (the job is `Idle`): the PDF, the level and the Compress button. */
@Composable
private fun CompressOptions(
    file: PickedFile?,
    level: CompressionLevel,
    onBack: () -> Unit,
    onChoose: () -> Unit,
    onLevel: (CompressionLevel) -> Unit,
    onRun: () -> Unit,
) {
    OffkitScaffold(
        title = stringResource(R.string.screen_compress),
        navIcon = OffkitNavIcon.Back,
        onNavigate = onBack,
        modifier = Modifier.testTag(TAG_COMPRESS_ROOT),
        bottomBar = {
            OffkitPrimaryButton(
                text = stringResource(if (file != null) R.string.action_compress else R.string.choose_pdf_first),
                onClick = onRun,
                enabled = file != null,
                // A disabled primary button shows only its label (designs 02, 07c, 08b).
                icon = if (file != null) painterResource(OffkitTool.COMPRESS.icon) else null,
                modifier = Modifier.fillMaxWidth(),
                testTag = TAG_JOB_RUN,
            )
        },
    ) {
        OffkitSectionHeader(stringResource(R.string.section_pdf), first = true)
        if (file == null) {
            OffkitFileSlot(
                title = stringResource(R.string.choose_pdf),
                hint = stringResource(R.string.choose_pdf_hint),
                onClick = onChoose,
                testTag = TAG_COMPRESS_CHOOSE,
            )
        } else {
            OffkitFileCard(
                name = file.name,
                detail = file.sizeBytes?.let(::formatBytes),
                actionLabel = stringResource(R.string.change),
                onAction = onChoose,
                actionTestTag = TAG_COMPRESS_CHOOSE,
                testTag = TAG_COMPRESS_FILE,
            )
        }

        OffkitSectionHeader(stringResource(R.string.section_compression_level))
        val levels = CompressionLevel.entries
        OffkitRadioGroup(
            options = levels.map {
                OffkitOption(
                    label = stringResource(levelLabel(it)),
                    description = stringResource(levelDescription(it)),
                    testTag = compressLevelTag(it),
                )
            },
            selectedIndex = levels.indexOf(level),
            onSelect = { onLevel(levels[it]) },
        )
        OffkitInfoNote(
            text = stringResource(R.string.compress_note),
            modifier = Modifier.padding(top = 16.dp),
            testTag = TAG_COMPRESS_NOTE,
        )
    }
}

@Preview(widthDp = 412, heightDp = 860)
@Composable
private fun CompressNoFilePreview() {
    OffkitTheme {
        CompressOptions(file = null, level = CompressionLevel.MEDIUM, onBack = {}, onChoose = {}, onLevel = {}, onRun = {})
    }
}

@Preview(widthDp = 412, heightDp = 860)
@Composable
private fun CompressFileChosenPreview() {
    OffkitTheme {
        CompressOptions(
            file = PickedFile(android.net.Uri.EMPTY, "airtel-Nov-Dec.pdf", 2_400_000),
            level = CompressionLevel.MEDIUM,
            onBack = {},
            onChoose = {},
            onLevel = {},
            onRun = {},
        )
    }
}
