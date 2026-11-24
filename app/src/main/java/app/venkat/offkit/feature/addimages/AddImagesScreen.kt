package app.venkat.offkit.feature.addimages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import app.venkat.offkit.core.PickedFile
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.OffkitFileCard
import app.venkat.offkit.ui.components.OffkitFileSlot
import app.venkat.offkit.ui.components.OffkitInfoNote
import app.venkat.offkit.ui.components.OffkitJobFlow
import app.venkat.offkit.ui.components.OffkitNavIcon
import app.venkat.offkit.ui.components.OffkitPrimaryButton
import app.venkat.offkit.ui.components.OffkitScaffold
import app.venkat.offkit.ui.components.OffkitSectionHeader
import app.venkat.offkit.ui.components.OffkitThumbnailRow
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.rememberImagePicker
import app.venkat.offkit.ui.components.rememberPdfPicker
import app.venkat.offkit.ui.theme.OffkitDimens
import app.venkat.offkit.ui.theme.OffkitTheme
import app.venkat.offkit.ui.theme.OffkitTool

/** Test tag of the options screen (its scaffold root). */
const val TAG_ADDIMAGES_ROOT = "addimages_root"

/** Test tag of the empty PDF slot and of the PDF card's Change button (both open the PDF picker). */
const val TAG_ADDIMAGES_CHOOSE_PDF = "addimages_choose_pdf"

/** Test tag of the card showing the chosen PDF: its texts are the name and, when known, the size. */
const val TAG_ADDIMAGES_PDF_NAME = "addimages_pdf_name"

/** Test tag of the "Add" button of the images header (opens the image picker). */
const val TAG_ADDIMAGES_ADD_IMAGES = "addimages_add_images"

/** Test tag of the note about how the new pages are sized. */
const val TAG_ADDIMAGES_NOTE = "addimages_note"

/**
 * "Add images to PDF" (SPEC §9). The options screen shows the PDF and the images in page order;
 * the shared Processing, Result and Error screens of [OffkitJobFlow] cover the job.
 *
 * The picked images are never decoded here (only the isolated engine process decodes them): each
 * tile is a placeholder in the tool's colours.
 */
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
    val shareRequest by vm.shareRequest.collectAsState()
    // Screen state: the selected image, by index. Cleared on add and remove; follows a moved image.
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val pdfPicker = rememberPdfPicker(multiple = false) { uris -> vm.choosePdf(uris) }
    val imagePicker = rememberImagePicker { uris ->
        if (uris.isNotEmpty()) selected = null
        vm.addImages(uris)
    }
    // Moves the image at [index] and keeps the same image selected, wherever it ends up (the
    // ViewModel may ignore the move).
    val moveSelected = { index: Int, move: (Int) -> Unit ->
        val key = vm.images.value.getOrNull(index)?.key
        move(index)
        selected = vm.images.value.indexOfFirst { it.key == key }.takeIf { it >= 0 }
    }

    OffkitJobFlow(
        tool = OffkitTool.ADD_PAGES,
        title = stringResource(R.string.screen_add_pages),
        state = state,
        inputName = pdf?.name,
        inputBytes = pdf?.sizeBytes,
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
    ) {
        AddImagesOptions(
            pdf = pdf,
            imageKeys = images.map { it.key },
            selectedIndex = selected?.takeIf { it in images.indices },
            onSelect = { selected = it },
            onMoveEarlier = { index -> moveSelected(index, vm::moveUp) },
            onMoveLater = { index -> moveSelected(index, vm::moveDown) },
            onRemove = { index ->
                vm.remove(index)
                selected = null
            },
            onChoosePdf = pdfPicker,
            onAddImages = imagePicker,
            onRun = vm::run,
            onBack = onBack,
        )
    }
}

/**
 * The stateless options screen: the PDF, the image strip in page order, the note and the Add bar.
 * [imageKeys] are the images' stable keys, in order (only their count and identity are shown).
 */
@Composable
private fun AddImagesOptions(
    pdf: PickedFile?,
    imageKeys: List<Long>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onMoveEarlier: (Int) -> Unit,
    onMoveLater: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onChoosePdf: () -> Unit,
    onAddImages: () -> Unit,
    onRun: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val count = imageKeys.size
    val runLabel = when {
        pdf == null -> stringResource(R.string.choose_pdf_first)
        count == 0 -> stringResource(R.string.add_images_first)
        else -> pluralStringResource(R.plurals.add_n_pages, count, count)
    }
    OffkitScaffold(
        title = stringResource(R.string.screen_add_pages),
        navIcon = OffkitNavIcon.Back,
        onNavigate = onBack,
        modifier = Modifier.testTag(TAG_ADDIMAGES_ROOT),
        bottomBar = {
            OffkitPrimaryButton(
                text = runLabel,
                onClick = onRun,
                enabled = pdf != null && count > 0,
                icon = painterResource(OffkitTool.ADD_PAGES.icon),
                modifier = Modifier.fillMaxWidth(),
                testTag = TAG_JOB_RUN,
            )
        },
    ) {
        OffkitSectionHeader(stringResource(R.string.section_pdf), first = true)
        if (pdf == null) {
            OffkitFileSlot(
                title = stringResource(R.string.choose_pdf),
                hint = stringResource(R.string.choose_pdf_hint),
                onClick = onChoosePdf,
                testTag = TAG_ADDIMAGES_CHOOSE_PDF,
            )
        } else {
            OffkitFileCard(
                name = pdf.name,
                detail = pdf.sizeBytes?.let(::formatBytes),
                actionLabel = stringResource(R.string.change),
                onAction = onChoosePdf,
                actionTestTag = TAG_ADDIMAGES_CHOOSE_PDF,
                testTag = TAG_ADDIMAGES_PDF_NAME,
            )
        }

        OffkitSectionHeader(
            text = stringResource(R.string.addimages_section_count, count),
            actionLabel = stringResource(R.string.add),
            onAction = onAddImages,
            actionIcon = AppIcons.add,
            actionTestTag = TAG_ADDIMAGES_ADD_IMAGES,
        )
        // Shown with or without images (design 07c), so the header is never followed by a gap.
        Text(
            text = stringResource(R.string.add_pages_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        )
        if (count > 0) {
            OffkitThumbnailRow(
                count = count,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                onMoveEarlier = onMoveEarlier,
                onMoveLater = onMoveLater,
                onRemove = onRemove,
                key = { imageKeys[it] },
            ) {
                ImagePlaceholder()
            }
        }
        OffkitInfoNote(
            text = stringResource(R.string.add_pages_note),
            modifier = Modifier.padding(top = OffkitDimens.screenPadding),
            testTag = TAG_ADDIMAGES_NOTE,
        )
    }
}

/** A tile's picture: the tool's container colour with the image glyph. The image itself is never decoded here. */
@Composable
private fun ImagePlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().background(OffkitTool.ADD_PAGES.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_tool_images),
            contentDescription = null,
            tint = OffkitTool.ADD_PAGES.onContainer,
            modifier = Modifier.size(OffkitDimens.iconSize),
        )
    }
}

private val previewPdf = PickedFile(android.net.Uri.EMPTY, "merged-compressed.pdf", 868_966)

@Composable
private fun AddImagesOptionsPreview(pdf: PickedFile?, imageCount: Int, selectedIndex: Int?) {
    OffkitTheme {
        AddImagesOptions(pdf, List(imageCount) { it.toLong() }, selectedIndex, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun AddImagesEmptyPreview() = AddImagesOptionsPreview(null, 0, null)

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun AddImagesTwoImagesPreview() = AddImagesOptionsPreview(previewPdf, 2, null)

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun AddImagesSelectedPreview() = AddImagesOptionsPreview(previewPdf, 2, 1)
