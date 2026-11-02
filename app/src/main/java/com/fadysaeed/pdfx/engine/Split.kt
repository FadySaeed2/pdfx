package com.fadysaeed.pdfx.engine

import java.io.File

/** Ways to cut a file into several, as lists of pages (0-based), one list per new file. */
object Splits {

    /** Every [n] pages a new file: 1–n, n+1–2n, … */
    fun every(n: Int, count: Int): List<List<Int>> {
        if (n <= 0 || count <= 0) return emptyList()
        return (0 until count).chunked(n)
    }

    /**
     * Pages as people write them: "1-3, 4-10, 11-" makes three files; "5" is a file of one page;
     * "8-" runs to the end and "-3" starts at the beginning. Dashes of any kind, commas or
     * semicolons between parts. Null, with the reason, when a part cannot be read or is outside the file.
     */
    fun ranges(text: String, count: Int): Pair<List<List<Int>>?, String?> {
        val parts = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null to "Write the pages for each file, like 1-3, 4-10."
        val out = ArrayList<List<Int>>()
        val dash = Regex("\\s*[-–—]\\s*")
        for (p in parts) {
            val bits = p.split(dash)
            val (a, b) = when {
                bits.size == 1 -> (bits[0].toIntOrNull() ?: return null to "\"$p\" is not a page number.").let { it to it }
                bits.size == 2 -> {
                    val a = if (bits[0].isBlank()) 1 else bits[0].trim().toIntOrNull() ?: return null to "\"$p\" is not a page range."
                    val b = if (bits[1].isBlank()) count else bits[1].trim().toIntOrNull() ?: return null to "\"$p\" is not a page range."
                    a to b
                }
                else -> return null to "\"$p\" is not a page range."
            }
            if (a < 1 || b > count) return null to "The file has $count pages; \"$p\" goes past them."
            if (a > b) return null to "\"$p\" runs backwards."
            out += (a - 1 until b).toList()
        }
        return out to null
    }

    /** "pages 1–3", "page 5": a part's pages, for its file name. */
    fun label(pages: List<Int>): String {
        if (pages.isEmpty()) return ""
        val runs = ArrayList<String>()
        var i = 0
        while (i < pages.size) {
            var j = i
            while (j + 1 < pages.size && pages[j + 1] == pages[j] + 1) j++
            runs += if (j > i) "${pages[i] + 1}–${pages[j] + 1}" else "${pages[i] + 1}"
            i = j + 1
        }
        return (if (pages.size == 1) "page " else "pages ") + runs.joinToString(", ")
    }

    /**
     * Cuts [count] pages into runs that each come out no bigger than [maxBytes], measured by
     * writing them ([write] puts the given pages into the given file). A page bigger than the limit
     * on its own is a file of its own. The runs grow by doubling and are then narrowed down, so a
     * long file takes a few writes per part, not one per page.
     */
    fun bySize(count: Int, maxBytes: Long, tmp: File, write: (List<Int>, File) -> Unit): List<List<Int>> {
        fun size(from: Int, to: Int): Long {
            write((from until to).toList(), tmp)
            return tmp.length().also { tmp.delete() }
        }
        val out = ArrayList<List<Int>>()
        var s = 0
        while (s < count) {
            // Grow until too big or the end, then narrow down between the last fit and the first miss.
            var good = s + 1
            var len = 1
            var bad = -1
            while (true) {
                val end = minOf(count, s + len * 2)
                if (end == good) break
                if (size(s, end) <= maxBytes) { good = end; if (end == count) break; len *= 2 } else { bad = end; break }
            }
            if (bad > 0) {
                var lo = good
                var hi = bad
                while (hi - lo > 1) {
                    val mid = (lo + hi) / 2
                    if (size(s, mid) <= maxBytes) lo = mid else hi = mid
                }
                good = lo
            }
            out += (s until good).toList()
            s = good
        }
        return out
    }
}
