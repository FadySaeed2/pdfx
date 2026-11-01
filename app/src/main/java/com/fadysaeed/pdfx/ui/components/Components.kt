package com.fadysaeed.pdfx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type

val Corner = RoundedCornerShape(4.dp)

@Composable
fun AmberButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(Corner)
            .background(if (enabled) Ink.Amber else Ink.Rule)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.button.copy(color = if (enabled) Ink.OnAmber else Ink.Ash), textAlign = TextAlign.Center)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ink.Bone, minHeight: Dp = 48.dp) {
    Box(
        modifier
            .heightIn(min = minHeight)
            .clip(Corner)
            .border(1.dp, Ink.Edge, Corner)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body.copy(color = color, fontSize = 14.sp), textAlign = TextAlign.Center)
    }
}

enum class Glyph { BACK, CLOSE, PLUS, PAGE, LOCK, STAR, STAR_FILLED, MORE, SEARCH, FOLDER, TAG, TRASH, RESTORE, RENAME, MOVE, COPY, SHARE, OPEN, SORT, CHECK, BOOKMARK, BOOKMARK_FILLED, LIST, MOON, UP, DOWN, PEN, UNDO, REDO, REFLOW }

/** Line glyphs drawn in code, so every icon shares one stroke weight and the app ships no icon font. */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = 1.9.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(s * x1, s * y1), Offset(s * x2, s * y2), w, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.BACK -> { line(0.62f, 0.2f, 0.32f, 0.5f); line(0.32f, 0.5f, 0.62f, 0.8f) }
            Glyph.CLOSE -> { line(0.25f, 0.25f, 0.75f, 0.75f); line(0.75f, 0.25f, 0.25f, 0.75f) }
            Glyph.PLUS -> { line(0.5f, 0.2f, 0.5f, 0.8f); line(0.2f, 0.5f, 0.8f, 0.5f) }
            Glyph.PAGE -> {
                val p = Path().apply {
                    moveTo(s * 0.24f, s * 0.12f); lineTo(s * 0.6f, s * 0.12f); lineTo(s * 0.78f, s * 0.3f)
                    lineTo(s * 0.78f, s * 0.88f); lineTo(s * 0.24f, s * 0.88f); close()
                }
                drawPath(p, color, style = Stroke(width = w, join = StrokeJoin.Round))
                line(0.36f, 0.5f, 0.66f, 0.5f); line(0.36f, 0.66f, 0.58f, 0.66f)
            }
            Glyph.LOCK -> {
                drawRoundRect(color, Offset(s * 0.22f, s * 0.44f), Size(s * 0.56f, s * 0.42f), style = Stroke(width = w))
                drawArc(color, 180f, 180f, false, Offset(s * 0.32f, s * 0.16f), Size(s * 0.36f, s * 0.5f), style = Stroke(width = w, cap = StrokeCap.Round))
            }
            Glyph.STAR, Glyph.STAR_FILLED -> {
                val p = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 0.42f else 0.18f
                    val a = Math.toRadians((-90 + i * 36).toDouble())
                    val x = s * (0.5f + r * Math.cos(a).toFloat())
                    val y = s * (0.53f + r * Math.sin(a).toFloat())
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                if (glyph == Glyph.STAR_FILLED) drawPath(p, color)
                drawPath(p, color, style = Stroke(width = w, join = StrokeJoin.Round))
            }
            Glyph.MORE -> listOf(0.22f, 0.5f, 0.78f).forEach { drawCircle(color, w * 0.95f, Offset(s * 0.5f, s * it)) }
            Glyph.SEARCH -> {
                drawCircle(color, s * 0.24f, Offset(s * 0.44f, s * 0.44f), style = Stroke(width = w))
                line(0.62f, 0.62f, 0.82f, 0.82f)
            }
            Glyph.FOLDER -> {
                val p = Path().apply {
                    moveTo(s * 0.14f, s * 0.26f); lineTo(s * 0.4f, s * 0.26f); lineTo(s * 0.48f, s * 0.34f)
                    lineTo(s * 0.86f, s * 0.34f); lineTo(s * 0.86f, s * 0.8f); lineTo(s * 0.14f, s * 0.8f); close()
                }
                drawPath(p, color, style = Stroke(width = w, join = StrokeJoin.Round))
            }
            Glyph.TAG -> {
                val p = Path().apply {
                    moveTo(s * 0.16f, s * 0.16f); lineTo(s * 0.5f, s * 0.16f); lineTo(s * 0.86f, s * 0.52f)
                    lineTo(s * 0.52f, s * 0.86f); lineTo(s * 0.16f, s * 0.5f); close()
                }
                drawPath(p, color, style = Stroke(width = w, join = StrokeJoin.Round))
                drawCircle(color, w * 0.9f, Offset(s * 0.33f, s * 0.33f))
            }
            Glyph.TRASH -> {
                line(0.18f, 0.26f, 0.82f, 0.26f); line(0.4f, 0.26f, 0.42f, 0.16f); line(0.42f, 0.16f, 0.58f, 0.16f); line(0.58f, 0.16f, 0.6f, 0.26f)
                val p = Path().apply { moveTo(s * 0.26f, s * 0.26f); lineTo(s * 0.32f, s * 0.86f); lineTo(s * 0.68f, s * 0.86f); lineTo(s * 0.74f, s * 0.26f) }
                drawPath(p, color, style = Stroke(width = w, join = StrokeJoin.Round))
            }
            Glyph.RESTORE -> {
                drawArc(color, 200f, 290f, false, Offset(s * 0.2f, s * 0.2f), Size(s * 0.6f, s * 0.6f), style = Stroke(width = w, cap = StrokeCap.Round))
                line(0.2f, 0.28f, 0.22f, 0.44f); line(0.22f, 0.44f, 0.37f, 0.4f)
            }
            Glyph.RENAME -> { line(0.22f, 0.78f, 0.7f, 0.3f); line(0.62f, 0.22f, 0.78f, 0.38f); line(0.2f, 0.84f, 0.36f, 0.82f) }
            Glyph.MOVE -> { line(0.18f, 0.5f, 0.78f, 0.5f); line(0.58f, 0.3f, 0.8f, 0.5f); line(0.8f, 0.5f, 0.58f, 0.7f) }
            Glyph.COPY -> {
                drawRoundRect(color, Offset(s * 0.34f, s * 0.34f), Size(s * 0.46f, s * 0.48f), style = Stroke(width = w))
                val p = Path().apply { moveTo(s * 0.24f, s * 0.68f); lineTo(s * 0.2f, s * 0.68f); lineTo(s * 0.2f, s * 0.18f); lineTo(s * 0.64f, s * 0.18f); lineTo(s * 0.64f, s * 0.26f) }
                drawPath(p, color, style = Stroke(width = w, join = StrokeJoin.Round))
            }
            Glyph.SHARE -> {
                line(0.5f, 0.16f, 0.5f, 0.6f); line(0.34f, 0.3f, 0.5f, 0.16f); line(0.5f, 0.16f, 0.66f, 0.3f)
                val p = Path().apply { moveTo(s * 0.3f, s * 0.46f); lineTo(s * 0.2f, s * 0.46f); lineTo(s * 0.2f, s * 0.84f); lineTo(s * 0.8f, s * 0.84f); lineTo(s * 0.8f, s * 0.46f); lineTo(s * 0.7f, s * 0.46f) }
                drawPath(p, color, style = Stroke(width = w, join = StrokeJoin.Round))
            }
            Glyph.OPEN -> { line(0.3f, 0.2f, 0.62f, 0.5f); line(0.62f, 0.5f, 0.3f, 0.8f); line(0.72f, 0.2f, 0.72f, 0.8f) }
            Glyph.SORT -> { line(0.2f, 0.3f, 0.8f, 0.3f); line(0.2f, 0.5f, 0.62f, 0.5f); line(0.2f, 0.7f, 0.44f, 0.7f) }
            Glyph.CHECK -> { line(0.2f, 0.52f, 0.42f, 0.74f); line(0.42f, 0.74f, 0.82f, 0.28f) }
            Glyph.BOOKMARK, Glyph.BOOKMARK_FILLED -> {
                val p = Path().apply {
                    moveTo(s * 0.28f, s * 0.14f); lineTo(s * 0.72f, s * 0.14f); lineTo(s * 0.72f, s * 0.86f)
                    lineTo(s * 0.5f, s * 0.68f); lineTo(s * 0.28f, s * 0.86f); close()
                }
                if (glyph == Glyph.BOOKMARK_FILLED) drawPath(p, color)
                drawPath(p, color, style = Stroke(width = w, join = StrokeJoin.Round))
            }
            Glyph.REFLOW -> {
                // A page outline with lines of text flowing inside it.
                drawRoundRect(color, Offset(s * 0.18f, s * 0.12f), androidx.compose.ui.geometry.Size(s * 0.64f, s * 0.76f), androidx.compose.ui.geometry.CornerRadius(s * 0.06f), style = Stroke(width = w))
                line(0.32f, 0.34f, 0.68f, 0.34f)
                line(0.32f, 0.5f, 0.68f, 0.5f)
                line(0.32f, 0.66f, 0.54f, 0.66f)
            }
            Glyph.LIST -> {
                listOf(0.28f, 0.5f, 0.72f).forEach { y ->
                    drawCircle(color, w * 0.8f, Offset(s * 0.2f, s * y))
                    line(0.34f, y, 0.82f, y)
                }
            }
            Glyph.MOON -> {
                val p = Path().apply {
                    moveTo(s * 0.62f, s * 0.16f)
                    cubicTo(s * 0.3f, s * 0.2f, s * 0.2f, s * 0.6f, s * 0.42f, s * 0.78f)
                    cubicTo(s * 0.56f, s * 0.88f, s * 0.76f, s * 0.84f, s * 0.86f, s * 0.7f)
                    cubicTo(s * 0.56f, s * 0.72f, s * 0.42f, s * 0.4f, s * 0.62f, s * 0.16f)
                    close()
                }
                drawPath(p, color, style = Stroke(width = w, join = StrokeJoin.Round))
            }
            Glyph.PEN -> {
                line(0.22f, 0.78f, 0.66f, 0.34f); line(0.66f, 0.34f, 0.78f, 0.22f)
                line(0.58f, 0.26f, 0.74f, 0.42f); line(0.22f, 0.78f, 0.18f, 0.84f)
                line(0.3f, 0.86f, 0.8f, 0.86f)
            }
            Glyph.UNDO, Glyph.REDO -> {
                val flip = glyph == Glyph.REDO
                fun fx(x: Float) = if (flip) 1f - x else x
                drawArc(color, if (flip) 90f else 270f, 180f, false, Offset(s * (if (flip) 0.24f else 0.28f), s * 0.3f), Size(s * 0.48f, s * 0.42f), style = Stroke(width = w, cap = StrokeCap.Round))
                line(fx(0.52f), 0.3f, fx(0.3f), 0.3f)
                line(fx(0.3f), 0.3f, fx(0.4f), 0.2f); line(fx(0.3f), 0.3f, fx(0.4f), 0.4f)
                line(fx(0.52f), 0.72f, fx(0.36f), 0.72f)
            }
            Glyph.UP -> { line(0.24f, 0.62f, 0.5f, 0.36f); line(0.5f, 0.36f, 0.76f, 0.62f) }
            Glyph.DOWN -> { line(0.24f, 0.38f, 0.5f, 0.64f); line(0.5f, 0.64f, 0.76f, 0.38f) }
        }
    }
}

@Composable
fun IconButton(glyph: Glyph, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ink.Bone, size: Dp = 48.dp) {
    Box(
        modifier
            .size(size)
            .clip(Corner)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, color)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Ink.Hairline) {
    Box(modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(color))
}

@Composable
fun InkField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    secret: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val style = Type.body.copy(fontSize = 16.sp)
    Column(modifier) {
        if (label != null) Text(label, style = Type.small, modifier = Modifier.padding(bottom = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style.copy(color = Ink.Bone),
            cursorBrush = SolidColor(Ink.Amber),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label ?: placeholder },
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(Corner)
                        .background(Ink.Field)
                        .border(1.dp, Ink.Edge, Corner)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = style.copy(color = Ink.Ash), maxLines = 1)
                        inner()
                    }
                }
            },
        )
    }
}

@Composable
fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(Corner)
            .background(if (selected) Ink.Bone else Color.Transparent)
            .border(1.dp, if (selected) Ink.Bone else Ink.Rule, Corner)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body.copy(fontSize = 14.sp, color = if (selected) Ink.Black else Ink.Sand), maxLines = 1)
    }
}

/** A row in a sheet: line glyph, label, optional note underneath. */
@Composable
fun SheetRow(glyph: Glyph, label: String, onClick: () -> Unit, note: String? = null, color: Color = Ink.Bone) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, if (color == Ink.Bone) Ink.Dust else color)
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.body.copy(color = color))
            if (note != null) Text(note, style = Type.small)
        }
    }
}

/** The six tag colours: warm, quiet, and readable on black. */
val TagColors = listOf(
    Color(0xFFE0A43B), Color(0xFF3E9F8F), Color(0xFFB07F55), Color(0xFF6F93AD), Color(0xFF8DA066), Color(0xFFCFC6B4),
)

fun tagColor(i: Int): Color = TagColors[((i % TagColors.size) + TagColors.size) % TagColors.size]

