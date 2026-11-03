package com.fadysaeed.pdfx.engine

import java.util.Calendar

/** A line on one of a file's first pages, with its type size and where it sits. */
class InfoLine(val text: String, val size: Float, val y0: Float, val y1: Float, val bold: Boolean = false)

/** One of a file's first pages, as far as finding its title goes. */
class InfoPage(val height: Float, val lines: List<InfoLine>, val text: String)

/** What a file says it is: its title, first author ("Malhi et al." when there are more), year, DOI. */
data class DocFacts(val title: String?, val author: String?, val year: Int, val doi: String?)

/**
 * Reads a file's real title, author and year, so a library of "s41380-023-02145-x.pdf" and
 * "document(3).pdf" shows "Lithium in bipolar disorder · Malhi et al. · 2023". The file's own
 * details are used when they look real (publishers fill them in; word processors often leave
 * "Microsoft Word - draft.docx"); otherwise the largest type near the top of the first pages is
 * the title and the line under it the authors. The year comes from the page (a copyright line, a
 * journal citation, "Published") before the file's creation date.
 */
object DocInfo {
    private val junkTitle = Regex(
        """^(untitled|no title|document\d*|title|microsoft (word|powerpoint|excel).*|word document|pdf|print|slide \d+|presentation\d*|.*\.(docx?|pdf|tex|indd|qxd|pptx?|xlsx?|rtf|odt|html?|jpe?g|png|tiff?)|[\w-]*\d{3,}[\w-]*|[A-Z0-9_ -]{1,12})$""",
        RegexOption.IGNORE_CASE,
    )
    private val junkAuthor = Regex(
        """^(user|admin|administrator|owner|unknown|author|default|windows user|guest|pc|hp|dell|lenovo|apple|microsoft.*|adobe.*|acrobat.*|word|office|[a-z]{1,3}|\d+|.*@.*|.*\.(com|org|net).*|.*(publisher|press|elsevier|springer|wiley|limited|ltd|inc\.?|llc|corp\.?)$)$""",
        RegexOption.IGNORE_CASE,
    )
    private val doiRe = Regex("""\b(10\.\d{4,9}/[-._;()/:A-Za-z0-9]+)""")
    private val notTitle = Regex(
        """(https?://|www\.|doi[:.]|©|\bissn\b|\bisbn\b|\bvol\.|\bpp\.|\bpage \d|downloaded from|all rights reserved|creative commons|licen[cs]e|received:|accepted:|published online|correspondence|e-?mail|copyright|^\d+$|^(abstract|background|introduction|contents|table of contents|preface|foreword)$|available (at|online)|journal homepage|sciencedirect|springer ?link|wiley online|tandfonline|cambridge\.org|oup\.com|sagepub|elsevier|^\s*(original|research|review|brief|special|invited|clinical)?\s*(article|report|communication|paper|editorial|commentary|letter|perspective)s?\s*$|^\s*(the )?(british |american |australian |new zealand |international |european )?journal of|\bjournal\b.*\b(19|20)\d\d|^\s*(the )?lancet|^\s*(bmj|jama)\b|psychological medicine\s*$|^\s*research\s*$|^\s*open access\s*$)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * [avoid]: "titles" already found on several different files (a journal's name, a website's
     * banner), never a file's own title.
     */
    fun find(meta: Map<String, String>, pages: List<InfoPage>, avoid: Set<String> = emptySet()): DocFacts {
        val text = pages.joinToString("\n") { it.text }
        val doi = doiRe.find(text)?.groupValues?.get(1)?.trimEnd('.', ',', ';', ')', ']')
        val fileName = meta["File"]?.substringBeforeLast('.')?.lowercase()
        val metaTitle = meta["Title"]?.let(::clean)?.takeIf { goodTitle(it) && it.lowercase() != fileName && key(it) !in avoid }
        val found = titleFromPages(pages, avoid)
        val title = metaTitle ?: found?.first
        val author = meta["Author"]?.let(::clean)?.takeIf(::goodAuthor)?.let(::firstAuthorOf)
            ?: found?.second?.let(::firstAuthorOf)
        val year = yearIn(text, doi) ?: meta["CreationDate"]?.let(::yearOfDate) ?: 0
        return DocFacts(title?.take(220), author, year, doi)
    }

    internal fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim().trim('"', '\'', '“', '”')

    internal fun goodTitle(t: String): Boolean {
        if (t.length < 6 || t.length > 300) return false
        val marks = t.count { !it.isWhitespace() }
        if (t.count { it.isLetter() } < marks * 0.6) return false
        // At least two real words, unless it is one long one.
        val words = t.split(Regex("[^\\p{L}]+")).count { it.length >= 3 }
        if (words < 2 && !(words == 1 && t.length >= 8 && !t.contains(' '))) return false
        // A file name or a code, not a title.
        if (!t.contains(' ') && (t.contains('_') || t.any { it.isDigit() } || t.length > 30)) return false
        return !junkTitle.matches(t)
    }

    internal fun goodAuthor(a: String): Boolean =
        a.length in 3..400 && a.any { it.isLetter() } && !junkAuthor.matches(a.trim()) &&
            // A login name ("jsmith"), not a person's name.
            (a.contains(' ') || a.contains(',') || a.first().isUpperCase())

    /** The title (the biggest type high on the first pages, joined across its lines) and the line under it. */
    /** How a title is compared with others: small letters, single spaces, no marks at the ends. */
    fun key(title: String) = title.lowercase().replace(Regex("\\s+"), " ").trim().trim('.', ':', ',', ';', '-')

    private fun titleFromPages(pages: List<InfoPage>, avoid: Set<String>): Pair<String, String?>? {
        for (p in pages.take(2)) {
            val body = p.lines.filter { it.text.length > 30 }.map { it.size }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
            val cands = p.lines.filter { l ->
                l.y0 < p.height * 0.65f && l.text.count { it.isLetter() } >= 4 && l.text.length <= 250 && !notTitle.containsMatchIn(l.text) && key(l.text) !in avoid
            }
            if (cands.isEmpty()) continue
            val top = cands.maxOf { it.size }
            if (body > 0f && top < body * 1.2f) continue
            val sorted = p.lines.sortedBy { it.y0 }
            val first = sorted.indexOfFirst { it in cands && kotlin.math.abs(it.size - top) < 0.6f }
            if (first < 0) continue
            val parts = arrayListOf(sorted[first])
            var k = first + 1
            while (k < sorted.size && parts.size < 4) {
                val l = sorted[k]
                val gap = l.y0 - parts.last().y1
                if (kotlin.math.abs(l.size - top) < 0.6f && gap < top * 1.2f && !notTitle.containsMatchIn(l.text)) { parts += l; k++ } else break
            }
            val title = clean(parts.joinToString(" ") { it.text.trim() }.replace(Regex("-\\s+(?=\\p{Ll})"), ""))
            if (!goodTitle(title) || key(title) in avoid) continue
            // The authors: the next line or two in smaller type that read as names.
            var author: String? = null
            for (l in sorted.drop(k).take(3)) {
                val t = l.text.trim()
                if (l.size >= top || t.isEmpty()) continue
                if (looksLikeNames(t)) { author = t; break }
            }
            return title to author
        }
        return null
    }

    private fun looksLikeNames(t: String): Boolean {
        if (t.length > 300 || notTitle.containsMatchIn(t)) return false
        val words = t.replace(Regex("[\\d*†‡§¶,;&]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 2) return false
        val caps = words.count { w -> w.first().isUpperCase() || w.lowercase() in setOf("and", "de", "van", "von", "der", "da", "el", "al") }
        return caps >= words.size * 0.8 && words.size <= 40
    }

    /** "Gin S. Malhi, Michelle Outhred and Tim Boyce" → "Malhi et al."; "Malhi, Gin S." → "Malhi". */
    internal fun firstAuthorOf(raw: String): String? {
        val s = raw.replace(Regex("[\\d*†‡§¶]+"), " ").replace(Regex("\\s+"), " ").trim().trim(',', ';')
        if (s.isEmpty()) return null
        val bySemi = s.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val people: List<String>
        val surnameFirst: Boolean
        if (bySemi.size > 1) { people = bySemi; surnameFirst = bySemi[0].contains(',') }
        else {
            val pieces = s.split(Regex(",|\\band\\b|&")).map { it.trim() }.filter { it.isNotEmpty() }
            // "Malhi, Gin S." is one person written surname first.
            surnameFirst = pieces.size == 2 && pieces[1].split(' ').all { it.length <= 3 || it.endsWith('.') } && !s.contains(" and ")
            people = if (surnameFirst) listOf(s) else pieces
        }
        val first = people.firstOrNull() ?: return null
        val words = first.split(' ').filter { it.isNotBlank() }
        // "Malhi GS" (initials after the name, as journals list authors) or "Gin S. Malhi".
        val initialsLast = words.size > 1 && words.last().length <= 3 && words.last().all { it.isUpperCase() || it == '.' }
        val surname = (if (surnameFirst) first.substringBefore(',') else if (initialsLast) words.first() else words.last()).trim().trim('.', ',')
        if (surname.length < 2 || !surname.first().isLetter()) return null
        return if (people.size > 1) "$surname et al." else surname
    }

    internal fun yearIn(text: String, doi: String? = null): Int? {
        val now = Calendar.getInstance().get(Calendar.YEAR) + 1
        // A DOI often carries its year: 10.1192/bjp.2024.172, 10.1016/j.jad.2021.03.004.
        doi?.let { d -> Regex("""[./](?:19|20)\d{2}[./]""").find(d)?.value?.trim('.', '/')?.toIntOrNull()?.takeIf { it in 1950..now }?.let { return it } }
        val pats = listOf(
            Regex("""©[^\n]{0,40}?\b((?:19|20)\d{2})\b"""),
            Regex("""copyright[^\n]{0,30}?\b((?:19|20)\d{2})\b""", RegexOption.IGNORE_CASE),
            Regex("""published online[^\n]{0,60}?\b((?:19|20)\d{2})\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:published|accepted|received):?\s[^\n]{0,30}?\b((?:19|20)\d{2})\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:journal|psychiatry|medicine|review|lancet|bmj|jama)[^\n]{0,60}?\(((?:19|20)\d{2})\)""", RegexOption.IGNORE_CASE),
            Regex("""\b((?:19|20)\d{2})\s*;\s*\d+\s*[(:]"""),
        )
        for (p in pats) {
            val y = p.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (y in 1900..now) return y
        }
        return null
    }

    private fun yearOfDate(d: String): Int? {
        val y = Regex("""(?:D:)?((?:19|20)\d{2})""").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return y.takeIf { it in 1990..Calendar.getInstance().get(Calendar.YEAR) + 1 }
    }
}
