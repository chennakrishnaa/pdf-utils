package app.venkat.offkit.reader

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.TestPageTexts
import app.venkat.offkit.engine.PdfPageText
import app.venkat.offkit.feature.reader.ReaderTextEvent
import app.venkat.offkit.feature.reader.ReaderTextLimits
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.PdfJobs
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.PdfPageInfo
import app.venkat.offkit.engine.PdfPageRenderer
import app.venkat.offkit.engine.PdfRender
import app.venkat.offkit.engine.RenderRequest
import app.venkat.offkit.feature.reader.ItemRect
import app.venkat.offkit.feature.reader.ReaderDocState
import app.venkat.offkit.feature.reader.ReaderLimits
import app.venkat.offkit.feature.reader.ReaderPlanner
import app.venkat.offkit.feature.reader.ReaderViewModel
import app.venkat.offkit.feature.reader.VisiblePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderViewModelTest {
    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine
    private lateinit var engineDispatcher: ExecutorCoroutineDispatcher
    private lateinit var reader: PdfReader
    private val store = ViewModelStore()

    /** Every render's control and whether it was cancelled when the engine returned, and the engine thread names. */
    private val renderJobs = CopyOnWriteArrayList<Pair<JobControl, Boolean>>()
    private val engineThreads = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = object : PdfEngine by fake, PdfPageRenderer by fake {
            override fun pageInfo(pdf: File, job: JobControl): PdfPageInfo {
                engineThreads += Thread.currentThread().name.substringBefore(" @")
                return fake.pageInfo(pdf, job)
            }

            override fun pageText(pdf: File, page: Int, out: File, job: JobControl): PdfPageText {
                engineThreads += Thread.currentThread().name.substringBefore(" @")
                textError?.let { return PdfPageText.Err(it) }
                return fake.pageText(pdf, page, out, job)
            }

            override fun renderPage(pdf: File, request: RenderRequest, out: File, job: JobControl): PdfRender {
                engineThreads += Thread.currentThread().name.substringBefore(" @")
                val r = fake.renderPage(pdf, request, out, job)
                renderJobs += job to job.isCancelled()
                return r
            }
        }
        engineDispatcher = Executors.newSingleThreadExecutor { Thread(it, ENGINE_THREAD) }.asCoroutineDispatcher()
        reader = PdfReader(app, PdfJobs(app, engineDispatcher, Dispatchers.IO, Dispatchers.Main))
    }

    @After
    fun tearDown() {
        fake.beforeRender = null
        store.clear()
        engineDispatcher.close()
    }

    /** Fails every page-text call when set. */
    @Volatile private var textError: PdfError? = null

    private fun newVm(limits: ReaderLimits = ReaderLimits(), textLimits: ReaderTextLimits = ReaderTextLimits()): ReaderViewModel =
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(app, reader, limits, textLimits) as T
            },
        )["reader-${System.nanoTime()}", ReaderViewModel::class.java]

    private fun pdf(name: String = "book.pdf") = CoreTestSupport.contentUri(app, name, "%PDF-1.7".toByteArray())

    private fun readerDirs() = File(app.cacheDir, "jobs").listFiles().orEmpty().filter { it.name.startsWith("reader-") }

    private fun openVm(vm: ReaderViewModel, name: String = "book.pdf"): ReaderDocState.Open {
        vm.open(pdf(name))
        CoreTestSupport.idleUntil(message = "open") { vm.document.value !is ReaderDocState.Opening }
        return vm.document.value as ReaderDocState.Open
    }

    private fun band(page: Int, width: Int = WIDTH): VisiblePage =
        VisiblePage(page, ItemRect(0f, 0f, width.toFloat(), ReaderPlanner.itemHeightPx(fake.readerPages[page], width).toFloat()))

    private fun renders() = fake.readerCalls.mapNotNull { it.request }

    @Test
    fun openReportsNameAndPagesThenCloseAndReopenDeleteTheCopy() {
        val vm = newVm()
        fake.readerPages = List(4) { PageDims(595f, 842f) }
        val open = openVm(vm, "Notes.pdf")
        assertEquals(ReaderDocState.Open("Notes.pdf", fake.readerPages), open)
        val first = readerDirs().single()
        assertTrue(File(first, "document.pdf").isFile)

        openVm(vm, "Other.pdf")
        CoreTestSupport.idleUntil(message = "previous dir deleted") { !first.exists() }
        val second = readerDirs().single()

        vm.close()
        assertEquals(ReaderDocState.Idle, vm.document.value)
        CoreTestSupport.idleUntil(message = "dir deleted on close") { !second.exists() }

        openVm(vm)
        val third = readerDirs().single()
        store.clear() // onCleared
        CoreTestSupport.idleUntil(message = "dir deleted on clear") { !third.exists() }
        assertTrue(engineThreads.all { it == ENGINE_THREAD })
    }

    @Test
    fun encryptedOrCorruptFailsAndLeavesNothing() {
        for (error in listOf(PdfError.Encrypted, PdfError.Corrupt)) {
            val vm = newVm()
            fake.readerError = error
            vm.open(pdf())
            CoreTestSupport.idleUntil { vm.document.value is ReaderDocState.Failed }
            assertEquals(ReaderDocState.Failed(error), vm.document.value)
            assertTrue(readerDirs().isEmpty())
        }
    }

    @Test
    fun unreadableUriIsIo() {
        val vm = newVm()
        vm.open(android.net.Uri.parse("content://app.venkat.offkit.test/missing/x.pdf"))
        CoreTestSupport.idleUntil { vm.document.value is ReaderDocState.Failed }
        assertTrue((vm.document.value as ReaderDocState.Failed).error is PdfError.Io)
        assertTrue(readerDirs().isEmpty())
    }

    @Test
    fun visiblePagesGetBaseThenDetailWhenZoomedAndDetailDropsAtZoomOne() {
        fake.readerPages = List(6) { PageDims(595f, 842f) }
        val vm = newVm()
        openVm(vm)
        vm.onViewport(WIDTH, 1f, listOf(band(2)))
        CoreTestSupport.idleUntil(message = "base 2") { vm.page(2).value.base != null }
        assertEquals(RenderRequest(2, ReaderPlanner.basePlan(2, fake.readerPages[2], WIDTH, ReaderLimits()).request.scale, 0, 0, WIDTH, 1529), renders().first())
        assertNull(vm.page(2).value.detail)
        assertSame(vm.page(2), vm.page(2))

        val visible = ItemRect(200f, 300f, 200f + WIDTH / 2.5f, 300f + 700f)
        vm.onViewport(WIDTH, 2.5f, listOf(VisiblePage(2, visible)))
        CoreTestSupport.idleUntil(message = "detail 2") { vm.page(2).value.detail != null }
        val detail = vm.page(2).value.detail!!
        assertTrue(detail.rect.contains(visible))
        assertEquals(detail.bitmap.width, renders().last { it.page == 2 && it.left > 0 }.width)
        assertNotNull(vm.page(2).value.base)

        vm.onViewport(WIDTH, 1f, listOf(band(2)))
        assertNull(vm.page(2).value.detail)
        assertNotNull(vm.page(2).value.base)
    }

    @Test
    fun staleViewportsAreCoalescedAndTheInFlightRenderIsNotCancelled() {
        fake.readerPages = List(200) { PageDims(595f, 842f) }
        val vm = newVm()
        openVm(vm)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeRender = { req -> if (req.page == 0) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
        vm.onViewport(WIDTH, 1f, listOf(band(0)))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        for (page in listOf(20, 40, 60, 80, 100)) {
            vm.onViewport(WIDTH, 1f, listOf(band(page)))
            CoreTestSupport.idleUntil { true }
        }
        assertEquals(1, renders().size)
        release.countDown()
        CoreTestSupport.idleUntil(message = "latest rendered") { (98..102).all { vm.page(it).value.base != null } }
        CoreTestSupport.idleUntil { true }
        val after = renders().drop(1)
        assertEquals(listOf(100, 101, 99, 102, 98), after.map { it.page })
        // The stale render ran to completion (never asked to stop) and its result was dropped.
        assertFalse(renderJobs.first().second)
        assertFalse(renderJobs.first().first.isCancelled())
        assertNull(vm.page(0).value.base)
        assertTrue(engineThreads.all { it == ENGINE_THREAD })
    }

    @Test
    fun lruStaysWithinBudgetAndNeverEvictsVisiblePages() {
        fake.readerPages = List(200) { i -> if (i % 7 == 3) PageDims(842f, 595f) else PageDims(595f, 842f) }
        val width = 100
        val onePage = ReaderPlanner.basePlan(0, fake.readerPages[0], width, ReaderLimits()).bytes
        val limits = ReaderLimits(baseCacheBytes = onePage * 5)
        val vm = newVm(limits)
        val open = openVm(vm)
        for (top in 0 until 199) {
            val visible = listOf(band(top, width), band(top + 1, width))
            vm.onViewport(width, 1f, visible)
            CoreTestSupport.idleUntil(message = "visible $top") { visible.all { vm.page(it.index).value.base != null } }
            val total = open.pages.indices.sumOf { i -> vm.page(i).value.base?.let { it.width.toLong() * it.height * 4 } ?: 0L }
            assertTrue("total $total at $top", total <= limits.baseCacheBytes)
        }
        // Each page rendered about once: no render-evict loop.
        assertTrue(renders().size.toString(), renders().size < 230)
    }

    @Test
    fun failedPageShowsErrorIsNotRetriedAutomaticallyAndRetryWorks() {
        fake.readerPages = List(5) { PageDims(595f, 842f) }
        val vm = newVm()
        openVm(vm)
        fake.beforeRender = { req -> fake.readerError = if (req.page == 1) PdfError.EngineCrashed else null }
        val visible = listOf(band(0), band(1), band(2))
        vm.onViewport(WIDTH, 1f, visible)
        CoreTestSupport.idleUntil(message = "pages") {
            vm.page(0).value.base != null && vm.page(2).value.base != null && vm.page(1).value.error != null
        }
        assertEquals(PdfError.EngineCrashed, vm.page(1).value.error)
        assertNull(vm.page(1).value.base)
        repeat(5) { vm.onViewport(WIDTH, if (it % 2 == 0) 1f else 2f, visible) }
        CoreTestSupport.idleUntil { true }
        Thread.sleep(100)
        CoreTestSupport.idleUntil { true }
        assertEquals(1, renders().count { it.page == 1 })
        assertTrue(vm.document.value is ReaderDocState.Open)

        fake.beforeRender = null
        fake.readerError = null
        vm.onViewport(WIDTH, 1f, visible)
        vm.retry(1)
        CoreTestSupport.idleUntil(message = "retried") { vm.page(1).value.base != null }
        assertNull(vm.page(1).value.error)
        assertEquals(2, renders().count { it.page == 1 })
    }

    @Test
    fun visiblePagesAreKeptEvenBeyondTheBudget() {
        fake.readerPages = List(10) { PageDims(595f, 842f) }
        val onePage = ReaderPlanner.basePlan(0, fake.readerPages[0], WIDTH, ReaderLimits()).bytes
        val vm = newVm(ReaderLimits(baseCacheBytes = onePage))
        openVm(vm)
        vm.onViewport(WIDTH, 1f, listOf(band(4), band(5)))
        CoreTestSupport.idleUntil(message = "both visible") { vm.page(4).value.base != null && vm.page(5).value.base != null }
        Thread.sleep(50)
        CoreTestSupport.idleUntil { true }
        assertNotNull(vm.page(4).value.base)
        assertNotNull(vm.page(5).value.base)
        assertEquals(setOf(4, 5), renders().map { it.page }.toSet())
    }

    @Test
    fun closingWhileOpeningLeavesNoFiles() {
        val vm = newVm()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : PdfEngine by fake, PdfPageRenderer by fake {
            override fun pageInfo(pdf: File, job: JobControl): PdfPageInfo {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return fake.pageInfo(pdf, job)
            }
        }
        (app as PdfUtilApp).engine = blocking
        vm.open(pdf())
        CoreTestSupport.idleUntil(message = "pageInfo entered") { entered.count == 0L }
        assertEquals(1, readerDirs().size)
        vm.close()
        release.countDown()
        CoreTestSupport.idleUntil(message = "no reader dir") { readerDirs().isEmpty() }
        assertEquals(ReaderDocState.Idle, vm.document.value)
    }

    private fun events(vm: ReaderViewModel): Pair<MutableList<ReaderTextEvent>, () -> Unit> {
        val list = CopyOnWriteArrayList<ReaderTextEvent>()
        val scope = MainScope()
        vm.textEvents.onEach { list += it }.launchIn(scope)
        return list to { scope.cancel() }
    }

    private fun textCallsFor(page: Int) = fake.textCalls.count { it.second == page }

    @Test
    fun textIsFetchedOnTheFirstLongPressThenCachedPerPage() {
        val vm = newVm()
        fake.readerPages = List(3) { PageDims(595f, 842f) }
        fake.readerTexts[1] = TestPageTexts.grid("Hello world", "Second line")
        openVm(vm)
        vm.onViewport(WIDTH, 1f, listOf(band(0), band(1)))
        CoreTestSupport.idleUntil(message = "rendered") { vm.page(1).value.base != null }
        assertTrue("no text fetched before a long-press", fake.textCalls.isEmpty())

        val (x, y) = TestPageTexts.centre(0, 8)
        vm.longPress(1, x, y, 5f)
        CoreTestSupport.idleUntil(message = "selected") { vm.selection.value != null }
        assertEquals("world", vm.selection.value!!.string())
        assertEquals(1, vm.selection.value!!.page)
        val (x2, y2) = TestPageTexts.centre(1, 0)
        vm.longPress(1, x2, y2, 5f)
        CoreTestSupport.idleUntil(message = "second word") { vm.selection.value?.string() == "Second" }
        assertEquals("one text call per page", 1, textCallsFor(1))

        vm.selectAll()
        assertEquals("Hello world\nSecond line", vm.selection.value!!.string())
        val (events, stop) = events(vm)
        vm.copySelection()
        CoreTestSupport.idleUntil(message = "copy event") { events.isNotEmpty() }
        assertEquals(ReaderTextEvent.Copy("Hello world\nSecond line", cut = false), events.single())
        assertNull("copy clears", vm.selection.value)
        stop()
        assertTrue(engineThreads.all { it == ENGINE_THREAD })
    }

    @Test
    fun aMissAndAnEmptyPageAndAFailedReadDoNotSelect() {
        val vm = newVm()
        fake.readerPages = List(3) { PageDims(595f, 842f) }
        fake.readerTexts[0] = TestPageTexts.grid("Hello")
        openVm(vm)
        val (events, stop) = events(vm)
        vm.longPress(0, 10f, 10f, 5f) // far from the text
        CoreTestSupport.idleUntil(message = "fetched") { fake.textCalls.size == 1 }
        CoreTestSupport.idleUntil { true }
        assertNull(vm.selection.value)
        assertTrue("a miss on a page with text says nothing", events.isEmpty())

        vm.longPress(1, 10f, 10f, 5f) // a page with no text at all
        CoreTestSupport.idleUntil(message = "no text") { events.isNotEmpty() }
        assertEquals(ReaderTextEvent.NoText, events.single())

        textError = PdfError.EngineCrashed
        vm.longPress(2, 10f, 10f, 5f)
        CoreTestSupport.idleUntil(message = "failed") { events.size == 2 }
        assertEquals(ReaderTextEvent.Failed(PdfError.EngineCrashed), events[1])
        assertNull(vm.selection.value)
        assertTrue("the document stays open", vm.document.value is ReaderDocState.Open)

        // A failure is not cached: the next try reads again.
        textError = null
        vm.copyPageText(2)
        CoreTestSupport.idleUntil(message = "retried") { events.size == 3 }
        assertEquals("read again (the failed call was answered before the fake)", 1, textCallsFor(2))
        assertEquals(ReaderTextEvent.NoText, events[2])
        stop()
    }

    @Test
    fun theTextCacheIsAnLruBoundedByPagesAndCharacters() {
        val vm = newVm(textLimits = ReaderTextLimits(pages = 2, chars = 1000))
        fake.readerPages = List(4) { PageDims(595f, 842f) }
        for (p in 0 until 4) fake.readerTexts[p] = TestPageTexts.grid("Page $p")
        openVm(vm)
        val (events, stop) = events(vm)
        for (p in listOf(0, 1, 0, 2)) {
            val before = events.size
            vm.copyPageText(p)
            CoreTestSupport.idleUntil(message = "copy $p") { events.size == before + 1 }
        }
        assertEquals(2, vm.cachedTextPagesForTest())
        assertEquals(listOf(1, 1, 1), listOf(0, 1, 2).map { textCallsFor(it) })
        // Page 1 was the least recently used: it went; page 0 stayed.
        vm.copyPageText(0)
        vm.copyPageText(1)
        CoreTestSupport.idleUntil(message = "copies") { events.size == 6 }
        assertEquals(1, textCallsFor(0))
        assertEquals(2, textCallsFor(1))
        assertEquals(ReaderTextEvent.Copy("Page 1", cut = false), events.last())

        // Bounded by characters too (a page being selected is kept even if it alone is over).
        val small = newVm(textLimits = ReaderTextLimits(pages = 8, chars = 7))
        openVm(small)
        val (ev2, stop2) = events(small)
        small.copyPageText(0)
        small.copyPageText(1)
        CoreTestSupport.idleUntil(message = "small copies") { ev2.size == 2 }
        assertEquals(1, small.cachedTextPagesForTest())
        stop()
        stop2()
    }

    @Test
    fun aTextCallDoesNotCancelARenderAndTheSelectionEndsWithTheDocument() {
        val vm = newVm()
        fake.readerPages = List(10) { PageDims(595f, 842f) }
        fake.readerTexts[0] = TestPageTexts.grid("Hello world")
        openVm(vm)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeRender = { req -> if (req.page == 0) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
        vm.onViewport(WIDTH, 1f, listOf(band(0)))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val (x, y) = TestPageTexts.centre(0, 1)
        vm.longPress(0, x, y, 5f)
        CoreTestSupport.idleUntil { true }
        assertNull("text waits behind the render on the engine thread", vm.selection.value)
        release.countDown()
        CoreTestSupport.idleUntil(message = "selected") { vm.selection.value != null }
        CoreTestSupport.idleUntil(message = "rendered") { vm.page(0).value.base != null }
        assertTrue("no render was cancelled", renderJobs.none { it.second })
        assertEquals("Hello", vm.selection.value!!.string())

        // Scrolling far away clears it; a near scroll keeps it.
        vm.onViewport(WIDTH, 1f, listOf(band(2)))
        assertNotNull(vm.selection.value)
        vm.onViewport(WIDTH, 1f, listOf(band(3)))
        assertNull(vm.selection.value)

        vm.onViewport(WIDTH, 1f, listOf(band(0)))
        vm.longPress(0, x, y, 5f)
        CoreTestSupport.idleUntil(message = "cached select") { vm.selection.value != null }
        assertEquals(1, fake.textCalls.size)
        openVm(vm, "other.pdf")
        assertNull("another document clears the selection", vm.selection.value)
        assertEquals(0, vm.cachedTextPagesForTest())
    }

    private companion object {
        const val WIDTH = 1080
        const val ENGINE_THREAD = "test-engine"
    }
}
