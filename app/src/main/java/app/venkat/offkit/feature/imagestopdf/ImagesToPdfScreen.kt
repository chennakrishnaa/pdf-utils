package app.venkat.offkit.feature.imagestopdf

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.venkat.offkit.engine.CompressionLevel
import app.venkat.offkit.engine.PageSize
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.OffkitBottomSummary
import app.venkat.offkit.ui.components.OffkitChipRow
import app.venkat.offkit.ui.components.OffkitInfoNote
import app.venkat.offkit.ui.components.OffkitJobFlow
import app.venkat.offkit.ui.components.OffkitNavIcon
import app.venkat.offkit.ui.components.OffkitOption
import app.venkat.offkit.ui.components.OffkitPrimaryButton
import app.venkat.offkit.ui.components.OffkitRadioGroup
import app.venkat.offkit.ui.components.OffkitScaffold
import app.venkat.offkit.ui.components.OffkitSectionHeader
import app.venkat.offkit.ui.components.OffkitThumbnailRow
import app.venkat.offkit.ui.components.TAG_JOB_RUN
import app.venkat.offkit.ui.components.formatBytes
import app.venkat.offkit.ui.components.rememberImagePicker
import app.venkat.offkit.ui.theme.OffkitTheme
import app.venkat.offkit.ui.theme.OffkitTool

/** Test tag of the options screen's root. */
const val TAG_IMAGESTOPDF_ROOT = "imagestopdf_root"

/** Test tag of the "Add" button in the images section header (opens the image picker). */
const val TAG_IMAGESTOPDF_ADD = "imagestopdf_add"

/** Test tag of the estimate note below the quality choice. */
const val TAG_IMAGESTOPDF_QUALITY_NOTE = "imagestopdf_quality_note"

/** Test tag of the page-size chip for [size]. */
fun imagesToPdfSizeTag(size: PageSize): String = when (size) {
    PageSize.FIT_IMAGE -> "imagestopdf_size_fit"
    PageSize.A4 -> "imagestopdf_size_a4"
    PageSize.LETTER -> "imagestopdf_size_letter"
}

/** Test tag of the image-quality row for [level] (null = original quality). */
fun imagesToPdfQualityTag(level: CompressionLevel?): String = when (level) {
    null -> "imagestopdf_quality_original"
    CompressionLevel.LOW -> "imagestopdf_quality_low"
    CompressionLevel.MEDIUM -> "imagestopdf_quality_medium"
    CompressionLevel.HIGH -> "imagestopdf_quality_high"
}

/** The quality options in display order. */
private val QUALITY_OPTIONS: List<CompressionLevel?> = listOf(null) + CompressionLevel.entries

/** The page sizes in display order. */
private val PAGE_SIZES: List<PageSize> = PageSize.entries

@StringRes
private fun qualityLabel(level: CompressionLevel?): Int = when (level) {
    null -> R.string.quality_original
    CompressionLevel.LOW -> R.string.quality_low
    CompressionLevel.MEDIUM -> R.string.quality_medium
    CompressionLevel.HIGH -> R.string.quality_high
}

@StringRes
private fun qualityDescription(level: CompressionLevel?): Int = when (level) {
    null -> R.string.quality_original_desc
    CompressionLevel.LOW -> R.string.quality_low_desc
    CompressionLevel.MEDIUM -> R.string.quality_medium_desc
    CompressionLevel.HIGH -> R.string.quality_high_desc
}

@StringRes
private fun sizeLabel(size: PageSize): Int = when (size) {
    PageSize.FIT_IMAGE -> R.string.page_size_fit
    PageSize.A4 -> R.string.page_size_a4
    PageSize.LETTER -> R.string.page_size_letter
}

/** "≈ size" for [level], or null while the estimate is unknown (no images, still estimating, or failed). */
@Composable
private fun estimateText(estimates: QualityEstimates, level: CompressionLevel?): String? =
    estimates.total(level)?.let { stringResource(R.string.size_estimate, formatBytes(it)) }

/** "Images to PDF" tool: options, then the shared Processing / Result / Error screens. */
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
    val shareRequest by vm.shareRequest.collectAsState()
    val pickImages = rememberImagePicker { uris -> vm.add(uris) }

    // The selected tile is screen state. Adding images clears it (wherever the add came from).
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var lastCount by rememberSaveable { mutableIntStateOf(images.size) }
    LaunchedEffect(images.size) {
        if (images.size > lastCount) selected = null
        lastCount = images.size
        if (selected?.let { it >= images.size } == true) selected = null
    }

    OffkitJobFlow(
        tool = OffkitTool.IMAGES,
        title = stringResource(R.string.screen_images),
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
        ImagesToPdfOptions(
            imageCount = images.size,
            selectedIndex = selected,
            pageSize = pageSize,
            quality = quality,
            estimates = estimates,
            onBack = onBack,
            onAdd = pickImages,
            onSelect = { selected = it },
            onMoveEarlier = { index ->
                vm.moveUp(index)
                selected = index - 1
            },
            onMoveLater = { index ->
                vm.moveDown(index)
                selected = index + 1
            },
            onRemove = { index ->
                vm.remove(index)
                selected = null
            },
            onPageSize = vm::setPageSize,
            onQuality = vm::setQuality,
            onRun = vm::run,
        )
    }
}

/** The options screen (the job is `Idle`): the images, page size, quality and the Create PDF button. */
@Composable
private fun ImagesToPdfOptions(
    imageCount: Int,
    selectedIndex: Int?,
    pageSize: PageSize,
    quality: CompressionLevel?,
    estimates: QualityEstimates,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (Int?) -> Unit,
    onMoveEarlier: (Int) -> Unit,
    onMoveLater: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onPageSize: (PageSize) -> Unit,
    onQuality: (CompressionLevel?) -> Unit,
    onRun: () -> Unit,
) {
    val hasImages = imageCount > 0
    OffkitScaffold(
        title = stringResource(R.string.screen_images),
        navIcon = OffkitNavIcon.Back,
        onNavigate = onBack,
        modifier = Modifier.testTag(TAG_IMAGESTOPDF_ROOT),
        bottomBar = {
            OffkitBottomSummary(
                title = pluralStringResource(R.plurals.pages_count, imageCount, imageCount),
                subtitle = estimateText(estimates, quality),
            ) { modifier ->
                OffkitPrimaryButton(
                    text = stringResource(if (hasImages) R.string.action_create_pdf else R.string.add_images_first),
                    onClick = onRun,
                    modifier = modifier,
                    enabled = hasImages,
                    icon = painterResource(OffkitTool.IMAGES.icon),
                    testTag = TAG_JOB_RUN,
                )
            }
        },
    ) {
        OffkitSectionHeader(
            text = stringResource(R.string.imagestopdf_section_count, imageCount),
            first = true,
            actionLabel = stringResource(R.string.add),
            onAction = onAdd,
            actionIcon = AppIcons.add,
            actionTestTag = TAG_IMAGESTOPDF_ADD,
        )
        // Shown with or without images (design 07c), so the header is never followed by a gap.
        Text(
            text = stringResource(R.string.images_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        )
        if (hasImages) {
            OffkitThumbnailRow(
                count = imageCount,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                onMoveEarlier = onMoveEarlier,
                onMoveLater = onMoveLater,
                onRemove = onRemove,
            ) { _ -> PlaceholderTile() }
        }

        OffkitSectionHeader(stringResource(R.string.section_page_size))
        OffkitChipRow(
            options = PAGE_SIZES.map { OffkitOption(label = stringResource(sizeLabel(it)), testTag = imagesToPdfSizeTag(it)) },
            selectedIndex = PAGE_SIZES.indexOf(pageSize),
            onSelect = { onPageSize(PAGE_SIZES[it]) },
        )

        OffkitSectionHeader(stringResource(R.string.section_image_quality))
        OffkitRadioGroup(
            options = QUALITY_OPTIONS.map {
                OffkitOption(
                    label = stringResource(qualityLabel(it)),
                    description = stringResource(qualityDescription(it)),
                    trailing = estimateText(estimates, it),
                    testTag = imagesToPdfQualityTag(it),
                )
            },
            selectedIndex = QUALITY_OPTIONS.indexOf(quality),
            onSelect = { onQuality(QUALITY_OPTIONS[it]) },
        )
        OffkitInfoNote(
            text = stringResource(R.string.estimate_note),
            modifier = Modifier.padding(top = 16.dp),
            testTag = TAG_IMAGESTOPDF_QUALITY_NOTE,
        )
    }
}

/**
 * Content of a thumbnail tile: the tool's container colour with the images glyph centred. The
 * picked image itself is never opened in the UI process (only the isolated engine reads it).
 */
@Composable
private fun PlaceholderTile() {
    Box(Modifier.fillMaxSize().background(OffkitTool.IMAGES.container), contentAlignment = Alignment.Center) {
        Icon(painterResource(OffkitTool.IMAGES.icon), contentDescription = null, tint = OffkitTool.IMAGES.onContainer)
    }
}

private val previewEstimates = QualityEstimates(
    imageCount = 2,
    original = 417_000,
    byLevel = mapOf(CompressionLevel.LOW to 165_400, CompressionLevel.MEDIUM to 121_000, CompressionLevel.HIGH to 88_900),
)

@Composable
private fun OptionsPreview(imageCount: Int, selectedIndex: Int?, estimates: QualityEstimates) {
    OffkitTheme {
        ImagesToPdfOptions(
            imageCount = imageCount,
            selectedIndex = selectedIndex,
            pageSize = PageSize.FIT_IMAGE,
            quality = null,
            estimates = estimates,
            onBack = {},
            onAdd = {},
            onSelect = {},
            onMoveEarlier = {},
            onMoveLater = {},
            onRemove = {},
            onPageSize = {},
            onQuality = {},
            onRun = {},
        )
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ImagesToPdfEmptyPreview() {
    OptionsPreview(imageCount = 0, selectedIndex = null, estimates = QualityEstimates())
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ImagesToPdfTwoImagesPreview() {
    OptionsPreview(imageCount = 2, selectedIndex = null, estimates = previewEstimates)
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ImagesToPdfSelectedPreview() {
    OptionsPreview(imageCount = 2, selectedIndex = 1, estimates = previewEstimates)
}
