package com.offlinepdf.utils.feature.imagestopdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinepdf.utils.R
import com.offlinepdf.utils.engine.PageSize
import com.offlinepdf.utils.ui.components.FileList
import com.offlinepdf.utils.ui.components.FileRow
import com.offlinepdf.utils.ui.components.JobPanel
import com.offlinepdf.utils.ui.components.JobUiState
import com.offlinepdf.utils.ui.components.SectionHeader
import com.offlinepdf.utils.ui.components.ToolScaffold
import com.offlinepdf.utils.ui.components.formatBytes
import com.offlinepdf.utils.ui.components.rememberImagePicker
import com.offlinepdf.utils.ui.components.rememberPdfSaver

/** Test tag of the root content column (kept from the original placeholder for navigation tests). */
const val TAG_IMAGESTOPDF_ROOT = "stub_images_to_pdf"
const val TAG_IMAGESTOPDF_ADD = "imagestopdf_add"
const val TAG_IMAGESTOPDF_SAVE_ERROR = "imagestopdf_save_error"

/** Test tag of the page-size chip for [size]. */
fun imagesToPdfSizeTag(size: PageSize): String = when (size) {
    PageSize.FIT_IMAGE -> "imagestopdf_size_fit"
    PageSize.A4 -> "imagestopdf_size_a4"
    PageSize.LETTER -> "imagestopdf_size_letter"
}

@Composable
fun ImagesToPdfScreen(onBack: () -> Unit) {
    val vm: ImagesToPdfViewModel = viewModel()
    val images by vm.images.collectAsState()
    val pageSize by vm.pageSize.collectAsState()
    val state by vm.jobState.collectAsState()
    val saveError by vm.saveError.collectAsState()
    val editable = state !is JobUiState.Running

    val pickImages = rememberImagePicker { uris -> vm.add(uris) }
    val saver = rememberPdfSaver { uri -> vm.save(uri) }

    ToolScaffold(title = stringResource(R.string.tool_images_to_pdf_title), onBack = onBack) { padding ->
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
            )
            OutlinedButton(
                onClick = pickImages,
                enabled = editable,
                modifier = Modifier.fillMaxWidth().testTag(TAG_IMAGESTOPDF_ADD),
            ) {
                Text(stringResource(R.string.imagestopdf_add))
            }

            SectionHeader(stringResource(R.string.imagestopdf_size_header))
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PageSize.entries.forEach { size ->
                    FilterChip(
                        selected = size == pageSize,
                        onClick = { vm.setPageSize(size) },
                        enabled = editable,
                        label = { Text(stringResource(sizeLabel(size))) },
                        modifier = Modifier
                            .semantics { role = Role.RadioButton }
                            .testTag(imagesToPdfSizeTag(size)),
                    )
                }
            }

            JobPanel(
                state = state,
                runLabel = stringResource(R.string.imagestopdf_run),
                runEnabled = images.isNotEmpty(),
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
                    modifier = Modifier.testTag(TAG_IMAGESTOPDF_SAVE_ERROR),
                )
            }
        }
    }
    // Keep the save error visible until the user acts again; clear it when the state moves on.
    LaunchedEffect(state) {
        if (state !is JobUiState.ReadyToSave) vm.clearSaveError()
    }
}

private fun sizeLabel(size: PageSize): Int = when (size) {
    PageSize.FIT_IMAGE -> R.string.imagestopdf_size_fit
    PageSize.A4 -> R.string.imagestopdf_size_a4
    PageSize.LETTER -> R.string.imagestopdf_size_letter
}
