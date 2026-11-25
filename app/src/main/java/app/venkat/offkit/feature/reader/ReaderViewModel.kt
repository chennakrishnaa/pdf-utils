package app.venkat.offkit.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.ReaderDocument
import app.venkat.offkit.core.ReaderOpenResult
import app.venkat.offkit.core.ReaderTile
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.RenderRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
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
 * visible pages' bases, then their details (zoom > 1), then neighbours' bases. A render already
 * sent to the engine is never cancelled because it became stale (its result is dropped if no
 * longer wanted); requests not yet sent are simply never sent. Base bitmaps are kept in an LRU
 * bounded by [ReaderLimits.baseCacheBytes] (pages farthest from the viewport go first; visible
 * pages are never evicted). Each visible page has at most one detail bitmap; details of pages
 * that are no longer visible, or all of them when the zoom returns to 1, are dropped.
 *
 * Bitmaps handed out are never recycled: an evicted one is just dropped and left to the GC, so the
 * screen may keep drawing it until it recomposes.
 *
 * Failures: a page whose render fails gets [ReaderPageState.error] and is not requested again
 * (neither its base nor its detail) until [retry]; other pages go on (after an engine crash the
 * next call binds a fresh engine). The document stays open.
 *
 * All methods must be called on the main thread.
 */
class ReaderViewModel internal constructor(
    application: Application,
    private val reader: PdfReader,
    private val limits: ReaderLimits,
) : AndroidViewModel(application) {
    /** Used by the default `ViewModelProvider` factory. */
    constructor(application: Application) : this(application, PdfReader.get(application), ReaderLimits())

    private val _document = MutableStateFlow<ReaderDocState>(ReaderDocState.Idle)

    /** The current document. */
    val document: StateFlow<ReaderDocState> = _document.asStateFlow()

    private var session: Session? = null
    private var openJob: Job? = null
    private var generation = 0

    /**
     * Opens the PDF at [uri] (a `content:` Uri from the system picker; it is read once, now, and
     * not kept). Closes any document already open or opening.
     */
    fun open(uri: Uri) {
        closeAll()
        val gen = ++generation
        _document.value = ReaderDocState.Opening
        openJob = viewModelScope.launch {
            val result = reader.open(uri)
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

    override fun onCleared() {
        closeAll()
        generation++
    }

    private fun closeAll() {
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
            val shown = vp.visible.map { it.index }.toSet()
            val keepDetails = vp.zoom.isFinite() && vp.zoom > 1f
            for ((index, plan) in details.entries.toList()) {
                if (!keepDetails || index !in shown || plan.itemWidthPx != vp.itemWidthPx) dropDetail(index)
            }
            wake.trySend(Unit)
        }

        fun retry(index: Int) {
            if (failed.remove(index) == null) return
            update(index) { it.copy(error = null) }
            wake.trySend(Unit)
        }

        fun close() {
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

    private companion object {
        val EMPTY_PAGE: StateFlow<ReaderPageState> = MutableStateFlow(ReaderPageState()).asStateFlow()
    }
}
