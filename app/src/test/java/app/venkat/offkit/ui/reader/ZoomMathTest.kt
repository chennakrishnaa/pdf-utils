package app.venkat.offkit.ui.reader

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure maths and state logic of [ZoomableList]; plain JVM, no Compose UI. */
class ZoomMathTest {
    private val width = 1000f

    /** Screen position of an unscaled content point (y relative to scroll top [scrollTop]). */
    private fun screenOf(content: Offset, scale: Float, offsetX: Float, scrollTop: Float) =
        Offset(content.x * scale - offsetX, (content.y - scrollTop) * scale)

    @Test
    fun scaleIsClampedToRange() {
        assertEquals(1f, ZoomMath.clampScale(0.3f, 1f, 5f))
        assertEquals(5f, ZoomMath.clampScale(40f, 1f, 5f))
        assertEquals(2f, ZoomMath.clampScale(2f, 1f, 5f))
    }

    @Test
    fun offsetIsClampedSoNoEmptySpaceShows() {
        assertEquals(0f, ZoomMath.clampOffsetX(-50f, 2f, 1f, width))
        assertEquals(1000f, ZoomMath.clampOffsetX(5000f, 2f, 1f, width))
        assertEquals(300f, ZoomMath.clampOffsetX(300f, 2f, 1f, width))
        assertEquals(0f, ZoomMath.clampOffsetX(300f, 1f, 1f, width))
    }

    @Test
    fun offsetIsZeroAtMinScaleEvenWhenMinScaleAboveOne() {
        // Spec rule 4 taken literally: at minScale the pan is 0 even if minScale > 1.
        assertEquals(0f, ZoomMath.clampOffsetX(300f, 1.5f, 1.5f, width))
        assertEquals(300f, ZoomMath.clampOffsetX(300f, 2f, 1.5f, width))
    }

    @Test
    fun zoomKeepsCentroidContentPointFixed() {
        val centroid = Offset(270f, 430f)
        var scale = 1f
        var offsetX = 0f
        var scrollTop = 2000f
        val content = Offset((centroid.x + offsetX) / scale, scrollTop + centroid.y / scale)
        for (target in listOf(1.7f, 3.2f, 4.9f, 2.1f, 1.3f)) {
            val step = ZoomMath.zoom(scale, offsetX, target, 1f, 5f, width, centroid, centroid)
            scale = step.scale
            offsetX = step.offsetX
            scrollTop += step.scrollDelta
            val screen = screenOf(content, scale, offsetX, scrollTop)
            assertEquals(centroid.x, screen.x, 0.01f)
            assertEquals(centroid.y, screen.y, 0.01f)
        }
    }

    @Test
    fun movingPinchCarriesContentPointToNewCentroid() {
        val from = Offset(400f, 300f)
        val to = Offset(450f, 260f)
        val scrollTop = 1000f
        val step = ZoomMath.zoom(2f, 200f, 3f, 1f, 5f, width, from, to)
        val content = Offset((from.x + 200f) / 2f, scrollTop + from.y / 2f)
        val screen = screenOf(content, step.scale, step.offsetX, scrollTop + step.scrollDelta)
        assertEquals(to.x, screen.x, 0.01f)
        assertEquals(to.y, screen.y, 0.01f)
    }

    @Test
    fun zoomBeyondRangeClampsScaleAndReturnsToZeroOffset() {
        val inStep = ZoomMath.zoom(2f, 500f, 50f, 1f, 5f, width, Offset(100f, 100f), Offset(100f, 100f))
        assertEquals(5f, inStep.scale)
        val outStep = ZoomMath.zoom(2f, 500f, 0.1f, 1f, 5f, width, Offset(100f, 100f), Offset(100f, 100f))
        assertEquals(1f, outStep.scale)
        assertEquals(0f, outStep.offsetX)
    }

    @Test
    fun panScrollsByScreenDeltaOverScaleAndClampsHorizontally() {
        val step = ZoomMath.pan(2f, 100f, 1f, width, Offset(-60f, -80f))
        assertEquals(160f, step.offsetX)
        assertEquals(40f, step.scrollDelta)
        assertEquals(0f, ZoomMath.pan(2f, 100f, 1f, width, Offset(5000f, 0f)).offsetX)
        assertEquals(1000f, ZoomMath.pan(2f, 100f, 1f, width, Offset(-5000f, 0f)).offsetX)
    }

    @Test
    fun doubleTapTogglesBetweenMinAndTwoAndAHalf() {
        assertEquals(2.5f, ZoomMath.doubleTapTarget(1f, 1f, 5f))
        assertEquals(1f, ZoomMath.doubleTapTarget(2.5f, 1f, 5f))
        assertEquals(1f, ZoomMath.doubleTapTarget(4f, 1f, 5f))
        assertEquals(2f, ZoomMath.doubleTapTarget(1f, 1f, 2f))
    }

    @Test
    fun listHeightFillsViewportOnceScaled() {
        assertEquals(800, ZoomMath.listHeight(800, 1f))
        assertEquals(320, ZoomMath.listHeight(800, 2.5f))
        assertTrue(ZoomMath.listHeight(799, 3f) * 3f >= 799f)
    }

    @Test
    fun stateRejectsZoomOutBelowOne() {
        assertThrows(IllegalArgumentException::class.java) { ZoomableListState(minScale = 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { ZoomableListState(minScale = 2f, maxScale = 1.5f) }
    }

    @Test
    fun stateClampsAndReclampsOnViewportChange() {
        val state = ZoomableListState()
        state.setViewportSize(width, 2000f)
        state.zoomTo(3f, Offset(900f, 0f), Offset(900f, 0f))
        assertEquals(3f, state.scale)
        assertEquals(1800f, state.offsetX, 0.01f)
        state.setViewportSize(500f, 2000f) // narrower viewport, max pan is now 1000
        assertEquals(1000f, state.offsetX, 0.01f)
        state.zoomTo(1f, Offset.Zero, Offset.Zero)
        assertEquals(0f, state.offsetX)
        assertFalse(state.isZooming)
    }

    @Test
    fun saverRoundTripKeepsScaleAndOffset() {
        val state = ZoomableListState(1f, 4f)
        state.setViewportSize(width, 2000f)
        state.zoomTo(2f, Offset(300f, 0f), Offset(300f, 0f))
        @Suppress("UNCHECKED_CAST")
        val saver = ZoomableListState.Saver as androidx.compose.runtime.saveable.Saver<ZoomableListState, Any>
        val saved = with(saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }!!
        val restored = saver.restore(saved)!!
        assertEquals(4f, restored.maxScale)
        assertEquals(2f, restored.scale)
        assertEquals(state.offsetX, restored.offsetX)
    }
}
