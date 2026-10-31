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

enum class Glyph { BACK, CLOSE, PLUS, PAGE, LOCK }

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
