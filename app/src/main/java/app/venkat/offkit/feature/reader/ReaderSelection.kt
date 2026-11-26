package app.venkat.offkit.feature.reader

import app.venkat.offkit.engine.PageDims
import app.venkat.offkit.engine.PageText
import app.venkat.offkit.engine.PdfError
import app.venkat.offkit.engine.TextRect
import kotlin.math.max
import kotlin.math.min

/**
 * A text selection on one page. [anchor] and [focus] are the character indices under the two
 * handles; they are unordered (a handle dragged past the other one just swaps the roles of start
 * and end), and both are always within [text]. [all] is true after "Select all" (and stays true
 * only while the range is still the whole page).
 */
data class ReaderSelection(
    val page: Int,
    val text: PageText,
    val anchor: Int,
    val focus: Int,
    val all: Boolean = false,
) {
    /** The selected characters, in reading order. */
    val range: IntRange get() = min(anchor, focus)..max(anchor, focus)

    /** The selected text, as copied. */
    fun string(): String = text.string(range)

    /** Highlight rectangles in page points, one per line. */
    fun rects(): List<TextRect> = text.rects(range)

    /** True when the copied text is the page's whole text and the engine cut that text. */
    val cut: Boolean get() = text.truncated && all
}

/** How long the text cache may grow. A page being selected is kept even if it alone is over [chars]. */
data class ReaderTextLimits(val pages: Int = 8, val chars: Int = 400_000) {
    init {
        require(pages >= 1 && chars >= 0)
    }
}

/** One-shot results of text actions, for the screen to show or act on. */
sealed interface ReaderTextEvent {
    /** Put [text] on the clipboard. [cut]: the page's text was too long and is only a part. */
    data class Copy(val text: String, val cut: Boolean) : ReaderTextEvent

    /** The page has no text at all (a scan, or only images). */
    data object NoText : ReaderTextEvent

    /** The page's text could not be read. */
    data class Failed(val error: PdfError) : ReaderTextEvent
}

/** What the screen shows after a copy. */
enum class CopyNotice { NONE, COPIED, COPIED_CUT, CUT }

/**
 * Pure selection maths. Item pixels are the unscaled pixels of a page item (the coordinates inside
 * the zoomed list, where a page is drawn into its [ItemRect]); page points are [PageText]'s.
 */
object SelectionMath {
    /** Hit-test tolerance for a long-press, on screen. */
    const val TOLERANCE_DP = 24f

    /** Touch target of a handle, on screen. */
    const val HANDLE_TOUCH_DP = 48f

    /** Drawn diameter of a handle's knob, on screen. */
    const val HANDLE_KNOB_DP = 20f

    /** Item pixels of [dp] density-independent pixels shown on screen at zoom [scale]. */
    fun screenDpToItemPx(dp: Float, density: Float, scale: Float): Float = dp * density / scale

    /** Page points per item pixel for a page of [dims] drawn into [rect]. */
    fun pointsPerItemPx(dims: PageDims, rect: ItemRect): Float {
        val w = rect.right - rect.left
        return if (w > 0f) dims.widthPt / w else 1f
    }

    /** Long-press tolerance in page points: [TOLERANCE_DP] on screen at zoom [scale]. */
    fun tolerancePt(density: Float, scale: Float, dims: PageDims, rect: ItemRect): Float =
        screenDpToItemPx(TOLERANCE_DP, density, scale) * pointsPerItemPx(dims, rect)

    /** Page point (x, y) of the item pixel ([x], [y]), clamped to the page when [clamp]. */
    fun itemToPage(x: Float, y: Float, dims: PageDims, rect: ItemRect, clamp: Boolean = false): Pair<Float, Float> {
        val w = (rect.right - rect.left).takeIf { it > 0f } ?: 1f
        val h = (rect.bottom - rect.top).takeIf { it > 0f } ?: 1f
        var px = (x - rect.left) / w * dims.widthPt
        var py = (y - rect.top) / h * dims.heightPt
        if (clamp) {
            px = px.coerceIn(0f, dims.widthPt)
            py = py.coerceIn(0f, dims.heightPt)
        }
        return px to py
    }

    /** Item pixel (x, y) of the page point ([xPt], [yPt]). */
    fun pageToItem(xPt: Float, yPt: Float, dims: PageDims, rect: ItemRect): Pair<Float, Float> {
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top
        return (rect.left + xPt / dims.widthPt * w) to (rect.top + yPt / dims.heightPt * h)
    }

    /** A page-point rectangle in item pixels. */
    fun pageRectToItem(r: TextRect, dims: PageDims, rect: ItemRect): ItemRect {
        val (x0, y0) = pageToItem(r.x0, r.y0, dims, rect)
        val (x1, y1) = pageToItem(r.x1, r.y1, dims, rect)
        return ItemRect(x0, y0, x1, y1)
    }

    /** The word under ([xPt], [yPt]) within [tolerancePt], or null (no text there). */
    fun selectWord(page: Int, text: PageText, xPt: Float, yPt: Float, tolerancePt: Float): ReaderSelection? {
        val i = text.charAt(xPt, yPt, tolerancePt) ?: return null
        val w = text.wordRange(i)
        if (w.isEmpty()) return null
        return ReaderSelection(page, text, w.first, w.last)
    }

    /** All the text of [page], or null if it has none. */
    fun selectAll(page: Int, text: PageText): ReaderSelection? =
        if (text.length == 0) null else ReaderSelection(page, text, 0, text.length - 1, all = true)

    /**
     * Moves the [anchorHandle] (else the focus handle) of [s] to the character nearest the page
     * point ([xPt], [yPt]), which is clamped to the page [dims] first (a drag never leaves the page).
     */
    fun drag(s: ReaderSelection, anchorHandle: Boolean, xPt: Float, yPt: Float, dims: PageDims): ReaderSelection {
        val x = xPt.coerceIn(0f, dims.widthPt)
        val y = yPt.coerceIn(0f, dims.heightPt)
        val i = s.text.charAt(x, y) ?: return s
        val next = if (anchorHandle) s.copy(anchor = i) else s.copy(focus = i)
        return next.copy(all = next.all && next.range == 0 until s.text.length)
    }

    /**
     * Where a handle sits, in page points: under the left edge of the first selected character for
     * the start handle, under the right edge of the last for the end handle.
     */
    fun handlePoint(s: ReaderSelection, start: Boolean): Pair<Float, Float> {
        val b = s.text.box(if (start) s.range.first else s.range.last)
        return (if (start) b.x0 else b.x1) to b.y1
    }

    /** The point a handle aims at: half a line above where it sits (the middle of its character). */
    fun handleAim(s: ReaderSelection, index: Int): Float {
        val b = s.text.box(index)
        return (b.y1 - b.y0) / 2f
    }

    /** What to say after a copy: Android 13 (API 33) and later show their own "copied" notice. */
    fun copyNotice(sdkInt: Int, cut: Boolean): CopyNotice = when {
        sdkInt >= 33 -> if (cut) CopyNotice.CUT else CopyNotice.NONE
        else -> if (cut) CopyNotice.COPIED_CUT else CopyNotice.COPIED
    }
}
