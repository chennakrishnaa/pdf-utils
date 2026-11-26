package app.venkat.offkit.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.customActions
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
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.ui.components.AppIcons
import app.venkat.offkit.ui.components.MIME_PDF
import app.venkat.offkit.ui.components.OffkitJobErrorScreen
import app.venkat.offkit.ui.components.OffkitTonalButton
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.components.launchOrExplain
import app.venkat.offkit.ui.components.mirrorInRtl
import app.venkat.offkit.ui.nav.ViewRequest
import app.venkat.offkit.ui.components.pdfErrorTitle
import app.venkat.offkit.ui.reader.ZoomableList
import app.venkat.offkit.ui.reader.ZoomableListState
import app.venkat.offkit.ui.reader.rememberZoomableListState
import app.venkat.offkit.ui.theme.OffkitDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

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

/** Test tags of the selection bar (the top bar while text is selected) and its actions. */
const val TAG_READER_SELECTION_BAR = "reader_selection_bar"
const val TAG_READER_COPY = "reader_copy"
const val TAG_READER_SELECT_ALL = "reader_select_all"
const val TAG_READER_SELECTION_CLOSE = "reader_selection_close"

/** Test tags of the two selection handles (the anchor's and the focus's, see [ReaderSelection]). */
const val TAG_READER_HANDLE_ANCHOR = "reader_handle_anchor"
const val TAG_READER_HANDLE_FOCUS = "reader_handle_focus"

/** Test tag of the reader's transient messages. */
const val TAG_READER_SNACKBAR = "reader_snackbar"

/** Test tag of page [index] (0-based) of the open document. */
fun readerPageTag(index: Int): String = "reader_page_$index"

/** Test tag of the Retry action of page [index] after its render failed. */
fun readerPageRetryTag(index: Int): String = "reader_page_${index}_retry"

/** Test tag of the reason shown under a failed page's message. */
fun readerPageReasonTag(index: Int): String = "reader_page_${index}_reason"

/** Semantics flag of a page item: true once its whole-page bitmap is drawn. */
val ReaderPageRendered = SemanticsPropertyKey<Boolean>("ReaderPageRendered")
private var SemanticsPropertyReceiver.readerPageRendered by ReaderPageRendered

/** Semantics flag of a page item: true while a sharp zoomed-in detail is drawn over the page. */
val ReaderPageDetailShown = SemanticsPropertyKey<Boolean>("ReaderPageDetailShown")
private var SemanticsPropertyReceiver.readerPageDetailShown by ReaderPageDetailShown

/** Colours of the reader's Home badge (`ic_tool_read_badge`). */
private val ReadContainer = Color(0xFFD5ECEA)
private val OnReadContainer = Color(0xFF175651)

/** How long the viewport must stay unchanged before it is reported to the view model (after the first). */
internal const val VIEWPORT_SETTLE_MS = 120L

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
 *
 * [viewRequest] ("Open with" from another app) is opened instead of launching the picker, and
 * replaces any document already open, exactly as one picked with "Open another PDF";
 * [onViewRequestHandled] is called as soon as it is being opened.
 */
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onExit: () -> Unit,
    viewRequest: ViewRequest? = null,
    onViewRequestHandled: (ViewRequest) -> Unit = {},
) {
    // Explicit initializer: the default factory caches the first Application process-wide.
    val vm: ReaderViewModel = viewModel {
        ReaderViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]), createSavedStateHandle())
    }
    val doc by vm.document.collectAsState()

    // The automatic picker ran (saved, so neither rotation nor process death launches it again).
    // Both flags are also kept in the view model's SavedStateHandle: after process death the
    // destination's rememberSaveable values were found not to come back (H3), which popped a
    // picker nobody asked for instead of going back home.
    var launched by rememberSaveable { mutableStateOf(vm.pickerLaunched) }
    // A picker is out; its result may arrive after rotation or process death.
    var pickerPending by rememberSaveable { mutableStateOf(vm.pickerPending) }
    SideEffect {
        vm.pickerLaunched = launched
        vm.pickerPending = pickerPending
    }
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
    val handled by rememberUpdatedState(onViewRequestHandled)
    LaunchedEffect(viewRequest) {
        if (viewRequest != null) {
            // Opened like a picked file; the Uri is not saved (its grant does not outlive the activity).
            launched = true
            openCount++
            vm.open(viewRequest.uri)
            handled(viewRequest)
        } else if (!launched) {
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
    bar: (@Composable () -> Unit)? = null,
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
            if (bar != null) bar() else ReaderTopBar(title, indicator, onBack, onOpenAnother)
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
    val selection by vm.selection.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Back clears a selection first, then leaves as before.
    BackHandler(enabled = selection != null) { vm.clearSelection() }
    LaunchedEffect(vm) {
        vm.textEvents.collect { event ->
            val message = when (event) {
                is ReaderTextEvent.Copy -> {
                    copyToClipboard(context, event.text)
                    when (SelectionMath.copyNotice(Build.VERSION.SDK_INT, event.cut)) {
                        CopyNotice.NONE -> null
                        CopyNotice.COPIED -> context.getString(R.string.reader_copied)
                        CopyNotice.COPIED_CUT -> context.getString(R.string.reader_copied_cut)
                        CopyNotice.CUT -> context.getString(R.string.reader_text_cut)
                    }
                }
                ReaderTextEvent.NoText -> context.getString(R.string.reader_no_text)
                is ReaderTextEvent.Failed -> context.getString(R.string.reader_text_error, context.getString(pdfErrorTitle(event.error)))
            }
            if (message != null) {
                snackbar.currentSnackbarData?.dismiss()
                launch { snackbar.showSnackbar(message) }
            }
        }
    }
    ReaderFrame(
        title = doc.name,
        indicator = (current + 1) to pageCount,
        onBack = onBack,
        onOpenAnother = onOpenAnother,
        bar = if (selection != null) {
            { SelectionBar(onClose = vm::clearSelection, onSelectAll = vm::selectAll, onCopy = vm::copySelection) }
        } else {
            null
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val gapPx = with(density) { PageGap.roundToPx() }
            val itemWidthPx = (constraints.maxWidth - 2 * gapPx).coerceIn(1, ReaderPlanner.MAX_ITEM_WIDTH_PX)
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val geometry = remember(doc, itemWidthPx, gapPx) { PageGeometry(doc.pages, itemWidthPx, gapPx) }

            // Read outside composition, so scrolling does not recompose the list scope every frame.
            LaunchedEffect(geometry, listState, zoomState) {
                snapshotFlow { ReaderPlanner.currentPage(geometry.visible(listState.layoutInfo, zoomState)) }
                    .filterNotNull()
                    .collect { current = it }
            }
            // The viewport is reported once it has not changed for VIEWPORT_SETTLE_MS (a one-finger
            // pan while zoomed is neither a scroll nor a zoom, and changes it every frame); the first
            // one of a document (or of a new item width) is reported at once. A scroll or zoom in
            // progress (null) cancels a pending report.
            LaunchedEffect(vm, geometry, listState, zoomState) {
                var reported = false
                snapshotFlow {
                    if (zoomState.isZooming || listState.isScrollInProgress) {
                        null
                    } else {
                        ReaderViewport(geometry.itemWidthPx, zoomState.scale, geometry.visible(listState.layoutInfo, zoomState))
                    }
                }.distinctUntilChanged().collectLatest { vp ->
                    if (vp == null) return@collectLatest
                    if (reported) delay(VIEWPORT_SETTLE_MS)
                    reported = true
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
                    val dims = doc.pages[index]
                    val pageRect = geometry.pageRects[index]
                    ReaderPage(
                        index = index,
                        pageCount = pageCount,
                        state = flow.collectAsState().value,
                        widthPx = itemWidthPx,
                        heightPx = geometry.pageHeights[index],
                        pageRect = pageRect,
                        gapPx = gapPx,
                        onRetry = { vm.retry(index) },
                        text = PageTextUi(
                            dims = dims,
                            zoom = zoomState,
                            selection = selection?.takeIf { it.page == index },
                            onLongPress = { x, y ->
                                val (xPt, yPt) = SelectionMath.itemToPage(x, y, dims, pageRect)
                                val tolerance = SelectionMath.tolerancePt(density.density, zoomState.scale, dims, pageRect)
                                vm.longPress(index, xPt, yPt, tolerance)
                            },
                            onTap = { x, y ->
                                val s = selection
                                if (s != null) {
                                    val inside = s.page == index && s.rects().any {
                                        val r = SelectionMath.pageRectToItem(it, dims, pageRect)
                                        x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
                                    }
                                    if (!inside) vm.clearSelection()
                                }
                            },
                            onDrag = { anchor, x, y ->
                                val (xPt, yPt) = SelectionMath.itemToPage(x, y, dims, pageRect, clamp = true)
                                vm.dragHandle(anchor, xPt, yPt)
                            },
                            onCopyPage = { vm.copyPageText(index) },
                        ),
                    )
                }
            }
            SnackbarHost(
                snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset).testTag(TAG_READER_SNACKBAR),
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
}

/** The top bar while text is selected: Close, then Select all and Copy (a contextual action bar). */
@Composable
private fun SelectionBar(onClose: () -> Unit, onSelectAll: () -> Unit, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selectAllCd = stringResource(R.string.reader_select_all_cd)
    val copyCd = stringResource(R.string.reader_copy_cd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.secondaryContainer)
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 8.dp)
            .testTag(TAG_READER_SELECTION_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag(TAG_READER_SELECTION_CLOSE)) {
            Icon(AppIcons.close, stringResource(R.string.reader_selection_close_cd), tint = colors.onSecondaryContainer)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.reader_selection_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onSelectAll,
            modifier = Modifier.semantics { contentDescription = selectAllCd }.testTag(TAG_READER_SELECT_ALL),
        ) { Text(stringResource(R.string.reader_select_all)) }
        TextButton(
            onClick = onCopy,
            modifier = Modifier.semantics { contentDescription = copyCd }.testTag(TAG_READER_COPY),
        ) { Text(stringResource(R.string.reader_copy)) }
    }
}

/** Everything a page item needs for text selection. [selection] is set only on the selected page. */
private class PageTextUi(
    val dims: PageDims,
    val zoom: ZoomableListState,
    val selection: ReaderSelection?,
    /** Item pixels of a long-press. */
    val onLongPress: (Float, Float) -> Unit,
    /** Item pixels of a single tap. */
    val onTap: (Float, Float) -> Unit,
    /** A handle (the anchor's if true) aims at these item pixels. */
    val onDrag: (Boolean, Float, Float) -> Unit,
    val onCopyPage: () -> Unit,
)

/**
 * Long-press and single tap in item pixels, without delaying anything: no double-tap detection
 * here (the list's own handler zooms on a double tap), and nothing is consumed. A press is
 * dropped when a second finger comes down, when it moves past the touch slop, or when another
 * handler consumed its events (a pan or pinch of the list, a handle drag).
 */
private suspend fun PointerInputScope.detectPressAndTap(onTap: (Offset) -> Unit, onLongPress: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var tapAt: Offset? = null
        var dropped = false
        val finished = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || event.changes.count { it.pressed } > 1 || event.changes.any { it.isConsumed } ||
                    (change.position - down.position).getDistance() > slop
                ) {
                    dropped = true
                    break
                }
                if (!change.pressed) {
                    tapAt = change.position
                    break
                }
            }
            true
        }
        when {
            dropped -> Unit
            finished == null -> onLongPress(down.position)
            else -> tapAt?.let(onTap)
        }
    }
}

/**
 * The two drag handles of [selection], drawn in item pixels but sized for the screen (divided by
 * the zoom), so they keep a 48 dp touch target and stay on the text at every zoom and scroll.
 * Each handle is keyed by its role (anchor or focus), so a drag that crosses the other handle goes
 * on with the same handle, now shown at the other end.
 */
@Composable
private fun SelectionHandles(selection: ReaderSelection, text: PageTextUi, pageRect: ItemRect) {
    val anchorIsStart = selection.anchor <= selection.focus
    key("anchor") { SelectionHandle(selection, text, pageRect, anchor = true, start = anchorIsStart, tag = TAG_READER_HANDLE_ANCHOR) }
    key("focus") { SelectionHandle(selection, text, pageRect, anchor = false, start = !anchorIsStart, tag = TAG_READER_HANDLE_FOCUS) }
}

@Composable
private fun SelectionHandle(selection: ReaderSelection, text: PageTextUi, pageRect: ItemRect, anchor: Boolean, start: Boolean, tag: String) {
    val density = LocalDensity.current.density
    val scale = text.zoom.scale
    val touch = SelectionMath.screenDpToItemPx(SelectionMath.HANDLE_TOUCH_DP, density, scale)
    val knob = SelectionMath.screenDpToItemPx(SelectionMath.HANDLE_KNOB_DP, density, scale)
    val (xPt, yPt) = SelectionMath.handlePoint(selection, start)
    val (cx, cy) = SelectionMath.pageToItem(xPt, yPt, text.dims, pageRect)
    // Half the character's height, in item pixels: a drag aims at the middle of the line, not below it.
    val index = if (anchor) selection.anchor else selection.focus
    val aim = SelectionMath.handleAim(selection, index) / SelectionMath.pointsPerItemPx(text.dims, pageRect)
    val left = cx - touch / 2f
    val origin by rememberUpdatedState(Offset(left, cy))
    val corner by rememberUpdatedState(Offset(cx, cy - aim))
    val onDrag by rememberUpdatedState(text.onDrag)
    val zoom = text.zoom
    DisposableEffect(zoom) { onDispose { zoom.childDragging = false } }
    val color = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .layout { measurable, _ ->
                val size = touch.toInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(size, size))
                layout(0, 0) { placeable.place(left.toInt(), cy.toInt()) }
            }
            .pointerInput(zoom) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    zoom.childDragging = true
                    down.consume()
                    // Keep the finger's offset from the aim point, so touching the handle moves nothing.
                    val grab = corner - (origin + down.position)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val target = origin + change.position + grab
                            onDrag(anchor, target.x, target.y)
                        }
                    } finally {
                        zoom.childDragging = false
                    }
                }
            }
            .testTag(tag),
    ) {
        val r = knob / 2f
        val centre = Offset(size.width / 2f, r + knob * 0.1f)
        drawRect(color, Offset(centre.x - knob * 0.06f, 0f), Size(knob * 0.12f, centre.y))
        drawCircle(color, r, centre)
    }
}


/**
 * Item geometry of a document laid out [itemWidthPx] wide, with [gapPx] below each page and at each
 * side. Item sizes and page rectangles come from [ReaderPlanner] (bounded for any page size).
 */
private class PageGeometry(pages: List<PageDims>, val itemWidthPx: Int, private val gapPx: Int) {
    val pageHeights: IntArray = IntArray(pages.size) { ReaderPlanner.itemHeightPx(pages[it], itemWidthPx) }
    val pageRects: List<ItemRect> = List(pages.size) { ReaderPlanner.pageRect(pages[it], itemWidthPx) }

    /** The on-screen part of each laid-out page, in unscaled item pixels ([ReaderPlanner.visibleRect]). */
    fun visible(info: LazyListLayoutInfo, zoom: ZoomableListState): List<VisiblePage> {
        val scale = zoom.scale
        val offsetX = zoom.offsetX
        val width = info.viewportSize.width
        return info.visibleItemsInfo.mapNotNull { item ->
            val index = item.index
            if (index !in pageHeights.indices) return@mapNotNull null
            ReaderPlanner.visibleRect(
                itemWidthPx, pageHeights[index], gapPx, scale, offsetX, width,
                info.viewportStartOffset, info.viewportEndOffset, item.offset,
            )?.let { VisiblePage(index, it) }
        }
    }
}

/**
 * One page: exactly [widthPx] x [heightPx] pixels (the planner's item size) followed by a [gapPx]
 * gap. The page itself is [pageRect] inside it (the whole width unless the page is very tall, then
 * centred). Draws the base bitmap stretched to [pageRect], then the detail over its rectangle; a
 * white page with a spinner while loading; a Retry action when the render failed.
 */
@Composable
private fun ReaderPage(
    index: Int,
    pageCount: Int,
    state: ReaderPageState,
    widthPx: Int,
    heightPx: Int,
    pageRect: ItemRect,
    gapPx: Int,
    onRetry: () -> Unit,
    text: PageTextUi,
) {
    val copyPageLabel = stringResource(R.string.reader_copy_page_action)
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val selection = text.selection
    val highlights = remember(selection, pageRect, text.dims) {
        selection?.rects()?.map { SelectionMath.pageRectToItem(it, text.dims, pageRect) }.orEmpty()
    }
    val longPress by rememberUpdatedState(text.onLongPress)
    val tap by rememberUpdatedState(text.onTap)
    val copyPage by rememberUpdatedState(text.onCopyPage)
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
            .pointerInput(Unit) {
                detectPressAndTap(onTap = { tap(it.x, it.y) }, onLongPress = { longPress(it.x, it.y) })
            }
            .semantics {
                contentDescription = description
                customActions = listOf(CustomAccessibilityAction(copyPageLabel) { copyPage(); true })
                readerPageRendered = base != null
                readerPageDetailShown = detail != null
            }
            .testTag(readerPageTag(index)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val page = ReaderPlanner.drawRect(pageRect, widthPx, size.width)
            if (page != null) {
                drawRect(Color.White, Offset(page.left.toFloat(), page.top.toFloat()), Size((page.right - page.left).toFloat(), (page.bottom - page.top).toFloat()))
                if (baseImage != null) drawBitmap(baseImage, page)
            }
            if (detail != null && detailImage != null) {
                ReaderPlanner.drawRect(detail.rect, detail.itemWidthPx, size.width)?.let { drawBitmap(detailImage, it) }
            }
            for (r in highlights) drawRect(highlight, Offset(r.left, r.top), Size(r.right - r.left, r.bottom - r.top))
        }
        if (selection != null) {
            SelectionHandles(selection, text, pageRect)
        }
        when {
            state.error != null && base == null -> PageError(index, state.error, onRetry, Modifier.align(Alignment.Center))
            state.error != null -> RetryIcon(index, onRetry, Modifier.align(Alignment.TopEnd).padding(4.dp))
            state.isLoading -> CircularProgressIndicator(
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
        }
    }
}

private fun DrawScope.drawBitmap(image: ImageBitmap, dst: DrawRect) {
    drawImage(
        image,
        dstOffset = IntOffset(dst.left, dst.top),
        dstSize = IntSize(dst.right - dst.left, dst.bottom - dst.top),
        filterQuality = FilterQuality.Medium,
    )
}

/**
 * A failed page with nothing to show: a short message, the reason ([pdfErrorTitle]) and a Retry
 * button. A page that ran the engine out of memory or time says it is too large or complex (Retry
 * is still offered, though it will likely fail the same way).
 */
@Composable
private fun PageError(index: Int, error: PdfError, onRetry: () -> Unit, modifier: Modifier) {
    val tooBig = error == PdfError.OutOfMemory || error == PdfError.TimedOut
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(AppIcons.warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(if (tooBig) R.string.reader_page_too_complex else R.string.reader_page_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = if (tooBig) 12.dp else 2.dp),
        )
        if (!tooBig) {
            Text(
                text = stringResource(pdfErrorTitle(error)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 12.dp).testTag(readerPageReasonTag(index)),
            )
        }
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
