package com.fadysaeed.pdfx.data

/**
 * Whether a file's name says what it is. A clear name ("Lithium guidelines 2024", "تقرير الأشعة",
 * "Lithium_guidelines_final_v2", "Report") is what the file is called everywhere; only a vague one
 * (a journal's code, a camera's or scanner's numbers, "document(3)") gives way to the title read
 * from inside the file, and only a vague one is offered a new name when tidying.
 */
object Names {
    /** Words an app gives a file it had no name for: beside one, a single other word is not enough. */
    private val APP_WORDS = setOf(
        "document", "documents", "doc", "file", "files", "download", "downloads", "untitled", "scan", "scanned", "scans",
        "print", "printout", "pdf", "attachment", "image", "img", "photo", "picture", "screenshot", "camscanner",
        "whatsapp", "dsc", "dcim", "export", "output", "default", "temp", "tmp",
        "مستند", "ملف", "صورة", "سكان",
    )

    /** Words that say nothing about what is inside, on top of those: not counted as words of the name. */
    private val GENERIC = APP_WORDS + setOf(
        "new", "copy", "page", "pages", "main", "final", "draft", "version", "mobile", "fulltext", "full", "text",
        "article", "paper", "manuscript", "and", "the", "جديد", "نسخة",
    )

    /**
     * What a file is called in lists: its name, or the title read from inside it when the name says
     * nothing (a code, a camera's numbers).
     */
    fun shown(d: Doc): String {
        val base = d.name.removeSuffix(".pdf").removeSuffix(".PDF")
        val title = d.title?.takeIf { it.isNotBlank() } ?: return base
        return if (isClear(base)) base else title
    }

    /** The name of a file without ".pdf": true when it tells what the file is. */
    fun isClear(base: String): Boolean {
        // Asked for every file each time a list is drawn or sorted: worked out once a name.
        clear[base]?.let { return it }
        return work(base).also { if (clear.size < 20_000) clear[base] = it }
    }

    private val clear = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun work(base: String): Boolean {
        val b = base.trim()
        if (b.isEmpty()) return false
        val digits = b.count { it.isDigit() }
        // Underscores, dashes and dots stand in for spaces, and "camelCase" runs are words too.
        val words = b.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split(Regex("[\\s_\\-.+,()\\[\\]{}#~]+"))
            .filter { it.isNotEmpty() }
        // Real words: three letters or more, no digits inside, not a word every app uses.
        val real = words.filter { w -> w.count { it.isLetter() } >= 3 && w.none { it.isDigit() } && w.lowercase() !in GENERIC }
        val fromAnApp = words.any { it.lowercase() in APP_WORDS }
        return when {
            real.isEmpty() -> false
            // Mostly numbers: a date and time with words stuck on ("WhatsApp Document 2024-03-01 at 10.15.32").
            digits * 10 >= b.length * 3 && real.size < 3 -> false
            real.size >= 2 -> true
            // One word says enough alone ("Report", "Lithium 2024"), unless an app's own word is beside it.
            else -> real.single().length >= 4 && !fromAnApp
        }
    }
}
