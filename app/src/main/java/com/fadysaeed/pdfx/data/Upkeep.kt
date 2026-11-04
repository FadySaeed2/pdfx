package com.fadysaeed.pdfx.data

import android.content.Context
import android.os.PowerManager
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fadysaeed.pdfx.PdfxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ------------------------------------------------------------------ scans read into search

/**
 * Files whose picture-only pages had their words read into search (and only into search: the file
 * stays as it is), at which version of the file, and how far: a long scan is read over several
 * nights, so the next page to read is kept. [NEXT_DONE] means every page is read.
 */
class ScanWords(private val db: LibraryDb) {
    private val sql get() = db.openHelper.writableDatabase

    data class State(val modified: Long, val next: Int, val found: Int)

    fun all(): Map<Long, State> = sql.query("SELECT doc, modified, next, found FROM scan_words").use { c ->
        val out = HashMap<Long, State>()
        while (c.moveToNext()) out[c.getLong(0)] = State(c.getLong(1), c.getInt(2), c.getInt(3))
        out
    }

    fun set(doc: Long, s: State) = sql.execSQL(
        "INSERT OR REPLACE INTO scan_words(doc, modified, next, found) VALUES (?, ?, ?, ?)",
        arrayOf<Any>(doc, s.modified, s.next, s.found),
    )

    fun forget(doc: Long) = sql.execSQL("DELETE FROM scan_words WHERE doc = ?", arrayOf<Any>(doc))

    companion object { const val NEXT_DONE = -1 }
}

// ------------------------------------------------------------------ tags put on by PDFx

/**
 * Tags PDFx put on new files by itself, because the files most like them carry that tag. Each
 * waits for the user's look ("Tagged for you"); one the user takes off is never put back on that
 * file.
 */
class AutoTags(private val db: LibraryDb) {
    private val sql get() = db.openHelper.writableDatabase

    data class Row(val doc: Long, val tag: Long, val at: Long)

    /** Tags put on and not yet looked at. */
    fun pending(): List<Row> = sql.query("SELECT doc, tag, at FROM auto_tags WHERE reviewed = 0 ORDER BY at DESC").use { c ->
        val out = ArrayList<Row>()
        while (c.moveToNext()) out += Row(c.getLong(0), c.getLong(1), c.getLong(2))
        out
    }

    /** Every (doc, tag) PDFx put on, looked at or not. */
    fun all(): Set<Pair<Long, Long>> = sql.query("SELECT doc, tag FROM auto_tags").use { c ->
        val out = HashSet<Pair<Long, Long>>()
        while (c.moveToNext()) out += c.getLong(0) to c.getLong(1)
        out
    }

    fun refused(): Set<Pair<Long, Long>> = sql.query("SELECT doc, tag FROM auto_tag_no").use { c ->
        val out = HashSet<Pair<Long, Long>>()
        while (c.moveToNext()) out += c.getLong(0) to c.getLong(1)
        out
    }

    fun put(doc: Long, tag: Long) = sql.execSQL(
        "INSERT OR REPLACE INTO auto_tags(doc, tag, at, reviewed) VALUES (?, ?, ?, 0)",
        arrayOf<Any>(doc, tag, System.currentTimeMillis()),
    )

    /** The user took [tag] off [doc]: when PDFx had put it on, it never does again. */
    fun takenOff(doc: Long, tag: Long) {
        val was = sql.query(SimpleSQLiteQuery("SELECT 1 FROM auto_tags WHERE doc = ? AND tag = ?", arrayOf<Any>(doc, tag))).use { it.moveToFirst() }
        if (!was) return
        sql.execSQL("DELETE FROM auto_tags WHERE doc = ? AND tag = ?", arrayOf<Any>(doc, tag))
        sql.execSQL("INSERT OR REPLACE INTO auto_tag_no(doc, tag) VALUES (?, ?)", arrayOf<Any>(doc, tag))
    }

    fun reviewedAll() = sql.execSQL("UPDATE auto_tags SET reviewed = 1")

    /** Files already weighed for tags, at the version weighed. */
    fun seen(): Map<Long, Long> = sql.query("SELECT doc, modified FROM auto_tag_seen").use { c ->
        val out = HashMap<Long, Long>()
        while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1)
        out
    }

    fun markSeen(doc: Long, modified: Long) =
        sql.execSQL("INSERT OR REPLACE INTO auto_tag_seen(doc, modified) VALUES (?, ?)", arrayOf<Any>(doc, modified))
}

// ------------------------------------------------------------------ the nightly upkeep

/** What the last upkeep did, for one line in the library. */
data class UpkeepReport(
    val at: Long = 0,
    val added: Int = 0,
    val read: Int = 0,
    val scans: Int = 0,
    val tagged: Int = 0,
    val copies: Int = 0,
    val cleaned: Int = 0,
    val backedUp: Boolean = false,
    /** Scans still waiting to be read on a later night. */
    val scansLeft: Int = 0,
) {
    fun toJson(): String = JSONObject()
        .put("at", at).put("added", added).put("read", read).put("scans", scans).put("tagged", tagged)
        .put("copies", copies).put("cleaned", cleaned).put("backedUp", backedUp).put("scansLeft", scansLeft).toString()

    operator fun plus(o: UpkeepReport) = UpkeepReport(
        at = maxOf(at, o.at), added = added + o.added, read = read + o.read, scans = scans + o.scans, tagged = tagged + o.tagged,
        copies = copies + o.copies, cleaned = cleaned + o.cleaned, backedUp = backedUp || o.backedUp, scansLeft = o.scansLeft,
    )

    /** "Looked after Wed 3:12 am: 4 new files read, 2 scans read for search." */
    fun line(now: Long = System.currentTimeMillis()): String? {
        if (at == 0L) return null
        val day = 86_400_000L
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(at)).lowercase()
        val whenText = when {
            now - at < day && SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date(at)) == SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date(now)) -> "today at $time"
            now - at < 2 * day -> "yesterday at $time"
            else -> SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(Date(at))
        }
        fun n(k: Int, one: String, many: String) = if (k == 1) "1 $one" else "$k $many"
        val parts = ArrayList<String>()
        if (added > 0) parts += n(added, "new file found", "new files found")
        if (read > 0) parts += n(read, "file read for search", "files read for search")
        if (scans > 0) parts += n(scans, "scan read for search", "scans read for search")
        if (tagged > 0) parts += n(tagged, "file tagged", "files tagged")
        if (copies > 0) parts += n(copies, "extra copy to the trash", "extra copies to the trash")
        if (cleaned > 0) parts += n(cleaned, "leftover cleared", "leftovers cleared")
        if (scansLeft > 0) parts += n(scansLeft, "scan left for another night", "scans left for another night")
        val what = if (parts.isEmpty()) "all in order" else parts.joinToString(", ")
        return "Looked after $whenText: $what."
    }

    companion object {
        fun fromJson(s: String?): UpkeepReport = runCatching {
            val o = JSONObject(s ?: return UpkeepReport())
            UpkeepReport(
                o.optLong("at"), o.optInt("added"), o.optInt("read"), o.optInt("scans"), o.optInt("tagged"),
                o.optInt("copies"), o.optInt("cleaned"), o.optBoolean("backedUp"), o.optInt("scansLeft"),
            )
        }.getOrDefault(UpkeepReport())
    }
}

/**
 * Looks after the library without being asked, once a day while the phone charges: finds new
 * files, reads them for search, reads scanned pages into search (only while the screen is off),
 * tags new files that plainly belong with tagged ones, moves extra copies PDFx itself made to the
 * trash, empties the trash of what is 30 days old, clears what interrupted saves left behind, backs
 * up tags, stars and bookmarks, and once a month checks the index and tidies the database.
 */
class Upkeep(
    private val context: Context,
    private val library: Library,
    private val indexer: TextIndexer,
    private val names: MyNames,
    /** Held while files are read for search, so the indexer and the upkeep never read at once. */
    private val work: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(),
    /** Held while the phone is looked through for files. */
    private val scanLock: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(),
    prefsName: String = "upkeep",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** A one-time line for the library: what was brought back from a backup. */
    val news = MutableStateFlow<String?>(null)

    fun report(): UpkeepReport = UpkeepReport.fromJson(prefs.getString("report", null))

    /**
     * One round of upkeep. [stopIf] is asked between steps and between pages. Returns true when
     * work is left (a long scan half read) for another round.
     */
    suspend fun run(stopIf: () -> Boolean): Boolean = work.withLock {
        var r = UpkeepReport(at = System.currentTimeMillis())
        val now = System.currentTimeMillis()
        runCatching { library.text.upgrade() }
        if (library.hasAllFilesAccess()) {
            scanLock.withLock { runCatching { library.scan() }.onSuccess { r = r.copy(added = it.added) } }
        }
        runCatching { restoreIfNew() }
        runCatching { library.purgeTrash() }
        r = r.copy(cleaned = runCatching { clearLeftovers() }.getOrDefault(0))
        if (stopIf()) return@withLock finish(r, more = true)

        r = r.copy(read = runCatching { indexer.runAll(stopIf) }.getOrDefault(0))
        if (stopIf()) return@withLock finish(r, more = true)

        r = r.copy(copies = runCatching { extraCopies() }.getOrDefault(0))
        r = r.copy(tagged = runCatching { learnTags() }.getOrDefault(0))

        if (now - prefs.getLong("backup_at", 0) > 20 * HOUR) {
            runCatching { backUp() }.onSuccess { prefs.edit().putLong("backup_at", now).apply(); r = r.copy(backedUp = true) }
        }
        if (now - prefs.getLong("health_at", 0) > 28 * DAY) {
            runCatching { health() }.onSuccess { (n) -> prefs.edit().putLong("health_at", now).apply(); r = r.copy(cleaned = r.cleaned + n) }
        }

        // Reading pictures of pages is the heavy part: only while the phone is put down.
        val screenOn = runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(true)
        var left = 0
        if (!screenOn && !stopIf()) {
            val (read, waiting) = runCatching { readScans { stopIf() || isScreenOn() } }.getOrDefault(0 to 0)
            r = r.copy(scans = read)
            left = waiting
        } else {
            left = runCatching { scansWaiting().size }.getOrDefault(0)
        }
        r = r.copy(scansLeft = left)
        finish(r, more = left > 0 && !screenOn && stopIf())
    }

    private fun isScreenOn() = runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(false)

    private fun finish(r: UpkeepReport, more: Boolean): Boolean {
        // A round that carries on from one earlier the same night adds to its line.
        val last = report()
        val joined = if (prefs.getBoolean("more", false) && r.at - last.at < 12 * HOUR) last + r else r
        prefs.edit().putString("report", joined.toJson()).putBoolean("more", more).apply()
        return more
    }

    // ---------------------------------------------------------------- leftovers

    /** Half-written files from saves cut short (the app closed, the phone turned off), an hour old or more, and old work files. */
    internal suspend fun clearLeftovers(): Int = withContext(Dispatchers.IO) {
        var n = 0
        val hourAgo = System.currentTimeMillis() - HOUR
        val half = Regex("^\\..+\\.(pdfx-saving|pdfx-restoring|part)$")
        val dirs = library.dao.all().mapNotNull { it.folder }.toMutableSet()
        dirs += library.homeDir.absolutePath
        dirs += library.trashDir.absolutePath
        for (d in dirs) {
            File(d).listFiles()?.forEach { f ->
                if (f.isFile && half.matches(f.name) && f.lastModified() < hourAgo && f.delete()) n++
            }
        }
        // The app's own work files (page pictures, tries at shrinking, copies for arranging).
        val dayAgo = System.currentTimeMillis() - DAY
        context.cacheDir.listFiles()?.filter { it.isDirectory && it.name != "thumbs" }?.forEach { dir ->
            dir.walkBottomUp().forEach { f -> if (f.isFile && f.lastModified() < dayAgo && f.delete()) n++ }
        }
        n
    }

    // ---------------------------------------------------------------- extra copies

    /**
     * Copies that are the very same file as another in the same folder, named like a copy of it
     * ("Report (2).pdf", "Report - Copy.pdf"): the plain-named one stays and takes their tags, star
     * and bookmarks; the copies go to the trash for 30 days. Every byte is compared first.
     */
    internal suspend fun extraCopies(): Int {
        val live = library.dao.all().filter { !it.inTrash && it.isFile && it.fingerprint != null && it.folder != null }
        var n = 0
        for (group in live.groupBy { it.fingerprint to it.folder }.values) {
            if (group.size < 2) continue
            val keep = group.minWith(compareBy<Doc> { copyMark(it.name) != null }.thenBy { it.name.length }.thenBy { it.addedAt })
            val base = plain(keep.name)
            val extra = group.filter { it.id != keep.id && copyMark(it.name) != null && plain(it.name).equals(base, ignoreCase = true) && sameBytes(keep, it) }
            if (extra.isEmpty()) continue
            library.mergeDuplicates(keep, extra).onSuccess { n += it }
        }
        return n
    }

    private fun stem(name: String) = name.replace(PDF_END, "")

    private fun copyMark(name: String): String? = COPY_MARK.find(stem(name))?.value

    private fun plain(name: String): String = COPY_MARK.replace(stem(name), "").trim()

    private suspend fun sameBytes(a: Doc, b: Doc): Boolean = withContext(Dispatchers.IO) {
        val fa = File(a.path ?: return@withContext false)
        val fb = File(b.path ?: return@withContext false)
        if (!fa.isFile || !fb.isFile || fa.length() != fb.length()) return@withContext false
        fun fill(i: java.io.InputStream, buf: ByteArray): Int {
            var got = 0
            while (got < buf.size) { val k = i.read(buf, got, buf.size - got); if (k < 0) break; got += k }
            return got
        }
        fa.inputStream().use { ia ->
            fb.inputStream().use { ib ->
                val x = ByteArray(1 shl 16)
                val y = ByteArray(1 shl 16)
                var same = true
                while (same) {
                    val na = fill(ia, x)
                    val nb = fill(ib, y)
                    if (na != nb) { same = false; break }
                    if (na == 0) break
                    for (i in 0 until na) if (x[i] != y[i]) { same = false; break }
                }
                same
            }
        }
    }

    // ---------------------------------------------------------------- tags learnt from the user's own

    /**
     * New files take a tag when most of the files most like them carry it. "Most like" is measured
     * on the words of the title, the name and the first two pages, each word weighed by how rare it
     * is across the library. A file gets a tag only when at least three of its five nearest files
     * carry it and are close enough; a tag the user took off a file is never put back on it.
     */
    internal suspend fun learnTags(): Int = withContext(Dispatchers.IO) {
        val since = prefs.getLong("tags_since", 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { prefs.edit().putLong("tags_since", it).apply() }
        val docs = library.dao.all().filter { !it.inTrash && it.isFile && !it.locked }
        val tagIds = library.dao.allTags().map { it.id }.toSet()
        if (tagIds.isEmpty()) return@withContext 0
        val auto = library.autoTags.all()
        val links = HashMap<Long, MutableSet<Long>>()
        // Tags the user put on (PDFx's own count once the user has looked at them and kept them).
        val pending = library.autoTags.pending().map { it.doc to it.tag }.toSet()
        for (d in docs) {
            val ids = library.dao.tagIdsOf(d.id).filter { it in tagIds && (d.id to it) !in pending }
            if (ids.isNotEmpty()) links[d.id] = ids.toMutableSet()
        }
        val seen = library.autoTags.seen()
        val candidates = docs.filter { d ->
            d.addedAt >= since && d.indexedAt == d.modified && seen[d.id] != d.modified &&
                library.dao.tagIdsOf(d.id).isEmpty()
        }
        if (candidates.isEmpty()) return@withContext 0
        val tagged = tagIds.filter { t -> links.values.count { t in it } >= 3 }.toSet()
        if (tagged.isEmpty()) { candidates.forEach { library.autoTags.markSeen(it.id, it.modified) }; return@withContext 0 }

        val texts = library.text.firstPages(docs.map { it.id })
        val bags = docs.associate { d -> d.id to bag(listOfNotNull(d.title, d.name.removeSuffix(".pdf"), d.author, texts[d.id]).joinToString(" ")) }
        val df = HashMap<String, Int>()
        for (b in bags.values) for (w in b.keys) df.merge(w, 1, Int::plus)
        val total = bags.size.toDouble()
        val vectors = bags.mapValues { (_, b) -> weigh(b, df, total) }
        val refused = library.autoTags.refused()
        var n = 0
        for (c in candidates) {
            val v = vectors[c.id].orEmpty()
            library.autoTags.markSeen(c.id, c.modified)
            if (v.size < 5) continue
            val near = vectors.asSequence()
                .filter { it.key != c.id && links.containsKey(it.key) }
                .map { it.key to cosine(v, it.value) }
                .sortedByDescending { it.second }
                .take(5).toList()
            // The five nearest of all files, tagged or not: a file like many untagged ones is left alone.
            val nearAll = vectors.asSequence().filter { it.key != c.id }.map { cosine(v, it.value) }.sortedDescending().take(5).toList()
            val fifth = nearAll.getOrNull(4) ?: continue
            val votes = HashMap<Long, Int>()
            for ((id, sim) in near) {
                if (sim < MIN_LIKE || sim < fifth) continue
                links[id]?.forEach { t -> if (t in tagged) votes.merge(t, 1, Int::plus) }
            }
            val put = votes.filter { it.value >= 3 && (c.id to it.key) !in refused && (c.id to it.key) !in auto }.keys.take(3)
            if (put.isEmpty()) continue
            library.db.withTransaction { put.forEach { library.dao.tag(DocTag(c.id, it)) } }
            put.forEach { library.autoTags.put(c.id, it) }
            n++
        }
        n
    }

    private fun bag(text: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (w in text.lowercase().split(WORD_BREAK)) {
            if (w.length < 3 || w.length > 24 || w in STOP || w.all { it.isDigit() }) continue
            out.merge(w, 1, Int::plus)
        }
        return out
    }

    private fun weigh(b: Map<String, Int>, df: Map<String, Int>, total: Double): Map<String, Double> {
        val v = HashMap<String, Double>()
        for ((w, k) in b) {
            val d = df[w] ?: 1
            // A word in nearly every file says nothing about any.
            if (d > total * 0.5) continue
            v[w] = (1 + kotlin.math.ln(k.toDouble())) * kotlin.math.ln(1 + total / d)
        }
        val norm = kotlin.math.sqrt(v.values.sumOf { it * it })
        if (norm > 0) for (k in v.keys.toList()) v[k] = v.getValue(k) / norm
        return v
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        val (s, l) = if (a.size <= b.size) a to b else b to a
        var sum = 0.0
        for ((w, x) in s) { val y = l[w] ?: continue; sum += x * y }
        return sum
    }

    // ---------------------------------------------------------------- scans read into search

    private suspend fun scansWaiting(): List<Doc> = withContext(Dispatchers.IO) {
        val state = library.scanWords.all()
        library.dao.all().filter { d ->
            !d.inTrash && d.isFile && !d.locked && d.pages > 0 && d.indexedAt == d.modified &&
                state[d.id].let { s -> s == null || s.modified != d.modified || s.next != ScanWords.NEXT_DONE }
        }.filter { d ->
            val s = state[d.id]
            if (s != null && s.modified == d.modified) return@filter true
            // Every page has words already: nothing to read, now or later.
            if (library.text.pagesIndexed(d.id) >= d.pages) {
                library.scanWords.set(d.id, ScanWords.State(d.modified, ScanWords.NEXT_DONE, 0)); false
            } else true
        }.sortedWith(compareByDescending<Doc> { it.kind == com.fadysaeed.pdfx.engine.DocKind.SCAN.name }.thenByDescending { it.lastOpened }.thenBy { it.pages })
    }

    /** Reads scanned pages into search until [stop]. Returns (files finished with words found, files still waiting). */
    internal suspend fun readScans(stop: () -> Boolean): Pair<Int, Int> = withContext(Dispatchers.Default) {
        val todo = scansWaiting()
        var finished = 0
        var left = todo.size
        for (d in todo) {
            if (stop()) break
            val path = d.path ?: continue
            val was = library.scanWords.all()[d.id]?.takeIf { it.modified == d.modified }
            val from = was?.next?.coerceAtLeast(0) ?: 0
            var found = was?.found ?: 0
            val got = ArrayList<PageText>()
            val next = runCatching {
                com.fadysaeed.pdfx.engine.Reading.readPages(path, from, stop) { page, words -> got += PageText(page, words.take(40_000)); found++ }
            }.getOrElse { ScanWords.NEXT_DONE }
            // The file may have changed while it was read: then its words wait for the next round.
            val now = library.dao.byId(d.id) ?: continue
            if (now.modified != d.modified) continue
            if (got.isNotEmpty()) withContext(Dispatchers.IO) { library.text.putPages(d.id, got) }
            withContext(Dispatchers.IO) { library.scanWords.set(d.id, ScanWords.State(d.modified, next, found)) }
            if (next == ScanWords.NEXT_DONE) {
                left--
                if (found > 0) {
                    finished++
                    // Search finds its words now, so the tidy no longer asks to read it.
                    library.markScanRead(d.id)
                }
            }
        }
        finished to left
    }

    // ---------------------------------------------------------------- backup

    /** The backup's folder: beside the files, so it outlives the app being removed and put back. */
    val backupDir: File get() = File(library.homeDir, "Backup")

    /**
     * Writes tags, stars, reading states, places and bookmarks, the user's names on marks and the
     * pinned folders to Documents/PDFx/Backup, keeping the last seven days. Highlights and notes are
     * inside the files themselves and need no backup. Signing certificates stay on the phone only:
     * a private key does not belong in a shared folder.
     */
    suspend fun backUp(): File = withContext(Dispatchers.IO) {
        val tags = library.dao.allTags()
        val tagName = tags.associate { it.id to it.name }
        val out = JSONObject().put("version", 1).put("made", System.currentTimeMillis())
        out.put("tags", JSONArray().apply { tags.forEach { put(JSONObject().put("name", it.name).put("color", it.color)) } })
        val docs = JSONArray()
        for (d in library.dao.all()) {
            if (d.inTrash || d.path == null) continue
            val ts = library.dao.tagIdsOf(d.id).mapNotNull { tagName[it] }
            val marks = library.bookmarks.pages(d.id)
            if (ts.isEmpty() && !d.starred && d.reading == 0 && marks.isEmpty() && d.lastOpened == 0L) continue
            docs.put(JSONObject().apply {
                put("fp", d.fingerprint ?: JSONObject.NULL); put("path", d.path); put("name", d.name); put("size", d.size)
                put("tags", JSONArray(ts)); put("star", d.starred); put("reading", d.reading)
                put("page", d.lastPage); put("opened", d.lastOpened); put("bookmarks", JSONArray(marks))
            })
        }
        out.put("docs", docs)
        out.put("names", JSONArray(names.names()))
        // Reading settings (paper colour, reflow text, own stamps, two pages when turned) and the
        // library's (sorting, grid, pinned folders), as they are.
        out.put("prefs", JSONObject().apply { for (n in PREFS) put(n, prefsToJson(context.getSharedPreferences(n, Context.MODE_PRIVATE))) })
        out.put("pins", JSONArray(context.getSharedPreferences("library", Context.MODE_PRIVATE).getStringSet("pinned_folders", emptySet()).orEmpty().toList()))
        val dir = backupDir.apply { mkdirs() }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date())
        val latest = File(dir, "PDFx library.json")
        val tmp = File(dir, ".PDFx library.json.part")
        tmp.writeText(out.toString(1))
        if (!tmp.renameTo(latest)) { tmp.copyTo(latest, overwrite = true); tmp.delete() }
        latest.copyTo(File(dir, "PDFx library $day.json"), overwrite = true)
        // Saved signatures and initials, drawn or photographed.
        runCatching {
            val signs = File(context.filesDir, "signatures.json")
            val to = File(dir, "Signatures").apply { mkdirs() }
            if (signs.isFile) {
                signs.copyTo(File(to, "signatures.json"), overwrite = true)
                File(context.filesDir, "signatures").listFiles()?.forEach { f -> if (f.isFile) f.copyTo(File(to, f.name), overwrite = true) }
            }
        }
        dir.listFiles { f -> f.name.matches(Regex("PDFx library \\d{4}-\\d{2}-\\d{2}\\.json")) }
            ?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }
        latest
    }

    /**
     * On a fresh install (nothing tagged, starred or bookmarked yet, and this install never checked
     * before), brings back what the latest backup holds. Checked once per install.
     */
    suspend fun restoreIfNew(): Int = withContext(Dispatchers.IO) {
        if (prefs.getBoolean("restore_checked", false)) return@withContext 0
        val file = File(backupDir, "PDFx library.json")
        val docs = library.dao.all()
        // Without access to the phone's files the backup cannot be read, and before the first look round nothing would match: try later.
        if (!library.hasAllFilesAccess() || docs.none { it.isFile }) return@withContext 0
        val blank = library.dao.allTags().isEmpty() && docs.none { it.starred || it.reading != 0 }
        prefs.edit().putBoolean("restore_checked", true).apply()
        if (!blank || !file.isFile) return@withContext 0
        val n = restore(file)
        if (n > 0) news.value = if (n == 1) "Brought back the tags, stars and bookmarks of 1 file from the backup." else "Brought back the tags, stars and bookmarks of $n files from the backup."
        n
    }

    /** Puts a backup's tags, stars, reading states and bookmarks back on the files it finds. Returns how many files it found. */
    suspend fun restore(file: File): Int = withContext(Dispatchers.IO) {
        val o = JSONObject(file.readText())
        val existing = library.dao.allTags().associateBy { it.name.lowercase() }.toMutableMap()
        val tagArr = o.optJSONArray("tags") ?: JSONArray()
        for (i in 0 until tagArr.length()) {
            val t = tagArr.getJSONObject(i)
            val name = t.optString("name").trim()
            if (name.isEmpty() || existing.containsKey(name.lowercase())) continue
            val id = runCatching { library.createTag(name, t.optInt("color")) }.getOrNull() ?: continue
            existing[name.lowercase()] = Tag(id, name, t.optInt("color"))
        }
        val all = library.dao.all().filter { !it.inTrash }
        val byFp = all.filter { it.fingerprint != null }.groupBy { it.fingerprint }
        val byPath = all.associateBy { it.path }
        val byNameSize = all.groupBy { it.name.lowercase() to it.size }
        val arr = o.optJSONArray("docs") ?: JSONArray()
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val fp = if (e.isNull("fp")) null else e.optString("fp")
            val d = fp?.let { byFp[it]?.firstOrNull() } ?: byPath[e.optString("path")] ?: byNameSize[e.optString("name").lowercase() to e.optLong("size")]?.firstOrNull() ?: continue
            val ts = e.optJSONArray("tags") ?: JSONArray()
            library.db.withTransaction {
                for (k in 0 until ts.length()) existing[ts.getString(k).lowercase()]?.let { library.dao.tag(DocTag(d.id, it.id)) }
            }
            val bm = e.optJSONArray("bookmarks") ?: JSONArray()
            for (k in 0 until bm.length()) library.bookmarks.set(d.id, bm.getInt(k), true)
            val now = library.dao.byId(d.id) ?: continue
            library.dao.update(now.copy(
                starred = now.starred || e.optBoolean("star"),
                reading = if (now.reading != 0) now.reading else e.optInt("reading"),
                lastPage = if (now.lastOpened >= e.optLong("opened")) now.lastPage else e.optInt("page"),
                lastOpened = maxOf(now.lastOpened, e.optLong("opened")),
            ))
            n++
        }
        val saved = o.optJSONArray("names")
        if (saved != null && saved.length() > 0 && names.names().isEmpty()) names.set((0 until saved.length()).map { saved.getString(it) })
        o.optJSONObject("prefs")?.let { all ->
            for (n in PREFS) all.optJSONObject(n)?.let { jsonToPrefs(it, context.getSharedPreferences(n, Context.MODE_PRIVATE)) }
        }
        // Signatures, when this install has none of its own yet.
        runCatching {
            val from = File(file.parentFile, "Signatures")
            val here = File(context.filesDir, "signatures.json")
            if (!here.exists() && File(from, "signatures.json").isFile) {
                val pics = File(context.filesDir, "signatures").apply { mkdirs() }
                from.listFiles()?.forEach { f -> if (f.isFile && f.name != "signatures.json") f.copyTo(File(pics, f.name), overwrite = true) }
                File(from, "signatures.json").copyTo(here, overwrite = true)
            }
        }
        val pins = o.optJSONArray("pins")
        if (pins != null && pins.length() > 0) {
            val p = context.getSharedPreferences("library", Context.MODE_PRIVATE)
            p.edit().putStringSet("pinned_folders", p.getStringSet("pinned_folders", emptySet()).orEmpty() + (0 until pins.length()).map { pins.getString(it) }).apply()
        }
        n
    }

    // ---------------------------------------------------------------- health

    /**
     * Once a month: checks the search index and builds it again if it is damaged, drops what is
     * kept for files PDFx no longer knows, merges the index's pieces, compacts the database and
     * keeps the first-page pictures within bounds. Returns (things cleared, index rebuilt).
     */
    suspend fun health(): Pair<Int, Boolean> = withContext(Dispatchers.IO) {
        val rebuilt = !library.text.healthy()
        if (rebuilt) {
            library.text.rebuild()
            library.dao.all().forEach { library.dao.update(it.copy(indexedAt = 0)) }
        }
        val ids = library.dao.all().map { it.id }.toSet()
        var n = library.text.dropOrphans()
        n += library.versions.dropOrphans(ids)
        n += trimThumbs()
        runCatching { library.text.optimize() }
        runCatching { library.db.openHelper.writableDatabase.execSQL("VACUUM") }
        n to rebuilt
    }

    private fun trimThumbs(): Int {
        val dir = File(context.cacheDir, "thumbs")
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return 0
        var size = files.sumOf { it.length() }
        var n = 0
        for (f in files) {
            if (size <= 100L * 1024 * 1024) break
            size -= f.length()
            if (f.delete()) n++
        }
        return n
    }

    /** A preferences file as JSON: each key with its type, so it comes back as it was. */
    private fun prefsToJson(p: android.content.SharedPreferences): JSONObject {
        val o = JSONObject()
        for ((k, v) in p.all) {
            val e = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            o.put(k, e)
        }
        return o
    }

    /** Puts back the keys this install has not set itself. */
    private fun jsonToPrefs(o: JSONObject, p: android.content.SharedPreferences) {
        val e = p.edit()
        for (k in o.keys()) {
            if (p.contains(k)) continue
            val x = o.getJSONObject(k)
            when (x.optString("t")) {
                "b" -> e.putBoolean(k, x.getBoolean("v"))
                "i" -> e.putInt(k, x.getInt("v"))
                "l" -> e.putLong(k, x.getLong("v"))
                "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                "s" -> e.putString(k, x.getString("v"))
                "set" -> e.putStringSet(k, x.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        e.apply()
    }

    companion object {
        /** The preference files the backup carries: reading settings and the library's. */
        private val PREFS = listOf("reader", "library")
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        /** How alike two files must be (cosine of their weighed words) for one to vote on the other's tags. */
        const val MIN_LIKE = 0.22
        private val WORD_BREAK = Regex("[^\\p{L}]+")
        private val PDF_END = Regex("\\.pdf$", RegexOption.IGNORE_CASE)
        private val COPY_MARK = Regex("(\\s*\\(\\d{1,2}\\))+$|\\s*-\\s*copy(\\s*\\(\\d+\\))?$|\\s+copy(\\s+\\d+)?$|\\s*\\(copy\\)$", RegexOption.IGNORE_CASE)
        private val STOP = setOf(
            "the", "and", "for", "with", "from", "into", "that", "this", "these", "those", "are", "was", "were", "been", "being", "have", "has",
            "had", "not", "but", "you", "your", "our", "their", "its", "his", "her", "they", "them", "who", "which", "what", "when", "where",
            "how", "why", "all", "any", "can", "may", "will", "would", "should", "could", "also", "such", "than", "then", "there", "here",
            "about", "after", "before", "between", "over", "under", "more", "most", "other", "some", "each", "only", "one", "two", "page",
            "pages", "pdf", "www", "http", "https", "com", "org", "doi", "file", "copy", "scan", "scanned", "document", "final", "draft",
        )

        /** Once a day, while the phone charges and the battery is not low. */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpkeepWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("upkeep", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Another round soon, for work left over (a long scan), on the same terms. */
        fun scheduleMore(context: Context) {
            val req = OneTimeWorkRequestBuilder<UpkeepWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("upkeep-more", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

class UpkeepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PdfxApp
        // Android gives a background job about ten minutes; stop a little before and carry on later.
        val end = System.currentTimeMillis() + 8 * 60_000L
        val more = runCatching { app.upkeep.run(stopIf = { isStopped || System.currentTimeMillis() > end }) }.getOrDefault(false)
        if (more && !isStopped) Upkeep.scheduleMore(applicationContext)
        return Result.success()
    }
}
