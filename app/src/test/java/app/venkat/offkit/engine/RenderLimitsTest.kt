package app.venkat.offkit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Boundaries of [RenderLimits.check] and the scaled page size. */
class RenderLimitsTest {
    private val ok = RenderRequest(page = 0, scale = 1f, left = 0, top = 0, width = 100, height = 100)
    private val a4 = PageDims(595f, 842f)

    private fun valid(r: RenderRequest, dims: PageDims? = null) = assertNull(RenderLimits.check(r, dims))
    private fun invalid(r: RenderRequest, dims: PageDims? = null) = assertNotNull("$r", RenderLimits.check(r, dims))

    @Test
    fun pageAndOrigin() {
        valid(ok)
        valid(ok.copy(page = Int.MAX_VALUE))
        invalid(ok.copy(page = -1))
        invalid(ok.copy(left = -1))
        invalid(ok.copy(top = -1))
        invalid(ok.copy(left = Int.MIN_VALUE))
    }

    @Test
    fun scale() {
        valid(ok.copy(scale = RenderLimits.MAX_SCALE))
        valid(ok.copy(scale = Float.MIN_VALUE))
        invalid(ok.copy(scale = 0f))
        invalid(ok.copy(scale = -0f))
        invalid(ok.copy(scale = -1f))
        invalid(ok.copy(scale = Float.NaN))
        invalid(ok.copy(scale = Float.POSITIVE_INFINITY))
        invalid(ok.copy(scale = Float.NEGATIVE_INFINITY))
        invalid(ok.copy(scale = Math.nextUp(RenderLimits.MAX_SCALE)))
    }

    @Test
    fun sizeAndPixelCapAreOverflowSafe() {
        invalid(ok.copy(width = 0))
        invalid(ok.copy(height = 0))
        invalid(ok.copy(width = -5, height = -5)) // product positive, still rejected
        valid(ok.copy(width = 4_000, height = 2_000)) // exactly MAX_PIXELS
        invalid(ok.copy(width = 4_000, height = 2_001))
        invalid(ok.copy(width = RenderLimits.MAX_PIXELS + 1, height = 1))
        // 65536 * 65536 overflows Int to 0; in Long it is far over the cap.
        invalid(ok.copy(width = 65_536, height = 65_536))
        invalid(ok.copy(width = Int.MAX_VALUE, height = Int.MAX_VALUE))
    }

    @Test
    fun rectangleMustLieInsideTheScaledPage() {
        // A4 at scale 2: 1190 x 1684 exactly.
        assertEquals(1190L, RenderLimits.scaledWidth(a4, 2f))
        assertEquals(1684L, RenderLimits.scaledHeight(a4, 2f))
        valid(ok.copy(scale = 2f, left = 0, top = 0, width = 1190, height = 1684), a4)
        valid(ok.copy(scale = 2f, left = 1189, top = 1683, width = 1, height = 1), a4)
        invalid(ok.copy(scale = 2f, left = 1190, top = 0, width = 1, height = 1), a4)
        invalid(ok.copy(scale = 2f, left = 0, top = 1, width = 1190, height = 1684), a4)
        invalid(ok.copy(scale = 2f, left = 1, top = 0, width = 1190, height = 1), a4)
        // left + width overflows Int but not Long.
        invalid(ok.copy(left = Int.MAX_VALUE, width = 1, height = 1), a4)
        // Fractional sizes round up: 100.5 pt at scale 1 is 101 px.
        val odd = PageDims(100.5f, 10.25f)
        assertEquals(101L, RenderLimits.scaledWidth(odd, 1f))
        assertEquals(11L, RenderLimits.scaledHeight(odd, 1f))
        valid(ok.copy(width = 101, height = 11), odd)
        invalid(ok.copy(width = 102, height = 11), odd)
        // Unknown dims: the rectangle is not checked.
        valid(ok.copy(left = 1_000_000, top = 1_000_000))
    }
}
