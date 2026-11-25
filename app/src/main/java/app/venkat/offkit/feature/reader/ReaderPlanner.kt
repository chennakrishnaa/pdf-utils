package app.venkat.offkit.feature.reader

import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.RenderLimits
import app.venkat.offkit.engine.RenderRequest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A rectangle in unscaled item pixels: (0, 0) is the top-left corner of a page's list item laid
 * out at zoom 1, x grows right, y grows down. `right` and `bottom` are exclusive.
 */
data class ItemRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = !(right > left && bottom > top)

    /** True if [other] lies inside this rectangle, allowing [slack] pixels of rounding error. */
    fun contains(other: ItemRect, slack: Float = 0.01f): Boolean =
        other.left >= left - slack && other.top >= top - slack &&
            other.right <= right + slack && other.bottom <= bottom + slack
}

/**
 * A page the screen currently shows. [index] is 0-based; [rect] is the part of the page's item that
 * is on screen, in unscaled item pixels (see [ItemRect]). At zoom 1 it is a horizontal band of the
 * full item width; zoomed in, it is the part of the item inside the viewport after zoom and pan,
 * divided back by the zoom (i.e. in `listState.layoutInfo` units).
 */
data class VisiblePage(val index: Int, val rect: ItemRect)

/** Kind of a [PlannedRender]. */
enum class RenderKind {
    /** The whole page at (at most) the fit-width resolution. */
    BASE,

    /** The visible part of a page (plus a margin) at the zoomed resolution. */
    DETAIL,
}

/**
 * One render the reader wants. [request] always passes `RenderLimits.check(request, dims)`.
 * [covers] is the part of the item the resulting bitmap shows, in unscaled item pixels for an item
 * [itemWidthPx] wide (the whole item for [RenderKind.BASE]). For [RenderKind.DETAIL], [visible] is
 * the on-screen part it was planned for (inside [covers]); null for [RenderKind.BASE].
 */
data class PlannedRender(
    val kind: RenderKind,
    val request: RenderRequest,
    val covers: ItemRect,
    val itemWidthPx: Int,
    val visible: ItemRect? = null,
) {
    val page: Int get() = request.page
    val bytes: Long get() = request.width.toLong() * request.height * 4
}

/**
 * Memory and quality limits of the reader.
 *
 * @property baseCacheBytes total bytes of base bitmaps kept (visible pages are kept even beyond it).
 * @property basePagePixels most pixels of one base bitmap; larger pages get a lower scale.
 * @property detailPixels most pixels of one detail bitmap; larger regions get a lower scale.
 * @property neighbours pages on each side of the visible ones whose base is prefetched (budget permitting).
 * @property detailMargin margin added around the visible rect of a detail, as a fraction of its size per side.
 */
data class ReaderLimits(
    val baseCacheBytes: Long = 48L * 1024 * 1024,
    val basePagePixels: Long = 2_500_000,
    val detailPixels: Long = 6_000_000,
    val neighbours: Int = 2,
    val detailMargin: Float = 0.15f,
) {
    init {
        require(basePagePixels in 1..RenderLimits.MAX_PIXELS.toLong())
        require(detailPixels in 1..RenderLimits.MAX_PIXELS.toLong())
        require(neighbours >= 0 && detailMargin >= 0f && baseCacheBytes >= 0)
    }
}

/** Pure geometry and planning of reader renders. */
object ReaderPlanner {
    /** Zoom at or below which no detail is rendered. */
    private const val NO_DETAIL_ZOOM = 1.001f

    /** A detail must be at least this much sharper than the base to be worth rendering. */
    private const val MIN_DETAIL_GAIN = 1.05f

    /**
     * Tallest page item, in pixels. A page taller than this at fit width (a long receipt or scroll,
     * or a hostile size) is shown in an item of this height, scaled down to fit it (keeping its
     * aspect ratio) and centred horizontally: it gets narrower rather than taller, and the user can
     * zoom in. Compose cannot measure an item much taller than 2^18 px (less when it is wide; see
     * [MAX_ITEM_WIDTH_PX]), so this keeps a wide margin.
     */
    const val MAX_ITEM_HEIGHT_PX = 60_000

    /**
     * Widest page item, in pixels (the screen coerces its item width to this). Compose `Constraints`
     * hold a height up to 2^18 - 1 px only while the width is below 2^13 px; 8,191 px is wider than
     * any screen.
     */
    const val MAX_ITEM_WIDTH_PX = 8_191

    /** `min(itemWidthPx / widthPt, MAX_ITEM_HEIGHT_PX / heightPt)`, see [fitScale]. */
    private fun fit(dims: PageDims, itemWidthPx: Int): Double =
        min(itemWidthPx.toDouble() / dims.widthPt, MAX_ITEM_HEIGHT_PX.toDouble() / dims.heightPt)

    /**
     * Item height in pixels of a page [dims] in an item [itemWidthPx] wide: the page's height at
     * [fitScale], rounded, in `[1, MAX_ITEM_HEIGHT_PX]`.
     */
    fun itemHeightPx(dims: PageDims, itemWidthPx: Int): Int {
        val h = Math.round(dims.heightPt * fit(dims, itemWidthPx)).toDouble()
        return if (h.isNaN()) 1 else h.coerceIn(1.0, MAX_ITEM_HEIGHT_PX.toDouble()).toInt()
    }

    /**
     * Where page [dims] is drawn inside its item ([itemWidthPx] x [itemHeightPx]), in item pixels:
     * its size at [fitScale], at the top, centred horizontally. That is the full item width unless
     * the page is too tall (see [MAX_ITEM_HEIGHT_PX]). Base and detail bitmaps are placed in it.
     */
    fun pageRect(dims: PageDims, itemWidthPx: Int): ItemRect {
        val w = itemWidthPx.toDouble()
        val f = fit(dims, itemWidthPx)
        val pw = (dims.widthPt * f).let { if (it.isNaN() || it > w) w else it.coerceAtLeast(0.0) }
        val ph = (dims.heightPt * f).let { if (it.isNaN()) 1.0 else it.coerceIn(0.0, MAX_ITEM_HEIGHT_PX.toDouble()) }
        if (pw >= w) return ItemRect(0f, 0f, itemWidthPx.toFloat(), ph.toFloat())
        val left = (w - pw) / 2
        return ItemRect(left.toFloat(), 0f, (left + pw).toFloat(), ph.toFloat())
    }

    /**
     * Pixels per PDF point at which page [dims] is shown in an item [itemWidthPx] wide: it fills the
     * item's width, or is [MAX_ITEM_HEIGHT_PX] tall if that is smaller (the size of [pageRect]).
     * Finite and > 0 for dims within `PdfReader.sanePageDims`.
     */
    fun fitScale(dims: PageDims, itemWidthPx: Int): Float = fit(dims, itemWidthPx).toFloat()

    /**
     * The on-screen part of one laid-out item, in unscaled item pixels, or null if none is on
     * screen. The list is laid out in unscaled content pixels (see `ZoomableList`): the zoomed
     * viewport's horizontal range is `[offsetX / zoom, (offsetX + viewportWidthPx) / zoom]` and items
     * start [gapPx] from its left edge; vertically the item at [itemOffset] (`LazyListItemInfo.offset`)
     * of height [itemHeightPx] is cut by `[viewportStartOffset, viewportEndOffset)`.
     */
    fun visibleRect(
        itemWidthPx: Int,
        itemHeightPx: Int,
        gapPx: Int,
        zoom: Float,
        offsetX: Float,
        viewportWidthPx: Int,
        viewportStartOffset: Int,
        viewportEndOffset: Int,
        itemOffset: Int,
    ): ItemRect? {
        if (!(zoom > 0f) || !zoom.isFinite()) return null
        val w = itemWidthPx.toFloat()
        val h = itemHeightPx.toFloat()
        val left = (offsetX / zoom - gapPx).coerceIn(0f, w)
        val right = ((offsetX + viewportWidthPx) / zoom - gapPx).coerceIn(0f, w)
        val top = (viewportStartOffset.toLong() - itemOffset).toFloat().coerceIn(0f, h)
        val bottom = (viewportEndOffset.toLong() - itemOffset).toFloat().coerceIn(0f, h)
        val rect = ItemRect(left, top, right, bottom)
        return if (rect.isEmpty) null else rect
    }

    /**
     * Where to draw a bitmap that shows [rect] (item pixels of an item [rectItemWidthPx] wide) in an
     * item drawn [drawWidthPx] wide: [rect] scaled by `drawWidthPx / rectItemWidthPx`, rounded to
     * whole pixels. Null if that is empty or not finite.
     */
    fun drawRect(rect: ItemRect, rectItemWidthPx: Int, drawWidthPx: Float): DrawRect? {
        if (rectItemWidthPx < 1 || !(drawWidthPx > 0f) || !drawWidthPx.isFinite()) return null
        val k = drawWidthPx.toDouble() / rectItemWidthPx
        val v = listOf(rect.left, rect.top, rect.right, rect.bottom).map { it * k }
        if (v.any { !it.isFinite() }) return null
        val (l, t, r, b) = v.map { Math.round(it).coerceIn(-DRAW_LIMIT, DRAW_LIMIT).toInt() }
        return if (r > l && b > t) DrawRect(l, t, r, b) else null
    }

    private const val DRAW_LIMIT = 1L shl 30

    /** The page with the largest visible height (ties: the first), or null if none is visible. */
    fun currentPage(visible: List<VisiblePage>): Int? =
        visible.filter { !it.rect.isEmpty }.maxByOrNull { it.rect.height }?.index

    /**
     * The full-page request for [page] ([dims]) in an item [itemWidthPx] wide: at the fit scale,
     * capped at [RenderLimits.MAX_SCALE] and lowered until it has at most [maxPixels] pixels.
     */
    fun baseRequest(page: Int, dims: PageDims, itemWidthPx: Int, maxPixels: Long): RenderRequest {
        var scale = min(fitScale(dims, itemWidthPx), RenderLimits.MAX_SCALE)
        // Float rounding can make ceil(widthPt * fit) one pixel wider than the page's rect.
        val maxWidth = max(1L, ceil(pageRect(dims, itemWidthPx).width.toDouble()).toLong())
        repeat(8) { if (RenderLimits.scaledWidth(dims, scale) > maxWidth) scale = Math.nextDown(scale) }
        scale = fitPixels(scale, maxPixels) { s -> RenderLimits.scaledWidth(dims, s).toDouble() * RenderLimits.scaledHeight(dims, s) }
        return RenderRequest(
            page,
            scale,
            0,
            0,
            RenderLimits.scaledWidth(dims, scale).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
            RenderLimits.scaledHeight(dims, scale).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    /** The base render of [page]; covers the page's [pageRect]. */
    fun basePlan(page: Int, dims: PageDims, itemWidthPx: Int, limits: ReaderLimits): PlannedRender =
        PlannedRender(
            RenderKind.BASE,
            baseRequest(page, dims, itemWidthPx, limits.basePagePixels),
            pageRect(dims, itemWidthPx),
            itemWidthPx,
        )

    /**
     * The detail render of [page] for its on-screen [visible] rect at [zoom], or null when none is
     * needed (zoom about 1, nothing visible, or the base is already as sharp). The rect plus
     * [ReaderLimits.detailMargin] is rendered at `fitScale * zoom`, capped at
     * [RenderLimits.MAX_SCALE]; if that exceeds [ReaderLimits.detailPixels], the margin is dropped and
     * then the scale lowered (the rect is never cropped, so the result always covers [visible]).
     */
    fun detailPlan(page: Int, dims: PageDims, itemWidthPx: Int, zoom: Float, visible: ItemRect, limits: ReaderLimits): PlannedRender? {
        if (!zoom.isFinite() || zoom <= NO_DETAIL_ZOOM || itemWidthPx < 1) return null
        val fit = fitScale(dims, itemWidthPx)
        val baseScale = baseRequest(page, dims, itemWidthPx, limits.basePagePixels).scale
        val target = min(fit * max(zoom, 1f), RenderLimits.MAX_SCALE)
        val pr = pageRect(dims, itemWidthPx)
        // The visible part of the page, relative to the page's top-left corner.
        val pw = pr.width
        val ph = pr.height
        val clamped = ItemRect(
            (visible.left - pr.left).coerceIn(0f, pw),
            (visible.top - pr.top).coerceIn(0f, ph),
            (visible.right - pr.left).coerceIn(0f, pw),
            (visible.bottom - pr.top).coerceIn(0f, ph),
        )
        if (clamped.isEmpty) return null
        val mx = clamped.width * limits.detailMargin
        val my = clamped.height * limits.detailMargin
        val withMargin = ItemRect(
            max(0f, clamped.left - mx),
            max(0f, clamped.top - my),
            min(pw, clamped.right + mx),
            min(ph, clamped.bottom + my),
        )
        var scale = target
        var region = withMargin
        if (pixelRect(dims, fit, scale, region).pixels > limits.detailPixels) region = clamped
        scale = fitPixels(scale, limits.detailPixels) { s -> pixelRect(dims, fit, s, region).pixels.toDouble() }
        if (scale < baseScale * MIN_DETAIL_GAIN) return null
        val px = pixelRect(dims, fit, scale, region)
        val k = scale / fit
        return PlannedRender(
            RenderKind.DETAIL,
            RenderRequest(page, scale, px.left, px.top, px.right - px.left, px.bottom - px.top),
            ItemRect(pr.left + px.left / k, pr.top + px.top / k, pr.left + px.right / k, pr.top + px.bottom / k),
            itemWidthPx,
            ItemRect(clamped.left + pr.left, clamped.top + pr.top, clamped.right + pr.left, clamped.bottom + pr.top),
        )
    }

    /**
     * Everything the reader wants for [viewport] of a document with [pages], in the order to render:
     * 1. the base of every visible page, most visible first;
     * 2. the detail of every visible page (same order), when zoomed in;
     * 3. the base of up to [ReaderLimits.neighbours] pages on each side (nearest first, after
     *    before before), only while all base renders so far fit [ReaderLimits.baseCacheBytes].
     * Visible pages with an invalid index are ignored.
     */
    fun plan(pages: List<PageDims>, viewport: ReaderViewport, limits: ReaderLimits): List<PlannedRender> {
        val width = viewport.itemWidthPx
        if (width < 1) return emptyList()
        val visible = viewport.visible
            .filter { it.index in pages.indices && !it.rect.isEmpty }
            .distinctBy { it.index }
            .sortedByDescending { it.rect.width * it.rect.height }
        if (visible.isEmpty()) return emptyList()
        val out = ArrayList<PlannedRender>()
        var bytes = 0L
        for (v in visible) {
            val base = basePlan(v.index, pages[v.index], width, limits)
            out += base
            bytes += base.bytes
        }
        for (v in visible) {
            detailPlan(v.index, pages[v.index], width, viewport.zoom, v.rect, limits)?.let { out += it }
        }
        val first = visible.minOf { it.index }
        val last = visible.maxOf { it.index }
        val shown = visible.map { it.index }.toSet()
        outer@ for (d in 1..limits.neighbours) {
            for (index in intArrayOf(last + d, first - d)) {
                if (index !in pages.indices || index in shown) continue
                val base = basePlan(index, pages[index], width, limits)
                if (bytes + base.bytes > limits.baseCacheBytes) break@outer
                out += base
                bytes += base.bytes
            }
        }
        return out
    }

    private class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val pixels: Long get() = (right - left).toLong() * (bottom - top)
    }

    /** The integer pixel rectangle of [region] (item pixels at [fit]) on the page at [scale], inside the scaled page. */
    private fun pixelRect(dims: PageDims, fit: Float, scale: Float, region: ItemRect): PixelRect {
        val k = scale.toDouble() / fit
        val w = RenderLimits.scaledWidth(dims, scale)
        val h = RenderLimits.scaledHeight(dims, scale)
        val left = floor(region.left * k).toLong().coerceIn(0, max(0, w - 1))
        val top = floor(region.top * k).toLong().coerceIn(0, max(0, h - 1))
        val right = ceil(region.right * k).toLong().coerceIn(left + 1, max(left + 1, w))
        val bottom = ceil(region.bottom * k).toLong().coerceIn(top + 1, max(top + 1, h))
        return PixelRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /** [scale], lowered until [pixelsAt] is at most [maxPixels] (always > 0). */
    private inline fun fitPixels(start: Float, maxPixels: Long, pixelsAt: (Float) -> Double): Float {
        var scale = start
        var pixels = pixelsAt(scale)
        if (pixels > maxPixels) {
            scale = (scale * sqrt(maxPixels.toDouble() / pixels)).toFloat()
            pixels = pixelsAt(scale)
        }
        while (pixels > maxPixels && scale > Float.MIN_VALUE) {
            scale *= 0.98f
            pixels = pixelsAt(scale)
        }
        return scale
    }
}

/** A rectangle in whole drawing pixels; `right > left` and `bottom > top` (both exclusive). */
data class DrawRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * What the screen shows: items [itemWidthPx] wide at zoom 1, drawn [zoom] times larger, and the
 * [visible] pages. Only a settled zoom should be passed (see `ZoomableListState.isZooming`).
 */
data class ReaderViewport(val itemWidthPx: Int, val zoom: Float, val visible: List<VisiblePage>)
