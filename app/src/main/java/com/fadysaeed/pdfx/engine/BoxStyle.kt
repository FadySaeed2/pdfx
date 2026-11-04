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

/**
 * A run of words in a text box styled apart from the rest: [start] to [end] in the box's text,
 * with whatever it sets differently (null: as the box).
 */
data class BoxSpan(val start: Int, val end: Int, val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null, val color: Int? = null) {
    val plain: Boolean get() = bold == null && italic == null && underline == null && color == null
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
    /** Words styled apart from the rest of the box. */
    val spans: List<BoxSpan> = emptyList(),
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

    /** The same, with each word's own style kept only where it differs from the box's. */
    fun tidySpans(n: Int): BoxStyle {
        val a = charsOf(spans, n).map { x ->
            Attr(
                x.bold?.takeIf { it != bold }, x.italic?.takeIf { it != italic }, x.underline?.takeIf { it != underline },
                x.color?.takeIf { (it and 0xFFFFFF) != (color and 0xFFFFFF) },
            )
        }
        return copy(spans = spansOf(a.toTypedArray()))
    }

    /** The words as rich text: one paragraph per line, each with its own direction, styled words in spans. */
    fun rich(text: String): String = buildString {
        append("<?xml version=\"1.0\"?><body xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:xfa=\"http://www.xfa.org/schema/xfa-data/1.0/\" xfa:APIVersion=\"Acrobat:7.0.0\" xfa:spec=\"2.0.2\" style=\"")
        append(css(text))
        append("\">")
        val attrs = charsOf(spans, text.length)
        var at = 0
        text.split('\n').forEach { line ->
            val d = if (rtl(line)) "rtl" else "ltr"
            append("<p dir=\"").append(d).append("\">")
            if (line.isEmpty()) append("&#160;")
            var i = 0
            while (i < line.length) {
                val a = attrs[at + i]
                var j = i + 1
                while (j < line.length && attrs[at + j] == a) j++
                val body = escape(line.substring(i, j))
                val u = a.underline ?: underline
                val css = buildString {
                    a.bold?.let { append("font-weight:").append(if (it) "bold" else "normal").append(';') }
                    a.italic?.let { append("font-style:").append(if (it) "italic" else "normal").append(';') }
                    a.color?.let { append("color:#").append(String.format("%06X", it and 0xFFFFFF)).append(';') }
                }
                if (css.isNotEmpty()) append("<span style=\"").append(css).append("\">")
                if (u) append("<u>")
                append(body)
                if (u) append("</u>")
                if (css.isNotEmpty()) append("</span>")
                i = j
            }
            append("</p>")
            at += line.length + 1
        }
        append("</body>")
    }

    companion object {
        /** One letter's own style: what it sets apart from the box. */
        data class Attr(val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null, val color: Int? = null)

        /** Each letter's own style, from [spans] over a text of [n] letters. */
        fun charsOf(spans: List<BoxSpan>, n: Int): Array<Attr> {
            val out = Array(n) { Attr() }
            for (sp in spans) for (i in sp.start.coerceAtLeast(0) until sp.end.coerceAtMost(n)) {
                val a = out[i]
                out[i] = Attr(sp.bold ?: a.bold, sp.italic ?: a.italic, sp.underline ?: a.underline, sp.color ?: a.color)
            }
            return out
        }

        /** The fewest spans that give each letter its own style. */
        fun spansOf(attrs: Array<Attr>): List<BoxSpan> {
            val out = ArrayList<BoxSpan>()
            var i = 0
            while (i < attrs.size) {
                val a = attrs[i]
                var j = i + 1
                while (j < attrs.size && attrs[j] == a) j++
                if (a != Attr()) out += BoxSpan(i, j, a.bold, a.italic, a.underline, a.color)
                i = j
            }
            return out
        }

        /**
         * Spans moved to follow an edit: text from [from] to [oldEnd] became text from [from] to
         * [newEnd]. Words typed at the end of a styled run take its style, as in a word processor.
         */
        fun shift(spans: List<BoxSpan>, from: Int, oldEnd: Int, newEnd: Int): List<BoxSpan> {
            val d = newEnd - oldEnd
            return spans.mapNotNull { sp ->
                val a = when { sp.start <= from -> sp.start; sp.start >= oldEnd -> sp.start + d; else -> newEnd }
                val b = when { sp.end < from -> sp.end; sp.end >= oldEnd -> sp.end + d; else -> from }
                if (b > a) sp.copy(start = a, end = b) else null
            }
        }

        /**
         * The spans in rich text written by [rich] (or by another app, as far as it goes), for a box
         * whose words are [text]. Empty when the rich text does not spell out the same words.
         */
        fun spansIn(rc: String?, text: String): List<BoxSpan> {
            if (rc.isNullOrBlank()) return emptyList()
            val body = Regex("<body[^>]*>(.*)</body>", RegexOption.DOT_MATCHES_ALL).find(rc)?.groupValues?.get(1) ?: return emptyList()
            // Each paragraph's letters with their style.
            val paras = ArrayList<MutableList<Pair<Char, Attr>>>()
            val stack = ArrayList<Attr>()
            var cur = Attr()
            val token = Regex("<(/?)([a-zA-Z]+)([^>]*)>|([^<]+)")
            for (m in token.findAll(body)) {
                val textPart = m.groupValues[4]
                if (textPart.isNotEmpty()) {
                    if (paras.isEmpty()) paras.add(ArrayList())
                    for (c in unescape(textPart)) paras.last().add(c to cur)
                    continue
                }
                val closing = m.groupValues[1] == "/"
                val tag = m.groupValues[2].lowercase()
                val rest = m.groupValues[3]
                when (tag) {
                    "p" -> if (!closing) paras.add(ArrayList())
                    "span", "u", "b", "i", "strong", "em" -> if (!closing) {
                        stack += cur
                        val css = Regex("style=\"([^\"]*)\"").find(rest)?.groupValues?.get(1).orEmpty().lowercase()
                        var a = cur
                        Regex("font-weight:\\s*([a-z0-9]+)").find(css)?.let { a = a.copy(bold = it.groupValues[1] == "bold" || (it.groupValues[1].toIntOrNull() ?: 400) >= 600) }
                        Regex("font-style:\\s*([a-z]+)").find(css)?.let { a = a.copy(italic = it.groupValues[1] == "italic") }
                        Regex("color:\\s*#([0-9a-f]{6})").find(css)?.let { a = a.copy(color = (0xFF000000 or it.groupValues[1].toLong(16)).toInt()) }
                        if (tag == "u") a = a.copy(underline = true)
                        if (tag == "b" || tag == "strong") a = a.copy(bold = true)
                        if (tag == "i" || tag == "em") a = a.copy(italic = true)
                        cur = a
                    } else if (stack.isNotEmpty()) cur = stack.removeAt(stack.lastIndex)
                }
            }
            val sb = StringBuilder()
            val attrs = ArrayList<Attr>()
            paras.forEachIndexed { k, p ->
                if (k > 0) { sb.append('\n'); attrs += Attr() }
                // An empty line is written as one no-break space.
                if (p.size == 1 && p[0].first == '\u00A0') return@forEachIndexed
                p.forEach { (c, a) -> sb.append(c); attrs += a }
            }
            if (sb.toString() != text) return emptyList()
            return spansOf(attrs.toTypedArray())
        }

        private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#160;", "\u00A0").replace("&nbsp;", "\u00A0").replace("&amp;", "&")

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
