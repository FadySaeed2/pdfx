package com.fadysaeed.pdfx.data

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * Everything PDFx knows about the user's PDFs, and every change made to them on disk.
 * File operations move real files, so each one is written to be safe to interrupt: a move that
 * cannot be a rename is a copy, a size check, and only then a delete.
 */
class Library(
    val context: Context,
    val db: LibraryDb,
    /** The shared storage root, /storage/emulated/0 on a phone. */
    val root: File = Environment.getExternalStorageDirectory(),
    private val allFilesAccess: () -> Boolean = { Environment.isExternalStorageManager() },
) {
    val dao = db.dao()
    val text = TextIndex(db)
    val bookmarks = Bookmarks(db)
    val versions = Versions(File(context.filesDir, "versions"))
    val trashDir = File(root, ".pdfx-trash")
    /** Where copies saved from other apps go, and the default place for new folders. */
    val homeDir = File(root, "Documents/PDFx")

    fun hasAllFilesAccess(): Boolean = runCatching { allFilesAccess() }.getOrDefault(false)

    // ---------------------------------------------------------------- opening

    /** Finds or adds the document behind a Uri. Paths are preferred so a file is known once. */
    suspend fun resolve(uri: Uri): Doc = withContext(Dispatchers.IO) {
        val path = pathFor(uri)
        if (path != null) {
            dao.byKey(path) ?: insertFile(File(path))
        } else {
            val key = uri.toString()
            dao.byKey(key) ?: run {
                val (name, size) = describe(uri)
                val d = Doc(key = key, path = null, uri = key, name = name, folder = null, size = size, modified = 0, indexedAt = 0)
                d.copy(id = dao.insert(d))
            }
        }
    }

    suspend fun recordOpen(id: Long, pages: Int, locked: Boolean) {
        val d = dao.byId(id) ?: return
        dao.update(d.copy(lastOpened = System.currentTimeMillis(), pages = if (pages > 0) pages else d.pages, locked = locked && pages == 0))
    }

    suspend fun updatePage(id: Long, page: Int) {
        val d = dao.byId(id) ?: return
        if (d.lastPage != page) dao.update(d.copy(lastPage = page))
    }

    // ---------------------------------------------------------------- finding files

    data class ScanResult(val found: Int, val added: Int, val removed: Int)

    /** Walks shared storage for PDFs and brings the library in line with what is on disk. */
    suspend fun scan(roots: List<File> = listOf(root)): ScanResult = withContext(Dispatchers.IO) {
        if (!hasAllFilesAccess()) return@withContext ScanResult(0, 0, 0)
        val files = HashMap<String, File>()
        roots.forEach { walk(it, files) }
        var added = 0
        var removed = 0
        db.withTransaction {
            val known = dao.all()
            val byKey = known.associateBy { it.key }
            for ((path, f) in files) {
                val d = byKey[path]
                if (d == null) {
                    insertFile(f); added++
                } else if (d.size != f.length() || d.modified != f.lastModified() || d.name != f.name) {
                    dao.update(d.copy(size = f.length(), modified = f.lastModified(), name = f.name, folder = f.parent))
                }
            }
            val rootPaths = roots.map { it.absolutePath }
            for (d in known) {
                val p = d.path ?: continue
                if (d.inTrash) {
                    if (!File(p).exists()) { forgetNow(d); removed++ }
                } else if (rootPaths.any { p.startsWith(it) } && p !in files) {
                    forgetNow(d); removed++
                }
            }
        }
        ScanResult(files.size, added, removed)
    }

    private fun walk(start: File, out: MutableMap<String, File>) {
        val skip = setOf(File(root, "Android/data").absolutePath, File(root, "Android/obb").absolutePath)
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(start to 0)
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (c in children) {
                if (c.name.startsWith(".")) continue
                if (c.isDirectory) {
                    if (depth < 14 && c.absolutePath !in skip) stack.addLast(c to depth + 1)
                } else if (c.name.endsWith(".pdf", ignoreCase = true) && c.length() > 0) {
                    out[c.absolutePath] = c
                }
            }
        }
    }

    private suspend fun insertFile(f: File): Doc {
        val d = Doc(
            key = f.absolutePath, path = f.absolutePath, uri = Uri.fromFile(f).toString(), name = f.name,
            folder = f.parent, size = f.length(), modified = f.lastModified(),
        )
        return d.copy(id = dao.insert(d))
    }

    /**
     * Callers are already off the main thread. It must stay on the calling thread: inside a scan
     * it runs within a Room transaction, and touching the database from another thread there
     * would wait for the transaction forever.
     */
    /** Where files handed over by other apps (Gmail, WhatsApp, Drive) are kept as real files. */
    val receivedDir: File get() = File(homeDir, "Received")

    /**
     * A file another app hands over, made a real file on the phone at once: Gmail, WhatsApp and the
     * like let PDFx read their files only for a while, and a file PDFx cannot read later cannot be
     * merged, split, or saved in place. Returns the file to open: the same file when it is already
     * on the phone (WhatsApp keeps one in WhatsApp Documents; the same name and size), a copy in
     * Documents/PDFx/Received otherwise (the same copy again when the same file comes twice). Null
     * when it cannot be copied (no access to the phone's files): the file is then opened as before.
     */
    suspend fun receive(uri: Uri): Doc? = withContext(Dispatchers.IO) {
        pathFor(uri)?.let { p -> return@withContext dao.byKey(p) ?: insertFile(File(p)) }
        if (!hasAllFilesAccess()) return@withContext null
        val (described, size) = describe(uri)
        val name = described.replace('/', '_').let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val old = dao.byKey(uri.toString())
        if (size > 0) {
            dao.all().firstOrNull { d -> d.path != null && !d.inTrash && d.name == name && d.size == size && File(d.path).isFile }
                ?.let { same -> return@withContext adoptCopy(File(same.path!!), old) }
        }
        val target = copyIn(name) { out -> context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) } != null }
            ?: return@withContext null
        adoptCopy(target, old)
    }

    /**
     * Files the library still knows only through another app: each becomes a real file where
     * possible (the same file already on the phone, the copy PDFx kept, or a fresh copy while the
     * other app still allows it). Those that cannot be read any more stay as they are.
     */
    suspend fun adoptShared() = withContext(Dispatchers.IO) {
        if (!hasAllFilesAccess()) return@withContext
        val all = dao.all()
        for (d in all) {
            if (d.isFile || d.inTrash) continue
            runCatching {
                val same = all.firstOrNull { f -> f.path != null && !f.inTrash && f.name == d.name && f.size == d.size && d.size > 0 && File(f.path).isFile }
                if (same != null) { adoptCopy(File(same.path!!), d); return@runCatching }
                val kept = keptCopy(d)
                val name = d.name.replace('/', '_').let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
                val target = if (kept.isFile && kept.length() > 0) copyIn(name) { out -> kept.inputStream().use { it.copyTo(out) }; true }
                else copyIn(name) { out -> context.contentResolver.openInputStream(Uri.parse(d.uri))?.use { it.copyTo(out) } != null }
                if (target != null) { adoptCopy(target, d); kept.delete() }
            }
        }
    }

    /**
     * Writes a file into Documents/PDFx/Received under [name], through [write]; when a file of that
     * name is already there with exactly the same bytes, that one is used and nothing is added.
     */
    private fun copyIn(name: String, write: (java.io.OutputStream) -> Boolean): File? {
        val dir = receivedDir.also { it.mkdirs() }
        val tmp = File(dir, ".$name.receiving")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val ok = runCatching { java.security.DigestOutputStream(tmp.outputStream(), digest).use { write(it) } }.getOrDefault(false)
        if (!ok || tmp.length() == 0L) { tmp.delete(); return null }
        val hash = digest.digest()
        fun sha(f: File): ByteArray {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest()
        }
        val here = File(dir, name)
        if (here.isFile && here.length() == tmp.length() && sha(here).contentEquals(hash)) { tmp.delete(); return here }
        val target = uniqueTarget(dir, name)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        scanMedia(target)
        return target
    }

    /**
     * The app's own copy of a file shared from another app (Gmail, WhatsApp). Those apps let PDFx
     * read the file only for a while; the copy, made while it can, keeps the file usable after.
     */
    fun keptCopy(d: Doc): File = File(context.filesDir, "shared/${d.id}.pdf")

    /** Makes or refreshes the kept copy of a shared file from [uri], while it can still be read. */
    suspend fun keepCopy(d: Doc, uri: Uri) = withContext(Dispatchers.IO) {
        if (d.isFile) return@withContext
        val f = keptCopy(d)
        if (f.exists() && f.length() == d.size && d.size > 0) return@withContext
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.part")
            context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return@runCatching
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }

    private suspend fun forgetNow(d: Doc) {
        keptCopy(d).delete()
        text.clear(d.id)
        bookmarks.clear(d.id)
        versions.clear(d.id)
        dao.untagDoc(d.id)
        dao.delete(d)
    }

    // ---------------------------------------------------------------- changes on disk

    suspend fun setStar(d: Doc, on: Boolean) = dao.update(d.copy(starred = on))

    suspend fun rename(d: Doc, newName: String): Result<Doc> = io {
        val src = fileOf(d)
        val clean = newName.trim().removeSuffix(".pdf").removeSuffix(".PDF").trim()
        require(clean.isNotEmpty() && !clean.contains('/')) { "That name can't be used." }
        val dst = File(src.parentFile, "$clean.pdf")
        if (dst.absolutePath == src.absolutePath) return@io d
        if (dst.exists()) throw IOException("There is already a file called ${dst.name} in this folder.")
        if (!src.renameTo(dst)) throw IOException("The file could not be renamed.")
        moved(d, src, dst)
    }

    suspend fun move(d: Doc, dir: File): Result<Doc> = io {
        val src = fileOf(d)
        if (src.parentFile?.absolutePath == dir.absolutePath) return@io d
        dir.mkdirs()
        val dst = uniqueTarget(dir, src.name)
        relocate(src, dst)
        moved(d, src, dst)
    }

    suspend fun copy(d: Doc, dir: File): Result<Doc> = io {
        val src = fileOf(d)
        dir.mkdirs()
        val dst = uniqueTarget(dir, src.name)
        copyVerified(src, dst)
        scanMedia(dst)
        insertFile(dst)
    }

    /** Moves a file into PDFx's trash. It comes back with [restore] for 30 days. */
    suspend fun trash(d: Doc): Result<Doc> = io {
        val src = fileOf(d)
        trashDir.mkdirs()
        File(trashDir, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        val dst = uniqueTarget(trashDir, "${d.id}-${src.name}")
        relocate(src, dst)
        val t = d.copy(key = dst.absolutePath, path = dst.absolutePath, uri = Uri.fromFile(dst).toString(), folder = dst.parent, trashedAt = System.currentTimeMillis(), trashOrigin = src.absolutePath)
        dao.update(t)
        scanMedia(src)
        t
    }

    suspend fun restore(d: Doc): Result<Doc> = io {
        val src = fileOf(d)
        val origin = File(d.trashOrigin ?: File(homeDir, d.name).absolutePath)
        val dir = origin.parentFile ?: homeDir
        dir.mkdirs()
        val dst = uniqueTarget(dir, origin.name)
        relocate(src, dst)
        val r = d.copy(key = dst.absolutePath, path = dst.absolutePath, uri = Uri.fromFile(dst).toString(), name = dst.name, folder = dst.parent, trashedAt = 0, trashOrigin = null)
        dao.update(r)
        scanMedia(dst)
        r
    }

    /** Deletes the file for good. The UI always asks first. */
    suspend fun deleteForever(d: Doc): Result<Unit> = io {
        val f = d.path?.let(::File)
        if (f != null && f.exists() && !f.delete()) throw IOException("The file could not be deleted.")
        forgetNow(d)
    }

    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        dao.all().filter { it.inTrash }.count { deleteForever(it).isSuccess }
    }

    suspend fun purgeTrash(olderThanDays: Int = 30) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - olderThanDays * 86_400_000L
        dao.all().filter { it.inTrash && it.trashedAt < cutoff }.forEach { deleteForever(it) }
    }

    /** Keeps a file shared from another app by copying it into Documents/PDFx. */
    suspend fun saveCopy(d: Doc): Result<Doc> = io {
        homeDir.mkdirs()
        val base = if (d.name.endsWith(".pdf", true)) d.name else "${d.name}.pdf"
        val dst = uniqueTarget(homeDir, base)
        context.contentResolver.openInputStream(Uri.parse(d.uri))?.use { input ->
            dst.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: throw IOException("The original is no longer available. Open it again from the app it came from.")
        scanMedia(dst)
        val kept = insertFile(dst).copy(lastOpened = d.lastOpened, lastPage = d.lastPage, pages = d.pages, starred = d.starred)
        dao.update(kept)
        forgetNow(d)
        kept
    }

    /** Brings the library's record in line with a file PDFx has just saved. */
    suspend fun afterSave(d: Doc, f: File): Doc {
        val now = dao.byId(d.id) ?: d
        val wasRead = now.indexedAt == now.modified
        val updated = now.copy(size = f.length(), modified = f.lastModified(), indexedAt = if (wasRead) f.lastModified() else now.indexedAt)
        dao.update(updated)
        return updated
    }

    /** Records a saved copy of a file from another app, carrying over its place and star. */
    suspend fun adoptCopy(f: File, from: Doc?): Doc {
        val kept = (dao.byKey(f.absolutePath) ?: insertFile(f)).let { d ->
            if (from == null) d else d.copy(lastOpened = System.currentTimeMillis(), lastPage = from.lastPage, pages = from.pages, starred = from.starred)
        }
        dao.update(kept)
        if (from != null && !from.isFile) forgetNow(from)
        return kept
    }

    /** Takes a file shared from another app off the list. The file itself is untouched. */
    suspend fun forget(d: Doc) = withContext(Dispatchers.IO) { forgetNow(d) }

    private suspend fun moved(d: Doc, src: File, dst: File): Doc {
        val m = d.copy(key = dst.absolutePath, path = dst.absolutePath, uri = Uri.fromFile(dst).toString(), name = dst.name, folder = dst.parent, modified = dst.lastModified(), indexedAt = if (d.indexedAt == d.modified) dst.lastModified() else d.indexedAt)
        dao.update(m)
        scanMedia(src, dst)
        return m
    }

    private fun fileOf(d: Doc): File {
        val p = d.path ?: throw IOException("This file belongs to another app. Save a copy first.")
        val f = File(p)
        if (!f.exists()) throw IOException("The file is no longer there.")
        return f
    }

    private fun relocate(src: File, dst: File) {
        if (src.renameTo(dst)) return
        copyVerified(src, dst)
        if (!src.delete()) {
            dst.delete()
            throw IOException("The file could not be moved.")
        }
    }

    private fun copyVerified(src: File, dst: File) {
        val tmp = File(dst.parentFile, ".${dst.name}.part")
        try {
            src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16); o.fd.sync() } }
            if (tmp.length() != src.length()) throw IOException("The copy came out incomplete.")
            if (!tmp.renameTo(dst)) throw IOException("The copy could not be finished.")
            dst.setLastModified(src.lastModified())
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun scanMedia(vararg files: File) {
        runCatching { MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null) }
    }

    private suspend fun <T> io(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching { block() } }

    // ---------------------------------------------------------------- tags

    suspend fun createTag(name: String, color: Int): Long = dao.insertTag(Tag(name = name.trim(), color = color))

    suspend fun setTag(docId: Long, tagId: Long, on: Boolean) {
        if (on) dao.tag(DocTag(docId, tagId)) else dao.untag(docId, tagId)
    }

    suspend fun deleteTag(id: Long) = db.withTransaction { dao.untagAll(id); dao.deleteTag(id) }

    // ---------------------------------------------------------------- search inside documents

    suspend fun searchText(query: String): List<TextHit> {
        val match = ftsQuery(query) ?: return emptyList()
        return withContext(Dispatchers.IO) { runCatching { text.search(match) }.getOrDefault(emptyList()) }
    }

    // ---------------------------------------------------------------- Uris and paths

    /** A real path for the Uri when PDFx can read it directly, so the same file is never listed twice. */
    fun pathFor(uri: Uri): String? {
        val path = when (uri.scheme) {
            "file" -> uri.path
            "content" -> contentPath(uri)
            else -> null
        } ?: return null
        val f = File(path)
        return if (f.isFile && f.canRead()) f.absolutePath else null
    }

    private fun contentPath(uri: Uri): String? {
        if (!hasAllFilesAccess()) return null
        return runCatching {
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    val id = DocumentsContract.getDocumentId(uri)
                    val volume = id.substringBefore(':')
                    val rel = id.substringAfter(':')
                    if (volume.equals("primary", true)) File(root, rel).absolutePath else "/storage/$volume/$rel"
                }
                "com.android.providers.downloads.documents" -> {
                    val id = DocumentsContract.getDocumentId(uri)
                    if (id.startsWith("raw:")) id.removePrefix("raw:") else null
                }
                "media" -> context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                else -> null
            }
        }.getOrNull()
    }

    fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        return name to size
    }

    /** A friendly name for a folder: "Download", "Documents/PDFx", "WhatsApp Documents". */
    fun folderLabel(folder: String?): String {
        if (folder == null) return "From other apps"
        val f = File(folder)
        if (f.absolutePath == trashDir.absolutePath) return "Trash"
        if (f.absolutePath.endsWith("WhatsApp/Media/WhatsApp Documents")) return "WhatsApp Documents"
        val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart('/')
        return if (rel.isEmpty()) "Phone storage" else rel
    }

    // ---------------------------------------------------------------- moving from phase 0

    /** Brings phase 0's recent list into the library once. */
    suspend fun migrateRecents() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)
        val raw = prefs.getString("items", null) ?: return@withContext
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = resolve(Uri.parse(o.getString("uri")))
                dao.update(
                    d.copy(
                        lastOpened = maxOf(d.lastOpened, o.optLong("opened", 0)),
                        lastPage = o.optInt("page", 0),
                        pages = maxOf(d.pages, o.optInt("pages", 0)),
                        name = if (d.isFile) d.name else o.optString("name", d.name),
                        size = if (d.size > 0) d.size else o.optLong("size", -1),
                    ),
                )
            }
        }
        prefs.edit().clear().apply()
    }

    companion object {
        fun uniqueTarget(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "pdf")
            var n = 2
            while (f.exists()) { f = File(dir, "$base ($n).$ext"); n++ }
            return f
        }
    }
}
