package app.venkat.offkit.reader

import androidx.compose.ui.geometry.Offset
import app.venkat.offkit.TestPageTexts
import app.venkat.offkit.TestPageTexts.centre
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.feature.reader.CopyNotice
import app.venkat.offkit.feature.reader.ReaderPlanner
import app.venkat.offkit.feature.reader.SelectionMath
import app.venkat.offkit.ui.reader.ZoomMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure selection maths: mapping, tolerance at each zoom, word, drag, swap, clamp, select all, notices. */
class ReaderSelectionTest {
    private val a4 = PageDims(595f, 842f)
    private val text = TestPageTexts.grid("Hello world", "Second line")

    @Test
    fun itemAndPagePointsRoundTripForAFullWidthAndACentredTallPage() {
        val full = ReaderPlanner.pageRect(a4, 1000)
        val (x, y) = SelectionMath.itemToPage(500f, 707.56f, a4, full)
        assertEquals(297.5f, x, 0.01f)
        assertEquals(421f, y, 0.1f)
        val tall = PageDims(100f, 2_000_000f)
        val rect = ReaderPlanner.pageRect(tall, 1000)
        assertTrue("a very tall page is centred", rect.left > 0f)
        val (ix, iy) = SelectionMath.pageToItem(50f, 1_000_000f, tall, rect)
        val (px, py) = SelectionMath.itemToPage(ix, iy, tall, rect)
        assertEquals(50f, px, 0.01f)
        assertEquals(1_000_000f, py, 1f)
        // Clamped to the page.
        assertEquals(0f to 0f, SelectionMath.itemToPage(-50f, -50f, a4, full, clamp = true))
    }

    @Test
    fun aScreenPointMapsToTheSamePagePointAtEveryZoomAndScroll() {
        val width = 1000
        val rect = ReaderPlanner.pageRect(a4, width)
        val itemTop = 300f // the item's top, in unscaled content pixels below the list's scroll top
        val (wantX, wantY) = centre(1, 3)
        val (ix, iy) = SelectionMath.pageToItem(wantX, wantY, a4, rect)
        for (scale in listOf(1f, 2.5f, 5f)) {
            for (offsetX in listOf(0f, ZoomMath.maxOffsetX(scale, width.toFloat()) / 2f)) {
                for (scroll in listOf(0f, 120f)) {
                    val o = if (scale == 1f) 0f else offsetX
                    val screen = Offset(ix * scale - o, (itemTop - scroll + iy) * scale)
                    val content = ZoomMath.contentPoint(screen, scale, o)
                    val (x, y) = SelectionMath.itemToPage(content.x, content.y - (itemTop - scroll), a4, rect)
                    assertEquals(wantX, x, 0.01f)
                    assertEquals(wantY, y, 0.01f)
                    assertEquals(text.wordRange(14), SelectionMath.selectWord(0, text, x, y, 1f)!!.range)
                }
            }
        }
    }

    @Test
    fun toleranceAndHandleSizesAreScreenSizesAtEveryZoom() {
        val rect = ReaderPlanner.pageRect(a4, 1000)
        val ptPerPx = 595f / 1000f
        for (scale in listOf(1f, 2.5f, 5f)) {
            assertEquals(24f * 2f / scale * ptPerPx, SelectionMath.tolerancePt(2f, scale, a4, rect), 0.001f)
            // 48 dp on screen at any zoom: item pixels times the zoom.
            assertEquals(96f, SelectionMath.screenDpToItemPx(SelectionMath.HANDLE_TOUCH_DP, 2f, scale) * scale, 0.001f)
        }
        // 20 pt beside the text: within reach at 1x (28.6 pt), not at 5x (5.7 pt).
        val (x, y) = TestPageTexts.X0 - 20f to centre(0, 0).second
        assertNotNull(SelectionMath.selectWord(0, text, x, y, SelectionMath.tolerancePt(2f, 1f, a4, rect)))
        assertNull(SelectionMath.selectWord(0, text, x, y, SelectionMath.tolerancePt(2f, 5f, a4, rect)))
    }

    @Test
    fun longPressSelectsTheWordAndHandlesExtendSwapAndClamp() {
        val (x, y) = centre(0, 8)
        val word = SelectionMath.selectWord(3, text, x, y, 10f)!!
        assertEquals(3, word.page)
        assertEquals("world", word.string())
        assertEquals(1, word.rects().size)

        // The end handle onto "Second": across the line, in reading order.
        val (x2, y2) = centre(1, 2)
        val longer = SelectionMath.drag(word, anchorHandle = false, x2, y2, a4)
        assertEquals("world\nSec", longer.string())
        assertEquals(2, longer.rects().size)
        // Shrink by one character.
        val (x3, y3) = centre(1, 1)
        assertEquals("world\nSe", SelectionMath.drag(longer, false, x3, y3, a4).string())

        // The start handle dragged past the end: start and end swap.
        val (x4, y4) = centre(1, 8)
        val swapped = SelectionMath.drag(longer, anchorHandle = true, x4, y4, a4)
        assertTrue(swapped.anchor > swapped.focus)
        assertEquals("cond li", swapped.string())

        // A drag far outside the page clamps to the page: the nearest character to its bottom-right corner.
        val clamped = SelectionMath.drag(word, false, 10_000f, 10_000f, a4)
        assertEquals(text.length - 1, clamped.focus)
        val top = SelectionMath.drag(word, true, -500f, -500f, a4)
        assertEquals(0, top.anchor)
    }

    @Test
    fun selectAllTakesThePageAndTheCutNoteOnlyAppliesToIt() {
        val all = SelectionMath.selectAll(0, text)!!
        assertEquals("Hello world\nSecond line", all.string())
        assertTrue(all.all)
        assertFalse("not cut", all.cut)
        assertNull(SelectionMath.selectAll(0, TestPageTexts.grid()))

        val cutText = TestPageTexts.grid("Hello world", "Second line", truncated = true)
        val cutAll = SelectionMath.selectAll(0, cutText)!!
        assertTrue(cutAll.cut)
        val (x, y) = centre(0, 2)
        val word = SelectionMath.selectWord(0, cutText, x, y, 5f)!!
        assertFalse("a word of a cut page is not cut", word.cut)
        // Shrinking a select-all is no longer "all".
        val (x2, y2) = centre(1, 2)
        assertFalse(SelectionMath.drag(cutAll, false, x2, y2, a4).cut)
    }

    @Test
    fun copiedIsShownOnlyUpToAndroid12() {
        assertEquals(CopyNotice.COPIED, SelectionMath.copyNotice(31, cut = false))
        assertEquals(CopyNotice.COPIED, SelectionMath.copyNotice(32, cut = false))
        assertEquals(CopyNotice.COPIED_CUT, SelectionMath.copyNotice(32, cut = true))
        assertEquals(CopyNotice.NONE, SelectionMath.copyNotice(33, cut = false))
        assertEquals(CopyNotice.CUT, SelectionMath.copyNotice(36, cut = true))
    }
}
