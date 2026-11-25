package app.venkat.offkit.ui.reader

import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.venkat.offkit.MainActivity
import app.venkat.offkit.OnboardingSeenRule
import app.venkat.offkit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZoomableListTest {
    @get:Rule(order = Int.MIN_VALUE)
    val onboardingSeen = OnboardingSeenRule()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var zoom: ZoomableListState
    private lateinit var list: LazyListState
    private val composed = mutableSetOf<Int>()
    private var clicks = 0
    private var itemHeightPx = 0f

    @Composable
    private fun TestList(count: Int, itemHeight: Dp, clickableItems: Boolean) {
        zoom = rememberZoomableListState()
        list = rememberLazyListState()
        ZoomableList(zoom, list, Modifier.fillMaxSize().testTag(TAG)) {
            items(count) { i ->
                DisposableEffect(i) {
                    composed += i
                    onDispose { composed -= i }
                }
                val base = Modifier.fillMaxWidth().height(itemHeight).testTag("item$i")
                Box(if (clickableItems) base.clickable { clicks++ } else base)
            }
        }
    }

    private fun show(count: Int = 50, itemHeight: Dp = 200.dp, clickableItems: Boolean = false) {
        rule.runOnUiThread { rule.activity.setContent { TestList(count, itemHeight, clickableItems) } }
        rule.waitForIdle()
        itemHeightPx = with(rule.density) { itemHeight.toPx() }
    }

    private val viewport get() = rule.onNodeWithTag(TAG).fetchSemanticsNode().size

    private fun zoomInstantly(scale: Float) {
        rule.runOnUiThread { list.dispatchRawDelta(zoom.zoomTo(scale, Offset.Zero, Offset.Zero)) }
        rule.waitForIdle()
    }

    /** Absolute scroll position of the list top in content pixels (uniform items, no padding). */
    private fun scrollTop() = list.firstVisibleItemIndex * itemHeightPx + list.firstVisibleItemScrollOffset

    private fun pinchAbout(centre: Offset, startHalf: Float, endHalf: Float) {
        rule.onNodeWithTag(TAG).performTouchInput {
            pinch(
                start0 = centre - Offset(startHalf, 0f), end0 = centre - Offset(endHalf, 0f),
                start1 = centre + Offset(startHalf, 0f), end1 = centre + Offset(endHalf, 0f),
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun pinchOutZoomsInAndHugePinchClampsAtMax() {
        show()
        val c = Offset(viewport.width / 2f, viewport.height / 2f)
        pinchAbout(c, 40f, 80f)
        assertEquals(2f, zoom.scale, 0.15f)
        assertFalse(zoom.isZooming)
        pinchAbout(c, 10f, 150f)
        assertEquals(zoom.maxScale, zoom.scale)
        assertFalse(zoom.isZooming)
    }

    @Test
    fun pinchInFromOneStaysAtMinAndPinchBackReturnsToMinWithZeroOffset() {
        show()
        // Off-centre, but with every finger inside the node (off-node touches are not delivered).
        val c = Offset(viewport.width * 0.4f, viewport.height / 2f)
        val far = viewport.width * 0.35f
        pinchAbout(c, far, 20f)
        assertEquals(zoom.minScale, zoom.scale)
        assertEquals(0f, zoom.offsetX)
        pinchAbout(c, 20f, far)
        assertTrue(zoom.scale > 2f)
        assertTrue(zoom.offsetX > 0f)
        pinchAbout(c, far, 10f)
        assertEquals(zoom.minScale, zoom.scale)
        assertEquals(0f, zoom.offsetX)
    }

    @Test
    fun pinchKeepsContentUnderOffCentreCentroid() {
        show()
        rule.runOnUiThread { list.requestScrollToItem(10) }
        rule.waitForIdle()
        val p = Offset(viewport.width * 0.3f, viewport.height * 0.4f)
        val contentX = (p.x + zoom.offsetX) / zoom.scale
        val contentY = scrollTop() + p.y / zoom.scale
        pinchAbout(p, 30f, 75f)
        assertTrue(zoom.scale > 2f)
        val screenX = contentX * zoom.scale - zoom.offsetX
        val screenY = (contentY - scrollTop()) * zoom.scale
        assertEquals(p.x, screenX, 4f)
        assertEquals(p.y, screenY, 4f)
    }

    @Test
    fun oneFingerScrollsAtScaleOneAndPansWhenZoomed() {
        show()
        rule.onNodeWithTag(TAG).performTouchInput { swipeUp() }
        rule.waitForIdle()
        val afterPlain = scrollTop()
        assertTrue("plain swipe scrolls", afterPlain > 0f)
        assertEquals(0f, zoom.offsetX)

        zoomInstantly(2.5f)
        rule.onNodeWithTag(TAG).performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertTrue("zoomed swipe scrolls", scrollTop() > afterPlain)

        val maxOffset = viewport.width * (zoom.scale - 1f)
        rule.onNodeWithTag(TAG).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue(zoom.offsetX > 0f)
        assertTrue(zoom.offsetX <= maxOffset)
        repeat(4) {
            rule.onNodeWithTag(TAG).performTouchInput { swipeLeft() }
            rule.waitForIdle()
        }
        assertEquals(maxOffset, zoom.offsetX, 0.5f)
        rule.onNodeWithTag(TAG).performTouchInput {
            swipe(Offset(10f, height / 2f), Offset(width - 10f, height / 2f), 300)
        }
        rule.waitForIdle()
        assertTrue(zoom.offsetX >= 0f && zoom.offsetX < maxOffset)
    }

    @Test
    fun zoomedSlowDragScrollsByScreenDistanceOverScale() {
        show()
        rule.runOnUiThread { list.requestScrollToItem(10) }
        rule.waitForIdle()
        zoomInstantly(2f)
        val before = scrollTop()
        val x = viewport.width / 2f
        // Slow drag: the release velocity is ~0, so only the drag itself scrolls.
        rule.onNodeWithTag(TAG).performTouchInput {
            down(Offset(x, 300f))
            repeat(20) { moveBy(Offset(0f, -10f), delayMillis = 100) }
            repeat(5) { moveBy(Offset.Zero, delayMillis = 100) }
            up()
        }
        rule.waitForIdle()
        val scrolled = scrollTop() - before
        // 200 px of finger travel minus at most the slop that crossed before panning began.
        assertTrue("scrolled $scrolled", scrolled in 80f..100.5f)
    }

    @Test
    fun doubleTapTogglesTwoAndAHalfAndBack() {
        show()
        val c = Offset(viewport.width / 2f, viewport.height / 3f)
        rule.onNodeWithTag(TAG).performTouchInput { doubleClick(c) }
        rule.waitForIdle()
        assertEquals(2.5f, zoom.scale, 0.001f)
        assertFalse(zoom.isZooming)
        rule.onNodeWithTag(TAG).performTouchInput { doubleClick(c) }
        rule.waitForIdle()
        assertEquals(1f, zoom.scale)
        assertEquals(0f, zoom.offsetX)
        assertFalse(zoom.isZooming)
    }

    @Test
    fun isZoomingIsTrueOnlyWhileTwoFingersAreDown() {
        show()
        assertFalse(zoom.isZooming)
        rule.onNodeWithTag(TAG).performTouchInput {
            down(0, center - Offset(30f, 0f))
            down(1, center + Offset(30f, 0f))
            moveBy(0, Offset(-20f, 0f))
            moveBy(1, Offset(20f, 0f))
        }
        rule.waitForIdle()
        assertTrue(zoom.isZooming)
        assertTrue(zoom.scale > 1f)
        rule.onNodeWithTag(TAG).performTouchInput { up(1) }
        rule.waitForIdle()
        assertFalse(zoom.isZooming)
        rule.onNodeWithTag(TAG).performTouchInput { up(0) }
        rule.waitForIdle()
        assertFalse(zoom.isZooming)
    }

    @Test
    fun isZoomingIsTrueDuringDoubleTapAnimation() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(TAG).performTouchInput { doubleClick(center) }
        rule.mainClock.advanceTimeBy(48)
        assertTrue(zoom.isZooming)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertFalse(zoom.isZooming)
    }

    @Test
    fun clickableChildStillReceivesClicksAndDoubleTapStillZooms() {
        show(clickableItems = true)
        rule.onNodeWithTag("item1").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
        assertEquals(1f, zoom.scale)
        zoomInstantly(2f)
        rule.onNodeWithTag("item1").performClick()
        rule.waitForIdle()
        assertEquals(2, clicks)
        zoomInstantly(1f)
        rule.onNodeWithTag(TAG).performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(2.5f, zoom.scale, 0.001f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun accessibilityActionsZoomInAndOut() {
        show()
        val zoomIn = rule.activity.getString(R.string.reader_zoom_in)
        val zoomOut = rule.activity.getString(R.string.reader_zoom_out)
        rule.onNodeWithTag(TAG).performCustomAccessibilityActionWithLabel(zoomIn)
        rule.waitForIdle()
        assertEquals(ZoomMath.STEP_FACTOR, zoom.scale, 0.001f)
        rule.onNodeWithTag(TAG).performCustomAccessibilityActionWithLabel(zoomOut)
        rule.waitForIdle()
        assertEquals(1f, zoom.scale, 0.001f)
        assertFalse(zoom.isZooming)
    }

    @Test
    fun staysLazyAndComposesEverythingVisibleAtAnyScale() {
        show(count = 200, itemHeight = 400.dp)
        assertTrue("composed ${composed.size}", composed.size in 1..5)
        rule.runOnUiThread { list.requestScrollToItem(100, 123) }
        rule.waitForIdle()
        for (scale in listOf(1f, 1.7f, 3.3f, 5f)) {
            zoomInstantly(scale)
            assertTrue("composed ${composed.size}", composed.size in 1..5)
            assertTrue(composed.all { it in 95..105 })
            // Every item intersecting the visible content range [top, top + H / scale) is composed.
            val top = scrollTop()
            val bottom = top + viewport.height / zoom.scale
            val first = (top / itemHeightPx).toInt()
            val last = ((bottom - 0.01f) / itemHeightPx).toInt()
            for (i in first..last) assertTrue("item $i visible at $scale", i in composed)
            val layoutHeight = list.layoutInfo.viewportSize.height
            assertTrue(abs(layoutHeight * zoom.scale - viewport.height) < zoom.scale + 0.5f)
        }
    }

    @Test
    fun scaleSurvivesStateRestoration() {
        val tester = StateRestorationTester(rule)
        rule.runOnUiThread {
            (rule.activity.findViewById<android.view.ViewGroup>(android.R.id.content)).removeAllViews()
        }
        tester.setContent { TestList(50, 200.dp, false) }
        rule.waitForIdle()
        zoomInstantly(3f)
        rule.runOnUiThread { list.dispatchRawDelta(zoom.panBy(Offset(-200f, 0f))) }
        rule.waitForIdle()
        val offset = zoom.offsetX
        assertTrue(offset > 0f)
        val before = zoom
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertTrue(before !== zoom)
        assertEquals(3f, zoom.scale)
        assertEquals(offset, zoom.offsetX, 0.01f)
    }

    private companion object {
        const val TAG = "zoomable"
    }
}
