package app.venkat.offkit.feature.addimages

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
import app.venkat.offkit.R
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.FileList
import app.venkat.offkit.ui.components.FileRow
import app.venkat.offkit.ui.components.InfoNote
import app.venkat.offkit.ui.components.JobPanel
import app.venkat.offkit.ui.components.JobUiState
import app.venkat.offkit.ui.components.SecondaryActionButton
import app.venkat.offkit.ui.components.SectionHeader
import app.venkat.offkit.ui.components.SelectedFileCard
import app.venkat.offkit.ui.components.ToolScaffold
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.hasUnsavedWork
import app.venkat.offkit.ui.components.rememberImagePicker
import app.venkat.offkit.ui.components.rememberPdfPicker
import app.venkat.offkit.ui.components.rememberPdfSaver

/** Test tag of the screen's root column. */
const val TAG_ADDIMAGES_ROOT = "addimages_root"

/** Test tag of the "Choose PDF" button. */
const val TAG_ADDIMAGES_CHOOSE_PDF = "addimages_choose_pdf"

/**
 * Test tag of the card showing the chosen PDF: its texts are the name and, when known, the size
 * (or just the "no PDF chosen" text).
 */
const val TAG_ADDIMAGES_PDF_NAME = "addimages_pdf_name"

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
    // Inputs are locked while the job runs and while its result is being saved.
    val locked = state is JobUiState.Running || state is JobUiState.Saving

    val pdfPicker = rememberPdfPicker(multiple = false) { uris -> vm.choosePdf(uris) }
    val imagePicker = rememberImagePicker { uris -> vm.addImages(uris) }
    val saver = rememberPdfSaver { uri -> vm.save(uri) }

    ToolScaffold(
        title = stringResource(R.string.tool_add_images_title),
        onBack = onBack,
        confirmLeave = state.hasUnsavedWork(),
    ) { padding ->
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
            SelectedFileCard(
                name = chosen?.name ?: stringResource(R.string.addimages_no_pdf),
                detail = chosen?.sizeBytes?.let(::formatBytes),
                testTag = TAG_ADDIMAGES_PDF_NAME,
            )
            SecondaryActionButton(
                text = stringResource(R.string.addimages_choose_pdf),
                onClick = pdfPicker,
                enabled = !locked,
                testTag = TAG_ADDIMAGES_CHOOSE_PDF,
                icon = AppIcons.pdf,
            )

            SectionHeader(stringResource(R.string.addimages_images_header))
            FileList(
                rows = images.map { item ->
                    FileRow(
                        key = item.key.toString(),
                        title = item.file.name,
                        subtitle = item.file.sizeBytes?.let(::formatBytes),
                    )
                },
                onMoveUp = if (locked) null else vm::moveUp,
                onMoveDown = if (locked) null else vm::moveDown,
                onRemove = if (locked) null else vm::remove,
                emptyText = stringResource(R.string.addimages_empty),
                leadingIcon = AppIcons.image,
            )
            SecondaryActionButton(
                text = stringResource(R.string.addimages_add_images),
                onClick = imagePicker,
                enabled = !locked,
                testTag = TAG_ADDIMAGES_ADD_IMAGES,
                icon = AppIcons.add,
            )
            InfoNote(text = stringResource(R.string.addimages_note), testTag = TAG_ADDIMAGES_NOTE)
            JobPanel(
                state = state,
                runLabel = stringResource(R.string.addimages_run),
                runEnabled = pdf != null && images.isNotEmpty(),
                onRun = vm::run,
                onCancel = vm::cancel,
                onSave = { saver(vm.suggestedFileName()) },
                onReset = vm::reset,
                runIcon = AppIcons.addToPdf,
                modifier = Modifier.padding(top = 8.dp),
            )
            // Shown in any state (e.g. "result no longer available" arrives in Idle); the
            // ViewModel clears it when a run starts, a save succeeds or the inputs change.
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
