package app.venkat.offkit.feature.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.venkat.offkit.R
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.MIME_PDF
import app.venkat.offkit.ui.components.OffkitJobErrorScreen
import app.venkat.offkit.ui.components.OffkitTonalButton
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.components.launchOrExplain
import app.venkat.offkit.ui.components.mirrorInRtl
import app.venkat.offkit.ui.reader.ZoomableList
import app.venkat.offkit.ui.reader.ZoomableListState
import app.venkat.offkit.ui.reader.rememberZoomableListState
import app.venkat.offkit.ui.theme.OffkitDimens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.roundToInt

/** Test tag of the reader's page list (the [ZoomableList]). */
const val TAG_READER_LIST = "reader_list"

/** Test tag of the "Opening PDF…" progress shown while a picked file is copied and read. */
const val TAG_READER_OPENING = "reader_opening"

/** Test tag of the page indicator ("3 / 12") in the top bar. */
const val TAG_READER_INDICATOR = "reader_indicator"

/** Test tag of the file name in the top bar (the screen title before a document is open). */
const val TAG_READER_TITLE = "reader_title"

/** Test tag of the "Open another PDF" top-bar action. */
const val TAG_READER_OPEN_ANOTHER = "reader_open_another"

/** Test tag of page [index] (0-based) of the open document. */
fun readerPageTag(index: Int): String = "reader_page_$index"

/** Test tag of the Retry action of page [index] after its render failed. */
fun readerPageRetryTag(index: Int): String = "reader_page_${index}_retry"

/** Semantics flag of a page item: true once its whole-page bitmap is drawn. */
val ReaderPageRendered = SemanticsPropertyKey<Boolean>("ReaderPageRendered")
private var SemanticsPropertyReceiver.readerPageRendered by ReaderPageRendered

/** Semantics flag of a page item: true while a sharp zoomed-in detail is drawn over the page. */
val ReaderPageDetailShown = SemanticsPropertyKey<Boolean>("ReaderPageDetailShown")
private var SemanticsPropertyReceiver.readerPageDetailShown by ReaderPageDetailShown

/** Colours of the reader's Home badge (`ic_tool_read_badge`). */
private val ReadContainer = Color(0xFFD5ECEA)
private val OnReadContainer = Color(0xFF175651)

/** Gap between pages, and around the list. */
private val PageGap = 8.dp

/**
 * The PDF reader: opens the system picker for a PDF as soon as it is entered (once per entry, not
 * again on rotation), then shows the document as a vertical list of pages that zooms as one
 * surface ([ZoomableList], 1x to 5x).
 *
 * [onBack] leaves the reader (top-bar Back). [onExit] leaves it without a user tap, so it must not
 * be gated on the RESUMED state (a picker result arrives before the activity resumes): it is
 * called when no document is open and no picker is pending, i.e. the first picker was cancelled
 * (or could not be launched), or the document was lost with the process (its read grant is gone).
 * The view model deletes its copy of the file when the reader leaves the back stack.
 */
@Composable
fun ReaderScreen(onBack: () -> Unit, onExit: () -> Unit) {
    // Explicit initializer: the default factory caches the first Application process-wide.
    val vm: ReaderViewModel = viewModel {
        ReaderViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]))
    }
    val doc by vm.document.collectAsState()

    // The automatic picker ran (saved, so neither rotation nor process death launches it again).
    var launched by rememberSaveable { mutableStateOf(false) }
    // A picker is out; its result may arrive after rotation or process death.
    var pickerPending by rememberSaveable { mutableStateOf(false) }
    // Bumped with every vm.open, so the page list restarts even for an equal Open (same file again).
    var openCount by rememberSaveable { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pickerPending = false
        if (uri != null) {
            openCount++
            vm.open(uri)
        }
    }
    val context = LocalContext.current
    val pick: () -> Unit = {
        pickerPending = true
        if (!launchOrExplain(context) { launcher.launch(arrayOf(MIME_PDF)) }) pickerPending = false
    }
    val pickNow by rememberUpdatedState(pick)
    LaunchedEffect(Unit) {
        if (!launched) {
            launched = true
            pickNow()
        }
    }
    val exit by rememberUpdatedState(onExit)
    val nothingToShow = doc is ReaderDocState.Idle && launched && !pickerPending
    LaunchedEffect(nothingToShow) {
        if (nothingToShow) exit()
    }

    when (val d = doc) {
        is ReaderDocState.Failed -> OffkitJobErrorScreen(
            title = stringResource(R.string.tool_read_title),
            error = d.error,
            fileName = null,
            fileBytes = null,
            actionLabel = stringResource(R.string.action_choose_another),
            onAction = pick,
            onBack = onBack,
        )
        is ReaderDocState.Open -> key(openCount) {
            ReaderDocument(vm, d, onBack = onBack, onOpenAnother = pick)
        }
        ReaderDocState.Opening, ReaderDocState.Idle -> ReaderFrame(
            title = stringResource(R.string.tool_read_title),
            indicator = null,
            onBack = onBack,
            onOpenAnother = null,
        ) {
            if (d == ReaderDocState.Opening) Opening()
        }
    }
}

/** Top bar over [body], which fills the rest of the screen on the reader's neutral background. */
@Composable
private fun ReaderFrame(
    title: String,
    indicator: Pair<Int, Int>?,
    onBack: () -> Unit,
    onOpenAnother: (() -> Unit)?,
    body: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceDim)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            ReaderTopBar(title, indicator, onBack, onOpenAnother)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { body() }
        }
    }
}

/** Back, the file name (one line), the page indicator and "Open another PDF" (SPEC §0 top bar). */
@Composable
private fun ReaderTopBar(title: String, indicator: Pair<Int, Int>?, onBack: () -> Unit, onOpenAnother: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(TAG_ACTION_BACK)) {
            Icon(AppIcons.back, stringResource(R.string.action_back), modifier = Modifier.mirrorInRtl())
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() }.testTag(TAG_READER_TITLE),
        )
        if (indicator != null) {
            val (page, count) = indicator
            val spoken = stringResource(R.string.reader_page_cd, page, count)
            Text(
                text = stringResource(R.string.reader_page_indicator, page, count),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { contentDescription = spoken }
                    .testTag(TAG_READER_INDICATOR),
            )
        }
        if (onOpenAnother != null) {
            IconButton(onClick = onOpenAnother, modifier = Modifier.testTag(TAG_READER_OPEN_ANOTHER)) {
                Icon(painterResource(R.drawable.ic_folder_open), stringResource(R.string.reader_open_another))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun Opening() {
    Column(
        modifier = Modifier.fillMaxSize().testTag(TAG_READER_OPENING),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The Processing screen's look: the tool's icon in a tile of its colour, then the progress.
        Box(
            modifier = Modifier.size(72.dp).background(ReadContainer, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_tool_read), contentDescription = null, tint = OnReadContainer, modifier = Modifier.size(32.dp))
        }
        CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        Text(
            text = stringResource(R.string.reader_opening),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/** The open document: frame, page list, viewport reports. One instance per opened document. */
@Composable
private fun ReaderDocument(vm: ReaderViewModel, doc: ReaderDocState.Open, onBack: () -> Unit, onOpenAnother: () -> Unit) {
    val listState = rememberLazyListState()
    val zoomState = rememberZoomableListState(minScale = 1f, maxScale = 5f)
    val pageCount = doc.pages.size
    var current by remember { mutableIntStateOf(0) }
    ReaderFrame(
        title = doc.name,
        indicator = (current + 1) to pageCount,
        onBack = onBack,
        onOpenAnother = onOpenAnother,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val gapPx = with(density) { PageGap.roundToPx() }
            val itemWidthPx = (constraints.maxWidth - 2 * gapPx).coerceAtLeast(1)
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val geometry = remember(doc, itemWidthPx, gapPx) { PageGeometry(doc.pages, itemWidthPx, gapPx) }

            val visible by remember(geometry, listState, zoomState) {
                derivedStateOf { geometry.visible(listState.layoutInfo, zoomState) }
            }
            LaunchedEffect(visible) {
                ReaderPlanner.currentPage(visible)?.let { current = it }
            }
            LaunchedEffect(vm, geometry, listState, zoomState) {
                snapshotFlow {
                    if (zoomState.isZooming || listState.isScrollInProgress) {
                        null
                    } else {
                        ReaderViewport(geometry.itemWidthPx, zoomState.scale, geometry.visible(listState.layoutInfo, zoomState))
                    }
                }.filterNotNull().distinctUntilChanged().collect { vp ->
                    vm.onViewport(vp.itemWidthPx, vp.zoom, vp.visible)
                }
            }

            ZoomableList(
                state = zoomState,
                listState = listState,
                modifier = Modifier.fillMaxSize().testTag(TAG_READER_LIST),
                contentPadding = PaddingValues(start = PageGap, end = PageGap, top = PageGap, bottom = bottomInset),
            ) {
                items(pageCount, key = { it }) { index ->
                    val flow = remember(doc, index) { vm.page(index) }
                    ReaderPage(
                        index = index,
                        pageCount = pageCount,
                        state = flow.collectAsState().value,
                        widthPx = itemWidthPx,
                        heightPx = geometry.pageHeights[index],
                        gapPx = gapPx,
                        onRetry = { vm.retry(index) },
                    )
                }
            }
        }
    }
}

/** Item geometry of a document laid out [itemWidthPx] wide, with [gapPx] below each page and at each side. */
private class PageGeometry(pages: List<PageDims>, val itemWidthPx: Int, private val gapPx: Int) {
    val pageHeights: IntArray = IntArray(pages.size) { ReaderPlanner.itemHeightPx(pages[it], itemWidthPx) }

    /**
     * The on-screen part of each laid-out page, in unscaled item pixels. The list's layout is in
     * unscaled content pixels and exactly as tall as the zoomed viewport ([ZoomableList]); its
     * horizontal visible range is `[offsetX / scale, (offsetX + W) / scale]`, and items start
     * [gapPx] from its left edge.
     */
    fun visible(info: LazyListLayoutInfo, zoom: ZoomableListState): List<VisiblePage> {
        val scale = zoom.scale
        val width = info.viewportSize.width
        val left = (zoom.offsetX / scale - gapPx).coerceIn(0f, itemWidthPx.toFloat())
        val right = ((zoom.offsetX + width) / scale - gapPx).coerceIn(0f, itemWidthPx.toFloat())
        return info.visibleItemsInfo.mapNotNull { item ->
            val index = item.index
            if (index !in pageHeights.indices) return@mapNotNull null
            val h = pageHeights[index].toFloat()
            val top = (info.viewportStartOffset - item.offset).toFloat().coerceIn(0f, h)
            val bottom = (info.viewportEndOffset - item.offset).toFloat().coerceIn(0f, h)
            val rect = ItemRect(left, top, right, bottom)
            if (rect.isEmpty) null else VisiblePage(index, rect)
        }
    }
}

/**
 * One page: exactly [widthPx] x [heightPx] pixels (the planner's item size) followed by a [gapPx]
 * gap. Draws the base bitmap stretched to the page, then the detail over its rectangle; a white
 * page with a spinner while loading; a Retry action when the render failed.
 */
@Composable
private fun ReaderPage(
    index: Int,
    pageCount: Int,
    state: ReaderPageState,
    widthPx: Int,
    heightPx: Int,
    gapPx: Int,
    onRetry: () -> Unit,
) {
    val description = stringResource(R.string.reader_page_cd, index + 1, pageCount)
    val base = state.base
    val detail = state.detail
    val baseImage = remember(base) { base?.asImageBitmap() }
    val detailImage = remember(detail) { detail?.bitmap?.asImageBitmap() }
    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(widthPx, heightPx))
                layout(widthPx, heightPx + gapPx) { placeable.place(0, 0) }
            }
            .background(Color.White)
            .semantics {
                contentDescription = description
                readerPageRendered = base != null
                readerPageDetailShown = detail != null
            }
            .testTag(readerPageTag(index)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            if (baseImage != null) {
                drawImage(
                    baseImage,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            if (detail != null && detailImage != null && detail.itemWidthPx > 0) {
                val k = size.width / detail.itemWidthPx
                val l = (detail.rect.left * k).roundToInt()
                val t = (detail.rect.top * k).roundToInt()
                val r = (detail.rect.right * k).roundToInt()
                val b = (detail.rect.bottom * k).roundToInt()
                if (r > l && b > t) {
                    drawImage(
                        detailImage,
                        dstOffset = IntOffset(l, t),
                        dstSize = IntSize(r - l, b - t),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
        when {
            state.error != null && base == null -> PageError(index, onRetry, Modifier.align(Alignment.Center))
            state.error != null -> RetryIcon(index, onRetry, Modifier.align(Alignment.TopEnd).padding(4.dp))
            state.isLoading -> CircularProgressIndicator(
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
        }
    }
}

/** A failed page with nothing to show: a short message and a Retry button. */
@Composable
private fun PageError(index: Int, onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(AppIcons.warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(R.string.reader_page_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        OffkitTonalButton(
            text = stringResource(R.string.reader_retry),
            onClick = onRetry,
            icon = painterResource(R.drawable.ic_refresh),
            testTag = readerPageRetryTag(index),
        )
    }
}

/** A failed re-render of a page that is still shown: a small Retry button in its corner. */
@Composable
private fun RetryIcon(index: Int, onRetry: () -> Unit, modifier: Modifier) {
    IconButton(
        onClick = onRetry,
        modifier = modifier
            .size(OffkitDimens.touchTarget)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f), CircleShape)
            .testTag(readerPageRetryTag(index)),
    ) {
        Icon(
            painterResource(R.drawable.ic_refresh),
            contentDescription = stringResource(R.string.reader_retry_page_cd, index + 1),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
