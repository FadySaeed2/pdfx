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
import kotlinx.coroutines.sync.withLock
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
    val marks = Marks(db)
    val scanWords = ScanWords(db)
    val autoTags = AutoTags(db)
    val versions = Versions(File(context.filesDir, "versions"))
    /** The phone's own list of files, kept true to what PDFx renames, moves and takes away. */
    val media = MediaIndex(context, root)
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
        // The home-screen widget lists the files opened last.
        runCatching { com.fadysaeed.pdfx.widget.RecentWidget.refresh(context) }
    }

    suspend fun updatePage(id: Long, page: Int) {
        val d = dao.byId(id) ?: return
        if (d.lastPage != page) dao.setPage(id, page)
        // The widget shows the page each file was left at; a few seconds behind is close enough.
        val now = System.currentTimeMillis()
        if (now - widgetAt > 15_000) { widgetAt = now; runCatching { com.fadysaeed.pdfx.widget.RecentWidget.refresh(context) } }
    }

    @Volatile private var widgetAt = 0L

    // ---------------------------------------------------------------- finding files

    data class ScanResult(val found: Int, val added: Int, val removed: Int, val moved: Int = 0)

    /**
     * Walks shared storage for PDFs and brings the library in line with what is on disk. A file
     * that went from one place and turned up in another (moved or renamed by another app) is the
     * same document: it keeps its tags, star, bookmarks and reading place. It is matched by its
     * fingerprint, or, for one known before fingerprints, by its name and size.
     */
    /**
     * Held while files are looked through or moved, renamed or trashed by PDFx. A rename that fell
     * between a look round the phone and the library reading what it knew made the file look gone
     * (and a new one appear in its place), so it dropped out of Recent and lost its tags.
     */
    private val fileOps = kotlinx.coroutines.sync.Mutex()

    /**
     * One lock for each file PDFx writes into: a save from the open file, scans read in the
     * background, a move to the trash. Two of them on one file at once could each write over the
     * other's work, or the trash could take the file away from under a save.
     */
    private val fileLocks = HashMap<String, kotlinx.coroutines.sync.Mutex>()

    suspend fun <T> holding(f: File, block: suspend () -> T): T {
        val m = synchronized(fileLocks) { fileLocks.getOrPut(f.absolutePath) { kotlinx.coroutines.sync.Mutex() } }
        return m.withLock { block() }
    }

    suspend fun scan(roots: List<File> = listOf(root)): ScanResult = withContext(Dispatchers.IO) {
        val r = fileOps.withLock { scanNow(roots) }
        // Reading fingerprints takes a while on a first run; a rename or a move need not wait for it.
        fillFingerprints()
        r
    }

    private suspend fun scanNow(roots: List<File>): ScanResult {
        if (!hasAllFilesAccess()) return ScanResult(0, 0, 0)
        val files = HashMap<String, File>()
        roots.forEach { walk(it, files) }
        val rootPaths = roots.map { it.absolutePath }
        val known = dao.all()
        val byKey = known.associateBy { it.key }
        // Gone from where they were: maybe moved, maybe deleted. Not a file added while the look round
        // went on (one just shared in from WhatsApp, a save): it is there, only after the look passed.
        val gone = known.filter { d -> val p = d.path; p != null && !d.inTrash && rootPaths.any { p.startsWith(it) } && p !in files && !File(p).exists() }.toMutableList()
        val fresh = files.filterKeys { it !in byKey }
        // Fingerprints of the new arrivals, read before the transaction (they read files).
        val prints = if (fresh.isEmpty()) emptyMap() else fresh.mapValues { (_, f) -> Fingerprint.of(f) }
        var added = 0
        var removed = 0
        var moved = 0
        // Files to forget, forgotten once the transaction is over: forgetting clears their words from
        // search, which takes search's own lock, and the indexer takes that lock first and the
        // database after. Both inside one another, each could wait for the other for ever.
        val forget = ArrayList<Doc>()
        db.withTransaction {
            for ((path, f) in fresh) {
                // Added by something else since the library was read (a file shared in, a save).
                if (dao.byKey(path) != null) continue
                val fp = prints[path]
                val was = gone.firstOrNull { fp != null && it.fingerprint == fp }
                    ?: gone.firstOrNull { it.fingerprint == null && it.name == f.name && it.size == f.length() }
                    // Renamed (here or in another app) while its fingerprint was being read again: a
                    // rename keeps the size and the time it last changed.
                    ?: gone.firstOrNull { (it.fingerprint == null || fp == null) && it.size == f.length() && it.modified == f.lastModified() }
                if (was != null) {
                    gone.remove(was)
                    // Renamed where it lies (by someone, in a file manager): that name is what it is called now.
                    val renamed = was.name != f.name && was.folder == f.parent
                    // As it is now, not as it was when the look round began.
                    val wasNow = dao.byId(was.id) ?: was
                    dao.update(wasNow.copy(
                        key = f.absolutePath, path = f.absolutePath, uri = Uri.fromFile(f).toString(), name = f.name,
                        folder = f.parent, size = f.length(), modified = f.lastModified(), fingerprint = fp ?: was.fingerprint,
                        title = if (renamed) null else was.title,
                    ))
                    if (renamed) { markNamed(was.id); keepNames(listOf(was.id)) }
                    moved++
                } else {
                    insertFile(f, fp); added++
                }
            }
            for ((path, f) in files) {
                if (path !in byKey) continue
                val d = dao.byKey(path) ?: continue
                if (d.size != f.length() || d.modified != f.lastModified() || d.name != f.name) {
                    // Changed inside: its fingerprint is read again later.
                    dao.update(d.copy(size = f.length(), modified = f.lastModified(), name = f.name, folder = f.parent, fingerprint = if (d.size != f.length() || d.modified != f.lastModified()) null else d.fingerprint))
                }
            }
            for (d in known) {
                val p = d.path ?: continue
                if (d.inTrash && !File(p).exists()) { forget += d; removed++ }
            }
            for (d in gone) { forget += d; removed++ }
        }
        // Still gone, and still the same row there: a file put back meanwhile, or a row that moved on, stays.
        for (d in forget) {
            val p = d.path ?: continue
            if (File(p).exists()) continue
            if (dao.byId(d.id)?.path != p) continue
            forgetNow(d)
        }
        return ScanResult(files.size, added, removed, moved)
    }

    /** Reads the fingerprints of files known without one (from before fingerprints, or changed since), a batch at a time. */
    suspend fun fillFingerprints(batch: Int = 400) = withContext(Dispatchers.IO) {
        for (d in dao.withoutFingerprint(batch)) {
            val f = File(d.path ?: continue)
            val fp = Fingerprint.of(f) ?: continue
            dao.byId(d.id)?.let { now -> if (now.path == d.path) dao.update(now.copy(fingerprint = fp)) }
        }
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

    private suspend fun insertFile(f: File, fingerprint: String? = Fingerprint.of(f)): Doc {
        val d = Doc(
            key = f.absolutePath, path = f.absolutePath, uri = Uri.fromFile(f).toString(), name = f.name,
            folder = f.parent, size = f.length(), modified = f.lastModified(), fingerprint = fingerprint,
            addedAt = System.currentTimeMillis(),
        )
        // Another look round or a share may have added the same file a moment before.
        return try {
            d.copy(id = dao.insert(d))
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            dao.byKey(f.absolutePath) ?: throw e
        }
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
            // The same file already on the phone: the same name and size, and every byte the same (two
            // reports of one name and size can still differ inside).
            dao.all().filter { d -> d.path != null && !d.inTrash && d.name == name && d.size == size && File(d.path).isFile }
                .firstOrNull { d -> runCatching { context.contentResolver.openInputStream(uri)?.use { sameBytes(it, File(d.path!!)) } }.getOrNull() == true }
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
                val kept = keptCopy(d)
                val same = all.filter { f -> f.path != null && !f.inTrash && f.name == d.name && f.size == d.size && d.size > 0 && File(f.path).isFile }
                    .firstOrNull { f ->
                        runCatching {
                            if (kept.isFile) kept.inputStream().use { sameBytes(it, File(f.path!!)) }
                            else context.contentResolver.openInputStream(Uri.parse(d.uri))?.use { sameBytes(it, File(f.path!!)) }
                        }.getOrNull() == true
                    }
                if (same != null) { adoptCopy(File(same.path!!), d); return@runCatching }
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
        // A name of its own for each copy being made: two files of one name shared in at once (or a
        // share while older ones are made real) never write into one another.
        val tmp = File(dir, ".$name.${System.nanoTime()}.receiving")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val ok = runCatching { java.security.DigestOutputStream(tmp.outputStream(), digest).use { write(it) } }.getOrDefault(false)
        if (!ok || tmp.length() == 0L) { tmp.delete(); return null }
        val hash = digest.digest()
        fun sha(f: File): ByteArray {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest()
        }
        // Picking the name and taking it, one copy at a time.
        val target = synchronized(receiving) {
            val here = File(dir, name)
            if (here.isFile && here.length() == tmp.length() && sha(here).contentEquals(hash)) { tmp.delete(); return here }
            val t = uniqueTarget(dir, name)
            if (!tmp.renameTo(t)) { tmp.delete(); return null }
            t
        }
        scanMedia(target)
        return target
    }

    private val receiving = Any()

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
        marks.clear(d.id)
        bookmarks.clear(d.id)
        versions.clear(d.id)
        dao.untagDoc(d.id)
        dao.delete(d)
    }

    // ---------------------------------------------------------------- changes on disk

    suspend fun setStar(d: Doc, on: Boolean) = dao.setStarred(d.id, on)

    /**
     * Copies of one file: [keep] stays and takes the others' tags, star, bookmarks and the furthest
     * reading place; the others go to the trash (for 30 days). Returns how many went.
     */
    suspend fun mergeDuplicates(keep: Doc, others: List<Doc>): Result<Int> = runCatching {
        var n = 0
        for (o0 in others) {
            // Compared and moved in one go, both files held (in one fixed order, so two merges never
            // wait on each other): a file changed between the two can't go to the trash as a copy.
            val went = withContext(Dispatchers.IO) {
                fileOps.withLock {
                    val k = dao.byId(keep.id) ?: error("That file is gone.")
                    val o = dao.byId(o0.id) ?: return@withLock false
                    if (o.id == k.id || o.inTrash) return@withLock false
                    val kf = File(k.path ?: return@withLock false)
                    val of = File(o.path ?: return@withLock false)
                    val (first, second) = listOf(kf, of).sortedBy { it.absolutePath }
                    holding(first) {
                        holding(second) {
                            // The fingerprint reads only parts of a big file: every byte is compared.
                            if (!sameBytes(kf, of)) return@holding false
                            db.withTransaction { dao.tagIdsOf(o.id).forEach { dao.tag(DocTag(k.id, it)) } }
                            bookmarks.pages(o.id).forEach { bookmarks.set(k.id, it, true) }
                            dao.update(k.copy(
                                starred = k.starred || o.starred,
                                lastOpened = maxOf(k.lastOpened, o.lastOpened),
                                lastPage = if (o.lastOpened > k.lastOpened) o.lastPage else k.lastPage,
                            ))
                            trashNow(o, of)
                            true
                        }
                    }
                }
            }
            if (went) n++
        }
        n
    }

    suspend fun rename(d0: Doc, newName: String, byUser: Boolean = true): Result<Doc> = locked {
        // As the library has it now, not as the screen last showed it.
        val d = dao.byId(d0.id) ?: d0
        val src = fileOf(d)
        val clean = newName.trim().removeSuffix(".pdf").removeSuffix(".PDF").trim()
        require(clean.isNotEmpty() && !clean.contains('/')) { "That name can't be used." }
        val dst = File(src.parentFile, "$clean.pdf")
        if (dst.absolutePath == src.absolutePath) return@locked d
        if (dst.exists()) throw IOException("There is already a file called ${dst.name} in this folder.")
        // Through the phone's own list of files, so My Files and the rest show the one file under its
        // new name, not a dead entry under the old one beside it.
        val done = holding(src) { media.move(src, dst) || src.renameTo(dst).also { if (it) media.forget(src.absolutePath) } }
        if (!done) throw IOException("The file could not be renamed.")
        val m = moved(d, src, dst)
        if (!byUser) return@locked m
        // The name the user gave is what the lists show from now on, in place of the title read from
        // inside the file; and the tidy does not offer to change it back.
        markNamed(d.id)
        keepNames(listOf(d.id))
        m.copy(title = null).also { dao.update(it) }
    }

    /** [name], or "name (2)" and on when a file of that name is in the file's folder already. */
    fun freeName(d: Doc, name: String): String {
        val dir = d.path?.let { File(it).parentFile } ?: return name
        val clean = name.trim().removeSuffix(".pdf").removeSuffix(".PDF").trim()
        val self = d.path
        fun taken(n: String) = File(dir, "$n.pdf").let { it.exists() && it.absolutePath != self }
        if (!taken(clean)) return clean
        var k = 2
        while (taken("$clean ($k)")) k++
        return "$clean ($k)"
    }

    suspend fun move(d0: Doc, dir: File): Result<Doc> = locked {
        val d = dao.byId(d0.id) ?: d0
        val src = fileOf(d)
        if (src.parentFile?.absolutePath == dir.absolutePath) return@locked d
        dir.mkdirs()
        val dst = uniqueTarget(dir, src.name)
        holding(src) { relocate(src, dst) }
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
    suspend fun trash(d0: Doc): Result<Doc> = locked {
        // As the library has it now (a star, a page, a place set since the screen read it).
        val d = dao.byId(d0.id) ?: d0
        val src = fileOf(d)
        holding(src) { trashNow(d, src) }
    }

    private suspend fun trashNow(d: Doc, src: File): Doc {
        trashDir.mkdirs()
        File(trashDir, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        val dst = uniqueTarget(trashDir, "${d.id}-${src.name}")
        // Copied, then the original deleted, rather than renamed: a rename into this hidden folder
        // leaves other apps (Samsung's My Files among them) listing the file where it was, and it
        // will not open from there. A delete tells every app the file has gone.
        copyVerified(src, dst)
        if (!src.delete()) {
            dst.delete()
            throw IOException("The file could not be moved to the trash.")
        }
        media.forget(src.absolutePath)
        val t = d.copy(key = dst.absolutePath, path = dst.absolutePath, uri = Uri.fromFile(dst).toString(), folder = dst.parent, trashedAt = System.currentTimeMillis(), trashOrigin = src.absolutePath)
        dao.update(t)
        scanMedia(src)
        return t
    }

    suspend fun restore(d0: Doc): Result<Doc> = locked {
        val d = dao.byId(d0.id) ?: d0
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
        f?.let { media.forget(it.absolutePath) }
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
        // Its words may have changed (read off a scan, blacked out), so search reads them again.
        // And what it is changed with it: the old fingerprint would make it look like a copy of the
        // file it was before (an untouched twin of it, say).
        val updated = now.copy(size = f.length(), modified = f.lastModified(), fingerprint = Fingerprint.of(f))
        dao.update(updated)
        runCatching { TextIndexer.schedule(context) }
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
        val m = d.copy(key = dst.absolutePath, path = dst.absolutePath, uri = Uri.fromFile(dst).toString(), name = dst.name, folder = dst.parent, modified = dst.lastModified(),
            // Moved or renamed, it is the same file inside: what was read from it stays read.
            indexedAt = if (d.indexedAt == d.modified) dst.lastModified() else d.indexedAt,
            infoAt = if (d.infoAt == d.modified) dst.lastModified() else d.infoAt)
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
        // Through the phone's own list of files where it knows the file; else moved here, and the
        // old entry taken off that list so no app shows it where it was.
        if (media.move(src, dst)) return
        if (!src.renameTo(dst)) {
            copyVerified(src, dst)
            if (!src.delete()) {
                dst.delete()
                throw IOException("The file could not be moved.")
            }
        }
        media.forget(src.absolutePath)
    }

    /** Whether two files hold exactly the same bytes, read through to the end. */
    internal fun sameBytes(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length()) return false
        if (a.absolutePath == b.absolutePath) return true
        return a.inputStream().use { sameBytes(it, b) }
    }

    /** Whether [input] holds exactly the bytes of [b], read through to the end. */
    internal fun sameBytes(input: java.io.InputStream, b: File): Boolean = b.inputStream().use { ib -> sameStreams(input, ib) }

    private fun sameStreams(a: java.io.InputStream, b: java.io.InputStream): Boolean {
        val x = ByteArray(1 shl 16)
        val y = ByteArray(1 shl 16)
        fun fill(i: java.io.InputStream, buf: ByteArray): Int {
            var got = 0
            while (got < buf.size) { val k = i.read(buf, got, buf.size - got); if (k < 0) break; got += k }
            return got
        }
        while (true) {
            val na = fill(a, x)
            val nb = fill(b, y)
            if (na != nb) return false
            if (na == 0) return true
            for (i in 0 until na) if (x[i] != y[i]) return false
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

    /** Like [io], one at a time with the look round the phone. */
    private suspend fun <T> locked(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) { fileOps.withLock { runCatching { block() } } }

    // ---------------------------------------------------------------- folders

    /**
     * Moves the folder [src], with everything in it, into [dstParent] under [newName] (a rename when
     * the parent stays). Every file the library knows inside it follows, keeping its tags and place.
     */
    suspend fun moveFolder(src: File, dstParent: File, newName: String = src.name): Result<File> = locked {
        val clean = newName.trim()
        require(clean.isNotEmpty() && !clean.contains('/') && !clean.startsWith(".")) { "That name can't be used." }
        require(src.isDirectory) { "That folder is no longer there." }
        val dst = File(dstParent, clean)
        if (dst.absolutePath == src.absolutePath) return@locked src
        require(!dst.exists()) { "There is already a folder called $clean there." }
        require(!(dst.absolutePath + "/").startsWith(src.absolutePath + "/")) { "A folder can't go inside itself." }
        dstParent.mkdirs()
        if (!src.renameTo(dst)) throw IOException("The folder could not be moved.")
        val from = src.absolutePath
        media.forgetUnder(from)
        db.withTransaction {
            fun under(p: String?) = if (p != null && (p == from || p.startsWith("$from/"))) dst.absolutePath + p.removePrefix(from) else null
            for (d in dao.all()) {
                // Files in it, and files in the trash that came from it (put back where it is now).
                val np = under(d.path)
                val origin = under(d.trashOrigin)
                if (np == null && origin == null) continue
                var u = d
                if (np != null) u = u.copy(key = np, path = np, uri = Uri.fromFile(File(np)).toString(), folder = File(np).parent)
                if (origin != null) u = u.copy(trashOrigin = origin)
                dao.update(u)
            }
        }
        dst.walkTopDown().filter { it.isFile }.take(500).toList().let { if (it.isNotEmpty()) scanMedia(*it.toTypedArray()) }
        dst
    }

    /** A new folder inside [parent]. */
    suspend fun makeFolder(parent: File, name: String): Result<File> = io {
        val clean = name.trim()
        require(clean.isNotEmpty() && !clean.contains('/') && !clean.startsWith(".")) { "That name can't be used." }
        val f = File(parent, clean)
        require(!f.exists()) { "There is already a folder called $clean there." }
        if (!f.mkdirs()) throw IOException("The folder could not be made.")
        f
    }

    /** Deletes a folder that holds nothing (or only empty folders); anything with files in it stays. */
    suspend fun deleteEmptyFolder(dir: File): Result<Unit> = io {
        val files = dir.walkTopDown().filter { it.isFile && it.name != ".nomedia" }.count()
        require(files == 0) { "It still has $files ${if (files == 1) "file" else "files"} in it. Move or trash them first." }
        dir.walkBottomUp().forEach { it.delete() }
    }

    // ---------------------------------------------------------------- tidying

    /**
     * A clearer name for a file whose name is a code ("s41380-023-02145-x", "document(3)",
     * "1-s2.0-S0165032721002123-main") when its real title is known: "Malhi 2023 - Lithium in the
     * treatment of bipolar disorder". Null when the name is fine or no title was found.
     */
    fun betterName(d: Doc): String? {
        val title = d.title?.takeIf { it.isNotBlank() } ?: return null
        val base = d.name.substringBeforeLast('.')
        if (!looksLikeCode(base)) return null
        val who = d.author?.removeSuffix(" et al.")?.trim()?.takeIf { it.isNotBlank() }
        val head = listOfNotNull(who, d.year.takeIf { it > 0 }?.toString()).joinToString(" ")
        var name = (if (head.isNotEmpty()) "$head - " else "") + title.replace(Regex("\\s*:\\s*"), " - ")
        name = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        if (name.length > 120) name = name.take(120).substringBeforeLast(' ').trimEnd(',', ';', ':', '-', ' ')
        return name.takeIf { it.isNotBlank() && it != base }
    }

    private fun looksLikeCode(base: String): Boolean = !Names.isClear(base)

    private val prefs = context.getSharedPreferences("library_titles", Context.MODE_PRIVATE)

    /** Scans whose words PDFx has read already (or found none on), so they are not offered again. */
    fun scansRead(): Set<Long> = prefs.getStringSet("scans_read", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun markScanRead(id: Long) {
        prefs.edit().putStringSet("scans_read", prefs.getStringSet("scans_read", emptySet()).orEmpty() + id.toString()).apply()
    }

    /** Files whose code names are to stay as they are: offered once and left unticked. */
    /** Files the user renamed in PDFx: their name, not the title inside, is what they are called. */
    fun namedByUser(): Set<Long> = prefs.getStringSet("named_by_user", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    private fun markNamed(id: Long) {
        prefs.edit().putStringSet("named_by_user", prefs.getStringSet("named_by_user", emptySet()).orEmpty() + id.toString()).apply()
    }

    fun namesKept(): Set<Long> = prefs.getStringSet("names_kept", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun keepNames(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        prefs.edit().putStringSet("names_kept", prefs.getStringSet("names_kept", emptySet()).orEmpty() + ids.map { it.toString() }).apply()
    }

    /** "Titles" found on several different files: a journal's or a website's name, never a file's own title. */
    fun sharedTitles(): Set<String> = prefs.getStringSet("shared", emptySet()).orEmpty().toSet()

    /**
     * Finds "titles" that several different files were given (three or more files that are not the
     * same file and not the same paper by DOI), remembers them so they are never taken as a title
     * again, and has those files' titles read afresh. Returns how many files are read again.
     */
    suspend fun fixSharedTitles(): Int = withContext(Dispatchers.IO) {
        val docs = dao.all().filter { !it.inTrash && !it.title.isNullOrBlank() }
        val shared = docs.groupBy { com.fadysaeed.pdfx.engine.DocInfo.key(it.title!!) }.filter { (_, g) ->
            g.mapNotNull { it.fingerprint ?: it.path }.distinct().size >= 3 && g.mapNotNull { it.doi }.distinct().size != 1
        }.keys
        if (shared.isEmpty()) return@withContext 0
        val all = sharedTitles() + shared
        prefs.edit().putStringSet("shared", all).apply()
        var n = 0
        for (d in docs) if (com.fadysaeed.pdfx.engine.DocInfo.key(d.title!!) in shared) {
            dao.update(d.copy(title = null, author = null, infoAt = 0)); n++
        }
        n
    }

    /** Folders that are a holding place rather than a home: Downloads, WhatsApp's, the received copies, the phone's top level. */
    fun isLoose(folder: String?): Boolean {
        val f = folder ?: return false
        return f == root.absolutePath || f.startsWith(File(root, "Download").absolutePath) || f.contains("/WhatsApp/") ||
            f == receivedDir.absolutePath || f.endsWith("/Telegram Documents") || f.contains("/Telegram/")
    }

    /** Where a file of a kind lives once sorted: Documents/Library/Papers and so on. */
    fun shelfDir(shelf: String): File = File(root, "Documents/Library/$shelf")


    // ---------------------------------------------------------------- tags

    suspend fun createTag(name: String, color: Int): Long = dao.insertTag(Tag(name = name.trim(), color = color))

    suspend fun setTag(docId: Long, tagId: Long, on: Boolean) {
        if (on) dao.tag(DocTag(docId, tagId)) else {
            dao.untag(docId, tagId)
            // A tag PDFx put on, taken off by the user, stays off that file.
            withContext(Dispatchers.IO) { runCatching { autoTags.takenOff(docId, tagId) } }
        }
    }

    /** Puts the tag [name] (made if new) on [docIds]. Returns how many files got it. */
    suspend fun applyTopic(name: String, docIds: List<Long>): Int {
        val existing = dao.allTags().firstOrNull { it.name.equals(name, true) }
        val id = existing?.id ?: createTag(name, (name.hashCode() and 0x7fffffff) % 8)
        db.withTransaction { docIds.forEach { dao.tag(DocTag(it, id)) } }
        return docIds.size
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
