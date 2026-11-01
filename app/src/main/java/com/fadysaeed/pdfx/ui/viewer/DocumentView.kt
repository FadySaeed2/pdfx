package com.fadysaeed.pdfx.ui.viewer

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fadysaeed.pdfx.engine.Box as PageBox
import com.fadysaeed.pdfx.ui.theme.Ink
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f
/** Whole-page images are drawn up to twice the screen width; past that, sharp patches take over. */
private const val TILE_FROM = 2.05f

fun paperColor(mode: PaperMode): Color = when (mode) {
    PaperMode.PAPER -> Ink.Paper
    PaperMode.NIGHT -> Color(0xFF0C0B09)
    PaperMode.SEPIA -> Color(0xFFF4E8CE)
}

/**
 * Continuous vertical reading with pinch and double-tap zoom, search highlights, text selection
 * and links. Zoom widens the page column and pans both ways with flinging; once still, the pages
 * are redrawn at the new size, and past 2x only what is on screen is drawn, at full sharpness.
 */
@OptIn(FlowPreview::class)
@Composable
fun DocumentView(
    reader: Reader,
    aspects: List<Float>,
    widths: List<Float>,
    image: (Int) -> ImageBitmap?,
    tile: (Int) -> Tile?,
    listState: LazyListState,
    onWant: (Want) -> Unit,
    onTiles: (List<TileReq>) -> Unit,
    onOpenUri: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val pageCount = reader.pageCount
    BoxWithConstraints(modifier.fillMaxSize().background(Ink.Black).clipToBounds()) {
        val density = LocalDensity.current
        val viewportPx = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()
        var zoom by remember { mutableFloatStateOf(1f) }
        var settledZoom by remember { mutableFloatStateOf(1f) }
        var panX by remember { mutableFloatStateOf(0f) }
        val want by rememberUpdatedState(onWant)
        val wantTiles by rememberUpdatedState(onTiles)
        val openUri by rememberUpdatedState(onOpenUri)
        val scope = rememberCoroutineScope()
        val decay = rememberSplineBasedDecay<Float>()
        val fling = remember { arrayOfNulls<Job>(1) }
        val filter = remember(reader.mode) { pageFilter(reader.mode)?.let { ColorFilter.colorMatrix(it) } }
        val paper = paperColor(reader.mode)

        fun pageWidthPt(i: Int) = widths.getOrNull(i)?.takeIf { it > 0f } ?: 595f
        fun clampX(x: Float) = x.coerceIn(0f, (viewportPx * (zoom - 1f)).coerceAtLeast(0f))

        /** Scales about [focus] and moves by [pan], keeping the point under the fingers in place. */
        fun transform(factor: Float, focus: Offset, pan: Offset) {
            val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val k = next / zoom
            if (k == 1f && pan == Offset.Zero) return
            val info = listState.layoutInfo
            val vso = info.viewportStartOffset
            val items = info.visibleItemsInfo
            val anchor = items.firstOrNull { it.offset - vso <= focus.y && it.offset - vso + it.size >= focus.y }
                ?: items.minByOrNull { abs(it.offset - vso + it.size / 2 - focus.y) }
            zoom = next
            panX = clampX((panX + focus.x) * k - focus.x - pan.x)
            if (anchor != null) {
                val top = (anchor.offset - vso).toFloat()
                val newTop = focus.y - (focus.y - top) * k + pan.y
                listState.requestScrollToItem(anchor.index, (-(newTop + vso)).roundToInt())
            }
        }

        /** Where a point on page [i] is on screen, for a double tap that lands on a page. */
        fun onScreen(i: Int, p: Offset): Offset? {
            val info = listState.layoutInfo
            val it = info.visibleItemsInfo.firstOrNull { it.index == i } ?: return null
            return Offset(p.x - panX, (it.offset - info.viewportStartOffset) + p.y)
        }

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
        // Deep zoom: once the view is still, draw just what is on screen at full size.
        LaunchedEffect(listState, viewportPx, viewportH) {
            snapshotFlow {
                val info = listState.layoutInfo
                Triple(info.visibleItemsInfo.map { Triple(it.index, it.offset - info.viewportStartOffset, it.size) }, panX, settledZoom to zoom)
            }
                .debounce(140)
                .collectLatest { (items, px, zs) ->
                    val (z, live) = zs
                    if (z <= TILE_FROM || z != live) { wantTiles(emptyList()); return@collectLatest }
                    val colPx = (viewportPx * z).roundToInt()
                    val reqs = items.mapNotNull { (index, top, size) ->
                        val x0 = px.roundToInt().coerceIn(0, colPx)
                        val x1 = (px + viewportPx).roundToInt().coerceIn(0, colPx)
                        val y0 = (-top).coerceIn(0, size)
                        val y1 = (viewportH.roundToInt() - top).coerceIn(0, size)
                        if (x1 - x0 < 8 || y1 - y0 < 8) null else TileReq(index, colPx, x0, y0, x1 - x0, y1 - y0)
                    }
                    wantTiles(reqs)
                }
        }
        // Search results, links in contents and bookmarks all arrive here as a jump.
        LaunchedEffect(reader.jump) {
            val j = reader.jump ?: return@LaunchedEffect
            val k = (viewportPx * zoom) / pageWidthPt(j.page)
            val y = (j.y * k - viewportH / 3f).roundToInt().coerceAtLeast(0)
            listState.scrollToItem(j.page, y)
            if (j.x != null && zoom > 1.01f) panX = clampX(j.x * k - viewportPx / 2f)
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
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
                            } else if (!pinched && zoom > 1.01f && !reader.draws(down.type.isPen())) {
                                val c = event.changes.firstOrNull { it.id == down.id }
                                if (c != null && c.pressed && !c.isConsumed) {
                                    panX = clampX(panX - (c.position.x - c.previousPosition.x))
                                    tracker.addPosition(c.uptimeMillis, c.position)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!pinched && zoom > 1.01f && !reader.draws(down.type.isPen())) {
                            val vx = tracker.calculateVelocity().x
                            if (abs(vx) > 250f) {
                                fling[0] = scope.launch {
                                    AnimationState(initialValue = panX, initialVelocity = -vx).animateDecay(decay) {
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
                    // Taps between pages.
                    detectTapGestures(
                        onTap = { if (reader.selection != null) reader.clearSelection() else reader.chrome = !reader.chrome },
                        onDoubleTap = { p -> transform(if (zoom > 1.05f) 1f / zoom else 2.5f / zoom, p, Offset.Zero) },
                    )
                },
        ) {
            val columnWidth = with(density) { (viewportPx * zoom).toDp() }
            val colPx = (viewportPx * zoom).roundToInt()
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
                    LaunchedEffect(i) { reader.loadLinks(i); reader.loadMarks(i) }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .background(paper)
                            .semantics { contentDescription = "Page ${i + 1}" }
                            .pointerInput(i, reader.marking) {
                                if (!reader.marking) return@pointerInput
                                // Marking: one finger (or the pen) draws; two fingers still zoom and scroll.
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val pen = down.type.isPen()
                                    if (pen) reader.stylusSeen = true
                                    if (!reader.draws(pen) || down.isConsumed) return@awaitEachGesture
                                    val k = size.width / pageWidthPt(i)
                                    down.consume()
                                    reader.begin(i, down.position.x / k, down.position.y / k, eraser = down.type == PointerType.Eraser)
                                    var done = false
                                    while (!done) {
                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                        if (e.changes.count { it.pressed } >= 2) { reader.cancelGesture(); break }
                                        val c = e.changes.firstOrNull { it.id == down.id } ?: run { reader.cancelGesture(); break }
                                        if (c.pressed) {
                                            val pts = c.historical.map { it.position.x / k to it.position.y / k } + (c.position.x / k to c.position.y / k)
                                            reader.extend(pts)
                                            c.consume()
                                        } else {
                                            c.consume()
                                            reader.finish()
                                            done = true
                                        }
                                    }
                                }
                            }
                            .pointerInput(i) {
                                // Dragging a selection handle; claimed before the list can scroll.
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val sel = reader.selection
                                    if (sel == null || sel.page != i) return@awaitEachGesture
                                    val k = size.width / pageWidthPt(i)
                                    val first = sel.boxes.first()
                                    val last = sel.boxes.last()
                                    val startAt = Offset(first.x0 * k, first.y1 * k)
                                    val endAt = Offset(last.x1 * k, last.y1 * k)
                                    val reach = 32.dp.toPx()
                                    val ds = (down.position - startAt).getDistance()
                                    val de = (down.position - endAt).getDistance()
                                    if (minOf(ds, de) > reach) return@awaitEachGesture
                                    val start = ds < de
                                    down.consume()
                                    do {
                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                        if (c.positionChange() != Offset.Zero) {
                                            // Aim a little above the finger so the text being picked stays visible.
                                            reader.dragSelection(start, c.position.x / k, (c.position.y - 18.dp.toPx()) / k)
                                        }
                                        c.consume()
                                    } while (c.pressed)
                                }
                            }
                            .pointerInput(i) {
                                detectTapGestures(
                                    onTap = { p ->
                                        val k = size.width / pageWidthPt(i)
                                        val link = reader.linkAt(i, p.x / k, p.y / k)
                                        val mark = reader.markAt(i, p.x / k, p.y / k)
                                        when {
                                            reader.selection != null -> reader.clearSelection()
                                            reader.selectedMark != null -> reader.selectMark(null)
                                            mark != null -> reader.selectMark(mark)
                                            link?.page != null -> reader.go(link.page)
                                            link?.uri != null -> openUri(link.uri)
                                            else -> reader.chrome = !reader.chrome
                                        }
                                    },
                                    onDoubleTap = { p ->
                                        onScreen(i, p)?.let { transform(if (zoom > 1.05f) 1f / zoom else 2.5f / zoom, it, Offset.Zero) }
                                    },
                                    onLongPress = { p ->
                                        val k = size.width / pageWidthPt(i)
                                        reader.selectWordAt(i, p.x / k, p.y / k)
                                    },
                                )
                            },
                    ) {
                        val img = image(i)
                        if (img != null) {
                            Image(img, contentDescription = null, contentScale = ContentScale.FillBounds, colorFilter = filter, modifier = Modifier.fillMaxSize())
                        }
                        val t = tile(i)
                        if (t != null && t.req.columnPx == colPx) {
                            val r = t.req
                            Image(
                                t.image, contentDescription = null, contentScale = ContentScale.FillBounds, colorFilter = filter,
                                modifier = Modifier.offset { IntOffset(r.x, r.y) }.size(with(density) { r.w.toDp() }, with(density) { r.h.toDp() }),
                            )
                        }
                        PageMarks(reader, i, pageWidthPt(i))
                    }
                }
            }
        }
    }
}

/** Search hits, the current hit, and the text selection with its two handles, drawn over a page. */
@Composable
private fun PageMarks(reader: Reader, page: Int, pageWidthPt: Float) {
    val hits = reader.hits[page]
    val sel = reader.selection?.takeIf { it.page == page }
    val cur = reader.currentHit()?.takeIf { it.first == page }?.second
    val draft = reader.draft?.takeIf { it.page == page }
    val picked = reader.selectedMark?.takeIf { it.page == page }
    if (hits == null && sel == null && draft == null && picked == null) return
    Canvas(Modifier.fillMaxSize()) {
        val k = size.width / pageWidthPt
        when (draft) {
            is Draft.Words -> draft.boxes.forEach { b -> mark(b, k, Color(draft.color).copy(alpha = 0.4f), null) }
            is Draft.Ink -> if (draft.points.isNotEmpty()) {
                val path = Path().apply {
                    moveTo(draft.points[0].first * k, draft.points[0].second * k)
                    draft.points.drop(1).forEach { lineTo(it.first * k, it.second * k) }
                }
                drawPath(path, Color(draft.color).copy(alpha = draft.opacity), style = Stroke(width = (draft.width * k).coerceAtLeast(1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            is Draft.Shape -> {
                val c = Color(draft.color)
                val st = Stroke(width = (draft.width * k).coerceAtLeast(1.5f), cap = StrokeCap.Round)
                val a = Offset(draft.x0 * k, draft.y0 * k)
                val b = Offset(draft.x1 * k, draft.y1 * k)
                val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                val sz = Size(abs(b.x - a.x), abs(b.y - a.y))
                when (draft.kind) {
                    ShapeKind.RECT -> drawRect(c, tl, sz, style = st)
                    ShapeKind.ELLIPSE -> drawOval(c, tl, sz, style = st)
                    ShapeKind.LINE -> drawLine(c, a, b, st.width, cap = StrokeCap.Round)
                    ShapeKind.ARROW -> {
                        drawLine(c, a, b, st.width, cap = StrokeCap.Round)
                        val ang = kotlin.math.atan2(b.y - a.y, b.x - a.x)
                        val len = 10.dp.toPx()
                        for (d in listOf(0.45f, -0.45f)) {
                            val tip = Offset(b.x - len * kotlin.math.cos(ang + d), b.y - len * kotlin.math.sin(ang + d))
                            drawLine(c, b, tip, st.width, cap = StrokeCap.Round)
                        }
                    }
                }
            }
            null -> Unit
        }
        if (picked != null) {
            val b = picked.box
            drawRect(Ink.Teal, Offset(b.x0 * k - 4f, b.y0 * k - 4f), Size(b.width * k + 8f, b.height * k + 8f), style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))))
        }
        hits?.forEachIndexed { h, boxes ->
            val now = h == cur
            boxes.forEach { b -> mark(b, k, Ink.Amber.copy(alpha = if (now) 0.55f else 0.28f), if (now) Ink.Amber else null) }
        }
        if (sel != null) {
            sel.boxes.forEach { b -> mark(b, k, Ink.Teal.copy(alpha = 0.38f), null) }
            val first = sel.boxes.first()
            val last = sel.boxes.last()
            handle(Offset(first.x0 * k, first.y1 * k), first.height * k)
            handle(Offset(last.x1 * k, last.y1 * k), last.height * k)
        }
    }
}

private fun DrawScope.mark(b: PageBox, k: Float, fill: Color, edge: Color?) {
    val tl = Offset(b.x0 * k - 1f, b.y0 * k - 1f)
    val sz = Size(b.width * k + 2f, b.height * k + 2f)
    drawRoundRect(fill, tl, sz, CornerRadius(2f, 2f))
    if (edge != null) drawRoundRect(edge, tl, sz, CornerRadius(2f, 2f), style = Stroke(width = 1.5.dp.toPx()))
}

private fun DrawScope.handle(at: Offset, lineHeight: Float) {
    drawLine(Ink.Teal, Offset(at.x, at.y - lineHeight), at, strokeWidth = 2.dp.toPx())
    drawCircle(Ink.Teal, radius = 7.dp.toPx(), center = Offset(at.x, at.y + 6.dp.toPx()))
}

/** Pages are redrawn at 1x, 1.5x or 2x the screen width; beyond that, tiles keep text sharp. */
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
        ?: items.minByOrNull { abs(it.offset + it.size / 2 - mid) }!!.index
}

/** Pens and their erasers draw; fingers and mice do whatever the tool says. */
fun PointerType.isPen(): Boolean = this == PointerType.Stylus || this == PointerType.Eraser
