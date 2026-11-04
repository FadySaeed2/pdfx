package com.fadysaeed.pdfx.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fadysaeed.pdfx.engine.Stamp
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Chip
import com.fadysaeed.pdfx.ui.components.GhostButton
import com.fadysaeed.pdfx.ui.components.InkField
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type

private val MARK_COLOURS = listOf("Grey" to 0x808080, "Red" to 0xC0392B, "Blue" to 0x2E5C9A)
private val STRENGTHS = listOf("Faint" to 0.15f, "Medium" to 0.28f, "Strong" to 0.5f)

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Label(text: String) = Text(text, style = Type.small, modifier = Modifier.padding(top = 14.dp))

/** A small page with the watermark or the number where it will go, to see before making it. */
@Composable
private fun PagePreview(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(118.dp).aspectRatio(0.707f).background(Ink.Paper).border(1.dp, Ink.Rule)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(11) { k -> Box(Modifier.fillMaxWidth(if (k % 4 == 3) 0.6f else 1f).height(3.dp).background(Color(0xFFCFCAC0))) }
            }
            content()
        }
    }
}

/** What the watermark dialog has chosen so far. */
class WatermarkUi {
    var text by mutableStateOf("CONFIDENTIAL")
    var colour by mutableIntStateOf(MARK_COLOURS[0].second)
    var strength by mutableStateOf(STRENGTHS[1].second)
    var diagonal by mutableStateOf(true)
    var firstOnly by mutableStateOf(false)
    var asNew by mutableStateOf(true)
    val ok get() = Stamp.writable(text)
    fun mark() = Stamp.Mark(text.trim(), colour, strength, diagonal, firstOnly)
}

/** Write a watermark on the pages: the words, colour, strength, corner to corner or straight, all pages or the first. */
@Composable
fun WatermarkDialog(busy: Boolean, note: String?, onMake: (Stamp.Mark, asNew: Boolean) -> Unit, onDismiss: () -> Unit) {
    val ui = remember { WatermarkUi() }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("Watermark", style = Type.title) },
        text = { WatermarkBody(ui, busy, note) },
        confirmButton = { AmberButton(if (busy) "Writing…" else "Add watermark", { onMake(ui.mark(), ui.asNew) }, enabled = !busy && ui.ok) },
        dismissButton = { GhostButton("Cancel", { if (!busy) onDismiss() }, minHeight = 52.dp) },
    )
}

@Composable
fun WatermarkBody(ui: WatermarkUi, busy: Boolean, note: String?) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Written faintly across the pages, as part of them: it stays when the file is printed or sent.", style = Type.note)
        Spacer(Modifier.height(12.dp))
        InkField(ui.text, { ui.text = it.take(40) }, "Words to write", label = "Words")
        ChipRow { listOf("CONFIDENTIAL", "DRAFT", "COPY", "NOT FOR CIRCULATION").forEach { w -> Chip(w, { ui.text = w }, selected = ui.text == w) } }
        if (!ui.ok && ui.text.isNotBlank()) Text("Latin letters, numbers and the usual marks only, for now.", style = Type.small.copy(color = Ink.Amber), modifier = Modifier.padding(top = 6.dp))
        PagePreview {
            Text(
                ui.text.ifBlank { " " }, maxLines = 1, overflow = TextOverflow.Clip,
                style = Type.bodyStrong.copy(fontSize = (if (ui.diagonal) 120f else 100f).div(ui.text.length.coerceAtLeast(4)).coerceIn(7f, 26f).sp, fontWeight = FontWeight.Bold, color = Color(0xFF000000 or ui.colour.toLong())),
                modifier = Modifier.align(Alignment.Center).rotate(if (ui.diagonal) -54.7f else 0f).alpha(ui.strength.coerceAtLeast(0.2f)),
            )
        }
        Label("Colour")
        ChipRow { MARK_COLOURS.forEach { (l, c) -> Chip(l, { ui.colour = c }, selected = ui.colour == c) } }
        Label("How strong")
        ChipRow { STRENGTHS.forEach { (l, s) -> Chip(l, { ui.strength = s }, selected = ui.strength == s) } }
        Label("Direction and pages")
        ChipRow {
            Chip("Corner to corner", { ui.diagonal = true }, selected = ui.diagonal)
            Chip("Straight across", { ui.diagonal = false }, selected = !ui.diagonal)
        }
        ChipRow {
            Chip("Every page", { ui.firstOnly = false }, selected = !ui.firstOnly)
            Chip("First page only", { ui.firstOnly = true }, selected = ui.firstOnly)
        }
        SaveChoiceOf(ui.asNew, { ui.asNew = it }, enabled = !busy)
        note?.let { Text(it, style = Type.small, modifier = Modifier.padding(top = 10.dp)) }
    }
}

/** What the page numbers dialog has chosen so far. */
class NumbersUi {
    var place by mutableStateOf(Stamp.Place.BOTTOM_CENTER)
    var style by mutableStateOf(Stamp.Style.PLAIN)
    var skipFirst by mutableStateOf(false)
    var startAt by mutableIntStateOf(1)
    var asNew by mutableStateOf(false)
    val from get() = if (skipFirst) 1 else 0
    fun numbers() = Stamp.Numbers(place, style, from, startAt)
}

/** Number the pages: where, how written, from which page and from which number. */
@Composable
fun PageNumbersDialog(pageCount: Int, busy: Boolean, note: String?, onMake: (Stamp.Numbers, asNew: Boolean) -> Unit, onDismiss: () -> Unit) {
    val ui = remember { NumbersUi() }
    val n = pageCount - ui.from
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("Page numbers", style = Type.title) },
        text = { PageNumbersBody(ui, pageCount, busy, note) },
        confirmButton = { AmberButton(if (busy) "Numbering…" else "Number $n ${if (n == 1) "page" else "pages"}", { onMake(ui.numbers(), ui.asNew) }, enabled = !busy) },
        dismissButton = { GhostButton("Cancel", { if (!busy) onDismiss() }, minHeight = 52.dp) },
    )
}

@Composable
fun PageNumbersBody(ui: NumbersUi, pageCount: Int, busy: Boolean, note: String?) {
    val last = ui.startAt + (pageCount - 1 - ui.from)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Written on the pages themselves, so they print and travel with the file.", style = Type.note)
        PagePreview {
            val p = ui.place
            val align = when (p.align) { 0 -> if (p.top) Alignment.TopCenter else Alignment.BottomCenter; 1 -> if (p.top) Alignment.TopEnd else Alignment.BottomEnd; else -> if (p.top) Alignment.TopStart else Alignment.BottomStart }
            Text(ui.style.text(ui.startAt, last), style = Type.small.copy(fontSize = 7.sp, color = Color(0xFF404040)), modifier = Modifier.align(align).padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Label("Where")
        ChipRow { Stamp.Place.entries.filter { !it.top }.forEach { p -> Chip(p.label, { ui.place = p }, selected = ui.place == p) } }
        ChipRow { Stamp.Place.entries.filter { it.top }.forEach { p -> Chip(p.label, { ui.place = p }, selected = ui.place == p) } }
        Label("How")
        ChipRow { Stamp.Style.entries.forEach { s -> Chip(s.text(3, 12), { ui.style = s }, selected = ui.style == s) } }
        Label("Starting")
        ChipRow {
            Chip("From the first page", { ui.skipFirst = false }, selected = !ui.skipFirst)
            if (pageCount > 1) Chip("Not on the cover", { ui.skipFirst = true }, selected = ui.skipFirst)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("First number", style = Type.body, modifier = Modifier.weight(1f))
            GhostButton("−", { if (ui.startAt > 0) ui.startAt-- }, minHeight = 44.dp)
            Text("${ui.startAt}", style = Type.title, modifier = Modifier.padding(horizontal = 16.dp))
            GhostButton("+", { ui.startAt++ }, minHeight = 44.dp)
        }
        SaveChoiceOf(ui.asNew, { ui.asNew = it }, enabled = !busy)
        note?.let { Text(it, style = Type.small, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
private fun SaveChoiceOf(asNew: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("New file beside it", { if (enabled) onChange(true) }, selected = asNew)
        Chip("Replace this file", { if (enabled) onChange(false) }, selected = !asNew)
    }
}
