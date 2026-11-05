package com.fadysaeed.pdfx.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * The phone's own list of files (Android's media index), which Samsung's My Files, Gallery,
 * WhatsApp's file picker and every other app read from. PDFx keeps it true to the files: a file it
 * renames or moves stays one entry under its new name, rather than a new entry beside a dead one
 * that opens to "file not found"; a file it takes away leaves no entry behind.
 */
class MediaIndex(private val context: Context, private val storage: File) {
    private val files: Uri = MediaStore.Files.getContentUri("external")
    private val dataCol = MediaStore.Files.FileColumns.DATA

    /** The index's entry for the file at [path], if it has one. */
    fun entryOf(path: String): Uri? = runCatching {
        context.contentResolver.query(files, arrayOf(MediaStore.Files.FileColumns._ID), "$dataCol = ?", arrayOf(path), null)?.use { c ->
            if (c.moveToFirst()) android.content.ContentUris.withAppendedId(files, c.getLong(0)) else null
        }
    }.getOrNull()

    /** Where the index has the entry [uri]'s file now. */
    private fun pathOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(dataCol), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * Moves or renames [src] to [dst] through the index itself, so every app sees the one file under
     * its new name. True when the file is at [dst] and no longer at [src]; false (nothing changed)
     * when the index has no entry for it or would not do it, and the caller moves it its own way.
     */
    fun move(src: File, dst: File): Boolean {
        if (!src.isFile || dst.exists()) return false
        val root = storage.absolutePath.trimEnd('/') + "/"
        val parent = dst.parentFile?.absolutePath?.let { "$it/" } ?: return false
        if (!parent.startsWith(root)) return false
        val entry = entryOf(src.absolutePath) ?: return false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, dst.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, parent.removePrefix(root))
        }
        val changed = runCatching { context.contentResolver.update(entry, values, null, null) }.getOrDefault(0)
        if (changed <= 0) return false
        // The index may have chosen a name of its own (a clash it saw, a character it would not take).
        val now = pathOf(entry)
        if (now != null && now != dst.absolutePath && !dst.exists()) {
            val there = File(now)
            if (there.isFile && there.renameTo(dst)) runCatching {
                context.contentResolver.update(entry, ContentValues().apply { put(dataCol, dst.absolutePath) }, null, null)
            }
        }
        if (dst.isFile && !src.exists()) return true
        // Not where it should be: put back as it was, if it went anywhere, and let the caller do it.
        val stray = now?.let(::File)
        if (!src.exists() && stray != null && stray.isFile && stray.absolutePath != dst.absolutePath) stray.renameTo(src)
        return dst.isFile && !src.exists()
    }

    /**
     * Where PDFx may take entries away: the phone's own storage, outside Android/ (other apps'
     * private folders, which even full file access cannot see into, so a file there looks missing
     * when it is not; taking its entry away could take the file with it).
     */
    private fun ours(path: String): Boolean {
        val root = storage.absolutePath.trimEnd('/')
        return path.startsWith("$root/") && !path.startsWith("$root/Android/")
    }

    /** Takes the index's entry for [path] away, once no file is there. */
    fun forget(path: String) {
        if (!ours(path) || File(path).exists()) return
        runCatching { context.contentResolver.delete(files, "$dataCol = ?", arrayOf(path)) }
    }

    /** Takes away the entries for files under [dir] that are no longer there (after a folder moved). */
    fun forgetUnder(dir: String) {
        val gone = ArrayList<String>()
        runCatching {
            context.contentResolver.query(files, arrayOf(dataCol), "$dataCol LIKE ?", arrayOf(dir.trimEnd('/') + "/%"), null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { if (!File(it).exists()) gone += it }
            }
        }
        gone.forEach(::forget)
    }

    /**
     * Entries for PDFs no longer on the phone, left by renames and moves done before PDFx kept the
     * index true (or by other apps), taken away. Returns how many went.
     */
    /** Missing for certain: on the phone's own storage, its folder there and readable, and the name not in it. */
    private fun surelyGone(path: String): Boolean {
        if (!ours(path)) return false
        val f = File(path)
        if (f.exists()) return false
        val names = f.parentFile?.list() ?: return false
        return f.name !in names
    }

    fun sweepPdfs(most: Int = 5000): Int {
        val gone = ArrayList<String>()
        runCatching {
            context.contentResolver.query(files, arrayOf(dataCol), "LOWER($dataCol) LIKE ?", arrayOf("%.pdf"), null)?.use { c ->
                while (c.moveToNext() && gone.size < most) c.getString(0)?.let { if (surelyGone(it)) gone += it }
            }
        }
        var n = 0
        for (p in gone) {
            forget(p)
            if (entryOf(p) == null) n++
        }
        return n
    }
}
