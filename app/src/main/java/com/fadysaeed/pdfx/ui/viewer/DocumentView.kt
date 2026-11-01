package com.fadysaeed.pdfx.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fadysaeed.pdfx.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

/**
 * Continuous vertical reading with pinch and double-tap zoom. Zoom widens the page column and
 * scrolls in both directions with normal flinging; once the fingers settle, the visible pages are
 * drawn again at the new size so text stays sharp.
 */
@Composable
fun DocumentView(
    pageCount: Int,
    aspects: List<Float>,
    image: (Int) -> ImageBitmap?,
    listState: LazyListState,
    onWant: (Want) -> Unit,
    onTap: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Ink.Black).clipToBounds()) {
        val density = LocalDensity.current
        val viewportPx = constraints.maxWidth.toFloat()
        var zoom by remember { mutableFloatStateOf(1f) }
        var settledZoom by remember { mutableFloatStateOf(1f) }
        // How far the widened column is slid left, in pixels. Owned here, not by a scroll
        // container, so a pinch can move it in the same frame the column grows.
        var panX by remember { mutableFloatStateOf(0f) }
        val tap by rememberUpdatedState(onTap)
        val want by rememberUpdatedState(onWant)
        val scope = rememberCoroutineScope()
        val decay = rememberSplineBasedDecay<Float>()
        val fling = remember { arrayOfNulls<Job>(1) }

        fun clampX(x: Float) = x.coerceIn(0f, (viewportPx * (zoom - 1f)).coerceAtLeast(0f))

        /**
         * Scales about [focus] and moves by [pan], keeping the exact point of the page under the
         * fingers where it was. Horizontally that is our own offset; vertically the page under the
         * fingers is re-anchored so the same line stays under them.
         */
        fun transform(factor: Float, focus: Offset, pan: Offset) {
            val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val k = next / zoom
            if (k == 1f && pan == Offset.Zero) return
            val info = listState.layoutInfo
            val vso = info.viewportStartOffset
            val items = info.visibleItemsInfo
            val anchor = items.firstOrNull { it.offset - vso <= focus.y && it.offset - vso + it.size >= focus.y }
                ?: items.minByOrNull { kotlin.math.abs(it.offset - vso + it.size / 2 - focus.y) }
            zoom = next
            panX = clampX((panX + focus.x) * k - focus.x - pan.x)
            if (anchor != null) {
                val top = (anchor.offset - vso).toFloat()
                val newTop = focus.y - (focus.y - top) * k + pan.y
                listState.requestScrollToItem(anchor.index, (-(newTop + vso)).roundToInt())
            }
        }

        // Re-render at the new size only once the pinch has come to rest.
        LaunchedEffect(zoom) {
            delay(220)
            settledZoom = zoom
        }
        LaunchedEffect(listState, pageCount, viewportPx) {
            snapshotFlow { Triple(visibleRange(listState, pageCount), centreItem(listState), settledZoom) }
                .collectLatest { (range, focus, z) ->
                    if (range.isEmpty()) return@collectLatest
                    val render = (viewportPx * bucket(z)).roundToInt()
                    want(Want(range, focus, render))
                }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Seen before the list: two fingers zoom and pan; one finger, when zoomed in,
                    // also slides sideways while the list keeps handling the up-and-down part.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        fling[0]?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var pinched = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                pinched = true
                                transform(event.calculateZoom(), event.calculateCentroid(useCurrent = true), event.calculatePan())
                                event.changes.forEach { it.consume() }
                            } else if (!pinched && zoom > 1.01f) {
                                val c = event.changes.firstOrNull { it.id == down.id }
                                if (c != null && c.pressed) {
                                    panX = clampX(panX - (c.position.x - c.previousPosition.x))
                                    tracker.addPosition(c.uptimeMillis, c.position)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!pinched && zoom > 1.01f) {
                            val vx = tracker.calculateVelocity().x
                            if (kotlin.math.abs(vx) > 250f) {
                                fling[0] = scope.launch {
                                    AnimationState(initialValue = panX, initialVelocity = -vx)
                                        .animateDecay(decay) {
                                            val c = clampX(value)
                                            panX = c
                                            if (c != value) cancelAnimation()
                                        }
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { tap() },
                        onDoubleTap = { p -> transform(if (zoom > 1.05f) 1f / zoom else 2.5f / zoom, p, Offset.Zero) },
                    )
                },
        ) {
            val columnWidth = with(density) { (viewportPx * zoom).toDp() }
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .width(columnWidth)
                    .fillMaxHeight()
                    .offset { IntOffset(-panX.roundToInt(), 0) },
            ) {
                items(pageCount, key = { it }) { i ->
                    val aspect = aspects.getOrNull(i)?.takeIf { it > 0f } ?: 0.707f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .background(Ink.Paper)
                            .semantics { contentDescription = "Page ${i + 1}" },
                    ) {
                        val img = image(i)
                        if (img != null) {
                            Image(img, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** Pages are redrawn at 1x, 1.5x or 2x the screen width; beyond 2x the image is stretched until phase 2's tiles. */
private fun bucket(z: Float): Float = when {
    z < 1.25f -> 1f
    z < 1.75f -> 1.5f
    else -> 2f
}

private fun visibleRange(state: LazyListState, count: Int): IntRange {
    val items = state.layoutInfo.visibleItemsInfo
    if (items.isEmpty() || count == 0) return IntRange.EMPTY
    val first = (items.first().index - 1).coerceAtLeast(0)
    val last = (items.last().index + 1).coerceAtMost(count - 1)
    return first..last
}

/** The page under the middle of the screen: the one being read. */
fun centreItem(state: LazyListState): Int {
    val info = state.layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return 0
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return items.firstOrNull { it.offset <= mid && it.offset + it.size >= mid }?.index
        ?: items.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - mid) }!!.index
}
