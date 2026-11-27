package app.venkat.offkit.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.venkat.offkit.core.LockedPdf
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.core.ReaderDocument
import app.venkat.offkit.core.ReaderOpenResult
import app.venkat.offkit.core.ReaderText
import app.venkat.offkit.core.ReaderTile
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PageText
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.ReaderPassword
import app.venkat.offkit.engine.RenderRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch

/** The reader's document. */
sealed interface ReaderDocState {
    /** Nothing open. */
    data object Idle : ReaderDocState

    /** Copying the picked file and reading its pages. */
    data object Opening : ReaderDocState

    /** Opening failed (`Corrupt`, `Io` for an unreadable file, ...). Nothing is kept. */
    data class Failed(val error: PdfError) : ReaderDocState

    /**
     * The file called [name] needs a password: its private copy waits for [ReaderViewModel.unlock]
     * or [ReaderViewModel.cancelPassword]. A document that was open before stays open (not shown)
     * until this one is unlocked. [attempt] is bumped by every wrong password; [problem] says why
     * the last attempt failed; [busy] while a password is being tried. Holds no password.
     */
    data class Locked(
        val name: String,
        val attempt: Int = 0,
        val problem: PasswordProblem? = null,
        val busy: Boolean = false,
    ) : ReaderDocState

    /** [pages] (one [PageDims] per page, at least one) of the file called [name] are ready to show. */
    data class Open(val name: String, val pages: List<PageDims>) : ReaderDocState
}

/** Why a password attempt failed. */
enum class PasswordProblem {
    /** The engine said the password does not open the PDF. */
    WRONG,

    /** [ReaderPassword.check] refused it (too long, or U+0000); the engine was not called. */
    INVALID,
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
 * A PDF that needs a password goes `Locked` ([unlock], [cancelPassword]).
 * Opening again (once the new one opens or fails), [close] and [onCleared] close the previous document (its engine work is asked to
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
 * Search ([search], [searchStep], [closeSearch]): one cancellable scan per query over
 * [PdfReader.text], page by page from the given page with wrap-around (see [scanPages]). It runs
 * at lower priority than rendering: before each page it yields and then waits until the render
 * worker has nothing to send (no render in flight and nothing missing from the plan), so a
 * render asked for during a scan goes before the next scan page (a text call already sent is
 * not cancelled, and neither is a render). Scanned text is not put in the selection cache and
 * not kept: hits are stored as ranges, with rectangles only for pages near the viewport. Folding,
 * matching and hit rectangles are computed on [compute] (`Dispatchers.Default`); the results are
 * recorded and published on the main thread.
 *
 * All methods must be called on the main thread.
 */
class ReaderViewModel internal constructor(
    application: Application,
    private val reader: PdfReader,
    private val limits: ReaderLimits,
    private val textLimits: ReaderTextLimits = ReaderTextLimits(),
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
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

    /** A "Save a copy" dialog is out and its result not yet received (kept across process death). */
    var saveCopyPending: Boolean
        get() = savedState[KEY_SAVE_PENDING] ?: false
        set(value) { savedState[KEY_SAVE_PENDING] = value }

    /**
     * The reader's session began with a document another app asked Offkit to open ("Open with"),
     * so leaving the reader returns to that app (kept across rotation and process death). Set
     * from each [app.venkat.offkit.ui.nav.ViewRequest] the screen takes; a document picked with
     * "Open another PDF" leaves it as it is.
     */
    val openedFromOtherApp: StateFlow<Boolean> = savedState.getStateFlow(KEY_FROM_OTHER_APP, false)

    /**
     * True once this instance (not one restored after process death) took a request from another
     * app: only then does the reader emptying itself (a cancelled password prompt) return to it.
     */
    var tookRequestFromOtherApp: Boolean = false
        private set

    /** How many returns through the launcher icon the screen has already applied (they clear [openedFromOtherApp]). */
    var launcherReturnsSeen: Int
        get() = savedState[KEY_LAUNCHER_RETURNS] ?: 0
        set(value) { savedState[KEY_LAUNCHER_RETURNS] = value }

    /**
     * The origin ([openedFromOtherApp], [tookRequestFromOtherApp]) of the document shown while a
     * requested one is still opening or waiting for its password: [cancelPassword] puts it back
     * when it shows that document again. Null once the requested document opens or fails.
     */
    private var originBeforeRequest: Pair<Boolean, Boolean>? = null

    /**
     * Records where the document just requested (opened next with [open]) came from (see
     * [openedFromOtherApp]). If its password prompt is cancelled and the document shown before
     * comes back, that document's own value comes back with it.
     */
    fun setOpenedFromOtherApp(fromOtherApp: Boolean) {
        if (originBeforeRequest == null) originBeforeRequest = openedFromOtherApp.value to tookRequestFromOtherApp
        setOrigin(fromOtherApp, fromOtherApp)
    }

    private fun setOrigin(fromOtherApp: Boolean, took: Boolean) {
        savedState[KEY_FROM_OTHER_APP] = fromOtherApp
        tookRequestFromOtherApp = took
    }

    /** The user came back to Offkit through its launcher icon: leaving the reader stays in Offkit. */
    fun forgetOtherApp() {
        setOrigin(false, false)
        if (originBeforeRequest != null) originBeforeRequest = false to false
    }

    private val _document = MutableStateFlow<ReaderDocState>(ReaderDocState.Idle)

    /** The current document. */
    val document: StateFlow<ReaderDocState> = _document.asStateFlow()

    private var session: Session? = null

    /** Share and Print of the open document ([ReaderShare.kt], [ReaderPrint.kt]). */
    val actions = ReaderActions(reader, viewModelScope) { session?.doc }

    private val _selection = MutableStateFlow<ReaderSelection?>(null)

    /** The current text selection, or null. */
    val selection: StateFlow<ReaderSelection?> = _selection.asStateFlow()

    private val _textEvents = Channel<ReaderTextEvent>(Channel.BUFFERED)

    /** Copy requests and messages of text actions; collect once, from the screen. */
    val textEvents: Flow<ReaderTextEvent> = _textEvents.receiveAsFlow()

    /** Bumped by every long-press, so only the latest one selects once its text arrives. */
    private var pressCount = 0
    private val _search = MutableStateFlow<ReaderSearchState?>(null)

    /** The current search's results, or null (search closed or no document). */
    val search: StateFlow<ReaderSearchState?> = _search.asStateFlow()

    private val _searchReveals = Channel<SearchReveal>(Channel.CONFLATED)

    /** Where to scroll for the selected hit; collect once, from the screen. */
    val searchReveals: Flow<SearchReveal> = _searchReveals.receiveAsFlow()

    private var openJob: Job? = null
    private var generation = 0

    /** The private copy of a PDF waiting for its password ([ReaderDocState.Locked]). */
    private var locked: LockedPdf? = null

    /**
     * Opens the PDF at [uri] (a `content:` Uri from the system picker; it is read once, now, and
     * not kept). Closes any document already open or opening. [checkType]: the Uri's type is not
     * known yet and the provider must report `application/pdf` first (see [PdfReader.open]).
     *
     * A document already open is closed only once the new one opens or fails; while the new one
     * is [ReaderDocState.Locked] it stays open, and [cancelPassword] shows it again. A PDF waiting
     * for its password is discarded.
     */
    fun open(uri: Uri, checkType: Boolean = false) {
        clearSelection()
        // A search on the shown document ends here, even if that document stays open while the
        // new one waits for its password.
        closeSearch()
        stopOpening()
        val gen = ++generation
        _document.value = ReaderDocState.Opening
        openJob = viewModelScope.launch {
            val result = reader.open(uri, checkType, keepLockedCopy = true)
            if (gen != generation) {
                when (result) {
                    is ReaderOpenResult.Opened -> reader.close(result.document)
                    is ReaderOpenResult.Failed -> result.locked?.let { reader.discard(it) }
                }
                return@launch
            }
            openJob = null
            settle(result)
        }
    }

    /**
     * Tries [password] on the PDF that is [ReaderDocState.Locked] (ignored otherwise, while busy,
     * or for an empty one). A password [ReaderPassword.check] refuses gives [PasswordProblem.INVALID]
     * without calling the engine; a wrong one [PasswordProblem.WRONG] (any number of attempts). The
     * password is not kept here: [PdfReader] keeps it inside the opened document only.
     */
    fun unlock(password: String) {
        val pdf = locked ?: return
        val state = _document.value as? ReaderDocState.Locked ?: return
        if (state.busy || password.isEmpty()) return
        if (ReaderPassword.check(password) != null) {
            _document.value = state.copy(problem = PasswordProblem.INVALID)
            return
        }
        val gen = generation
        _document.value = state.copy(busy = true, problem = null)
        openJob = viewModelScope.launch {
            val result = reader.unlock(pdf, password)
            if (gen != generation) {
                if (result is ReaderOpenResult.Opened) reader.close(result.document)
                return@launch
            }
            openJob = null
            if (result is ReaderOpenResult.Failed && result.locked != null) {
                _document.value = state.copy(attempt = state.attempt + 1, problem = PasswordProblem.WRONG, busy = false)
            } else {
                locked = null
                settle(result)
            }
        }
    }

    /**
     * The user gave up on the password: the private copy is deleted and the document open before
     * is shown again, or, if there was none, [document] becomes `Idle`.
     */
    fun cancelPassword() {
        if (_document.value !is ReaderDocState.Locked) return
        stopOpening()
        generation++
        val shown = session?.state
        // The document shown again keeps its own origin, not the cancelled request's.
        if (shown != null) originBeforeRequest?.let { (from, took) -> setOrigin(from, took) }
        originBeforeRequest = null
        _document.value = shown ?: ReaderDocState.Idle
    }

    private fun settle(result: ReaderOpenResult) {
        // The requested document opened or failed: its origin is now the shown document's.
        if (result !is ReaderOpenResult.Failed || result.locked == null) originBeforeRequest = null
        _document.value = when (result) {
            is ReaderOpenResult.Opened -> {
                closeSession()
                Session(result.document).also { session = it }.state
            }
            is ReaderOpenResult.Failed -> {
                val pdf = result.locked
                if (pdf != null) {
                    locked = pdf
                    ReaderDocState.Locked(pdf.name)
                } else {
                    closeSession()
                    ReaderDocState.Failed(result.error)
                }
            }
        }
    }

    /** Closes the document (or stops opening it) and deletes its files; [document] becomes `Idle`. */
    fun close() {
        originBeforeRequest = null
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

    /**
     * Searches the open document for [query], starting at page [fromPage]. An empty query
     * (after trimming) stops any scan and shows nothing; the query already searched is a no-op.
     */
    fun search(query: String, fromPage: Int) {
        session?.search(query, fromPage)
    }

    /** Selects the next (or previous) hit, wrapping, and asks the screen to reveal it. */
    fun searchStep(forward: Boolean) {
        session?.searchStep(forward)
    }

    /** Stops the scan and drops the results and highlights. */
    fun closeSearch() {
        session?.stopSearch()
        _search.value = null
    }

    override fun onCleared() {
        closeAll()
        generation++
    }

    private fun closeAll() {
        clearSelection()
        stopOpening()
        closeSession()
    }

    /** Stops opening or unlocking and discards a PDF waiting for its password. */
    private fun stopOpening() {
        openJob?.cancel()
        openJob = null
        locked?.let { reader.discard(it) }
        locked = null
    }

    private fun closeSession() {
        session?.close()
        session = null
        _search.value = null
    }

    /** One open document: its page states, caches and render worker. Main thread only. */
    private inner class Session(val doc: ReaderDocument) {
        val state = ReaderDocState.Open(doc.name, doc.pages)
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
        /** True while the render worker has a render in flight or one to send (the scan waits). */
        private val renderBusy = MutableStateFlow(false)
        private var rendering = false

        // Declared after the fields work() uses: viewModelScope starts it at once (Main.immediate).
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
            if (nextRender() != null) renderBusy.value = true
            searchOnViewport(vp)
        }

        fun retry(index: Int) {
            if (failed.remove(index) == null) return
            update(index) { it.copy(error = null) }
            wake.trySend(Unit)
            if (nextRender() != null) renderBusy.value = true
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
            stopSearch()
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
                    renderBusy.value = false
                    wake.receive()
                    continue
                }
                rendering = true
                renderBusy.value = true
                val tile = try {
                    reader.render(doc, next.request)
                } finally {
                    rendering = false
                }
                apply(next, tile)
            }
        }

        private var searchJob: Job? = null
        private var results: SearchResults? = null
        private val rectJobs = HashMap<Int, Job>()

        /** The hit Next / Previous moved to whose rectangles are being fetched: revealed when they arrive. */
        private var pendingReveal: SearchHit? = null

        fun search(raw: String, fromPage: Int) {
            val query = SearchMath.parse(raw)
            if (query != null && results?.query?.text == query.text) return
            stopSearch()
            if (query == null) {
                _search.value = null
                return
            }
            val res = SearchResults(query)
            results = res
            publish(res)
            searchJob = viewModelScope.launch {
                scanPages(
                    pages = SearchMath.scanOrder(fromPage, pageCount),
                    results = res,
                    textOf = { reader.text(doc, it) },
                    awaitTurn = {
                        yield()
                        renderBusy.first { !it }
                    },
                    near = ::nearViewport,
                    publish = { publish(res) },
                    reveal = { hit, rects -> _searchReveals.trySend(SearchReveal(hit.page, rects?.firstOrNull())) },
                    compute = compute,
                )
            }
        }

        fun stopSearch() {
            searchJob?.cancel()
            searchJob = null
            results = null
            pendingReveal = null
            for (j in rectJobs.values) j.cancel()
            rectJobs.clear()
        }

        fun searchStep(forward: Boolean) {
            val res = results ?: return
            val i = SearchMath.step(res.hits.size, res.current?.let { res.hits.binarySearch(it) } ?: -1, forward)
            if (i < 0) return
            val hit = res.hits[i]
            res.current = hit
            publish(res)
            val known = res.rects[hit.page]?.rectsOf(hit)
            if (known != null) {
                pendingReveal = null
                _searchReveals.trySend(SearchReveal(hit.page, known.firstOrNull()))
            } else {
                _searchReveals.trySend(SearchReveal(hit.page, null))
                pendingReveal = hit
                res.retryRects.remove(hit.page) // an explicit step tries a passing failure again
                fetchRects(res, hit.page) // already in flight for this page: its result reveals the hit too
            }
        }

        private fun publish(res: SearchResults) {
            if (results === res) _search.value = res.state()
        }

        private fun nearViewport(page: Int): Boolean {
            val shown = viewport?.visible?.map { it.index }.orEmpty()
            if (shown.isEmpty()) return false
            return page in shown.min() - SearchMath.RECT_PAGES..shown.max() + SearchMath.RECT_PAGES
        }

        /** Drops rectangles of pages that left the viewport's neighbourhood; fetches them for visible pages with hits. */
        private fun searchOnViewport(vp: ReaderViewport) {
            val res = results ?: return
            val dropped = res.rects.keys.removeAll { !nearViewport(it) }
            res.retryRects.removeAll { !nearViewport(it) } // left the neighbourhood: tried again when back
            for (page in vp.visible.map { it.index }) {
                if (page !in res.rects && page !in rectJobs && res.hitsOn(page).isNotEmpty()) fetchRects(res, page)
            }
            if (dropped) publish(res)
        }

        /** Reads [page]'s text again (not cached) to compute its hits' rectangles. */
        private fun fetchRects(res: SearchResults, page: Int) {
            if (page in rectJobs || page in res.failedRects || page in res.retryRects) return
            rectJobs[page] = viewModelScope.launch {
                val r = reader.text(doc, page)
                if (results !== res) return@launch
                if (r !is ReaderText.Ok) {
                    rectJobs.remove(page)
                    if (r is ReaderText.Err && r.error != PdfError.Cancelled) {
                        if (isTransientTextError(r.error)) res.retryRects += page else res.failedRects += page
                    }
                    return@launch
                }
                val hits = res.hitsOn(page) // a copy, read here on the main thread
                // Computed off the main thread; the page stays in rectJobs meanwhile (no second fetch).
                val rects = withContext(compute) {
                    val ctx = currentCoroutineContext()
                    SearchMath.rectsOf(r.text, hits) { ctx.ensureActive() }
                }
                if (results !== res) return@launch
                rectJobs.remove(page)
                // Kept only if still wanted: near the viewport, or the page of the hit waiting to be revealed.
                if (!nearViewport(page) && pendingReveal?.page != page) return@launch
                res.rects[page] = rects
                publish(res)
                val hit = pendingReveal
                if (hit != null && hit.page == page && res.current == hit) {
                    pendingReveal = null
                    rects.rectsOf(hit)?.firstOrNull()?.let { _searchReveals.trySend(SearchReveal(page, it)) }
                }
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
        private const val KEY_SAVE_PENDING = "reader.saveCopyPending"
        private const val KEY_FROM_OTHER_APP = "reader.openedFromOtherApp"
        private const val KEY_LAUNCHER_RETURNS = "reader.launcherReturnsSeen"

        val EMPTY_PAGE: StateFlow<ReaderPageState> = MutableStateFlow(ReaderPageState()).asStateFlow()
    }
}
