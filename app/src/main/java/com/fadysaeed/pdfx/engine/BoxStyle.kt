package com.fadysaeed.pdfx.engine

/** Type families a text box can use. They are the PDF engine's own, so they travel inside the file. */
enum class BoxFont(val label: String, val css: String, val da: String) {
    SANS("Sans", "sans-serif", "Helv"),
    SERIF("Serif", "serif", "TiRo"),
    MONO("Mono", "monospace", "Cour"),
}

/** Which way the lines run. AUTO looks at each line's first letter. */
enum class BoxDir(val label: String) { AUTO("Auto"), RTL("Right to left"), LTR("Left to right") }

/** Where lines sit in the box. AUTO follows the direction: right for Arabic, left otherwise. */
enum class BoxAlign(val label: String, val css: String) {
    AUTO("Auto", ""), LEFT("Left", "left"), CENTER("Centre", "center"), RIGHT("Right", "right"),
}

/** Line spacing steps for text boxes. */
val BoxSpacings = listOf(1.15f, 1.4f, 1.75f)

/**
 * How a whole text box looks. Written into the file as the box's default style and rich text,
 * which the PDF engine lays out (with right-to-left lines where needed) and saves with its fonts.
 */
data class BoxStyle(
    val font: BoxFont = BoxFont.SANS,
    val size: Float = 15f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val align: BoxAlign = BoxAlign.AUTO,
    val dir: BoxDir = BoxDir.AUTO,
    val color: Int = 0xFF1A1A1A.toInt(),
    /** Background colour, or null for none. */
    val fill: Int? = null,
    val border: Boolean = false,
    /** A step in [BoxSpacings]. */
    val spacing: Int = 0,
) {
    /** The direction of one line: forced, or from its first letter. */
    fun rtl(line: String): Boolean = when (dir) {
        BoxDir.RTL -> true
        BoxDir.LTR -> false
        BoxDir.AUTO -> firstStrongIsRtl(line) ?: firstStrongIsRtl(line.ifBlank { "" }) ?: false
    }

    /** The alignment written into the file for [text]. */
    fun alignFor(text: String): BoxAlign = if (align != BoxAlign.AUTO) align else {
        val first = text.lines().firstOrNull { it.isNotBlank() } ?: ""
        if (rtl(first)) BoxAlign.RIGHT else BoxAlign.LEFT
    }

    /** 0 left, 1 centre, 2 right: the alignment other PDF apps read. */
    fun quadding(text: String): Int = when (alignFor(text)) { BoxAlign.CENTER -> 1; BoxAlign.RIGHT -> 2; else -> 0 }

    /** The box's default style, as CSS. */
    fun css(text: String): String = buildString {
        append("font-family:").append(font.css).append(';')
        append("font-size:").append(fmt(size)).append("pt;")
        append("font-weight:").append(if (bold) "bold" else "normal").append(';')
        append("font-style:").append(if (italic) "italic" else "normal").append(';')
        if (underline) append("text-decoration:underline;")
        append("text-align:").append(alignFor(text).css).append(';')
        append("line-height:").append(fmt(BoxSpacings[spacing.coerceIn(0, 2)])).append(';')
        append("color:#").append(String.format("%06X", color and 0xFFFFFF)).append(';')
    }

    /** The words as rich text: one paragraph per line, each with its own direction. */
    fun rich(text: String): String = buildString {
        append("<?xml version=\"1.0\"?><body xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:xfa=\"http://www.xfa.org/schema/xfa-data/1.0/\" xfa:APIVersion=\"Acrobat:7.0.0\" xfa:spec=\"2.0.2\" style=\"")
        append(css(text))
        append("\">")
        text.split('\n').forEach { line ->
            val d = if (rtl(line)) "rtl" else "ltr"
            append("<p dir=\"").append(d).append("\">")
            val body = escape(line).ifEmpty { "&#160;" }
            if (underline) append("<u>").append(body).append("</u>") else append(body)
            append("</p>")
        }
        append("</body>")
    }

    companion object {
        /** Reads back a style written by [css] (and the box's fill and border), for editing. */
        fun parse(ds: String?, text: String, fallbackSize: Float, fallbackColor: Int, fill: Int?, border: Boolean, rtlLines: Boolean?): BoxStyle {
            val m = HashMap<String, String>()
            ds?.split(';')?.forEach { part ->
                val i = part.indexOf(':')
                if (i > 0) m[part.substring(0, i).trim().lowercase()] = part.substring(i + 1).trim()
            }
            val font = BoxFont.entries.firstOrNull { m["font-family"]?.contains(it.css) == true } ?: BoxFont.SANS
            val size = m["font-size"]?.removeSuffix("pt")?.toFloatOrNull() ?: fallbackSize
            val color = m["color"]?.removePrefix("#")?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } ?: fallbackColor
            val spacing = m["line-height"]?.toFloatOrNull()?.let { lh -> BoxSpacings.indices.minByOrNull { kotlin.math.abs(BoxSpacings[it] - lh) } } ?: 0
            val base = BoxStyle(
                font = font, size = size, bold = m["font-weight"] == "bold", italic = m["font-style"] == "italic",
                underline = m["text-decoration"]?.contains("underline") == true, color = color, fill = fill, border = border, spacing = spacing,
            )
            // Direction: AUTO when every line runs the way its own letters say.
            val dir = when (rtlLines) {
                null -> BoxDir.AUTO
                else -> if (text.lines().filter { it.isNotBlank() }.all { firstStrongIsRtl(it) == rtlLines || firstStrongIsRtl(it) == null }) BoxDir.AUTO
                else if (rtlLines) BoxDir.RTL else BoxDir.LTR
            }
            val written = BoxAlign.entries.firstOrNull { it.css.isNotEmpty() && it.css == m["text-align"] }
            val withDir = base.copy(dir = dir)
            val align = if (written == null || written == withDir.copy(align = BoxAlign.AUTO).alignFor(text)) BoxAlign.AUTO else written
            return withDir.copy(align = align)
        }

        private fun fmt(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else String.format(java.util.Locale.US, "%.2f", f)

        private fun escape(s: String): String = buildString {
            s.forEach { c ->
                when (c) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    else -> append(c)
                }
            }
        }
    }
}

/** True when the first letter of [s] is right-to-left (Arabic, Hebrew), false for other letters, null when there are none. */
fun firstStrongIsRtl(s: String): Boolean? {
    for (ch in s) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return null
}
