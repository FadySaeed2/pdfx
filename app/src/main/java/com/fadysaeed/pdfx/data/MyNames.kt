package com.fadysaeed.pdfx.data

import android.content.Context
import com.fadysaeed.pdfx.engine.PdfSession

/**
 * The names that are the user's on marks: the one PDFx writes into each new mark, and any others
 * other apps wrote ("Fady Saeed" in Adobe, "fady" in Xodo), so the Highlights page can show the
 * user's own marks and not those that came in the files from others. Until a name is given, the
 * names on the user's certificates stand in, and new marks are signed "PDFx".
 */
class MyNames(context: Context, private val certificates: () -> List<String>) {
    private val prefs = context.getSharedPreferences("marks", Context.MODE_PRIVATE)

    fun names(): List<String> {
        val saved = prefs.getString("names", null)
        val list = saved?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: certificates().filter { it.isNotBlank() }
        return list.distinctBy { it.lowercase() }
    }

    fun set(names: List<String>) {
        prefs.edit().putString("names", names.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")).apply()
        apply()
    }

    /** Has new marks carry the first name. */
    fun apply() { PdfSession.author = names().firstOrNull() ?: "PDFx" }

    /** A mark is the user's when it is signed with one of their names or by PDFx, or not signed at all. */
    fun mine(author: String): Boolean {
        val a = author.trim()
        if (a.isEmpty() || a.equals("PDFx", true)) return true
        return names().any { it.equals(a, true) }
    }
}
