package com.fadysaeed.pdfx.data

/**
 * How well a file answers a search, as a person would weigh it: the words in its title count most,
 * then its author and tags, then its file name; a title that holds the whole phrase counts more
 * still. Words inside the file add by how many pages have them and how much of the file that is;
 * the user's own highlights and notes that have them add too.
 */
object Ranking {
    /** The words of a search, small letters, without marks; "lithium-induced" is two words. */
    fun words(q: String): List<String> = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    private fun wordsOf(s: String?): List<String> = s?.let(::words).orEmpty()

    class Fields(val title: List<String>, val author: List<String>, val name: List<String>, val tags: List<String>, val other: List<String>)

    fun fields(d: Doc, tags: List<String>): Fields = Fields(
        wordsOf(d.title), wordsOf(d.author), wordsOf(d.name.substringBeforeLast('.')), tags.flatMap(::wordsOf),
        listOfNotNull(d.doi?.lowercase(), d.year.takeIf { it > 0 }?.toString()),
    )

    /** How a word sits in a field: 2 for a whole word, 1 for the start of one, 0 for not at all. */
    private fun fit(w: String, field: List<String>): Int {
        var best = 0
        for (x in field) { if (x == w) return 2; if (x.startsWith(w)) best = 1 }
        return best
    }

    /** What the file's details say for the search: a score, and whether every word was found in them. */
    fun inDetails(words: List<String>, phrase: String, d: Doc, f: Fields): Pair<Double, Boolean> {
        var score = 0.0
        var all = true
        for (w in words) {
            val t = fit(w, f.title)
            val a = fit(w, f.author)
            val g = fit(w, f.tags)
            val n = fit(w, f.name)
            val o = if (f.other.any { it.contains(w) }) 1 else 0
            val best = maxOf(t * 4.0, a * 3.0, g * 3.0, n * 1.5, o * 3.0)
            if (best == 0.0) all = false
            score += best
        }
        val title = d.title?.lowercase().orEmpty()
        if (phrase.length > 3 && words.size > 1 && title.contains(phrase)) score += 8.0
        if (phrase.length > 3 && title.startsWith(phrase)) score += 3.0
        return (if (all) score else score * 0.3) to all
    }

    /** What the words inside add: more pages with them, and more of the file, rank higher. */
    fun inside(pagesHit: Int, pages: Int): Double {
        if (pagesHit <= 0) return 0.0
        val share = pagesHit.toDouble() / pages.coerceAtLeast(1)
        return 2.0 + 1.5 * kotlin.math.ln(1.0 + pagesHit) + 6.0 * share.coerceAtMost(1.0)
    }

    fun inMarks(n: Int): Double = if (n <= 0) 0.0 else 4.0 + n.coerceAtMost(6)
}
