package app.venkat.offkit.reader

import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.RenderLimits
import app.venkat.offkit.feature.reader.ItemRect
import app.venkat.offkit.feature.reader.ReaderLimits
import app.venkat.offkit.feature.reader.ReaderPlanner
import app.venkat.offkit.feature.reader.ReaderViewport
import app.venkat.offkit.feature.reader.RenderKind
import app.venkat.offkit.feature.reader.VisiblePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPlannerTest {
    private val width = 1080
    private val limits = ReaderLimits()

    private val a4 = PageDims(595f, 842f)
    private val tall = PageDims(595f, 14_400f)
    private val tiny = PageDims(20f, 30f)
    private val huge = PageDims(14_400f, 14_400f)
    private val shapes = listOf(a4, tall, tiny, huge)

    /** Visible rects to try for a page item of [h] px: top band, middle, a zoomed-in corner, all. */
    private fun rects(h: Float, zoom: Float): List<ItemRect> {
        val vw = width / zoom
        val vh = 2_200f / zoom
        return listOf(
            ItemRect(0f, 0f, width.toFloat(), minOf(h, 2_200f)),
            ItemRect(0f, h / 3, vw, minOf(h, h / 3 + vh)),
            ItemRect(width - vw, maxOf(0f, h - vh), width.toFloat(), h),
            ItemRect(0f, 0f, width.toFloat(), h),
            ItemRect(123.4f, h / 2, 123.4f + vw / 3, h / 2 + 1.7f),
        )
    }

    @Test
    fun everyRequestIsValidAndCoversWhatItShould() {
        for (dims in shapes) for (zoom in listOf(1f, 2.5f, 5f)) {
            val itemH = width * dims.heightPt / dims.widthPt
            for (rect in rects(itemH, zoom)) {
                val pages = listOf(a4, dims, a4)
                val plan = ReaderPlanner.plan(pages, ReaderViewport(width, zoom, listOf(VisiblePage(1, rect))), limits)
                val ctx = "dims=$dims zoom=$zoom rect=$rect"
                for (p in plan) {
                    assertNull("$ctx $p", RenderLimits.check(p.request, pages[p.page]))
                    val d = pages[p.page]
                    if (p.kind == RenderKind.BASE) {
                        assertEquals(0, p.request.left)
                        assertEquals(0, p.request.top)
                        assertEquals(RenderLimits.scaledWidth(d, p.request.scale), p.request.width.toLong())
                        assertEquals(RenderLimits.scaledHeight(d, p.request.scale), p.request.height.toLong())
                        assertTrue(p.request.width.toLong() * p.request.height <= limits.basePagePixels)
                        assertTrue(p.request.scale <= ReaderPlanner.fitScale(d, width) + 1e-4f)
                        assertTrue(p.request.width <= width)
                    } else {
                        // At zoom 1 only for a base clearly softer than the screen (RDR-2).
                        val baseSharpness = ReaderPlanner.basePlan(1, d, width, limits).request.scale / ReaderPlanner.fitScale(d, width)
                        assertTrue(ctx, zoom > 1f || baseSharpness < ReaderPlanner.ZOOM1_MIN_SHARPNESS)
                        assertTrue("$ctx $p", p.covers.contains(rect))
                        assertTrue(p.request.width.toLong() * p.request.height <= limits.detailPixels)
                        assertTrue(p.request.scale <= minOf(RenderLimits.MAX_SCALE, ReaderPlanner.fitScale(d, width) * zoom) + 1e-4f)
                        assertTrue(p.request.scale > ReaderPlanner.basePlan(1, d, width, limits).request.scale)
                    }
                }
                // The visible page's base comes first.
                assertEquals(RenderKind.BASE, plan.first().kind)
                assertEquals(1, plan.first().page)
                if (zoom == 1f) {
                    val sharp = ReaderPlanner.basePlan(1, dims, width, limits).request.scale >= ReaderPlanner.fitScale(dims, width) * ReaderPlanner.ZOOM1_MIN_SHARPNESS
                    if (sharp) assertTrue(plan.none { it.kind == RenderKind.DETAIL })
                }
            }
        }
    }

    @Test
    fun a4AtFitWidthIsSharpAndDetailFollowsZoom() {
        val base = ReaderPlanner.basePlan(0, a4, width, limits)
        assertEquals(1080, base.request.width)
        assertEquals(1529, base.request.height)
        val visible = ItemRect(100f, 200f, 100f + width / 2.5f, 200f + 880f)
        val detail = ReaderPlanner.detailPlan(0, a4, width, 2.5f, visible, limits)!!
        assertEquals(ReaderPlanner.fitScale(a4, width) * 2.5f, detail.request.scale, 1e-4f)
        assertTrue(detail.covers.contains(visible))
        assertEquals(visible, detail.visible)
    }

    @Test
    fun hugeAndTallPagesGetALowerBaseScale() {
        val tallBase = ReaderPlanner.basePlan(0, tall, width, limits).request
        assertTrue(tallBase.scale < ReaderPlanner.fitScale(tall, width) / 3)
        val huge4k = ReaderPlanner.basePlan(0, huge, 4_000, limits).request
        assertTrue(huge4k.scale < ReaderPlanner.fitScale(huge, 4_000))
        for (dims in listOf(tall, huge)) {
            val base = ReaderPlanner.basePlan(0, dims, width, limits).request
            assertTrue(base.scale <= ReaderPlanner.fitScale(dims, width))
            assertTrue(base.width.toLong() * base.height <= limits.basePagePixels)
        }
        // The tiny page is capped at MAX_SCALE, and zooming cannot sharpen it further.
        val tinyBase = ReaderPlanner.basePlan(0, tiny, width, limits).request
        assertEquals(RenderLimits.MAX_SCALE, tinyBase.scale)
        assertNull(ReaderPlanner.detailPlan(0, tiny, width, 5f, ItemRect(0f, 0f, 200f, 300f), limits))
    }

    @Test
    fun orderIsVisibleBaseThenDetailThenNeighbours() {
        val pages = List(10) { a4 }
        val itemH = ReaderPlanner.itemHeightPx(a4, width).toFloat()
        val visible = listOf(
            VisiblePage(4, ItemRect(0f, itemH - 300f, 432f, itemH)),
            VisiblePage(5, ItemRect(0f, 0f, 432f, 580f)),
        )
        val plan = ReaderPlanner.plan(pages, ReaderViewport(width, 2.5f, visible), limits)
        val summary = plan.map { it.kind to it.page }
        assertEquals(
            listOf(
                RenderKind.BASE to 5, RenderKind.BASE to 4,
                RenderKind.DETAIL to 5, RenderKind.DETAIL to 4,
                RenderKind.BASE to 6, RenderKind.BASE to 3, RenderKind.BASE to 7, RenderKind.BASE to 2,
            ),
            summary,
        )
    }

    @Test
    fun neighboursStopAtTheBudget() {
        val pages = List(10) { a4 }
        val onePage = ReaderPlanner.basePlan(0, a4, width, limits).bytes
        val small = limits.copy(baseCacheBytes = onePage * 2)
        val visible = listOf(VisiblePage(5, ItemRect(0f, 0f, width.toFloat(), 800f)))
        val plan = ReaderPlanner.plan(pages, ReaderViewport(width, 1f, visible), small)
        assertEquals(listOf(5, 6), plan.map { it.page })
        // Visible pages are planned even beyond the budget.
        val none = limits.copy(baseCacheBytes = 0)
        assertEquals(listOf(5), ReaderPlanner.plan(pages, ReaderViewport(width, 1f, visible), none).map { it.page })
    }

    @Test
    fun currentPageIsTheMostVisible() {
        val v = listOf(VisiblePage(2, ItemRect(0f, 1000f, 1080f, 1529f)), VisiblePage(3, ItemRect(0f, 0f, 1080f, 600f)))
        assertEquals(3, ReaderPlanner.currentPage(v))
        assertNull(ReaderPlanner.currentPage(emptyList()))
    }
}
