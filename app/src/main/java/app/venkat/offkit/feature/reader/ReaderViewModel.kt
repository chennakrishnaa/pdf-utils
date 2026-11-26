package app.venkat.offkit.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.ReaderDocument
import app.venkat.offkit.core.ReaderOpenResult
import app.venkat.offkit.core.ReaderText
import app.venkat.offkit.core.ReaderTile
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PageText
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.RenderRequest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The reader's document. */
sealed interface ReaderDocState {
    /** Nothing open. */
    data object Idle : ReaderDocState

    /** Copying the picked file and reading its pages. */
    data object Opening : ReaderDocState

    /** Opening failed (`Encrypted`, `Corrupt`, `Io` for an unreadable file, ...). Nothing is kept. */
    data class Failed(val error: PdfError) : ReaderDocState

    /** [pages] (one [PageDims] per page, at least one) of the file called [name] are ready to show. */
    data class Open(val name: String, val pages: List<PageDims>) : ReaderDocState
}

/**
 * A sharp bitmap of part of a page, to draw over the base. [bitmap] shows exactly [rect], given in
 * unscaled item pixels for an item [itemWidthPx] wide (multiply by `currentItemWidth / itemWidthPx`
 * if the item width has changed since).
 */
data class PageDetail(val bitmap: Bitmap, val rect: ItemRect, val itemWidthPx: Int)

/**
 * What to draw for one page.
 *
 * @property base the whole page, or null (not rendered yet, evicted, or failed). Draw it stretched to
 *   the full item; its pixel size may be smaller than the item (memory cap) or larger (never).
 * @property detail the sharp part of the page when zoomed in, drawn on top of [base], or null.
 * @property error the page's render failed (it is not retried automatically: offer [ReaderViewModel.retry]).
 *   [base] may still hold an earlier bitmap.
 */
data class ReaderPageState(val base: Bitmap? = null, val detail: PageDetail? = null, val error: PdfError? = null) {
    /** True while the page has nothing to show and has not failed: show a placeholder. */
    val isLoading: Boolean get() = base == null && error == null
}

/**
 * State and actions of the PDF reader screen. Holds no Compose types.
 *
 * Lifecycle: [open] a picked `content:` Uri -> [document] goes `Opening`, then `Open` or `Failed`.
 * Opening again, [close] and [onCleared] close the previous document (its engine work is asked to
 * stop and its `cacheDir/jobs/reader-<uuid>/` copy is deleted).
 *
 * Rendering: the screen reports what it shows with [onViewport] whenever scrolling or zooming
 * settles (it may call it often; calls are coalesced, the latest wins). One worker renders, one
 * request at a time, the first missing item of [ReaderPlanner.plan] for the latest viewport:
 * visible pages' bases, then their details (zoomed in, or at zoom 1 when the base is softer
 * than the screen), then neighbours' bases. A render already
 * sent to the engine is never cancelled because it became stale (its result is dropped if no
 * longer wanted); requests not yet sent are simply never sent. Base bitmaps are kept in an LRU
 * bounded by [ReaderLimits.baseCacheBytes] (pages farthest from the viewport go first; visible
 * pages are never evicted). Each visible page has at most one detail bitmap; details of pages
 * that the plan no longer wants (not visible, or zoom back to 1 with a sharp base) are dropped.
 *
 * Bitmaps handed out are never recycled: an evicted one is just dropped and left to the GC, so the
 * screen may keep drawing it until it recomposes.
 *
 * Failures: a page whose render fails gets [ReaderPageState.error] and is not requested again
 * (neither its base nor its detail) until [retry]; other pages go on (after an engine crash the
 * next call binds a fresh engine). The document stays open.
 *
 * Text selection: a page's text is fetched on the first [longPress] (or [copyPageText]) on it,
 * through [PdfReader.text] (the same engine thread as renders, queued between them; a render is
 * never cancelled for it), and kept in a small LRU ([ReaderTextLimits]) until the document closes.
 * [selection] is on one page at a time; it is cleared when the document closes or another opens,
 * and when the viewport moves more than [SELECTION_KEEP_PAGES] pages away from it. Copy results
 * and messages arrive on [textEvents].
 *
 * All methods must be called on the main thread.
 */
class ReaderViewModel internal constructor(
    application: Application,
    private val reader: PdfReader,
    private val limits: ReaderLimits,
    private val textLimits: ReaderTextLimits = ReaderTextLimits(),
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : AndroidViewModel(application) {
    /** Used by the default `ViewModelProvider` factory. */
    constructor(application: Application) : this(application, PdfReader.get(application), ReaderLimits())

    /** Used by the screen, with the destination's saved state. */
    constructor(application: Application, savedState: SavedStateHandle) :
        this(application, PdfReader.get(application), ReaderLimits(), ReaderTextLimits(), savedState)

    /** The screen's automatic picker has run (kept across process death). */
    var pickerLaunched: Boolean
        get() = savedState[KEY_LAUNCHED] ?: false
        set(value) { savedState[KEY_LAUNCHED] = value }

    /** A picker is out and its result not yet received (kept across process death). */
    var pickerPending: Boolean
        get() = savedState[KEY_PENDING] ?: false
        set(value) { savedState[KEY_PENDING] = value }

    private val _document = MutableStateFlow<ReaderDocState>(ReaderDocState.Idle)

    /** The current document. */
    val document: StateFlow<ReaderDocState> = _document.asStateFlow()

    private var session: Session? = null

    private val _selection = MutableStateFlow<ReaderSelection?>(null)

    /** The current text selection, or null. */
    val selection: StateFlow<ReaderSelection?> = _selection.asStateFlow()

    private val _textEvents = Channel<ReaderTextEvent>(Channel.BUFFERED)

    /** Copy requests and messages of text actions; collect once, from the screen. */
    val textEvents: Flow<ReaderTextEvent> = _textEvents.receiveAsFlow()

    /** Bumped by every long-press, so only the latest one selects once its text arrives. */
    private var pressCount = 0
    private var openJob: Job? = null
    private var generation = 0

    /**
     * Opens the PDF at [uri] (a `content:` Uri from the system picker; it is read once, now, and
     * not kept). Closes any document already open or opening. [checkType]: the Uri's type is not
     * known yet and the provider must report `application/pdf` first (see [PdfReader.open]).
     */
    fun open(uri: Uri, checkType: Boolean = false) {
        closeAll()
        val gen = ++generation
        _document.value = ReaderDocState.Opening
        openJob = viewModelScope.launch {
            val result = reader.open(uri, checkType)
            if (gen != generation) {
                if (result is ReaderOpenResult.Opened) reader.close(result.document)
                return@launch
            }
            openJob = null
            _document.value = when (result) {
                is ReaderOpenResult.Opened -> {
                    session = Session(result.document)
                    ReaderDocState.Open(result.document.name, result.document.pages)
                }
                is ReaderOpenResult.Failed -> ReaderDocState.Failed(result.error)
            }
        }
    }

    /** Closes the document (or stops opening it) and deletes its files; [document] becomes `Idle`. */
    fun close() {
        closeAll()
        generation++
        _document.value = ReaderDocState.Idle
    }

    /**
     * Per-page state of page [index] of the open document, for one list item to collect. Returns
     * a fresh flow per document: collect it keyed on the [ReaderDocState.Open] instance. An index
     * out of range (or no document) gives a flow that stays empty.
     */
    fun page(index: Int): StateFlow<ReaderPageState> = session?.flow(index) ?: EMPTY_PAGE

    /**
     * What the screen shows, called when scrolling or zooming settles (and as often as convenient).
     *
     * @param itemWidthPx width in pixels of a page item at zoom 1 (the list's content width).
     * @param zoom the settled zoom (`ZoomableListState.scale` while `!isZooming`); 1 = fit width.
     * @param visible the pages on screen with their visible rect in unscaled item pixels (see
     *   [VisiblePage]); pages of height [ReaderPlanner.itemHeightPx].
     */
    fun onViewport(itemWidthPx: Int, zoom: Float, visible: List<VisiblePage>) {
        session?.onViewport(ReaderViewport(itemWidthPx, zoom, visible.toList()))
    }

    /** Clears page [index]'s error so it is rendered again (if still near the viewport). */
    fun retry(index: Int) {
        session?.retry(index)
    }

    /**
     * Long-press at page point ([xPt], [yPt]) of [page]: selects the word there (within
     * [tolerancePt]) once the page's text is known. No text within reach: nothing happens, unless
     * the page has no text at all ([ReaderTextEvent.NoText]).
     */
    fun longPress(page: Int, xPt: Float, yPt: Float, tolerancePt: Float) {
        val s = session ?: return
        val press = ++pressCount
        s.withText(page) { text ->
            if (press != pressCount) return@withText
            if (text.length == 0) {
                _textEvents.trySend(ReaderTextEvent.NoText)
            } else {
                SelectionMath.selectWord(page, text, xPt, yPt, tolerancePt)?.let { _selection.value = it }
            }
        }
    }

    /** Selects all the text of the selected page. */
    fun selectAll() {
        val s = _selection.value ?: return
        SelectionMath.selectAll(s.page, s.text)?.let { _selection.value = it }
    }

    /** Moves the anchor (else the focus) handle to the character nearest page point ([xPt], [yPt]), clamped to the page. */
    fun dragHandle(anchor: Boolean, xPt: Float, yPt: Float) {
        val s = _selection.value ?: return
        val dims = session?.doc?.pages?.getOrNull(s.page) ?: return
        _selection.value = SelectionMath.drag(s, anchor, xPt, yPt, dims)
    }

    fun clearSelection() {
        pressCount++
        _selection.value = null
    }

    /** Sends the selected text to be copied ([ReaderTextEvent.Copy]) and clears the selection. */
    fun copySelection() {
        val s = _selection.value ?: return
        clearSelection()
        _textEvents.trySend(ReaderTextEvent.Copy(s.string(), s.cut))
    }

    /** Copies all the text of [page] (the accessibility action), fetching it first if needed. */
    fun copyPageText(page: Int) {
        session?.withText(page) { text ->
            _textEvents.trySend(
                if (text.length == 0) ReaderTextEvent.NoText else ReaderTextEvent.Copy(text.string(0 until text.length), text.truncated),
            )
        }
    }

    override fun onCleared() {
        closeAll()
        generation++
    }

    private fun closeAll() {
        clearSelection()
        openJob?.cancel()
        openJob = null
        session?.close()
        session = null
    }

    /** One open document: its page states, caches and render worker. Main thread only. */
    private inner class Session(val doc: ReaderDocument) {
        private val pageCount = doc.pages.size
        private val states = HashMap<Int, MutableStateFlow<ReaderPageState>>()

        /** Request of each page's current base bitmap. */
        private val baseRequests = HashMap<Int, RenderRequest>()

        /** Plan of each page's current detail bitmap. */
        private val details = HashMap<Int, PlannedRender>()

        /** Pages whose last render failed; not requested until [retry]. */
        private val failed = HashMap<Int, PdfError>()

        private var viewport: ReaderViewport? = null
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private val worker: Job = viewModelScope.launch { work() }

        /** The same instance for the life of this document, so collectors are not restarted. */
        fun flow(index: Int): StateFlow<ReaderPageState>? = mutableFlow(index)

        private fun mutableFlow(index: Int): MutableStateFlow<ReaderPageState>? =
            if (index in 0 until pageCount) states.getOrPut(index) { MutableStateFlow(ReaderPageState()) } else null

        private fun update(index: Int, change: (ReaderPageState) -> ReaderPageState) {
            val flow = mutableFlow(index) ?: return
            flow.value = change(flow.value)
        }

        fun onViewport(vp: ReaderViewport) {
            viewport = vp
            val sel = _selection.value
            if (sel != null && vp.visible.isNotEmpty()) {
                val shown = vp.visible.map { it.index }
                if (sel.page < shown.min() - SELECTION_KEEP_PAGES || sel.page > shown.max() + SELECTION_KEEP_PAGES) clearSelection()
            }
            // Keep a page's detail only while the new plan still wants one for it (zoomed in, or a
            // base softer than the screen at zoom 1); it is replaced when the new one arrives.
            val wanted = ReaderPlanner.plan(doc.pages, vp, limits)
                .filter { it.kind == RenderKind.DETAIL }.map { it.page }.toSet()
            for ((index, plan) in details.entries.toList()) {
                if (index !in wanted || plan.itemWidthPx != vp.itemWidthPx) dropDetail(index)
            }
            wake.trySend(Unit)
        }

        fun retry(index: Int) {
            if (failed.remove(index) == null) return
            update(index) { it.copy(error = null) }
            wake.trySend(Unit)
        }

        /** Text of each fetched page, least recently used first. */
        private val texts = LinkedHashMap<Int, PageText>(16, 0.75f, true)
        private val fetching = HashMap<Int, Deferred<ReaderText>>()
        private var closed = false

        /** Runs [then] with [page]'s text: now if cached, else once fetched (not if it fails or the document closes). */
        fun withText(page: Int, then: (PageText) -> Unit) {
            if (page !in 0 until pageCount) return
            texts[page]?.let { return then(it) }
            val call = fetching.getOrPut(page) { viewModelScope.async { reader.text(doc, page) } }
            viewModelScope.launch {
                val result = call.await()
                fetching.remove(page)
                if (closed) return@launch
                when (result) {
                    is ReaderText.Ok -> {
                        texts[page] = result.text
                        trimTexts(keep = page)
                        then(result.text)
                    }
                    is ReaderText.Err -> if (result.error != PdfError.Cancelled) {
                        _textEvents.trySend(ReaderTextEvent.Failed(result.error))
                    }
                }
            }
        }

        /** Number of cached pages' texts (for tests). */
        val cachedTexts: Int get() = texts.size

        private fun trimTexts(keep: Int) {
            val selected = _selection.value?.page
            var total = texts.values.sumOf { it.length }
            val it = texts.entries.iterator()
            while (it.hasNext() && (texts.size > textLimits.pages || total > textLimits.chars)) {
                val e = it.next()
                if (e.key == keep || e.key == selected) continue
                total -= e.value.length
                it.remove()
            }
        }

        fun close() {
            closed = true
            texts.clear()
            for (f in fetching.values) f.cancel()
            fetching.clear()
            worker.cancel()
            reader.close(doc)
            for (flow in states.values) flow.value = ReaderPageState()
        }

        private suspend fun work() {
            while (true) {
                val next = nextRender()
                if (next == null) {
                    wake.receive()
                    continue
                }
                val tile = reader.render(doc, next.request)
                apply(next, tile)
            }
        }

        private fun currentPlan(): List<PlannedRender> =
            viewport?.let { ReaderPlanner.plan(doc.pages, it, limits) }.orEmpty()

        private fun nextRender(): PlannedRender? =
            currentPlan().firstOrNull { it.page !in failed && !satisfied(it) }

        private fun satisfied(p: PlannedRender): Boolean = when (p.kind) {
            RenderKind.BASE -> baseRequests[p.page] == p.request
            RenderKind.DETAIL -> details[p.page]?.let { d ->
                d.request == p.request ||
                    (d.request.scale == p.request.scale && d.itemWidthPx == p.itemWidthPx && d.covers.contains(p.visible ?: p.covers))
            } == true
        }

        private fun apply(p: PlannedRender, tile: ReaderTile) {
            when (tile) {
                is ReaderTile.Err -> {
                    failed[p.page] = tile.error
                    update(p.page) { it.copy(error = tile.error) }
                }
                is ReaderTile.Ok -> {
                    val plan = currentPlan()
                    when (p.kind) {
                        RenderKind.BASE -> if (plan.any { it.kind == RenderKind.BASE && it.request == p.request }) {
                            baseRequests[p.page] = p.request
                            update(p.page) { it.copy(base = tile.bitmap, error = null) }
                            evict(plan)
                        }
                        RenderKind.DETAIL -> {
                            val wanted = plan.firstOrNull { it.kind == RenderKind.DETAIL && it.page == p.page }
                            val stillUseful = wanted != null && (
                                wanted.request == p.request ||
                                    (wanted.request.scale == p.request.scale && p.covers.contains(wanted.visible ?: wanted.covers))
                                )
                            if (stillUseful) {
                                details[p.page] = p
                                update(p.page) { it.copy(detail = PageDetail(tile.bitmap, p.covers, p.itemWidthPx), error = null) }
                            }
                        }
                    }
                }
            }
        }

        private fun dropDetail(index: Int) {
            details.remove(index)
            update(index) { it.copy(detail = null) }
        }

        /**
         * Drops base bitmaps until their total fits [ReaderLimits.baseCacheBytes]: first pages not
         * in [plan], then pages whose bitmap is not the planned one, then planned pages, each group
         * farthest from the viewport first. Visible pages are never dropped.
         */
        private fun evict(plan: List<PlannedRender>) {
            var total = baseRequests.values.sumOf { it.width.toLong() * it.height * 4 }
            if (total <= limits.baseCacheBytes) return
            val vp = viewport ?: return
            val shown = vp.visible.map { it.index }.filter { it in 0 until pageCount }
            if (shown.isEmpty()) return
            val first = shown.min()
            val last = shown.max()
            val planned = plan.filter { it.kind == RenderKind.BASE }.associate { it.page to it.request }
            fun group(index: Int, request: RenderRequest) = when (planned[index]) {
                null -> 0
                request -> 2
                else -> 1
            }
            fun distance(index: Int) = if (index < first) first - index else index - last
            val candidates = baseRequests.entries
                .filter { it.key !in shown }
                .sortedWith(compareBy({ group(it.key, it.value) }, { -distance(it.key) }))
            for ((index, request) in candidates) {
                if (total <= limits.baseCacheBytes) break
                baseRequests.remove(index)
                total -= request.width.toLong() * request.height * 4
                update(index) { it.copy(base = null) }
            }
        }
    }

    /** Number of pages' texts cached for the open document (for tests). */
    @androidx.annotation.VisibleForTesting
    fun cachedTextPagesForTest(): Int = session?.cachedTexts ?: 0

    internal companion object {
        /** A selection is cleared once the viewport is more than this many pages away from it. */
        const val SELECTION_KEEP_PAGES = 2
        private const val KEY_LAUNCHED = "reader.pickerLaunched"
        private const val KEY_PENDING = "reader.pickerPending"

        val EMPTY_PAGE: StateFlow<ReaderPageState> = MutableStateFlow(ReaderPageState()).asStateFlow()
    }
}
