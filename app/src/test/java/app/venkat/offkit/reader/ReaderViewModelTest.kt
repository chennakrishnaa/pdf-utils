package app.venkat.offkit.reader

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.PdfUtilApp
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

    private fun newVm(limits: ReaderLimits = ReaderLimits()): ReaderViewModel =
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(app, reader, limits) as T
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

    private companion object {
        const val WIDTH = 1080
        const val ENGINE_THREAD = "test-engine"
    }
}
