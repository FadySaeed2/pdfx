package com.fadysaeed.pdfx.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fadysaeed.pdfx.engine.TableKind
import com.fadysaeed.pdfx.engine.TableModel
import com.fadysaeed.pdfx.engine.TableRow
import com.fadysaeed.pdfx.engine.findAll
import com.fadysaeed.pdfx.ui.theme.PlexArabic
import com.fadysaeed.pdfx.ui.theme.Type

private const val LIST_BULLETS = "•▪●◦■□–"

/**
 * A table's text marks made real: ^…^ raised small, ⟦c|k⟧ as the glyph's own picture cut from the
 * page (the k-th of the table's glyphs), and search hits marked.
 */
internal fun tableText(s: String, query: String?, mark: Color): AnnotatedString = buildAnnotatedString {
    fun plain(part: String) {
        var i = 0
        while (i < part.length) {
            val c = part[i]
            val end = if (c == '⟦') part.indexOf('⟧', i) else -1
            if (end > i) {
                val inner = part.substring(i + 1, end)
                val k = inner.substringAfterLast('|', "").toIntOrNull()
                if (k != null) appendInlineContent("g$k", inner.substringBeforeLast('|')) else append(inner)
                i = end + 1
                continue
            }
            append(c)
            i++
        }
    }
    var i = 0
    while (i < s.length) {
        val end = if (s[i] == '^') s.indexOf('^', i + 1) else -1
        if (end > i) {
            withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.68.em)) { plain(s.substring(i + 1, end)) }
            i = end + 1
            continue
        }
        val next = s.indexOf('^', i + 1).let { if (it < 0) s.length else it }
        plain(s.substring(i, next))
        i = next
    }
    if (!query.isNullOrEmpty()) {
        val text = toAnnotatedString().text
        findAll(text, query).forEach { r -> addStyle(SpanStyle(background = mark), r.first, r.last + 1) }
    }
}

/** The words of a table's text as they read: marks taken away, glyphs as their file codes. */
internal fun tablePlain(s: String): String =
    s.replace("^", "").replace(Regex("⟦([^|⟧]*)(\\|\\d+)?⟧"), "$1")

/**
 * A table from the page, set for reading on a phone. Long cells read best as cards, one row to a
 * card with each column's name over its words; short ones stay a grid, its first column held in
 * place while the rest slides sideways; a one-column table is a list. Its title and notes stay with
 * it, and the page itself is a tap away.
 */
@Composable
internal fun ReflowTableView(
    reader: Reader, page: Int, t: TableModel, size: Int, ink: Color, bg: Color,
    latin: FontFamily, query: String?, mark: Color, filter: ColorFilter?,
) {
    val density = LocalDensity.current
    val glyphPx = with(density) { (size * 1.3f).dp.roundToPx() }.coerceAtLeast(8)
    val inline = t.glyphs.indices.associate { k ->
        "g$k" to InlineTextContent(Placeholder(1.05.em, 1.05.em, PlaceholderVerticalAlign.TextCenter)) {
            val box = t.glyphs[k]
            LaunchedEffect(page, box, glyphPx) { reader.loadGlyph(page, box, glyphPx) }
            val img = reader.glyphs[reader.figureKey(page, box, glyphPx)]
            if (img != null) Image(img, null, Modifier.fillMaxSize(), colorFilter = filter)
        }
    }
    fun style(text: String, fs: Float, weight: FontWeight = FontWeight.Normal, alpha: Float = 1f) = TextStyle(
        fontFamily = if (isArabic(tablePlain(text))) PlexArabic else latin,
        fontWeight = weight, fontSize = fs.sp, lineHeight = (fs * 1.38f).sp,
        color = ink.copy(alpha = alpha), textDirection = TextDirection.Content,
    )
    @Composable
    fun Words(text: String, fs: Float, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal, alpha: Float = 1f) {
        Text(tableText(text, query, mark), style = style(text, fs, weight, alpha), inlineContent = inline, modifier = modifier)
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy((size * 0.55f).dp)) {
        // A caption set apart from its number on the page still reads with it.
        val caption = if (t.number.isNotEmpty() && t.caption.isNotBlank() && !t.caption.contains(t.number)) "Table ${t.number}. ${t.caption}" else t.caption
        if (caption.isNotBlank()) Words(caption, size * 0.95f, weight = FontWeight.SemiBold)
        when (t.kind) {
            TableKind.LIST -> TableList(t, size, ink) { text, fs, m, w, a -> Words(text, fs, m, w, a) }
            TableKind.GRID -> TableGrid(t, size, ink, bg) { text, fs, m, w, a -> Words(text, fs, m, w, a) }
            else -> TableCards(t, size, ink) { text, fs, m, w, a -> Words(text, fs, m, w, a) }
        }
        t.notes.forEach { n -> Words(n, size * 0.78f, alpha = 0.72f) }
        DisableSelection {
            Text(
                "See this table as on the page",
                style = Type.small.copy(color = ink.copy(alpha = 0.55f), textDecoration = TextDecoration.Underline),
                modifier = Modifier.clickable { reader.zoomFigure = page to t.extent }.padding(vertical = 4.dp),
            )
        }
    }
}

private typealias Cell = @Composable (String, Float, Modifier, FontWeight, Float) -> Unit

/** One card to a row: the first column as its title, then each other column's name over its words. */
@Composable
private fun TableCards(t: TableModel, size: Int, ink: Color, cell: Cell) {
    val fs = size * 0.92f
    t.rows.forEach { r ->
        if (r.group) {
            cell(r.cells.firstOrNull { it.isNotBlank() } ?: "", size * 0.98f, Modifier.padding(top = (size * 0.4f).dp), FontWeight.SemiBold, 1f)
            return@forEach
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ink.copy(alpha = 0.05f))
                .border(0.5.dp, ink.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val title = r.cells.firstOrNull() ?: ""
            if (title.isNotBlank()) cell(title, fs, Modifier, FontWeight.SemiBold, 1f)
            for (c in 1 until r.cells.size) {
                val v = r.cells[c]
                if (v.isBlank()) continue
                val label = t.header.getOrNull(c)?.takeIf { it.isNotBlank() }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (label != null) cell(label, size * 0.74f, Modifier, FontWeight.SemiBold, 0.58f)
                    cell(v, fs, Modifier, FontWeight.Normal, 1f)
                }
            }
        }
    }
}

/** A real grid: columns as on the page, left to right, the first one held while the rest slide. */
@Composable
private fun TableGrid(t: TableModel, size: Int, ink: Color, bg: Color, cell: Cell) {
    val fs = size * 0.84f
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.cells.size } ?: 0)
    if (cols == 0) return
    val all = (listOf(t.header) + t.rows.filter { !it.group }.map { it.cells })
    val widths: List<Dp> = (0 until cols).map { c ->
        val texts = all.mapNotNull { it.getOrNull(c) }.map { tablePlain(it) }
        val longestWord = texts.flatMap { it.split(' ', '\n') }.maxOfOrNull { it.length } ?: 0
        val longest = texts.flatMap { it.split('\n') }.maxOfOrNull { it.length } ?: 0
        val chars = maxOf(longestWord + 1, minOf(longest, 22)).coerceIn(4, 30)
        (chars * fs * 0.56f + 20f).dp
    }
    val rule = ink.copy(alpha = 0.2f)
    val headBg = ink.copy(alpha = 0.07f).compositeOver(bg)
    val scroll = rememberScrollState()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Column(Modifier.border(1.dp, rule)) {
                @Composable
                fun Line(cells: List<String>, head: Boolean) {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        for (c in 0 until cols) {
                            val pin = if (c == 0) Modifier.zIndex(1f).graphicsLayer { translationX = scroll.value.toFloat() }.background(if (head) headBg else bg) else Modifier
                            Box(
                                Modifier.width(widths[c]).fillMaxHeight().then(pin).background(if (head) headBg else Color.Transparent)
                                    .border(0.5.dp, rule).padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                cell(cells.getOrNull(c) ?: "", fs, Modifier, if (head || c == 0) FontWeight.SemiBold else FontWeight.Normal, 1f)
                            }
                        }
                    }
                }
                if (t.header.any { it.isNotBlank() }) Line(t.header, true)
                t.rows.forEach { r ->
                    if (r.group) {
                        var total = 0.dp
                        widths.forEach { total += it }
                        Box(Modifier.width(total).border(0.5.dp, rule).background(ink.copy(alpha = 0.035f)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                            cell(r.cells.firstOrNull { it.isNotBlank() } ?: "", fs, Modifier.graphicsLayer { translationX = scroll.value.toFloat() }, FontWeight.SemiBold, 1f)
                        }
                    } else Line(r.cells, false)
                }
            }
        }
    }
}

/** A table that is really a list: its items with their bullets, nested items set in a step. */
@Composable
private fun TableList(t: TableModel, size: Int, ink: Color, cell: Cell) {
    val fs = size.toFloat()
    val items: List<TableRow> = t.rows
    items.forEach { r ->
        if (r.group) {
            cell(r.cells.firstOrNull { it.isNotBlank() } ?: "", fs, Modifier.padding(top = (size * 0.3f).dp), FontWeight.SemiBold, 1f)
            return@forEach
        }
        // A list in two columns keeps its bullet on the left and the words on the right.
        val raw = if (r.cells.size >= 2) r.cells.drop(1).filter { it.isNotBlank() }.joinToString(" ") else r.cells.firstOrNull() ?: ""
        val bulleted = r.cells.size >= 2 && r.cells[0].isNotBlank()
        val nested = raw.startsWith("\t")
        var text = raw.trimStart('\t', ' ')
        var marker = if (bulleted) "•" else ""
        if (text.isNotEmpty() && LIST_BULLETS.contains(text[0])) { marker = if (nested) "◦" else "•"; text = text.drop(1).trimStart() }
        else if (text.startsWith("⟦")) { val e = text.indexOf('⟧'); if (e > 0) { marker = text.substring(0, e + 1); text = text.substring(e + 1).trimStart() } }
        if (marker.isEmpty() && !nested) { cell(text, fs, Modifier, FontWeight.Normal, 1f); return@forEach }
        Row(Modifier.fillMaxWidth().padding(start = if (nested) (size * 1.6f).dp else 0.dp)) {
            Box(Modifier.width((size * 1.6f).dp)) { cell(marker, fs, Modifier, FontWeight.Normal, 0.7f) }
            Box(Modifier.weight(1f)) { cell(text, fs, Modifier, FontWeight.Normal, 1f) }
        }
    }
}
