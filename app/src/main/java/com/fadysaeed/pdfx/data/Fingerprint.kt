package com.fadysaeed.pdfx.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * What a file is, read from its bytes rather than its name or place: its size and a SHA-256 of its
 * first, middle and last 64 KB. Two files with the same fingerprint are the same file for every
 * practical purpose; a file moved or renamed outside PDFx keeps it, so its tags, star, bookmarks
 * and reading place can follow it. Reading 192 KB at most keeps it quick for thousands of files.
 */
object Fingerprint {
    private const val CHUNK = 64 * 1024

    fun of(f: File): String? = runCatching {
        val len = f.length()
        if (len <= 0) return null
        val md = MessageDigest.getInstance("SHA-256")
        md.update(len.toString().toByteArray())
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(CHUNK)
            fun take(at: Long) {
                r.seek(at.coerceIn(0, maxOf(0, len - 1)))
                val n = r.read(buf, 0, minOf(CHUNK.toLong(), len - at.coerceAtLeast(0)).toInt().coerceAtLeast(0))
                if (n > 0) md.update(buf, 0, n)
            }
            if (len <= 3L * CHUNK) {
                var at = 0L
                while (at < len) { take(at); at += CHUNK }
            } else {
                take(0); take(len / 2 - CHUNK / 2); take(len - CHUNK)
            }
        }
        "1:$len:" + md.digest().joinToString("") { "%02x".format(it) }.take(40)
    }.getOrNull()
}
