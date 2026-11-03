package com.fadysaeed.pdfx.engine

/** What sort of file it is, for the shelves. */
enum class DocKind(val label: String, val shelf: String) {
    PAPER("Paper", "Papers"),
    BOOK("Book", "Books"),
    GUIDELINE("Guideline", "Guidelines"),
    LETTER("Letter", "Letters"),
    FORM("Form", "Forms"),
    RECEIPT("Receipt", "Receipts and bills"),
    SLIDES("Slides", "Slides"),
    SCAN("Scan", "Scans"),
    OTHER("Other", "Everything else"),
}

/** What a file shows of itself, for telling its kind. */
class KindClues(
    val name: String,
    val folder: String?,
    val pages: Int,
    /** The words of its first three pages. */
    val text: String,
    val title: String?,
    val doi: String?,
    val formFields: Int,
    /** Its first page is wider than tall. */
    val landscape: Boolean,
    /** The program that made it, as the file says. */
    val creator: String,
)

/**
 * Tells a file's kind from what is in it, by rules a person would use: a DOI or an abstract with
 * methods and references makes a paper; a long file with a contents list or an ISBN a book; a
 * title or opening that says guideline, guidance or recommendations a guideline; "Dear …" and a
 * sign-off a letter; fillable fields a form; an invoice number and amounts a receipt; wide pages
 * from a slide program slides; pages that are only pictures a scan. Wrong now and then; nothing is
 * moved or renamed because of it.
 */
object Kinds {
    private fun has(text: String, vararg words: String) = words.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

    fun of(c: KindClues): DocKind {
        val t = c.text
        val head = (c.title.orEmpty() + "\n" + t.take(2500))
        val words = t.split(Regex("\\s+")).count { it.length > 1 }
        if (c.formFields >= 3) return DocKind.FORM
        if (c.creator.contains("PowerPoint", true) || c.creator.contains("Keynote", true) || c.creator.contains("Impress", true) || c.creator.contains("Google Slides", true)) return DocKind.SLIDES
        if (words < 15 * c.pages.coerceAtMost(3) && (c.name.startsWith("Scan ") || c.name.startsWith("Pictures ") || words < 5)) return DocKind.SCAN
        if (c.landscape && c.pages >= 5 && words < 120 * c.pages.coerceAtMost(3)) return DocKind.SLIDES
        if (c.pages <= 4 && has(t, "tax invoice", "invoice", "receipt", "amount due", "amount paid", "balance due", "payment received", "statement of account") &&
            (has(t, "GST", "ABN", "total", "subtotal", "NZBN") || Regex("[$€£]\\s?\\d").containsMatchIn(t))
        ) return DocKind.RECEIPT
        if (has(c.title.orEmpty(), "guideline", "guidelines", "guidance", "clinical practice", "position statement", "consensus statement", "protocol", "policy", "procedure", "pathway") ||
            (has(head, "clinical practice guideline", "practice guideline", "NICE guideline", "recommendations for", "consensus statement", "position statement") && c.pages >= 4)
        ) return DocKind.GUIDELINE
        val paperish = c.doi != null || (has(t, "abstract") && has(t, "introduction", "methods", "background", "results", "conclusions"))
        if (c.pages >= 80 && (has(t, "ISBN", "contents", "table of contents", "preface", "foreword", "chapter 1", "chapter one") || c.pages >= 150)) return DocKind.BOOK
        if (paperish && c.pages <= 80) return DocKind.PAPER
        if (c.pages <= 8 && Regex("(?m)^\\s*Dear\\s+\\S").containsMatchIn(t) &&
            has(t, "yours sincerely", "yours faithfully", "kind regards", "best regards", "regards", "sincerely", "thank you for", "re:")
        ) return DocKind.LETTER
        if (c.pages >= 80) return DocKind.BOOK
        return DocKind.OTHER
    }
}
