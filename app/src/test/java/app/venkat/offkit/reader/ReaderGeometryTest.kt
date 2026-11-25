package app.venkat.offkit.reader

import androidx.compose.ui.unit.Constraints
import app.venkat.offkit.core.PdfReader
import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.RenderLimits
import app.venkat.offkit.feature.reader.DrawRect
import app.venkat.offkit.feature.reader.ItemRect
import app.venkat.offkit.feature.reader.ReaderLimits
import app.venkat.offkit.feature.reader.ReaderPlanner
import app.venkat.offkit.feature.reader.ReaderViewport
import app.venkat.offkit.feature.reader.RenderKind
import app.venkat.offkit.feature.reader.VisiblePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Pure geometry of the reader: bounded item sizes for any page size (RDR-1) and the screen's rect maths (RDR-7). */
class ReaderGeometryTest {
    private val limits = ReaderLimits()

    /** Raw engine replies, hostile ones included; each goes through [PdfReader.sanePageDims] as on open. */
    private val extremes: List<PageDims> = run {
        val sides = listOf(
            Float.MIN_VALUE, 1e-6f, 0.5f, 1f, 10f, 595f, 842f, 14_400f, 100_000f,
            PdfReader.MAX_PAGE_PT, 1e9f, Float.MAX_VALUE / 2, Float.MAX_VALUE,
        )
        val fixed = sides.flatMap { w -> sides.map { h -> PageDims(w, h) } }
        val rnd = Random(42)
        fixed + List(300) { PageDims(Math.scalb(1f, rnd.nextInt(-20, 128)) * rnd.nextFloat(), Math.scalb(1f, rnd.nextInt(-20, 128)) * rnd.nextFloat()) }
            .filter { it.widthPt > 0f && it.heightPt > 0f && it.widthPt.isFinite() && it.heightPt.isFinite() }
    }

    private val widths = listOf(1, 2, 304, 1064, 1800, 2544, ReaderPlanner.MAX_ITEM_WIDTH_PX)

    private fun ItemRect.finite() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    @Test
    fun saneDimsClampEachSideAndRejectNonSizes() {
        assertEquals(PageDims(1f, PdfReader.MAX_PAGE_PT), PdfReader.sanePageDims(PageDims(1e-6f, Float.MAX_VALUE)))
        assertEquals(PageDims(595f, 842f), PdfReader.sanePageDims(PageDims(595f, 842f)))
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(PdfReader.sanePageDims(PageDims(bad, 10f)))
            assertNull(PdfReader.sanePageDims(PageDims(10f, bad)))
        }
    }

    @Test
    fun itemSizesStayMeasurableByCompose() {
        // Throws IllegalArgumentException if Compose cannot represent the largest item (plus a gap).
        Constraints.fixed(ReaderPlanner.MAX_ITEM_WIDTH_PX, ReaderPlanner.MAX_ITEM_HEIGHT_PX + 1_000)
    }

    @Test
    fun everyPlanIsValidAndBoundedForExtremePageSizes() {
        for (raw in extremes) {
            val dims = PdfReader.sanePageDims(raw)!!
            for (w in widths) {
                val h = ReaderPlanner.itemHeightPx(dims, w)
                assertTrue("$raw @ $w: item height $h", h in 1..ReaderPlanner.MAX_ITEM_HEIGHT_PX)
                val page = ReaderPlanner.pageRect(dims, w)
                assertTrue("$raw @ $w: page $page", page.finite() && page.left >= 0f && page.top == 0f)
                assertTrue("$raw @ $w: page $page in item", ItemRect(0f, 0f, w.toFloat(), h.toFloat()).contains(page, slack = 1f))
                val fit = ReaderPlanner.fitScale(dims, w)
                assertTrue("$raw @ $w: fit $fit", fit.isFinite() && fit > 0f)
                for (zoom in listOf(1f, 2.5f, 5f)) {
                    val visible = listOf(
                        VisiblePage(0, ItemRect(0f, 0f, w.toFloat(), h.toFloat())),
                        VisiblePage(1, ItemRect(w / 3f, h / 4f, w / 3f + w / zoom, h / 4f + 2000f / zoom)),
                    )
                    val plan = ReaderPlanner.plan(listOf(dims, dims, dims), ReaderViewport(w, zoom, visible), limits)
                    assertTrue(plan.isNotEmpty())
                    for (p in plan) {
                        assertNull("$raw @ $w x$zoom: ${p.request}", RenderLimits.check(p.request, dims))
                        assertTrue("$raw @ $w x$zoom: covers ${p.covers}", p.covers.finite() && !p.covers.isEmpty)
                        if (p.kind == RenderKind.DETAIL) {
                            assertTrue(p.covers.contains(p.visible!!, slack = 0.5f))
                            // Within the page, up to one bitmap pixel of rounding (ceil of the scaled page).
                            val onePixel = fit / p.request.scale
                            assertTrue("$raw @ $w x$zoom: ${p.covers} in $page", page.contains(p.covers, slack = 0.01f + onePixel))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun tooTallPageIsCappedNarrowedAndCentred() {
        val dims = PageDims(10f, 14_400f)
        assertEquals(ReaderPlanner.MAX_ITEM_HEIGHT_PX, ReaderPlanner.itemHeightPx(dims, 304))
        val page = ReaderPlanner.pageRect(dims, 304)
        val pw = ReaderPlanner.MAX_ITEM_HEIGHT_PX * 10f / 14_400f
        assertEquals(pw, page.width, 0.01f)
        assertEquals((304 - pw) / 2, page.left, 0.01f)
        assertEquals(ReaderPlanner.MAX_ITEM_HEIGHT_PX.toFloat(), page.height, 0.01f)
        // An A4 page is unchanged: the full item width.
        assertEquals(ItemRect(0f, 0f, 1064f, 1064f * 842f / 595f), ReaderPlanner.pageRect(PageDims(595f, 842f), 1064))
    }

    @Test
    fun visibleRectAtZoomTwoWithHorizontalPan() {
        // Item 1000 x 1400, gap 8, viewport 1016 wide; zoom 2, panned 600 px right; item 2 starts 300
        // px above the viewport top (list units), viewport [0, 900).
        val rect = ReaderPlanner.visibleRect(
            itemWidthPx = 1000, itemHeightPx = 1400, gapPx = 8, zoom = 2f, offsetX = 600f,
            viewportWidthPx = 1016, viewportStartOffset = 0, viewportEndOffset = 900, itemOffset = -300,
        )
        // left = 600 / 2 - 8 = 292; right = (600 + 1016) / 2 - 8 = 800; top = 0 - (-300) = 300; bottom = 900 + 300 = 1200.
        assertEquals(ItemRect(292f, 300f, 800f, 1200f), rect)
        // Panned past the right edge: clamped to the item; an item below the viewport: nothing.
        assertEquals(ItemRect(758f, 0f, 1000f, 500f), ReaderPlanner.visibleRect(1000, 1400, 8, 2f, 1532f, 1016, 0, 900, 400))
        assertNull(ReaderPlanner.visibleRect(1000, 1400, 8, 2f, 0f, 1016, 0, 900, 900))
        assertNull(ReaderPlanner.visibleRect(1000, 1400, 8, Float.NaN, 0f, 1016, 0, 900, 0))
    }

    @Test
    fun detailRectMapsToItemPixels() {
        // A detail planned for a 1000 px item, drawn in an item now 1500 px wide: scaled by 1.5, rounded.
        assertEquals(DrawRect(438, 300, 1201, 1500), ReaderPlanner.drawRect(ItemRect(292f, 200f, 800.4f, 1000f), 1000, 1500f))
        assertEquals(DrawRect(292, 200, 800, 1000), ReaderPlanner.drawRect(ItemRect(292f, 200f, 800f, 1000f), 1000, 1000f))
        assertNull(ReaderPlanner.drawRect(ItemRect(10f, 10f, 10.2f, 20f), 1000, 1000f))
        assertNull(ReaderPlanner.drawRect(ItemRect(0f, 0f, Float.POSITIVE_INFINITY, 1f), 1000, 1000f))

        // The planner's detail for a zoom-2 view lands on the visible part when drawn.
        val a4 = PageDims(595f, 842f)
        val visible = ItemRect(292f, 300f, 800f, 1200f)
        val detail = ReaderPlanner.detailPlan(0, a4, 1000, 2f, visible, limits)
        assertNotNull(detail)
        val dst = ReaderPlanner.drawRect(detail!!.covers, detail.itemWidthPx, 1000f)!!
        assertTrue(dst.left <= 292 && dst.top <= 300 && dst.right >= 800 && dst.bottom >= 1200)
        // covers = request pixels / (scale / fit), offset by the page rect (here at 0, 0).
        val k = detail.request.scale / ReaderPlanner.fitScale(a4, 1000)
        assertEquals(detail.request.left / k, detail.covers.left, 0.01f)
        assertEquals((detail.request.top + detail.request.height) / k, detail.covers.bottom, 0.01f)
    }
}
