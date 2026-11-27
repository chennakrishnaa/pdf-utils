package app.venkat.offkit.reader

import app.venkat.offkit.feature.reader.TAG_PASSWORD_CANCEL
import app.venkat.offkit.feature.reader.TAG_PASSWORD_DIALOG
import app.venkat.offkit.feature.reader.TAG_PASSWORD_FIELD
import app.venkat.offkit.feature.reader.TAG_PASSWORD_NAME
import app.venkat.offkit.feature.reader.TAG_PASSWORD_PROBLEM
import app.venkat.offkit.feature.reader.TAG_PASSWORD_TOGGLE
import app.venkat.offkit.feature.reader.TAG_PASSWORD_UNLOCK
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.os.Parcel
import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.swipe
import app.venkat.offkit.TestPageTexts
import app.venkat.offkit.engine.PageTextFormat
import app.venkat.offkit.feature.reader.SelectionMath
import app.venkat.offkit.feature.reader.TAG_READER_COPY
import app.venkat.offkit.feature.reader.TAG_READER_HANDLE_ANCHOR
import app.venkat.offkit.feature.reader.TAG_READER_HANDLE_FOCUS
import app.venkat.offkit.feature.reader.TAG_READER_SELECTION_BAR
import app.venkat.offkit.feature.reader.TAG_READER_SELECTION_CLOSE
import app.venkat.offkit.feature.reader.TAG_READER_SELECT_ALL
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import app.venkat.offkit.feature.reader.TAG_GO_TO_PAGE_CANCEL
import app.venkat.offkit.feature.reader.TAG_GO_TO_PAGE_FIELD
import app.venkat.offkit.feature.reader.TAG_GO_TO_PAGE_GO
import app.venkat.offkit.feature.reader.TAG_GO_TO_PAGE_HINT
import app.venkat.offkit.feature.reader.TAG_READER_GO_TO_PAGE
import app.venkat.offkit.feature.reader.TAG_READER_SAVE_COPY
import app.venkat.offkit.feature.reader.TAG_READER_COMPRESS
import app.venkat.offkit.feature.reader.TAG_READER_MENU
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import app.venkat.offkit.FakePdfEngine
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.PdfUtilApp
import app.venkat.offkit.R
import app.venkat.offkit.core.CoreTestSupport
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.RenderLimits
import app.venkat.offkit.feature.reader.ReaderPageDetailShown
import app.venkat.offkit.feature.reader.ReaderPageRendered
import app.venkat.offkit.feature.reader.ReaderPlanner
import app.venkat.offkit.feature.reader.TAG_READER_INDICATOR
import app.venkat.offkit.feature.reader.ReaderPageSearchHits
import app.venkat.offkit.feature.reader.TAG_READER_SEARCH
import app.venkat.offkit.feature.reader.TAG_READER_SEARCH_BAR
import app.venkat.offkit.feature.reader.TAG_READER_SEARCH_CLOSE
import app.venkat.offkit.feature.reader.TAG_READER_SEARCH_COUNT
import app.venkat.offkit.feature.reader.TAG_READER_SEARCH_FIELD
import app.venkat.offkit.feature.reader.TAG_READER_SEARCH_NEXT
import app.venkat.offkit.feature.reader.TAG_READER_SEARCH_PREVIOUS
import app.venkat.offkit.feature.reader.TAG_READER_SEARCH_SKIPPED
import app.venkat.offkit.engine.JobControl
import app.venkat.offkit.engine.PdfEngine
import app.venkat.offkit.engine.PdfPageRenderer
import app.venkat.offkit.engine.PdfPageText
import app.venkat.offkit.feature.reader.TAG_READER_LIST
import app.venkat.offkit.feature.reader.TAG_READER_OPEN_ANOTHER
import app.venkat.offkit.feature.reader.TAG_READER_PRINT
import app.venkat.offkit.feature.reader.TAG_READER_SHARE
import app.venkat.offkit.feature.reader.TAG_READER_TITLE
import app.venkat.offkit.feature.reader.VIEWPORT_SETTLE_MS
import app.venkat.offkit.feature.reader.readerPageReasonTag
import app.venkat.offkit.feature.reader.readerPageRetryTag
import app.venkat.offkit.feature.reader.readerPageTag
import app.venkat.offkit.ui.components.MIME_PDF
import app.venkat.offkit.ui.components.TAG_ACTION_BACK
import app.venkat.offkit.ui.components.TAG_JOB_RESET
import app.venkat.offkit.ui.components.pdfErrorTitle
import app.venkat.offkit.ui.home.TAG_TOOL_READ
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The reader screen through the real activity and navigation, on [FakePdfEngine], with the picker faked by Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderScreenTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: Application
    private lateinit var fake: FakePdfEngine

    private val a4 = PageDims(595f, 842f)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fake = FakePdfEngine()
        (app as PdfUtilApp).engine = fake
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun pdf(name: String): Uri = CoreTestSupport.contentUri(app, name, "%PDF-1.7\n%fake\n".toByteArray())

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = CoreTestSupport.idleUntil(rule, message = tag) { exists(tag) }

    private fun waitUntil(message: String, condition: () -> Boolean) = CoreTestSupport.idleUntil(rule, message = message, condition = condition)

    private fun shadow() = shadowOf(rule.activity)

    /** Asserts exactly one picker for one PDF was launched since the last call, and returns it. */
    private fun takePdfPicker(): ShadowActivity.IntentForResult {
        val forResult = shadow().nextStartedActivityForResult
        assertNotNull("no picker was launched", forResult)
        val intent = forResult!!.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertArrayEquals(arrayOf(MIME_PDF), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertNull("a second picker was launched", shadow().nextStartedActivityForResult)
        // The same launch, as a plain started activity; nothing else was started.
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadow().nextStartedActivity?.action)
        assertNull(shadow().nextStartedActivity)
        return forResult
    }

    private fun assertNoPicker() {
        assertNull("a picker was launched", shadow().nextStartedActivityForResult)
    }

    private fun answer(picker: ShadowActivity.IntentForResult, uri: Uri?) {
        rule.runOnUiThread {
            if (uri == null) {
                shadow().receiveResult(picker.intent, Activity.RESULT_CANCELED, null)
            } else {
                shadow().receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        rule.waitForIdle()
    }

    private fun enterReader(): ShadowActivity.IntentForResult {
        rule.onNodeWithTag(TAG_TOOL_READ).performScrollTo().performClick()
        rule.waitForIdle()
        return takePdfPicker()
    }

    /** Enters the reader, picks [name] and waits until its first page is drawn. */
    private fun openDocument(name: String = "report.pdf") {
        answer(enterReader(), pdf(name))
        waitUntilRendered(0)
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals(name)
    }

    /** Matches the item of page [index] whose base bitmap is (or, with [value] false, is not) drawn. */
    private fun rendered(index: Int, value: Boolean = true) =
        hasTestTag(readerPageTag(index)) and SemanticsMatcher.expectValue(ReaderPageRendered, value)

    private fun isRendered(index: Int): Boolean =
        rule.onAllNodesWithTag(readerPageTag(index)).fetchSemanticsNodes()
            .any { it.config.getOrElseNullable(ReaderPageRendered) { null } == true }

    private fun detailShown(index: Int): Boolean =
        rule.onAllNodesWithTag(readerPageTag(index)).fetchSemanticsNodes()
            .any { it.config.getOrElseNullable(ReaderPageDetailShown) { null } == true }

    private fun waitUntilRendered(index: Int) = waitUntil("page $index rendered") { isRendered(index) }

    private fun renderRequests() = fake.readerCalls.mapNotNull { it.request }

    private fun indicator(page: Int, count: Int) = str(R.string.reader_page_indicator, page, count)

    private fun openMenu() {
        rule.onNodeWithTag(TAG_READER_MENU).performClick()
        rule.waitForIdle()
    }

    /** "Open another PDF", from the overflow menu. */
    private fun openAnother() {
        openMenu()
        rule.onNodeWithTag(TAG_READER_OPEN_ANOTHER).performClick()
        rule.waitForIdle()
    }

    private fun readerDirs(): List<File> =
        File(app.cacheDir, "jobs").listFiles().orEmpty().filter { it.name.startsWith(PdfReader.READER_DIR_PREFIX) }

    @Test
    fun homeShowsReadCardThatOpensOnePdfPicker() {
        rule.onNodeWithTag(TAG_TOOL_READ).performScrollTo().assertIsDisplayed()
        enterReader()
        rule.onNodeWithTag(TAG_ACTION_BACK).assertIsDisplayed()
        // Recomposition does not launch it again.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertNoPicker()
    }

    @Test
    fun cancelledFirstPickerReturnsHome() {
        val picker = enterReader()
        answer(picker, null)
        waitFor(TAG_TOOL_READ)
        assertTrue(!exists(TAG_READER_TITLE))
        assertNoPicker()
    }

    @Test
    fun cancelledFirstPickerReturnsHomeEvenWhenTheResultArrivesBeforeResume() {
        val picker = enterReader()
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        answer(picker, null)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        waitFor(TAG_TOOL_READ)
        assertTrue(!exists(TAG_READER_TITLE))
    }

    @Test
    fun pickedPdfShowsNameIndicatorAndPagesWithTheirAspectRatio() {
        fake.readerPages = List(12) { if (it == 1) PageDims(842f, 595f) else a4 }
        openDocument("report.pdf")
        rule.onNodeWithTag(TAG_READER_INDICATOR).assertTextEquals(indicator(1, 12))
        for (index in 0..1) {
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
            rule.waitForIdle()
            val bounds = rule.onNodeWithTag(readerPageTag(index)).fetchSemanticsNode().size
            val dims = fake.readerPages[index]
            assertTrue("page $index is laid out", bounds.width > 0)
            assertEquals(ReaderPlanner.itemHeightPx(dims, bounds.width), bounds.height)
        }

        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_READER_INDICATOR).assertTextEquals(indicator(1, 12))
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(6)
        rule.waitForIdle()
        waitUntil("indicator moved") {
            rule.onNodeWithTag(TAG_READER_INDICATOR).fetchSemanticsNode()
                .config[SemanticsProperties.Text].joinToString() == indicator(7, 12)
        }
        // The pages scrolled to are rendered too.
        waitUntilRendered(6)
    }

    @Test
    fun visiblePagesAreRenderedAtTheFitWidth() {
        openDocument()
        val width = rule.onNodeWithTag(readerPageTag(0)).fetchSemanticsNode().size.width
        val first = renderRequests().first { it.page == 0 }
        val base = ReaderPlanner.baseRequest(0, a4, width, 2_500_000)
        assertEquals(base, first)
        rule.onNodeWithTag(readerPageTag(0)).assert(rendered(0))
    }

    @Test
    fun pinchRendersASharpDetailAndZoomingBackStopsDetails() {
        openDocument()
        val list = rule.onNodeWithTag(TAG_READER_LIST).fetchSemanticsNode().size
        val c = Offset(list.width / 2f, list.height / 3f)
        val base = renderRequests().first { it.page == 0 }
        rule.onNodeWithTag(TAG_READER_LIST).performTouchInput {
            pinch(c - Offset(30f, 0f), c - Offset(100f, 0f), c + Offset(30f, 0f), c + Offset(100f, 0f))
        }
        rule.waitForIdle()
        waitUntil("detail of page 0") { detailShown(0) }
        val detail = renderRequests().last { it.page == 0 && it.scale > base.scale }
        assertTrue("detail clip ${detail.width}x${detail.height} is smaller than the page", detail.width < 0.9 * base.width * detail.scale / base.scale || detail.height < 0.9 * base.height * detail.scale / base.scale)

        rule.onNodeWithTag(TAG_READER_LIST).performTouchInput {
            pinch(c - Offset(150f, 0f), c - Offset(10f, 0f), c + Offset(150f, 0f), c + Offset(10f, 0f))
        }
        rule.waitForIdle()
        waitUntil("details dropped at zoom 1") { !detailShown(0) }
        val before = renderRequests().size
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        CoreTestSupport.idleUntil(rule, message = "settle") { true }
        val after = renderRequests().drop(before)
        assertTrue("detail requested at zoom 1: $after", after.none { it.scale > base.scale * 1.01f })
    }

    /**
     * RDR-1: hostile page sizes (a 10 x 14,400 pt scroll, a 14,400 x 10 pt strip, 1 x 100,000 pt,
     * Float.MAX-ish sides) open, lay out in bounded items and get rendered, without crashing.
     */
    @Test
    fun extremePageSizesOpenAndRenderWithoutCrashing() {
        fake.readerPages = listOf(
            PageDims(10f, 14_400f),
            PageDims(14_400f, 10f),
            PageDims(1f, 100_000f),
            PageDims(Float.MAX_VALUE / 2, 10f),
            PageDims(10f, Float.MAX_VALUE / 2),
            PageDims(Float.MAX_VALUE, Float.MAX_VALUE),
            a4,
        )
        openDocument("scroll.pdf")
        rule.onNodeWithTag(TAG_READER_INDICATOR).assertTextEquals(indicator(1, fake.readerPages.size))
        for (index in fake.readerPages.indices) {
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
            rule.waitForIdle()
            waitUntilRendered(index)
            val size = rule.onNodeWithTag(readerPageTag(index)).fetchSemanticsNode().size
            assertTrue("page $index height ${size.height}", size.height in 1..ReaderPlanner.MAX_ITEM_HEIGHT_PX)
            assertTrue("page $index width ${size.width}", size.width >= 1)
        }
        for (request in renderRequests()) {
            val dims = PdfReader.sanePageDims(fake.readerPages[request.page])!!
            assertNull(RenderLimits.check(request, dims))
            assertNull(RenderLimits.check(request, fake.readerPages[request.page]))
        }
    }

    /** RDR-4: a one-finger pan while zoomed sends no detail request until it has stopped for a while. */
    @Test
    fun zoomedPanRequestsNoDetailUntilItStops() {
        openDocument()
        val list = rule.onNodeWithTag(TAG_READER_LIST).fetchSemanticsNode().size
        val c = Offset(list.width / 2f, list.height / 3f)
        val base = renderRequests().first { it.page == 0 }
        rule.onNodeWithTag(TAG_READER_LIST).performTouchInput {
            pinch(c - Offset(30f, 0f), c - Offset(100f, 0f), c + Offset(30f, 0f), c + Offset(100f, 0f))
        }
        rule.waitForIdle()
        waitUntil("detail of page 0") { detailShown(0) }
        fun details() = renderRequests().count { it.scale > base.scale * 1.01f }
        val before = details()

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(TAG_READER_LIST).performTouchInput { down(c) }
        repeat(30) {
            rule.onNodeWithTag(TAG_READER_LIST).performTouchInput { moveBy(Offset(-6f, 0f)) }
            rule.mainClock.advanceTimeBy(16)
        }
        // Let anything that was sent reach the engine thread.
        repeat(10) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals("detail requested mid-pan", before, details())

        // The finger rests: after the settle time exactly one detail, for the new position, is requested.
        rule.mainClock.advanceTimeBy(VIEWPORT_SETTLE_MS + 50)
        CoreTestSupport.idleUntil(message = "detail after pan") { details() > before }
        rule.onNodeWithTag(TAG_READER_LIST).performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        waitUntil("new detail shown") { detailShown(0) }
        assertEquals(before + 1, details())
    }

    @Test
    fun accessibilityZoomActionStillWorksOnTheReaderList() {
        openDocument()
        val actions = rule.onNodeWithTag(TAG_READER_LIST).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label }
        assertTrue(actions.contains(str(R.string.reader_zoom_in)))
    }

    private fun assertOpenErrorScreen(error: PdfError) {
        fake.readerError = error
        answer(enterReader(), pdf("locked.pdf"))
        waitUntil("error screen") { rule.onAllNodesWithTag(TAG_JOB_RESET).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(str(pdfErrorTitle(error))).assertIsDisplayed()
        rule.onNodeWithTag(TAG_JOB_RESET).assertTextEquals(str(R.string.action_choose_another)).performClick()
        rule.waitForIdle()
        val picker = takePdfPicker()
        fake.readerError = null
        answer(picker, pdf("fine.pdf"))
        waitUntilRendered(0)
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals("fine.pdf")
    }

    @Test
    fun corruptPdfShowsDamagedErrorAndCanChooseAnother() = assertOpenErrorScreen(PdfError.Corrupt)

    @Test
    fun failedPageShowsRetryThatRendersItAgain() {
        val failPage0 = AtomicBoolean(true)
        fake.beforeRender = { request -> fake.readerError = if (failPage0.get() && request.page == 0) PdfError.Corrupt else null }
        answer(enterReader(), pdf("report.pdf"))
        waitFor(readerPageRetryTag(0))
        assertTrue(!isRendered(0))
        rule.onNodeWithText(str(R.string.reader_page_error)).assertExists()
        rule.onNodeWithTag(readerPageReasonTag(0)).assertTextEquals(str(pdfErrorTitle(PdfError.Corrupt)))
        val before = renderRequests().count { it.page == 0 }
        failPage0.set(false)
        rule.onNodeWithTag(readerPageRetryTag(0)).performClick()
        rule.waitForIdle()
        waitUntilRendered(0)
        assertTrue(renderRequests().count { it.page == 0 } > before)
        assertTrue(!exists(readerPageRetryTag(0)))
    }

    @Test
    fun pageThatRunsTheEngineOutOfMemoryOrTimeSaysItIsTooComplex() {
        for (error in listOf(PdfError.OutOfMemory, PdfError.TimedOut)) {
            fake.beforeRender = { request -> fake.readerError = if (request.page == 0) error else null }
            answer(enterReader(), pdf("big.pdf"))
            waitFor(readerPageRetryTag(0))
            rule.onNodeWithText(str(R.string.reader_page_too_complex)).assertExists()
            assertTrue(!exists(readerPageReasonTag(0)))
            rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
            rule.waitForIdle()
            waitFor(TAG_TOOL_READ)
        }
    }

    @Test
    fun openAnotherReplacesTheDocumentAndCancellingKeepsIt() {
        openDocument("first.pdf")
        openAnother()
        answer(takePdfPicker(), pdf("second.pdf"))
        waitUntil("second open") {
            rule.onAllNodesWithTag(TAG_READER_TITLE).fetchSemanticsNodes()
                .any { it.config[SemanticsProperties.Text].joinToString() == "second.pdf" }
        }
        waitUntilRendered(0)

        openAnother()
        answer(takePdfPicker(), null)
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals("second.pdf")
        rule.onNodeWithTag(readerPageTag(0)).assert(rendered(0))
        waitUntil("only the current copy is kept") { readerDirs().size == 1 }
    }

    @Test
    fun reopeningTheSameFileShowsItAgain() {
        openDocument("same.pdf")
        openAnother()
        answer(takePdfPicker(), pdf("same.pdf"))
        waitUntilRendered(0)
        rule.onNodeWithTag(TAG_READER_INDICATOR).assertTextEquals(indicator(1, 3))
    }

    @Test
    fun recreationKeepsTheDocumentWithoutAnotherPicker() {
        fake.readerPages = List(12) { a4 }
        openDocument("report.pdf")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        rule.waitForIdle()
        val page = rule.onNodeWithTag(TAG_READER_INDICATOR).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        waitFor(TAG_READER_TITLE)
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals("report.pdf")
        rule.onNodeWithTag(TAG_READER_INDICATOR).assertTextEquals(page)
        assertNoPicker()
        assertNull(shadow().nextStartedActivity)
    }

    @Test
    fun backReturnsHomeAndDeletesTheReaderCopy() {
        openDocument()
        assertEquals(1, readerDirs().size)
        rule.onNodeWithTag(TAG_ACTION_BACK).performClick()
        rule.waitForIdle()
        waitFor(TAG_TOOL_READ)
        waitUntil("reader copy deleted") { readerDirs().isEmpty() }
    }

    @Test
    fun talkBackLabels() {
        openDocument()
        rule.onNodeWithTag(TAG_ACTION_BACK).assertContentDescriptionEquals(str(R.string.action_back))
        rule.onNodeWithTag(TAG_READER_MENU).assertContentDescriptionEquals(str(R.string.reader_more_cd))
        rule.onNodeWithTag(TAG_READER_INDICATOR)
            .assertTextEquals(indicator(1, 3))
            .assertContentDescriptionEquals(str(R.string.reader_page_cd, 1, 3))
        rule.onNodeWithTag(readerPageTag(0)).assertContentDescriptionEquals(str(R.string.reader_page_cd, 1, 3))
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        rule.waitForIdle()
        rule.onNodeWithTag(readerPageTag(1)).assertContentDescriptionEquals(str(R.string.reader_page_cd, 2, 3))
        // The page indicator is a button that opens Go to page.
        val indicatorNode = rule.onNodeWithTag(TAG_READER_INDICATOR).fetchSemanticsNode()
        assertEquals(Role.Button, indicatorNode.config[SemanticsProperties.Role])
        assertEquals(str(R.string.reader_go_to_page), indicatorNode.config[SemanticsActions.OnClick].label)
        openMenu()
        rule.onNodeWithTag(TAG_READER_OPEN_ANOTHER).assertContentDescriptionEquals(str(R.string.reader_open_another))
        rule.onNodeWithTag(TAG_READER_GO_TO_PAGE).assertContentDescriptionEquals(str(R.string.reader_go_to_page))
    }

    // ---- Text selection ----

    private fun clip(): String? =
        app.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    /** Root position of page point ([xPt], [yPt]) of page [index], drawn at zoom [scale]. */
    private fun rootOf(index: Int, xPt: Float, yPt: Float, scale: Float): Offset {
        val node = rule.onNodeWithTag(readerPageTag(index)).fetchSemanticsNode()
        val pxPerPt = node.size.width / a4.widthPt * scale
        return node.positionInRoot + Offset(xPt * pxPerPt, yPt * pxPerPt)
    }

    private fun listOrigin(): Offset = rule.onNodeWithTag(TAG_READER_LIST).fetchSemanticsNode().positionInRoot

    /** A real long-press on the list, over page point ([xPt], [yPt]) of page [index]. */
    private fun longPressAt(index: Int, xPt: Float, yPt: Float, scale: Float = 1f) {
        val at = rootOf(index, xPt, yPt, scale) - listOrigin()
        rule.onNodeWithTag(TAG_READER_LIST).performTouchInput { longClick(at) }
        rule.waitForIdle()
    }

    private fun tapAt(index: Int, xPt: Float, yPt: Float) {
        val at = rootOf(index, xPt, yPt, 1f) - listOrigin()
        rule.onNodeWithTag(TAG_READER_LIST).performTouchInput { click(at) }
        rule.waitForIdle()
    }

    private fun selectionShown() = exists(TAG_READER_SELECTION_BAR)

    private fun openWithText(vararg lines: String) {
        fake.readerPages = List(3) { a4 }
        fake.readerTexts[0] = TestPageTexts.grid(*lines)
        openDocument()
    }

    private fun customAction(tag: String, label: String) {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].single { it.label == label }
        rule.runOnUiThread { action.action() }
        rule.waitForIdle()
    }

    @Test
    fun longPressSelectsTheWordAndCopyPutsItOnTheClipboard() {
        openWithText("Hello world", "Second line")
        val (x, y) = TestPageTexts.centre(0, 8)
        longPressAt(0, x, y)
        waitUntil("selection bar") { selectionShown() }
        rule.onNodeWithTag(TAG_READER_TITLE).assertDoesNotExist()
        rule.onNodeWithTag(TAG_READER_HANDLE_ANCHOR).assertExists()
        rule.onNodeWithTag(TAG_READER_HANDLE_FOCUS).assertExists()
        rule.onNodeWithTag(TAG_READER_COPY).assertContentDescriptionEquals(str(R.string.reader_copy_cd))
        rule.onNodeWithTag(TAG_READER_SELECT_ALL).assertContentDescriptionEquals(str(R.string.reader_select_all_cd))
        assertEquals("text fetched once, on the long-press", 1, fake.textCalls.size)
        // Handles keep a 48 dp touch target.
        val handle = rule.onNodeWithTag(TAG_READER_HANDLE_FOCUS).fetchSemanticsNode()
        assertTrue(handle.size.width >= (48 * app.resources.displayMetrics.density).toInt() - 1)

        rule.onNodeWithTag(TAG_READER_COPY).performClick()
        rule.waitForIdle()
        assertEquals("world", clip())
        assertTrue("copy clears the selection", !selectionShown())
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals("report.pdf")
        // Android 13+ shows its own notice: no "Copied" here (SDK 36).
        assertTrue(rule.onAllNodesWithText(str(R.string.reader_copied)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aHandleDragExtendsTheSelectionAndSelectAllTakesThePage() {
        openWithText("Hello world", "Second line")
        val (x, y) = TestPageTexts.centre(0, 8)
        longPressAt(0, x, y)
        waitUntil("selection bar") { selectionShown() }
        // Drag the end handle from the middle of "d" to the middle of "n" of "line".
        val handle = rule.onNodeWithTag(TAG_READER_HANDLE_FOCUS).fetchSemanticsNode()
        val start = handle.positionInRoot + Offset(handle.size.width / 2f, handle.size.height / 2f)
        val aimFrom = rootOf(0, TestPageTexts.X0 + 11 * TestPageTexts.CHAR_W, TestPageTexts.centre(0, 10).second, 1f)
        val (tx, ty) = TestPageTexts.centre(1, 9)
        val delta = rootOf(0, tx, ty, 1f) - aimFrom
        val origin = listOrigin()
        rule.onNodeWithTag(TAG_READER_LIST).performTouchInput { swipe(start - origin, start + delta - origin, 400) }
        rule.waitForIdle()
        assertTrue("still selected after the drag", selectionShown())
        rule.onNodeWithTag(TAG_READER_COPY).performClick()
        rule.waitForIdle()
        assertEquals("world\nSecond lin", clip())

        longPressAt(0, x, y)
        waitUntil("selection bar") { selectionShown() }
        rule.onNodeWithTag(TAG_READER_SELECT_ALL).performClick()
        rule.onNodeWithTag(TAG_READER_COPY).performClick()
        rule.waitForIdle()
        assertEquals("Hello world\nSecond line", clip())
        assertEquals(1, fake.textCalls.size)
    }

    @Test
    fun aTapOutsideOrBackOrCloseClearsTheSelectionFirst() {
        openWithText("Hello world", "Second line")
        val (x, y) = TestPageTexts.centre(0, 8)
        longPressAt(0, x, y)
        waitUntil("selection bar") { selectionShown() }
        // A tap on the selected word keeps it; a tap away from it clears it.
        tapAt(0, x, y)
        mainClockPastDoubleTap()
        assertTrue(selectionShown())
        tapAt(0, 100f, 100f)
        assertTrue("tap outside clears", !selectionShown())

        longPressAt(0, x, y)
        waitUntil("selection bar") { selectionShown() }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue("Back clears the selection", !selectionShown())
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals("report.pdf")

        longPressAt(0, x, y)
        waitUntil("selection bar") { selectionShown() }
        rule.onNodeWithTag(TAG_READER_SELECTION_CLOSE).performClick()
        rule.waitForIdle()
        assertTrue(!selectionShown())
        // Then Back leaves as before.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_TOOL_READ).performScrollTo()
    }

    private fun mainClockPastDoubleTap() {
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    @Test
    fun aLongPressOnAPageWithoutTextSaysSoAndAMissDoesNothing() {
        openWithText("Hello world")
        longPressAt(0, 100f, 100f) // far from the text: nothing
        CoreTestSupport.idleUntil(rule) { fake.textCalls.size == 1 }
        rule.waitForIdle()
        assertTrue(!selectionShown())
        assertTrue(rule.onAllNodesWithText(str(R.string.reader_no_text)).fetchSemanticsNodes().isEmpty())

        fake.readerTexts.clear()
        // Page 1 has no text.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        rule.waitForIdle()
        longPressAt(1, 300f, 300f)
        waitUntil("no-text message") { rule.onAllNodesWithText(str(R.string.reader_no_text)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!selectionShown())
    }

    @Test
    fun theCopyPageTextActionCopiesTheWholePage() {
        openWithText("Hello world", "Second line")
        customAction(readerPageTag(0), str(R.string.reader_copy_page_action))
        waitUntil("copied") { clip() == "Hello world\nSecond line" }
        assertTrue(!selectionShown())
    }

    @Test
    fun copyingAHugePageCopiesTheFirstPartAndSaysSo() {
        // The most text a page can have (a crafted page's string() can be about three times this
        // with its line breaks); whole, it would not fit in one clipboard transaction.
        openWithText("x".repeat(PageTextFormat.MAX_UNITS))
        customAction(readerPageTag(0), str(R.string.reader_copy_page_action))
        waitUntil("copied") { clip() != null }
        assertEquals(SelectionMath.MAX_COPY_CHARS, clip()!!.length)
        waitUntil("cut note") { rule.onAllNodesWithText(str(R.string.reader_text_cut)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun highlightsStayOnTheTextWhenZoomedIn() {
        openWithText("Hello world", "Second line")
        customAction(TAG_READER_LIST, str(R.string.reader_zoom_in))
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        val (x, y) = TestPageTexts.centre(1, 2)
        longPressAt(0, x, y, scale = 1.5f)
        waitUntil("selection bar") { selectionShown() }
        // The handle sits under the word's start at this zoom (left edge of "S", bottom of the line).
        val handle = rule.onNodeWithTag(TAG_READER_HANDLE_ANCHOR).fetchSemanticsNode()
        val corner = rootOf(0, TestPageTexts.X0, TestPageTexts.Y0 + TestPageTexts.ROW + TestPageTexts.CHAR_H, 1.5f)
        assertEquals(corner.x, handle.positionInRoot.x + handle.size.width * 1.5f / 2f, 3f)
        assertEquals(corner.y, handle.positionInRoot.y, 3f)
        rule.onNodeWithTag(TAG_READER_COPY).performClick()
        rule.waitForIdle()
        assertEquals("Second", clip())
    }

    // ---- Overflow menu and Go to page ----

    private fun indicatorText(): String =
        rule.onNodeWithTag(TAG_READER_INDICATOR).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()

    private fun goToFromMenu() {
        openMenu()
        rule.onNodeWithTag(TAG_READER_GO_TO_PAGE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_GO_TO_PAGE_FIELD).assertExists()
    }

    private fun typePage(text: String) {
        rule.onNodeWithTag(TAG_GO_TO_PAGE_FIELD).performTextReplacement(text)
        rule.waitForIdle()
    }

    private fun pressGo() {
        rule.onNodeWithTag(TAG_GO_TO_PAGE_GO).performClick()
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h640dp", fontScale = 1.3f)
    fun atANarrowWidthWithLargeTextEveryTopBarButtonIsAFull48dpTargetOnScreenAndTheTitleKeepsItsWidth() {
        openDocument("A rather long quarterly report name.pdf")
        val rootWidth = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val density = rule.density.density
        for (tag in listOf(TAG_ACTION_BACK, TAG_READER_SEARCH, TAG_READER_SHARE, TAG_READER_MENU)) {
            // Touch bounds: the 48dp target (an IconButton draws 40dp and pads its target to 48dp).
            val b = rule.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
            assertTrue("$tag at least 48dp: $b", b.width / density >= 47.9f && b.height / density >= 47.9f)
            assertTrue("$tag on screen: $b within $rootWidth", b.left >= 0f && b.right <= rootWidth + 0.5f)
        }
        val title = rule.onNodeWithTag(TAG_READER_TITLE).fetchSemanticsNode().boundsInRoot
        assertTrue("title width ${title.width / density}dp", title.width / density >= 72f - 0.5f)
        // Whatever the indicator shows (or not), it never overlaps the buttons.
        val indicator = rule.onAllNodesWithTag(TAG_READER_INDICATOR).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        val search = rule.onNodeWithTag(TAG_READER_SEARCH).fetchSemanticsNode().boundsInRoot
        if (indicator != null) assertTrue(indicator.right <= search.left + 0.5f && indicator.left >= title.right - 0.5f)
    }

    @Test
    fun theMenuListsGoToPageThenOpenAnotherAndOpenAnotherLaunchesThePicker() {
        openDocument()
        assertTrue(!exists(TAG_READER_OPEN_ANOTHER))
        // Search and Share are top-bar buttons, in this order before the three dots.
        val bar = listOf(TAG_READER_SEARCH, TAG_READER_SHARE, TAG_READER_MENU).map {
            rule.onNodeWithTag(it).assertHasClickAction().fetchSemanticsNode()
        }
        assertTrue(bar.zipWithNext().all { (a, b) -> a.boundsInRoot.left < b.boundsInRoot.left })
        rule.onNodeWithTag(TAG_READER_SEARCH).assertContentDescriptionEquals(str(R.string.reader_search))
        rule.onNodeWithTag(TAG_READER_SHARE).assertContentDescriptionEquals(str(R.string.reader_share))
        openMenu()
        val items = rule.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).fetchSemanticsNodes()
        assertEquals(
            listOf(TAG_READER_GO_TO_PAGE, TAG_READER_SAVE_COPY, TAG_READER_COMPRESS, TAG_READER_PRINT, TAG_READER_OPEN_ANOTHER),
            items.map { it.config[SemanticsProperties.TestTag] },
        )
        rule.onNodeWithTag(TAG_READER_PRINT).assertContentDescriptionEquals(str(R.string.reader_print))
        rule.onNodeWithTag(TAG_READER_OPEN_ANOTHER).performClick()
        rule.waitForIdle()
        answer(takePdfPicker(), null)
        assertTrue("the menu closed", !exists(TAG_READER_GO_TO_PAGE))
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals("report.pdf")
    }

    @Test
    fun shareFromTheMenuOpensTheShareSheetForACopyOfTheDocument() {
        CoreTestSupport.clearFileProviderCache()
        openDocument("Shared report.pdf")
        rule.onNodeWithTag(TAG_READER_SHARE).assertIsEnabled().performClick()
        var started: Intent? = null
        waitUntil("share sheet") { started = started ?: shadow().nextStartedActivity; started != null }
        val chooser = requireNotNull(started)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("app.venkat.offkit.share", uri.authority)
        val copy = File(app.cacheDir, "shared").walkTopDown().single { it.isFile }
        assertEquals("Shared report.pdf", copy.name)
        assertTrue("%PDF-1.7\n%fake\n".toByteArray().contentEquals(copy.readBytes()))
        // The document stays open.
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals("Shared report.pdf")
    }

    @Test
    fun noMenuOrIndicatorBeforeADocumentIsOpenOrOnTheErrorScreen() {
        val picker = enterReader()
        // The frame shown before the picker answers (and while opening) has neither.
        assertTrue(!exists(TAG_READER_MENU))
        assertTrue(!exists(TAG_READER_INDICATOR))
        fake.readerError = PdfError.Corrupt
        answer(picker, pdf("bad.pdf"))
        waitUntil("error screen") { exists(TAG_JOB_RESET) }
        assertTrue(!exists(TAG_READER_MENU))
        assertTrue(!exists(TAG_READER_INDICATOR))
    }

    @Test
    fun goToPageFromTheMenuShowsTheCurrentPageAndCancelKeepsThePlace() {
        fake.readerPages = List(12) { a4 }
        openDocument()
        goToFromMenu()
        rule.onNodeWithTag(TAG_GO_TO_PAGE_FIELD).assert(hasText("1"))
        rule.onNodeWithText(str(R.string.reader_go_to_page_of, 12)).assertExists()
        rule.onNodeWithTag(TAG_GO_TO_PAGE_GO).assertIsEnabled()
        // Focused, with the current page all selected, so typing replaces it.
        val field = rule.onNodeWithTag(TAG_GO_TO_PAGE_FIELD).assertIsFocused().fetchSemanticsNode()
        assertEquals(androidx.compose.ui.text.TextRange(0, 1), field.config[SemanticsProperties.TextSelectionRange])
        rule.onNodeWithTag(TAG_GO_TO_PAGE_CANCEL).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_GO_TO_PAGE_FIELD).assertDoesNotExist()
        assertEquals(indicator(1, 12), indicatorText())
    }

    @Test
    fun goToPageFromTheIndicatorAndTheKeyboardsDone() {
        fake.readerPages = List(12) { a4 }
        openDocument()
        rule.onNodeWithTag(TAG_READER_INDICATOR).performClick()
        rule.waitForIdle()
        typePage("7")
        rule.onNodeWithTag(TAG_GO_TO_PAGE_FIELD).performImeAction()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_GO_TO_PAGE_FIELD).assertDoesNotExist()
        waitUntil("page 7") { indicatorText() == indicator(7, 12) }
        waitUntilRendered(6)
    }

    @Test
    fun invalidInputKeepsGoDisabledAndShowsAHint() {
        fake.readerPages = List(12) { a4 }
        openDocument()
        goToFromMenu()
        rule.onNodeWithTag(TAG_GO_TO_PAGE_HINT).assertDoesNotExist()
        for (bad in listOf("", "0", "13", "99999999999999999999")) {
            typePage(bad)
            rule.onNodeWithTag(TAG_GO_TO_PAGE_GO).assertIsNotEnabled()
            rule.onNodeWithTag(TAG_GO_TO_PAGE_HINT).assertTextEquals(str(R.string.reader_go_to_page_hint, 12))
        }
        // Pasted text keeps only its digits; leading zeros are fine.
        typePage("p. 1x2")
        rule.onNodeWithTag(TAG_GO_TO_PAGE_FIELD).assert(hasText("12"))
        rule.onNodeWithTag(TAG_GO_TO_PAGE_GO).assertIsEnabled()
        typePage("012")
        rule.onNodeWithTag(TAG_GO_TO_PAGE_GO).assertIsEnabled()
        rule.onNodeWithTag(TAG_GO_TO_PAGE_HINT).assertDoesNotExist()
        rule.onNodeWithTag(TAG_GO_TO_PAGE_FIELD).performImeAction()
        rule.waitForIdle()
        waitUntil("page 12") { indicatorText() == indicator(12, 12) }
    }

    @Test
    fun goingFarAwayRendersOnlyThatPlaceNotThePagesBetween() {
        fake.readerPages = List(500) { a4 }
        openDocument()
        goToFromMenu()
        typePage("400")
        pressGo()
        waitUntil("page 400") { indicatorText() == indicator(400, 500) }
        waitUntilRendered(399)
        assertTrue(renderRequests().any { it.page == 399 })
        // Page 1 and its neighbours were drawn first; page 400 and its neighbours now; nothing between.
        val between = renderRequests().filter { it.page in 5..396 }
        assertTrue("pages between rendered: ${between.map { it.page }.distinct()}", between.isEmpty())
        assertTrue("items between composed", !exists(readerPageTag(200)))
    }

    @Test
    fun goToPageKeepsTheZoomAndPutsThePageAtTheTop() {
        fake.readerPages = List(12) { a4 }
        openDocument()
        customAction(TAG_READER_LIST, str(R.string.reader_zoom_in))
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        // Zoomed in about the centre: the page's (transformed, unclipped) left edge is left of the list.
        val zoomedLeft = rule.onNodeWithTag(readerPageTag(0)).fetchSemanticsNode().positionInRoot.x
        val list = rule.onNodeWithTag(TAG_READER_LIST).fetchSemanticsNode().boundsInRoot
        assertTrue("zoomed in: $zoomedLeft vs ${list.left}", zoomedLeft < list.left - 10f)
        rule.onNodeWithTag(TAG_READER_INDICATOR).performClick()
        rule.waitForIdle()
        typePage("5")
        pressGo()
        waitUntil("page 5") { indicatorText() == indicator(5, 12) }
        val page = rule.onNodeWithTag(readerPageTag(4)).fetchSemanticsNode().positionInRoot
        assertEquals("zoom and horizontal pan kept", zoomedLeft, page.x, 1f)
        val slack = 24 * app.resources.displayMetrics.density * 1.5f
        assertTrue("page top at the viewport top: ${page.y} vs ${list.top}", page.y >= list.top - 1f && page.y <= list.top + slack)
        // Still zoomed: a later page is drawn at the same zoom too.
        assertTrue(rule.onNodeWithTag(TAG_READER_LIST).fetchSemanticsNode().config[SemanticsActions.CustomActions].any { it.label == str(R.string.reader_zoom_out) })
    }

    @Test
    fun goToPageClearsTheSelection() {
        fake.readerPages = List(12) { a4 }
        fake.readerTexts[0] = TestPageTexts.grid("Hello world", "Second line")
        openDocument()
        rule.onNodeWithTag(TAG_READER_INDICATOR).performClick()
        rule.waitForIdle()
        // A selection made while the dialog is up (the dialog stays; the top bar becomes the selection bar).
        val (x, y) = TestPageTexts.centre(0, 8)
        longPressAt(0, x, y)
        waitUntil("selection bar") { selectionShown() }
        typePage("3")
        pressGo()
        assertTrue("selection cleared", !selectionShown())
        waitUntil("page 3") { indicatorText() == indicator(3, 12) }
    }

    @Test
    fun theDialogAndItsTextSurviveRotation() {
        fake.readerPages = List(12) { a4 }
        openDocument()
        goToFromMenu()
        typePage("9")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        waitFor(TAG_GO_TO_PAGE_FIELD)
        rule.onNodeWithTag(TAG_GO_TO_PAGE_FIELD).assert(hasText("9"))
        pressGo()
        waitUntil("page 9") { indicatorText() == indicator(9, 12) }
    }

    // ---- Search ----

    private fun openSearch() {
        assertTrue("Search is not in the menu", !exists(TAG_READER_GO_TO_PAGE))
        rule.onNodeWithTag(TAG_READER_SEARCH).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_READER_SEARCH_BAR).assertExists()
    }

    private fun searchFor(query: String) {
        rule.onNodeWithTag(TAG_READER_SEARCH_FIELD).performTextReplacement(query)
        rule.onNodeWithTag(TAG_READER_SEARCH_FIELD).performImeAction()
        rule.waitForIdle()
    }

    private fun countText(): String =
        rule.onNodeWithTag(TAG_READER_SEARCH_COUNT).fetchSemanticsNode().let { n ->
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString().orEmpty()
        }

    private fun searchHitsOn(index: Int): Int =
        rule.onAllNodesWithTag(readerPageTag(index)).fetchSemanticsNodes()
            .maxOfOrNull { it.config.getOrElseNullable(ReaderPageSearchHits) { null } ?: 0 } ?: 0

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun searchFromTheMenuCountsHitsStepsThroughThemAndBackClosesItThenLeaves() {
        fake.readerPages = List(12) { a4 }
        fake.readerTexts[0] = TestPageTexts.grid("the cat sat", "on the mat")
        fake.readerTexts[6] = TestPageTexts.grid("a cat")
        fake.readerTexts[9] = TestPageTexts.grid("cat")
        openDocument()
        openSearch()
        rule.onNodeWithTag(TAG_READER_SEARCH_NEXT).assertContentDescriptionEquals(str(R.string.reader_search_next_cd))
        rule.onNodeWithTag(TAG_READER_SEARCH_PREVIOUS).assertContentDescriptionEquals(str(R.string.reader_search_previous_cd))
        searchFor("CAT")
        waitUntil("count") { countText() == str(R.string.reader_search_position, 1, "3") }
        assertEquals(indicator(1, 12), indicatorText())
        waitUntil("highlight on page 1") { searchHitsOn(0) == 1 }

        rule.onNodeWithTag(TAG_READER_SEARCH_NEXT).performClick()
        rule.waitForIdle()
        waitUntil("page 7") { indicatorText() == indicator(7, 12) }
        assertEquals(str(R.string.reader_search_position, 2, "3"), countText())
        waitUntil("highlight on page 7") { searchHitsOn(6) == 1 }
        rule.onNodeWithTag(TAG_READER_SEARCH_PREVIOUS).performClick()
        rule.onNodeWithTag(TAG_READER_SEARCH_PREVIOUS).performClick()
        rule.waitForIdle()
        waitUntil("wrapped to page 10") { indicatorText() == indicator(10, 12) }

        back()
        assertTrue("Back closes search", !exists(TAG_READER_SEARCH_BAR))
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals("report.pdf")
        waitUntil("highlights removed") { searchHitsOn(9) == 0 }
        back()
        rule.onNodeWithTag(TAG_TOOL_READ).performScrollTo()
    }

    @Test
    fun noResultsAndTheCloseButton() {
        fake.readerPages = List(3) { a4 }
        fake.readerTexts[0] = TestPageTexts.grid("Hello world")
        openDocument()
        openSearch()
        searchFor("zebra")
        waitUntil("no results") { countText() == str(R.string.reader_search_none) }
        assertTrue(!exists(TAG_READER_SEARCH_SKIPPED))
        rule.onNodeWithTag(TAG_READER_SEARCH_CLOSE).performClick()
        rule.waitForIdle()
        assertTrue(!exists(TAG_READER_SEARCH_BAR))
        rule.onNodeWithTag(TAG_READER_INDICATOR).assertExists()
    }

    @Test
    fun pagesThatCannotBeReadAreNotedAfterTheScan() {
        (app as PdfUtilApp).engine = object : PdfEngine by fake, PdfPageRenderer by fake {
            override fun pageText(pdf: File, page: Int, out: File, job: JobControl): PdfPageText =
                if (page == 1 || page == 2) PdfPageText.Err(PdfError.TimedOut) else fake.pageText(pdf, page, out, job)
        }
        fake.readerPages = List(4) { a4 }
        fake.readerTexts[3] = TestPageTexts.grid("cat")
        openDocument()
        openSearch()
        searchFor("cat")
        waitUntil("skipped note") { exists(TAG_READER_SEARCH_SKIPPED) }
        rule.onNodeWithTag(TAG_READER_SEARCH_SKIPPED)
            .assertTextEquals(app.resources.getQuantityString(R.plurals.reader_search_skipped, 2, 2))
        assertEquals(str(R.string.reader_search_position, 1, "1"), countText())
    }

    @Test
    fun aLongPressStillSelectsWhileSearchIsOpenAndBackClearsTheSelectionFirst() {
        openWithText("Hello world", "Second line")
        openSearch()
        searchFor("world")
        waitUntil("count") { countText() == str(R.string.reader_search_position, 1, "1") }
        val (x, y) = TestPageTexts.centre(0, 1)
        longPressAt(0, x, y)
        waitUntil("selection bar") { selectionShown() }
        back()
        assertTrue(!selectionShown())
        assertTrue("search still open", exists(TAG_READER_SEARCH_BAR))
        assertEquals(1, searchHitsOn(0))
    }

    private fun waitForPasswordDialog(name: String) {
        waitFor(TAG_PASSWORD_DIALOG)
        rule.onNodeWithTag(TAG_PASSWORD_NAME).assertTextEquals(name)
        rule.onNodeWithText(str(R.string.reader_password_body)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.reader_password_title)).assertIsDisplayed()
    }

    private fun typePassword(text: String) {
        rule.onNodeWithTag(TAG_PASSWORD_FIELD).performTextInput(text)
        rule.waitForIdle()
    }

    private fun passwordFieldText(): String =
        rule.onNodeWithTag(TAG_PASSWORD_FIELD).fetchSemanticsNode().config
            .getOrElseNullable(SemanticsProperties.EditableText) { null }?.text.orEmpty()

    private fun pressUnlock() {
        rule.onNodeWithTag(TAG_PASSWORD_UNLOCK).performClick()
        rule.waitForIdle()
    }

    @Test
    fun protectedPdfFromThePickerAsksForThePasswordWrongThenRightShowsThePages() {
        fake.readerPassword = "right-one"
        answer(enterReader(), pdf("locked.pdf"))
        waitForPasswordDialog("locked.pdf")
        assertTrue(!exists(TAG_JOB_RESET))
        rule.onNodeWithTag(TAG_PASSWORD_UNLOCK).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_PASSWORD_TOGGLE).assertContentDescriptionContains(str(R.string.reader_password_show_description))
        rule.onNodeWithTag(TAG_PASSWORD_TOGGLE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PASSWORD_TOGGLE).assertContentDescriptionContains(str(R.string.reader_password_hide_description))

        typePassword("wrong-one")
        pressUnlock()
        waitFor(TAG_PASSWORD_PROBLEM)
        rule.onNodeWithTag(TAG_PASSWORD_PROBLEM).assertTextEquals(str(R.string.reader_password_wrong))
        waitUntil("field cleared") { passwordFieldText().isEmpty() }
        rule.onNodeWithTag(TAG_PASSWORD_FIELD).assertIsFocused()
        assertEquals(1, readerDirs().size)

        typePassword("right-one")
        pressUnlock()
        waitUntilRendered(0)
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals("locked.pdf")
        assertTrue(!exists(TAG_PASSWORD_DIALOG))
        assertEquals(1, readerDirs().size)
    }

    @Test
    fun cancellingThePasswordOnFirstEntryLeavesTheReader() {
        fake.readerPassword = "pw"
        answer(enterReader(), pdf("locked.pdf"))
        waitForPasswordDialog("locked.pdf")
        rule.onNodeWithTag(TAG_PASSWORD_CANCEL).performClick()
        rule.waitForIdle()
        waitFor(TAG_TOOL_READ)
        assertNoPicker()
        waitUntil("locked copy deleted") { readerDirs().isEmpty() }
    }

    @Test
    fun aProtectedPdfFromOpenAnotherKeepsTheOpenDocumentWhenCancelled() {
        openDocument("first.pdf")
        fake.readerPassword = "pw"
        openAnother()
        answer(takePdfPicker(), pdf("locked.pdf"))
        waitForPasswordDialog("locked.pdf")
        assertEquals(2, readerDirs().size)
        rule.onNodeWithTag(TAG_PASSWORD_CANCEL).performClick()
        rule.waitForIdle()
        waitFor(TAG_READER_TITLE)
        assertTrue(!exists(TAG_PASSWORD_DIALOG))
        rule.onNodeWithTag(TAG_READER_TITLE).assertTextEquals("first.pdf")
        waitUntilRendered(0)
        waitUntil("only the open document's copy is kept") { readerDirs().size == 1 }

        openAnother()
        answer(takePdfPicker(), pdf("locked.pdf"))
        waitForPasswordDialog("locked.pdf")
        typePassword("pw")
        pressUnlock()
        waitUntil("locked.pdf open") {
            rule.onAllNodesWithTag(TAG_READER_TITLE).fetchSemanticsNodes()
                .any { it.config[SemanticsProperties.Text].joinToString() == "locked.pdf" }
        }
        waitUntilRendered(0)
        waitUntil("first closed") { readerDirs().size == 1 }
    }

    @Test
    fun rotationKeepsTheDialogWithAnEmptyFieldAndThePasswordIsNotSaved() {
        val secret = "Zq9-unique-K3-secret"
        fake.readerPassword = "pw"
        answer(enterReader(), pdf("locked.pdf"))
        waitForPasswordDialog("locked.pdf")
        rule.onNodeWithTag(TAG_PASSWORD_TOGGLE).performClick() // shown: the field holds the plain text
        typePassword(secret)
        assertEquals(secret, passwordFieldText())

        val saved = Bundle()
        rule.runOnUiThread { InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(rule.activity, saved) }
        val parcel = Parcel.obtain()
        val bytes = try {
            parcel.writeBundle(saved)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
        fun contains(needle: ByteArray) = (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } }
        // The search works: a key known to be saved is found (Robolectric parcels strings as UTF-8, Android as UTF-16).
        assertTrue(listOf(Charsets.UTF_8, Charsets.UTF_16LE).any { contains("reader.pickerLaunched".toByteArray(it)) })
        assertTrue(!contains(secret.toByteArray(Charsets.UTF_16LE)))
        assertTrue(!contains(secret.toByteArray(Charsets.UTF_8)))

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        waitForPasswordDialog("locked.pdf")
        assertEquals("", passwordFieldText())
        rule.onNodeWithTag(TAG_PASSWORD_UNLOCK).assertIsNotEnabled()
        assertNoPicker()
    }

    @Test
    fun aPasswordTooLongShowsAHintWithoutCallingTheEngine() {
        fake.readerPassword = "pw"
        answer(enterReader(), pdf("locked.pdf"))
        waitForPasswordDialog("locked.pdf")
        val calls = fake.readerPasswords.size
        typePassword("a".repeat(128))
        pressUnlock()
        waitFor(TAG_PASSWORD_PROBLEM)
        rule.onNodeWithTag(TAG_PASSWORD_PROBLEM).assertTextEquals(str(R.string.reader_password_invalid))
        assertEquals(calls, fake.readerPasswords.size)
        rule.onNodeWithTag(TAG_PASSWORD_DIALOG).assertExists()
    }
}
