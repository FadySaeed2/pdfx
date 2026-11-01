package com.fadysaeed.pdfx.engine

import java.text.Normalizer

/**
 * Text folded for matching: lower case, with accents, Arabic short vowels and tatweel removed,
 * hamza seats (أ إ آ) reduced to the plain letter and Arabic presentation forms to the base letters.
 * [map] gives, for each folded character, its index in the original text.
 */
class Folded(val text: String, val map: IntArray)

fun fold(s: String): Folded {
    val sb = StringBuilder(s.length)
    val map = IntArray(s.length * 3 + 1)
    var n = 0
    for (i in s.indices) {
        val d = Normalizer.normalize(s[i].toString(), Normalizer.Form.NFKD)
        for (ch in d) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt() || ch == 'ـ') continue
            if (n >= map.size) break
            sb.append(ch.lowercaseChar())
            map[n++] = i
        }
    }
    return Folded(sb.toString(), map.copyOf(n))
}

/** Every place [query] appears in [text], ignoring case, accents and Arabic marks, as ranges of [text]. */
fun findAll(text: String, query: String): List<IntRange> {
    val q = fold(query).text.trim()
    if (q.isEmpty()) return emptyList()
    val f = fold(text)
    val out = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = f.text.indexOf(q, from)
        if (at < 0) break
        out += f.map[at]..f.map[at + q.length - 1]
        from = at + q.length
    }
    return out
}

/** A search hit: the boxes it covers, and the words around it so the list of hits can show where it is. */
data class Hit(val boxes: List<Box>, val before: String, val match: String, val after: String)

/** Builds the before / match / after of a hit from the text around it. [near] is roughly where the hit starts. */
internal fun snippet(context: String, query: String, near: Int, boxes: List<Box>, reach: Int = 70): Hit {
    val ranges = findAll(context, query)
    val r = ranges.minByOrNull { kotlin.math.abs(it.first - near) } ?: return Hit(boxes, "", query, "")
    var before = context.substring(0, r.first)
    var after = context.substring(r.last + 1)
    if (before.length > reach) {
        val cut = before.length - reach
        val space = before.indexOf(' ', cut).takeIf { it in cut..(cut + 15) } ?: cut
        before = "…" + before.substring(space).trimStart()
    }
    if (after.length > reach) {
        val space = after.lastIndexOf(' ', reach).takeIf { it >= reach - 15 } ?: reach
        after = after.substring(0, space).trimEnd() + "…"
    }
    return Hit(boxes, before, context.substring(r.first, r.last + 1), after)
}
