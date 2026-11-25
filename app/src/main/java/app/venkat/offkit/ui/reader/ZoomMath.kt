package app.venkat.offkit.ui.reader

import androidx.compose.ui.geometry.Offset

/**
 * One zoom step computed by [ZoomMath.zoom]: the new [scale] and [offsetX], and how far the
 * list must scroll ([scrollDelta], in unscaled content pixels, positive = towards the end).
 */
internal data class ZoomStep(val scale: Float, val offsetX: Float, val scrollDelta: Float)

/**
 * Pure maths behind [ZoomableList], free of Compose so it can be unit-tested on the JVM.
 *
 * Coordinates: the list is drawn with a top-left transform origin, so a content point at
 * `(x, y)` (x in unscaled layout pixels, y in unscaled pixels below the list's scroll top)
 * appears on screen at `(x * scale - offsetX, y * scale)`.
 */
internal object ZoomMath {
    /** Target of a double tap when not zoomed (clamped to the state's range). */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** Factor applied by the accessibility zoom in / zoom out actions. */
    const val STEP_FACTOR = 1.5f

    /** Scales within this distance of `minScale` count as "not zoomed" for the double-tap toggle. */
    private const val EPSILON = 0.01f

    fun clampScale(scale: Float, minScale: Float, maxScale: Float): Float =
        scale.coerceIn(minScale, maxScale)

    /** Largest pan that still shows no empty space at the right edge. */
    fun maxOffsetX(scale: Float, viewportWidth: Float): Float =
        (viewportWidth * (scale - 1f)).coerceAtLeast(0f)

    /** Clamp [offsetX] to `[0, maxOffsetX]`; always 0 at (or below) [minScale]. */
    fun clampOffsetX(offsetX: Float, scale: Float, minScale: Float, viewportWidth: Float): Float =
        if (scale <= minScale) 0f else offsetX.coerceIn(0f, maxOffsetX(scale, viewportWidth))

    /**
     * Zoom from [scale] to [targetScale] (clamped) so that the content point under [from]
     * ends up under [to]. `from == to` is a pure zoom; `from != to` also pans (a moving pinch).
     * The vertical part is returned as a scroll delta; it is exact only while the list is
     * free to scroll that far (at the very top or end it is clamped by the list).
     */
    fun zoom(
        scale: Float,
        offsetX: Float,
        targetScale: Float,
        minScale: Float,
        maxScale: Float,
        viewportWidth: Float,
        from: Offset,
        to: Offset,
    ): ZoomStep {
        val newScale = clampScale(targetScale, minScale, maxScale)
        val contentX = (from.x + offsetX) / scale
        val newOffsetX = clampOffsetX(contentX * newScale - to.x, newScale, minScale, viewportWidth)
        val scrollDelta = from.y / scale - to.y / newScale
        return ZoomStep(newScale, newOffsetX, scrollDelta)
    }

    /** One-finger pan by a screen-pixel [delta] while zoomed. */
    fun pan(
        scale: Float,
        offsetX: Float,
        minScale: Float,
        viewportWidth: Float,
        delta: Offset,
    ): ZoomStep = ZoomStep(
        scale = scale,
        offsetX = clampOffsetX(offsetX - delta.x, scale, minScale, viewportWidth),
        scrollDelta = -delta.y / scale,
    )

    /** Where a double tap goes: back to [minScale] when zoomed, else to 2.5x within range. */
    fun doubleTapTarget(scale: Float, minScale: Float, maxScale: Float): Float =
        if (scale > minScale + EPSILON) minScale
        else clampScale(DOUBLE_TAP_SCALE, minScale, maxScale)

    /** Unscaled content point (relative to the scroll top) under the screen point [screen]. */
    fun contentPoint(screen: Offset, scale: Float, offsetX: Float): Offset =
        Offset((screen.x + offsetX) / scale, screen.y / scale)

    /** Height to measure the list at so that, once scaled, it exactly fills [viewportHeight]. */
    fun listHeight(viewportHeight: Int, scale: Float): Int =
        kotlin.math.ceil(viewportHeight / scale).toInt().coerceAtLeast(1)
}
