package com.fadysaeed.pdfx.engine

/** The kinds of form field PDFx fills. */
enum class FieldKind { TEXT, CHECKBOX, RADIO, CHOICE, SIGNATURE, OTHER }

/**
 * A form field on a page: what it is, where it is, what it holds. [index] is its place among the
 * page's fields. [on] is whether a checkbox or radio button is ticked. [datePattern] is set for
 * date fields: the file's own format when it has one, else null with [isDate] still true.
 */
data class Field(
    val page: Int,
    val index: Int,
    val kind: FieldKind,
    val name: String,
    val value: String,
    val box: Box,
    val readOnly: Boolean = false,
    val multiline: Boolean = false,
    val options: List<String> = emptyList(),
    val on: Boolean = false,
    val isDate: Boolean = false,
    val datePattern: String? = null,
    val maxLen: Int = 0,
) {
    /** A readable name from the field's own: "date_of_birth" becomes "Date of birth". */
    val label: String get() = name.substringAfterLast('.').replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .trim().lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Field" }
}

/** Turns an Acrobat date format ("dd/mm/yyyy", "d-mmm-yy") into a Java one ("dd/MM/yyyy", "d-MMM-yy"). */
fun acrobatToJavaDate(f: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < f.length) {
        val c = f[i]
        var n = 1
        while (i + n < f.length && f[i + n] == c) n++
        val run = c.toString().repeat(n)
        sb.append(
            when (c) {
                'm' -> "M".repeat(n)       // months in Acrobat are lower case
                'y' -> "y".repeat(n)
                'd' -> "d".repeat(n)
                'H', 'h', 'M', 's', 't' -> if (c == 'M') "m".repeat(n) else run
                else -> if (c.isLetter()) "'$run'" else run
            },
        )
        i += n
    }
    return sb.toString()
}

/** Reads the date format out of a field's format script, like AFDate_FormatEx("dd/mm/yyyy"). */
fun dateFormatFromScript(js: String?): String? {
    if (js.isNullOrBlank()) return null
    val m = Regex("AFDate_Format(?:Ex)?\\(\\s*\"([^\"]+)\"").find(js) ?: return null
    return acrobatToJavaDate(m.groupValues[1])
}

/** The three ways PDFx writes today's date. */
val DatePatterns = listOf("dd/MM/yyyy", "d MMMM yyyy", "yyyy-MM-dd")

fun formatToday(pattern: String, today: java.time.LocalDate = java.time.LocalDate.now()): String =
    runCatching { today.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH)) }
        .getOrDefault(today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
