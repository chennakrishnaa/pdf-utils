package com.offlinepdf.utils.feature.addimages

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
import com.offlinepdf.utils.ui.components.rememberImagePicker
import com.offlinepdf.utils.ui.components.rememberPdfPicker
import com.offlinepdf.utils.ui.components.rememberPdfSaver

/** Test tag of the screen's root column. */
const val TAG_ADDIMAGES_ROOT = "addimages_root"

/** Test tag of the "Choose PDF" button. */
const val TAG_ADDIMAGES_CHOOSE_PDF = "addimages_choose_pdf"

/** Test tag of the chosen PDF's name (or the "no PDF" text). */
const val TAG_ADDIMAGES_PDF_NAME = "addimages_pdf_name"

/** Test tag of the chosen PDF's size. */
const val TAG_ADDIMAGES_PDF_SIZE = "addimages_pdf_size"

/** Test tag of the "Add images" button. */
const val TAG_ADDIMAGES_ADD_IMAGES = "addimages_add_images"

/** Test tag of the explanation of where and how the pages are added. */
const val TAG_ADDIMAGES_NOTE = "addimages_note"

/** Test tag of the inline save-error text. */
const val TAG_ADDIMAGES_SAVE_ERROR = "addimages_save_error"

@Composable
fun AddImagesScreen(onBack: () -> Unit) {
    // Explicit initializer: the default factory caches the first Application process-wide
    // (AndroidViewModelFactory.getInstance), which goes stale when tests create a fresh one.
    val vm: AddImagesViewModel = viewModel {
        AddImagesViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]))
    }
    val pdf by vm.pdf.collectAsState()
    val images by vm.images.collectAsState()
    val state by vm.jobState.collectAsState()
    val saveError by vm.saveError.collectAsState()
    val running = state is JobUiState.Running

    val pdfPicker = rememberPdfPicker(multiple = false) { uris -> vm.choosePdf(uris) }
    val imagePicker = rememberImagePicker { uris -> vm.addImages(uris) }
    val saver = rememberPdfSaver { uri -> vm.save(uri) }

    ToolScaffold(title = stringResource(R.string.tool_add_images_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag(TAG_ADDIMAGES_ROOT),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(stringResource(R.string.addimages_pdf_header))
            val chosen = pdf
            if (chosen == null) {
                Text(
                    text = stringResource(R.string.addimages_no_pdf),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TAG_ADDIMAGES_PDF_NAME),
                )
            } else {
                Column {
                    Text(
                        text = chosen.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(TAG_ADDIMAGES_PDF_NAME),
                    )
                    chosen.sizeBytes?.let { size ->
                        Text(
                            text = formatBytes(size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(TAG_ADDIMAGES_PDF_SIZE),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = pdfPicker,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag(TAG_ADDIMAGES_CHOOSE_PDF),
            ) {
                Text(stringResource(R.string.addimages_choose_pdf))
            }

            SectionHeader(stringResource(R.string.addimages_images_header))
            FileList(
                rows = images.map { item ->
                    FileRow(
                        key = item.key.toString(),
                        title = item.file.name,
                        subtitle = item.file.sizeBytes?.let(::formatBytes),
                    )
                },
                onMoveUp = if (running) null else vm::moveUp,
                onMoveDown = if (running) null else vm::moveDown,
                onRemove = if (running) null else vm::remove,
                emptyText = stringResource(R.string.addimages_empty),
            )
            OutlinedButton(
                onClick = imagePicker,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag(TAG_ADDIMAGES_ADD_IMAGES),
            ) {
                Text(stringResource(R.string.addimages_add_images))
            }
            Text(
                text = stringResource(R.string.addimages_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_ADDIMAGES_NOTE),
            )
            JobPanel(
                state = state,
                runLabel = stringResource(R.string.addimages_run),
                runEnabled = pdf != null && images.isNotEmpty(),
                onRun = vm::run,
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
                    modifier = Modifier.testTag(TAG_ADDIMAGES_SAVE_ERROR),
                )
            }
        }
    }
}
