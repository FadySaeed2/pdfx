package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument

/** A password on a file, taking it off, and the hidden details a file carries about where it came from. */
object Secure {
    /** Why a password cannot be used, or null when it can. */
    fun problem(password: String): String? = when {
        password.isEmpty() -> "Write a password."
        '"' in password -> "A password here cannot have the \" mark in it."
        password.length > 120 -> "That password is longer than PDF readers accept."
        else -> null
    }

    private fun opt(v: String) = if (',' in v) "\"$v\"" else v

    /**
     * Writes the file locked with [newPassword], AES-256: it opens only with that password, in any
     * reader. The same password unlocks everything, so nobody can open it with fewer rights.
     */
    fun protect(path: String, out: String, newPassword: String, password: String? = null): Int {
        problem(newPassword)?.let { throw IllegalArgumentException(it) }
        val d = Arranger.open(path, password)
        try {
            val p = opt(newPassword)
            d.save(out, "garbage=compact,encrypt=aes-256,user-password=$p,owner-password=$p")
            return d.countPages()
        } finally {
            d.destroy()
        }
    }

    /** Writes the file with no password, opened with the one it has. */
    fun unprotect(path: String, out: String, password: String): Int {
        val d = Arranger.open(path, password)
        try {
            d.save(out, "garbage=compact,encrypt=none")
            return d.countPages()
        } finally {
            d.destroy()
        }
    }

    /** The details a file says about itself, as (what, value), for showing before they are removed. */
    fun details(path: String, password: String? = null): List<Pair<String, String>> {
        val d = Arranger.open(path, password)
        try {
            val out = ArrayList<Pair<String, String>>()
            val names = listOf(
                "Title" to "Title", "Author" to "Author", "Subject" to "Subject", "Keywords" to "Keywords",
                "Creator" to "Made with", "Producer" to "Written by", "CreationDate" to "Created", "ModDate" to "Changed",
            )
            for ((key, label) in names) {
                val v = runCatching { (d as Document).getMetaData("info:$key") }.getOrNull()?.trim()
                if (!v.isNullOrEmpty()) out += label to (if (key.endsWith("Date")) date(v) else v)
            }
            val root = d.trailer.get("Root")
            if (!root.get("Metadata").isNull) out += "XMP" to "A hidden block of details (XMP), often with the names above and the editing history"
            if (!root.get("PieceInfo").isNull) out += "Program data" to "Data an editing program left behind"
            return out
        } finally {
            d.destroy()
        }
    }

    /** Writes the file without its details: title, author, programs, dates, the XMP block and leftovers of editing programs. */
    fun scrub(path: String, out: String, password: String? = null): Int {
        val d = Arranger.open(path, password)
        try {
            scrub(d)
            d.save(out, "garbage=compact,regenerate-id=yes")
            return d.countPages()
        } finally {
            d.destroy()
        }
    }

    fun scrub(d: PDFDocument) {
        d.trailer.delete("Info")
        val root = d.trailer.get("Root")
        root.delete("Metadata")
        root.delete("PieceInfo")
        for (i in 0 until d.countPages()) {
            val p = d.findPage(i)
            p.delete("Metadata")
            p.delete("PieceInfo")
        }
    }

    /** "D:20240312101500+11'00'" → "2024-03-12 10:15". */
    private fun date(v: String): String {
        val m = Regex("""D:(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?""").find(v) ?: return v
        val (y, mo, da, hh, mi) = m.destructured
        return buildString {
            append(y)
            if (mo.isNotEmpty()) append('-').append(mo)
            if (da.isNotEmpty()) append('-').append(da)
            if (hh.isNotEmpty()) append(' ').append(hh).append(':').append(mi.ifEmpty { "00" })
        }
    }
}
