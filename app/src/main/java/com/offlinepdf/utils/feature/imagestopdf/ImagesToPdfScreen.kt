package com.offlinepdf.utils.feature.imagestopdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinepdf.utils.R
import com.offlinepdf.utils.engine.CompressionLevel
import com.offlinepdf.utils.engine.PageSize
import com.offlinepdf.utils.ui.components.AppIcons
import com.offlinepdf.utils.ui.components.ChoiceCard
import com.offlinepdf.utils.ui.components.ChoiceGroup
import com.offlinepdf.utils.ui.components.FileList
import com.offlinepdf.utils.ui.components.FileRow
import com.offlinepdf.utils.ui.components.InfoNote
import com.offlinepdf.utils.ui.components.JobPanel
import com.offlinepdf.utils.ui.components.JobUiState
import com.offlinepdf.utils.ui.components.SecondaryActionButton
import com.offlinepdf.utils.ui.components.SectionHeader
import com.offlinepdf.utils.ui.components.ToolScaffold
import com.offlinepdf.utils.ui.components.formatBytes
import com.offlinepdf.utils.ui.components.hasUnsavedWork
import com.offlinepdf.utils.ui.components.rememberImagePicker
import com.offlinepdf.utils.ui.components.rememberPdfSaver

/** Test tag of the root content column. */
const val TAG_IMAGESTOPDF_ROOT = "imagestopdf_root"
const val TAG_IMAGESTOPDF_ADD = "imagestopdf_add"
const val TAG_IMAGESTOPDF_SAVE_ERROR = "imagestopdf_save_error"
const val TAG_IMAGESTOPDF_QUALITY_NOTE = "imagestopdf_quality_note"

/** Test tag of the page-size chip for [size]. */
fun imagesToPdfSizeTag(size: PageSize): String = when (size) {
    PageSize.FIT_IMAGE -> "imagestopdf_size_fit"
    PageSize.A4 -> "imagestopdf_size_a4"
    PageSize.LETTER -> "imagestopdf_size_letter"
}

/** Test tag of the image-quality card for [level] (null = original quality). */
fun imagesToPdfQualityTag(level: CompressionLevel?): String = when (level) {
    null -> "imagestopdf_quality_original"
    CompressionLevel.LOW -> "imagestopdf_quality_low"
    CompressionLevel.MEDIUM -> "imagestopdf_quality_medium"
    CompressionLevel.HIGH -> "imagestopdf_quality_high"
}

/** The quality options in display order. */
private val QUALITY_OPTIONS: List<CompressionLevel?> = listOf(null) + CompressionLevel.entries

@Composable
fun ImagesToPdfScreen(onBack: () -> Unit) {
    // Explicit initializer: the default AndroidViewModelFactory caches the first Application of the
    // process, which under Robolectric binds later tests' ViewModels to a stale Application/engine.
    val vm: ImagesToPdfViewModel = viewModel {
        ImagesToPdfViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]))
    }
    val images by vm.images.collectAsState()
    val pageSize by vm.pageSize.collectAsState()
    val quality by vm.quality.collectAsState()
    val estimates by vm.estimates.collectAsState()
    val state by vm.jobState.collectAsState()
    val saveError by vm.saveError.collectAsState()
    val editable = state !is JobUiState.Running && state !is JobUiState.Saving

    val pickImages = rememberImagePicker { uris -> vm.add(uris) }
    val saver = rememberPdfSaver { uri -> vm.save(uri) }

    ToolScaffold(
        title = stringResource(R.string.tool_images_to_pdf_title),
        onBack = onBack,
        confirmLeave = state.hasUnsavedWork(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag(TAG_IMAGESTOPDF_ROOT),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(stringResource(R.string.imagestopdf_images_header))
            FileList(
                rows = images.mapIndexed { index, f ->
                    FileRow(
                        key = "${f.uri}#$index",
                        title = f.name,
                        subtitle = f.sizeBytes?.let(::formatBytes),
                    )
                },
                onMoveUp = if (editable) vm::moveUp else null,
                onMoveDown = if (editable) vm::moveDown else null,
                onRemove = if (editable) vm::remove else null,
                emptyText = stringResource(R.string.imagestopdf_empty),
                leadingIcon = AppIcons.image,
            )
            SecondaryActionButton(
                text = stringResource(R.string.imagestopdf_add),
                onClick = pickImages,
                testTag = TAG_IMAGESTOPDF_ADD,
                enabled = editable,
                icon = AppIcons.add,
            )

            SectionHeader(stringResource(R.string.imagestopdf_size_header))
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PageSize.entries.forEach { size ->
                    val selected = size == pageSize
                    FilterChip(
                        selected = selected,
                        onClick = { vm.setPageSize(size) },
                        enabled = editable,
                        label = { Text(stringResource(sizeLabel(size))) },
                        leadingIcon = if (selected) {
                            { Icon(AppIcons.check, contentDescription = null, modifier = Modifier.padding(end = 2.dp)) }
                        } else {
                            null
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .semantics { role = Role.RadioButton }
                            .testTag(imagesToPdfSizeTag(size)),
                    )
                }
            }

            SectionHeader(stringResource(R.string.imagestopdf_quality_header))
            ChoiceGroup {
                QUALITY_OPTIONS.forEach { level ->
                    ChoiceCard(
                        title = stringResource(qualityTitle(level)),
                        subtitle = qualitySubtitle(level),
                        trailing = estimateText(estimates, level),
                        selected = level == quality,
                        onClick = { vm.setQuality(level) },
                        enabled = editable,
                        testTag = imagesToPdfQualityTag(level),
                    )
                }
            }
            InfoNote(
                text = stringResource(R.string.imagestopdf_quality_note),
                testTag = TAG_IMAGESTOPDF_QUALITY_NOTE,
            )

            JobPanel(
                state = state,
                runLabel = stringResource(R.string.imagestopdf_run),
                runEnabled = images.isNotEmpty(),
                onRun = vm::run,
                onCancel = vm::cancel,
                onSave = {
                    vm.clearSaveError()
                    saver(vm.suggestedFileName())
                },
                onReset = vm::reset,
                runIcon = AppIcons.pdf,
                modifier = Modifier.padding(top = 8.dp),
            )
            // Shown until a new run, a successful save, a reset or a changed input clears it.
            saveError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TAG_IMAGESTOPDF_SAVE_ERROR),
                )
            }
        }
    }
}

/** Trailing text of a quality card: the estimated size, "Estimating…", "—", or nothing without images. */
@Composable
private fun estimateText(estimates: QualityEstimates, level: CompressionLevel?): String? {
    if (estimates.imageCount == 0) return null
    val total = estimates.total(level)
    return when {
        total != null -> stringResource(R.string.imagestopdf_estimate, formatBytes(total))
        !estimates.anyFailed && estimates.pendingCount > 0 -> stringResource(R.string.imagestopdf_estimating)
        else -> stringResource(R.string.imagestopdf_estimate_unknown)
    }
}

private fun qualityTitle(level: CompressionLevel?): Int = when (level) {
    null -> R.string.imagestopdf_quality_original
    CompressionLevel.LOW -> R.string.imagestopdf_quality_low
    CompressionLevel.MEDIUM -> R.string.imagestopdf_quality_medium
    CompressionLevel.HIGH -> R.string.imagestopdf_quality_high
}

@Composable
private fun qualitySubtitle(level: CompressionLevel?): String = when (level) {
    null -> stringResource(R.string.imagestopdf_quality_original_detail)
    CompressionLevel.LOW -> stringResource(R.string.imagestopdf_quality_low_detail, level.maxLongEdgePx)
    CompressionLevel.MEDIUM -> stringResource(R.string.imagestopdf_quality_medium_detail, level.maxLongEdgePx)
    CompressionLevel.HIGH -> stringResource(R.string.imagestopdf_quality_high_detail, level.maxLongEdgePx)
}

private fun sizeLabel(size: PageSize): Int = when (size) {
    PageSize.FIT_IMAGE -> R.string.imagestopdf_size_fit
    PageSize.A4 -> R.string.imagestopdf_size_a4
    PageSize.LETTER -> R.string.imagestopdf_size_letter
}
