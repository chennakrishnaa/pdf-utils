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

    /** Item height in pixels of a page [dims] in an item [itemWidthPx] wide (at least 1). */
    fun itemHeightPx(dims: PageDims, itemWidthPx: Int): Int =
        max(1, (itemWidthPx.toDouble() * dims.heightPt / dims.widthPt).roundToInt())

    /** Pixels per PDF point at which page [dims] fills an item [itemWidthPx] wide. */
    fun fitScale(dims: PageDims, itemWidthPx: Int): Float = itemWidthPx / dims.widthPt

    /** The page with the largest visible height (ties: the first), or null if none is visible. */
    fun currentPage(visible: List<VisiblePage>): Int? =
        visible.filter { !it.rect.isEmpty }.maxByOrNull { it.rect.height }?.index

    /**
     * The full-page request for [page] ([dims]) in an item [itemWidthPx] wide: at the fit scale,
     * capped at [RenderLimits.MAX_SCALE] and lowered until it has at most [maxPixels] pixels.
     */
    fun baseRequest(page: Int, dims: PageDims, itemWidthPx: Int, maxPixels: Long): RenderRequest {
        var scale = min(fitScale(dims, itemWidthPx), RenderLimits.MAX_SCALE)
        // Float rounding can make ceil(widthPt * fit) one pixel wider than the item.
        while (RenderLimits.scaledWidth(dims, scale) > itemWidthPx && scale > Float.MIN_VALUE) scale = Math.nextDown(scale)
        scale = fitPixels(scale, maxPixels) { s -> RenderLimits.scaledWidth(dims, s) * RenderLimits.scaledHeight(dims, s) }
        return RenderRequest(
            page,
            scale,
            0,
            0,
            RenderLimits.scaledWidth(dims, scale).toInt(),
            RenderLimits.scaledHeight(dims, scale).toInt(),
        )
    }

    /** The base render of [page]; covers the whole item. */
    fun basePlan(page: Int, dims: PageDims, itemWidthPx: Int, limits: ReaderLimits): PlannedRender {
        val itemHeight = itemWidthPx * dims.heightPt / dims.widthPt
        return PlannedRender(
            RenderKind.BASE,
            baseRequest(page, dims, itemWidthPx, limits.basePagePixels),
            ItemRect(0f, 0f, itemWidthPx.toFloat(), itemHeight),
            itemWidthPx,
        )
    }

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
        val itemW = itemWidthPx.toFloat()
        val itemH = itemWidthPx * dims.heightPt / dims.widthPt
        val clamped = ItemRect(
            visible.left.coerceIn(0f, itemW),
            visible.top.coerceIn(0f, itemH),
            visible.right.coerceIn(0f, itemW),
            visible.bottom.coerceIn(0f, itemH),
        )
        if (clamped.isEmpty) return null
        val baseScale = baseRequest(page, dims, itemWidthPx, limits.basePagePixels).scale
        val mx = clamped.width * limits.detailMargin
        val my = clamped.height * limits.detailMargin
        val withMargin = ItemRect(
            max(0f, clamped.left - mx),
            max(0f, clamped.top - my),
            min(itemW, clamped.right + mx),
            min(itemH, clamped.bottom + my),
        )
        var scale = min(fit * zoom, RenderLimits.MAX_SCALE)
        var region = withMargin
        if (pixelRect(dims, fit, scale, region).pixels > limits.detailPixels) region = clamped
        scale = fitPixels(scale, limits.detailPixels) { s -> pixelRect(dims, fit, s, region).pixels }
        if (scale < baseScale * MIN_DETAIL_GAIN) return null
        val px = pixelRect(dims, fit, scale, region)
        val k = scale / fit
        return PlannedRender(
            RenderKind.DETAIL,
            RenderRequest(page, scale, px.left, px.top, px.right - px.left, px.bottom - px.top),
            ItemRect(px.left / k, px.top / k, px.right / k, px.bottom / k),
            itemWidthPx,
            clamped,
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
        val left = floor(region.left * k).toLong().coerceIn(0, w - 1)
        val top = floor(region.top * k).toLong().coerceIn(0, h - 1)
        val right = ceil(region.right * k).toLong().coerceIn(left + 1, w)
        val bottom = ceil(region.bottom * k).toLong().coerceIn(top + 1, h)
        return PixelRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /** [scale], lowered until [pixelsAt] is at most [maxPixels] (always > 0). */
    private inline fun fitPixels(start: Float, maxPixels: Long, pixelsAt: (Float) -> Long): Float {
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

/**
 * What the screen shows: items [itemWidthPx] wide at zoom 1, drawn [zoom] times larger, and the
 * [visible] pages. Only a settled zoom should be passed (see `ZoomableListState.isZooming`).
 */
data class ReaderViewport(val itemWidthPx: Int, val zoom: Float, val visible: List<VisiblePage>)
