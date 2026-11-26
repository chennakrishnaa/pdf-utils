package app.venkat.offkit.ui.reader

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import app.venkat.offkit.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * State of a [ZoomableList]: the zoom [scale] and the horizontal pan [offsetX]. The vertical
 * position is the [LazyListState] passed to [ZoomableList].
 *
 * To re-render content at the new resolution only after a gesture settles, observe
 * `snapshotFlow { state.isZooming to state.scale }` and act when `isZooming` is false.
 */
@Stable
class ZoomableListState(val minScale: Float = 1f, val maxScale: Float = 5f) {
    init {
        require(minScale >= 1f) { "minScale must be >= 1 (zooming out would break laziness): $minScale" }
        require(maxScale >= minScale) { "maxScale ($maxScale) must be >= minScale ($minScale)" }
    }

    /** Current zoom, always within [minScale, maxScale]. */
    var scale: Float by mutableFloatStateOf(minScale)
        private set

    /** Horizontal pan in viewport pixels, clamped so no empty space shows at either edge. */
    var offsetX: Float by mutableFloatStateOf(0f)
        private set

    /**
     * Set by a child that owns the current one-finger drag (a text selection handle), from its
     * own down event: while true, the list does not turn that drag into a pan. The child must
     * reset it when its pointer goes up, is cancelled or the child leaves the composition.
     */
    var childDragging: Boolean = false

    private var pinchActive by mutableStateOf(false)
    private var animations by mutableIntStateOf(0)

    /** True while a pinch or a zoom animation is in progress. */
    val isZooming: Boolean get() = pinchActive || animations > 0

    internal var viewportWidth = 0f
        private set
    internal var viewportHeight = 0f
        private set

    /** Records the viewport size and re-applies the horizontal clamp. */
    internal fun setViewportSize(width: Float, height: Float) {
        viewportWidth = width
        viewportHeight = height
        if (width > 0f) offsetX = ZoomMath.clampOffsetX(offsetX, scale, minScale, width)
    }

    /**
     * Zooms to [target] so the content under [from] ends under [to].
     * Returns the vertical scroll the list must perform (content pixels).
     */
    internal fun zoomTo(target: Float, from: Offset, to: Offset): Float {
        val step = ZoomMath.zoom(scale, offsetX, target, minScale, maxScale, viewportWidth, from, to)
        scale = step.scale
        offsetX = step.offsetX
        return step.scrollDelta
    }

    /** Pans by a screen-pixel [delta]; returns the vertical scroll the list must perform. */
    internal fun panBy(delta: Offset): Float {
        val step = ZoomMath.pan(scale, offsetX, minScale, viewportWidth, delta)
        offsetX = step.offsetX
        return step.scrollDelta
    }

    internal fun setPinching(active: Boolean) {
        pinchActive = active
    }

    internal fun animationStarted() {
        animations++
    }

    internal fun animationEnded() {
        animations--
    }

    companion object {
        /** Saves min/max, scale and offsetX. The offset is re-clamped once the viewport is known. */
        val Saver: Saver<ZoomableListState, *> = listSaver(
            save = { listOf(it.minScale, it.maxScale, it.scale, it.offsetX) },
            restore = {
                ZoomableListState(it[0], it[1]).apply {
                    scale = ZoomMath.clampScale(it[2], minScale, maxScale)
                    offsetX = if (scale <= minScale) 0f else it[3].coerceAtLeast(0f)
                }
            },
        )
    }
}

/** Remembers a [ZoomableListState] that survives configuration changes and process death. */
@Composable
fun rememberZoomableListState(minScale: Float = 1f, maxScale: Float = 5f): ZoomableListState =
    rememberSaveable(minScale, maxScale, saver = ZoomableListState.Saver) {
        ZoomableListState(minScale, maxScale)
    }

/**
 * A vertical lazy list that zooms as one surface, like a document viewer.
 *
 * Approach
 * --------
 * A plain LazyColumn is placed with a layer (scale s, top-left transform origin,
 * translationX = -offsetX) inside an unscaled, clipped Box. The list is *measured* at the
 * viewport width and at height ceil(H / s), so once scaled it exactly fills the viewport:
 * the region LazyColumn composes is exactly the visible region at every scale (laziness is
 * preserved, nothing visible is missing, and the end of the list is reachable when zoomed).
 * Vertical position is the list's own scroll; horizontal position is offsetX.
 *
 * Gestures are observed by one pointer handler on the outer Box in the Initial pass, so it
 * sees events before the list and its children:
 *  - two or more fingers: pinch (zoom about the centroid, plus pan); the events are consumed,
 *    which cancels any drag the list had started;
 *  - one finger at minScale: nothing is consumed, the LazyColumn scrolls and flings itself;
 *  - one finger when zoomed: once past touch slop the drag is consumed and pans both axes
 *    (vertical via listState.dispatchRawDelta(-dy / s)); on release a vertical fling runs
 *    through listState.scroll with the default fling behaviour;
 *  - taps are only observed, never consumed, so clickable children keep working. Two quick
 *    taps close together toggle the zoom (animated, about the tapped point).
 *
 * Known limitations
 *  - Zooming about a centroid near the very top or end of the list cannot keep the point
 *    under the centroid vertically: the list's scroll is clamped there.
 *  - No horizontal fling; horizontal pan stops when the finger lifts.
 *  - A double tap on a clickable child also delivers two clicks to that child.
 *  - When a pinch ends at minScale with one finger still down, that finger does not scroll
 *    the list until it is lifted (the list's drag was cancelled by the pinch).
 *  - The container needs a bounded height (do not put it inside a vertical scroll).
 *  - If minScale > 1, offsetX is still forced to 0 at minScale (as specified), so the right
 *    part of the content is only reachable after zooming in further.
 *  - The list's own scrollbar/overscroll effects are drawn inside the scaled layer.
 */
@Composable
fun ZoomableList(
    state: ZoomableListState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    val controller = remember(state, listState, scope) { ZoomController(state, listState, scope) }
    controller.flingBehavior = fling

    val zoomInLabel = stringResource(R.string.reader_zoom_in)
    val zoomOutLabel = stringResource(R.string.reader_zoom_out)
    val canZoomIn by remember(state) { derivedStateOf { state.scale < state.maxScale } }
    val canZoomOut by remember(state) { derivedStateOf { state.scale > state.minScale } }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { state.setViewportSize(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(controller) { with(controller) { detectZoomGestures() } }
            .semantics {
                customActions = buildList {
                    if (canZoomIn) {
                        add(CustomAccessibilityAction(zoomInLabel) { controller.step(ZoomMath.STEP_FACTOR) })
                    }
                    if (canZoomOut) {
                        add(CustomAccessibilityAction(zoomOutLabel) { controller.step(1f / ZoomMath.STEP_FACTOR) })
                    }
                }
            },
    ) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.layout { measurable, constraints ->
                check(constraints.hasBoundedHeight && constraints.hasBoundedWidth) {
                    "ZoomableList needs a bounded size"
                }
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val placeable = measurable.measure(
                    Constraints.fixed(width, ZoomMath.listHeight(height, state.scale)),
                )
                layout(width, height) {
                    placeable.placeWithLayer(0, 0) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = state.scale
                        scaleY = state.scale
                        translationX = -state.offsetX
                    }
                }
            },
            content = content,
        )
    }
}

/** Gesture and animation glue between a [ZoomableListState] and a [LazyListState]. */
private class ZoomController(
    private val state: ZoomableListState,
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    var flingBehavior: FlingBehavior? = null
    private var animationJob: Job? = null
    private var flingJob: Job? = null
    private var lastTapUptime = -1L
    private var lastTapPosition = Offset.Zero

    private fun cancelMotion() {
        animationJob?.cancel()
        flingJob?.cancel()
    }

    private fun scrollList(delta: Float) {
        if (delta != 0f) listState.dispatchRawDelta(delta)
    }

    fun animateZoomTo(target: Float, centre: Offset) {
        cancelMotion()
        animationJob = scope.launch {
            state.animationStarted()
            try {
                animate(state.scale, target) { value, _ -> scrollList(state.zoomTo(value, centre, centre)) }
            } finally {
                state.animationEnded()
            }
        }
    }

    /** Accessibility step about the viewport centre. */
    fun step(factor: Float): Boolean {
        val target = ZoomMath.clampScale(state.scale * factor, state.minScale, state.maxScale)
        if (target == state.scale) return false
        animateZoomTo(target, Offset(state.viewportWidth / 2f, state.viewportHeight / 2f))
        return true
    }

    private fun flingVertically(screenVelocityY: Float) {
        val behavior = flingBehavior ?: return
        val velocity = -screenVelocityY / state.scale
        if (velocity == 0f) return
        flingJob = scope.launch {
            listState.scroll { with(behavior) { performFling(velocity) } }
        }
    }

    private fun onTap(downUptime: Long, upUptime: Long, position: Offset, timeout: Long, slop: Float) {
        val isSecond = lastTapUptime >= 0 && downUptime - lastTapUptime <= timeout &&
            (position - lastTapPosition).getDistance() <= slop
        if (isSecond) {
            lastTapUptime = -1L
            animateZoomTo(ZoomMath.doubleTapTarget(state.scale, state.minScale, state.maxScale), position)
        } else {
            lastTapUptime = upUptime
            lastTapPosition = position
        }
    }

    suspend fun PointerInputScope.detectZoomGestures() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            cancelMotion()
            val slop = viewConfiguration.touchSlop
            var tracker = VelocityTracker()
            var trackedId: PointerId = down.id
            tracker.addPosition(down.uptimeMillis, down.position)
            var moved = Offset.Zero
            var multiTouch = false
            var pinching = false
            var panning = false
            var upUptime = down.uptimeMillis
            var upPosition = down.position
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        val up = event.changes.firstOrNull { it.id == trackedId } ?: event.changes.first()
                        upUptime = up.uptimeMillis
                        upPosition = up.position
                        break
                    }
                    if (pressed.size >= 2) {
                        multiTouch = true
                        panning = false
                        if (!pinching) {
                            pinching = true
                            state.setPinching(true)
                        }
                        val zoom = event.calculateZoom()
                        val from = event.calculateCentroid(useCurrent = false)
                        val to = event.calculateCentroid(useCurrent = true)
                        if (from.isSpecified && to.isSpecified && (zoom != 1f || from != to)) {
                            scrollList(state.zoomTo(state.scale * zoom, from, to))
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } else {
                        if (pinching) {
                            pinching = false
                            state.setPinching(false)
                        }
                        val change = pressed[0]
                        if (change.id != trackedId) {
                            trackedId = change.id
                            tracker = VelocityTracker()
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val delta = change.positionChange()
                        if (!panning) {
                            moved += delta
                            if (moved.getDistance() > slop && state.scale > state.minScale && !state.childDragging) panning = true
                        }
                        if (panning) {
                            scrollList(state.panBy(delta))
                            change.consume()
                        }
                    }
                }
            } finally {
                if (pinching) state.setPinching(false)
            }
            if (panning) {
                flingVertically(tracker.calculateVelocity().y)
            } else if (!multiTouch && moved.getDistance() <= slop &&
                upUptime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
            ) {
                onTap(
                    downUptime = down.uptimeMillis,
                    upUptime = upUptime,
                    position = upPosition,
                    timeout = viewConfiguration.doubleTapTimeoutMillis,
                    slop = slop * 4f,
                )
            } else {
                lastTapUptime = -1L
            }
        }
    }
}
