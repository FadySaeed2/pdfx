package com.fadysaeed.pdfx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.withTransform
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

enum class Glyph {
    BACK, CLOSE, PLUS, PAGE, LOCK, STAR, STAR_FILLED, MORE, SEARCH, FOLDER,
    FOLDER_PLUS, TAG, TRASH, RESTORE, RENAME, MOVE, COPY, SHARE, OPEN, SORT,
    CHECK, BOOKMARK, BOOKMARK_FILLED, LIST, MOON, UP, DOWN, PEN, UNDO, REDO,
    REFLOW, GRID, TURN_LEFT, TURN_RIGHT, EXTRACT, TOOLS, POINTER, HIGHLIGHTER, SIGNATURE, EDIT_TEXT,
    SELECT_MANY, EXPORT, IMAGE, REDACT, EYE_OFF, WATERMARK, LAYERS, HASH, CROP, SCISSORS,
    MERGE, SCAN_TEXT, COMPRESS, CAMERA, PAGE_PLUS, INFO,
}

/**
 * Each glyph as outline path data on a 24-unit grid, with any part that is filled in. One stroke
 * weight, round ends and corners, so every icon in the app looks drawn by the same hand.
 */
private val GLYPH_PATHS: Map<Glyph, Pair<String, String?>> = mapOf(
    Glyph.BACK to ("M15 18l-6-6 6-6" to null),
    Glyph.CLOSE to ("M18 6L6 18M6 6l12 12" to null),
    Glyph.PLUS to ("M12 5v14M5 12h14" to null),
    Glyph.PAGE to ("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M9 13h6M9 17h4" to null),
    Glyph.LOCK to ("M7 11V8a5 5 0 0 1 10 0v3M6 11h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1zM12 15v2" to null),
    Glyph.STAR to ("M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z" to null),
    Glyph.STAR_FILLED to ("M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z" to "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"),
    Glyph.MORE to ("M12 5h.01M12 12h.01M12 19h.01" to null),
    Glyph.SEARCH to ("M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13zM20 20l-4.8-4.8" to null),
    Glyph.FOLDER to ("M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" to null),
    Glyph.FOLDER_PLUS to ("M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM12 11v6M9 14h6" to null),
    Glyph.TAG to ("M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9zM7.5 7.5h.01" to null),
    Glyph.TRASH to ("M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3" to null),
    Glyph.RESTORE to ("M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 2" to null),
    Glyph.RENAME to ("M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16zM13.5 6.5l4 4" to null),
    Glyph.MOVE to ("M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM9 14h6M13 11.5l2.5 2.5-2.5 2.5" to null),
    Glyph.COPY to ("M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1zM5 15H4.5A1.5 1.5 0 0 1 3 13.5v-9A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5V5" to null),
    Glyph.SHARE to ("M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM8.6 13.5l6.8 4M15.4 6.5l-6.8 4" to null),
    Glyph.OPEN to ("M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" to null),
    Glyph.SORT to ("M4 6h16M7 12h10M10 18h4" to null),
    Glyph.CHECK to ("M5 12.5l4.5 4.5L19 7" to null),
    Glyph.BOOKMARK to ("M6 4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17l-6-4-6 4z" to null),
    Glyph.BOOKMARK_FILLED to ("M6 4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17l-6-4-6 4z" to "M6 4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17l-6-4-6 4z"),
    Glyph.LIST to ("M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01" to null),
    Glyph.MOON to ("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" to null),
    Glyph.UP to ("M6 15l6-6 6 6" to null),
    Glyph.DOWN to ("M6 9l6 6 6-6" to null),
    Glyph.PEN to ("M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16zM13.5 6.5l4 4" to null),
    Glyph.UNDO to ("M9 14L4 9l5-5M4 9h10.5a5.5 5.5 0 0 1 0 11H11" to null),
    Glyph.REDO to ("M15 14l5-5-5-5M20 9H9.5a5.5 5.5 0 0 0 0 11H13" to null),
    Glyph.REFLOW to ("M4 6h16M4 12h13a3 3 0 0 1 0 6h-4M15 16l-2 2 2 2M4 18h5" to null),
    Glyph.GRID to ("M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z" to null),
    Glyph.TURN_LEFT to ("M3 4v5h5M3.5 9A8.5 8.5 0 1 1 5 17" to null),
    Glyph.TURN_RIGHT to ("M21 4v5h-5M20.5 9A8.5 8.5 0 1 0 19 17" to null),
    Glyph.EXTRACT to ("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M12 18v-6M9.5 14.5L12 12l2.5 2.5" to null),
    Glyph.TOOLS to ("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.8-3.8a6 6 0 0 1-7.9 7.9l-6.9 6.9a2.1 2.1 0 0 1-3-3l6.9-6.9a6 6 0 0 1 7.9-7.9z" to null),
    Glyph.POINTER to ("M5 3l14 7.5-6 1.5-2.5 6z" to null),
    Glyph.HIGHLIGHTER to ("M9 11l-5 5v3h6l2-2M9 11l6-6 4 4-6 6zM15 21h6" to null),
    Glyph.SIGNATURE to ("M3 17c3-1 5-9 7-9s-1 9 1 9 3-5 4.5-5 1 3 2.5 3 2-1 3-1M3 21h18" to null),
    Glyph.EDIT_TEXT to ("M5 6V4h12v2M11 4v16M8.5 20h5M17 12v8M15 12h4M15 20h4" to null),
    Glyph.SELECT_MANY to ("M4 5h3v3H4zM4 11h3v3H4zM4 17h3v3H4zM10 6.5h10M10 12.5h10M10 18.5h10" to "M4 5h3v3H4zM4 11h3v3H4z"),
    Glyph.EXPORT to ("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h4M14 3v5h5v3M15 18h6M18.5 15.5L21 18l-2.5 2.5" to null),
    Glyph.IMAGE to ("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM20 15l-4.5-4.5L6 20M9 10.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z" to null),
    Glyph.REDACT to ("M4 6h16M4 18h9M4 10.5h16v3H4z" to "M4 10.5h16v3H4z"),
    Glyph.EYE_OFF to ("M3 3l18 18M10.6 5.1A9.6 9.6 0 0 1 12 5c5 0 8.5 4.5 9.5 7a13 13 0 0 1-2.4 3.6M6.6 6.6C4.4 8 3 10.2 2.5 12c1 2.5 4.5 7 9.5 7a9.4 9.4 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2" to null),
    Glyph.WATERMARK to ("M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11zM9.5 15a2.5 2.5 0 0 0 2.5 2.5" to null),
    Glyph.LAYERS to ("M12 3l9 4.5-9 4.5-9-4.5zM3 12l9 4.5 9-4.5M3 16.5L12 21l9-4.5" to null),
    Glyph.HASH to ("M5 9h15M4 15h15M10 3L8 21M16 3l-2 18" to null),
    Glyph.CROP to ("M6 2v14a2 2 0 0 0 2 2h14M18 22V8a2 2 0 0 0-2-2H2" to null),
    Glyph.SCISSORS to ("M6 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM20 4L8.1 15.9M14.5 14.5L20 20M8.1 8.1L12 12" to null),
    Glyph.MERGE to ("M6 3v3a6 6 0 0 0 6 6 6 6 0 0 0 6-6V3M12 12v9M9 18l3 3 3-3" to null),
    Glyph.SCAN_TEXT to ("M3 8V5a2 2 0 0 1 2-2h3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3M8 9V8h8v1M12 8v8M10.5 16h3" to null),
    Glyph.COMPRESS to ("M8 3v5H3M16 3v5h5M8 21v-5H3M16 21v-5h5" to null),
    Glyph.CAMERA to ("M4 8a1 1 0 0 1 1-1h2.5L9 5h6l1.5 2H19a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1zM12 16a3 3 0 1 0 0-6 3 3 0 0 0 0 6z" to null),
    Glyph.PAGE_PLUS to ("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M12 11v6M9 14h6" to null),
    Glyph.INFO to ("M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5M12 8h.01" to null),
)

private val glyphCache = java.util.concurrent.ConcurrentHashMap<Glyph, Pair<Path, Path?>>()

private fun glyphPaths(g: Glyph): Pair<Path, Path?> = glyphCache.getOrPut(g) {
    val (stroke, fill) = GLYPH_PATHS.getValue(g)
    androidx.compose.ui.graphics.vector.PathParser().parsePathString(stroke).toPath() to
        fill?.let { androidx.compose.ui.graphics.vector.PathParser().parsePathString(it).toPath() }
}

/** Line glyphs drawn in code, so every icon shares one stroke weight and the app ships no icon font. */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val k = s / 24f
        val (stroke, fill) = glyphPaths(glyph)
        val w = 1.85.dp.toPx() / k
        withTransform({
            translate((this@Canvas.size.width - s) / 2f, (this@Canvas.size.height - s) / 2f)
            scale(k, k, Offset.Zero)
        }) {
            if (fill != null) drawPath(fill, color)
            drawPath(stroke, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun IconButton(glyph: Glyph, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ink.Bone, size: Dp = 48.dp) {
    // A long press says what the button does, as Android's own buttons do.
    var tip by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.runtime.LaunchedEffect(tip) { if (tip) { kotlinx.coroutines.delay(1600); tip = false } }
    Box(
        modifier
            .size(size)
            .clip(Corner)
            .semantics { contentDescription = description }
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                tip = true
            }),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, color)
        if (tip) androidx.compose.ui.window.Popup(
            alignment = Alignment.BottomCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.roundToPx() }),
            onDismissRequest = { tip = false },
        ) {
            Text(
                description.substringBefore(':'),
                style = Type.small.copy(color = Ink.Black),
                modifier = Modifier.background(Ink.Bone, Corner).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
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
            // Easy to hit with a thumb: Android's 48 dp.
            .heightIn(min = 48.dp)
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

