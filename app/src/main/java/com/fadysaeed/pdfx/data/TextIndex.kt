package com.fadysaeed.pdfx.data

import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase

/** One page's words, ready for the index. */
data class PageText(val page: Int, val body: String)

/**
 * The full-text index of every page. It is a plain SQLite FTS4 table that lives in the library
 * database but outside Room's entities, created on open; the porter tokenizer matches "monitor"
 * with "monitoring" and is built into every SQLite on Android.
 *
 * Each page's row id carries its file and page (file × [SPAN] + page), so "which files have this
 * word, on how many pages" is answered from the index alone, without reading the pages' words
 * back off the disk, and a file's pages are found and dropped by a range of ids. Libraries
 * indexed before this kept file and page as columns in "page_text"; [upgrade] moves them over once.
 */
class TextIndex(private val db: LibraryDb) {
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase
    private val lock = Any()

    /** Goes up whenever any file's words change, so what was worked out from them can be kept until then. */
    @Volatile var version = 0L
        private set

    private fun oldThere(s: SupportSQLiteDatabase = sql): Boolean =
        s.query("SELECT 1 FROM sqlite_master WHERE name = 'page_text'").use { it.moveToFirst() }

    /**
     * Moves an index made by an earlier PDFx into the new table, a few hundred pages at a time, and
     * drops the old one. [progress] gets the share done. Safe to stop and start again.
     */
    fun upgrade(progress: (Float) -> Unit = {}) = synchronized(lock) {
        val s = sql
        if (!oldThere(s)) return@synchronized
        val total = s.query("SELECT MAX(docid) FROM page_text").use { if (it.moveToFirst()) it.getLong(0) else 0L }.coerceAtLeast(1L)
        var last = s.query("SELECT last FROM text_upgrade").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        while (true) {
            val rows = ArrayList<Array<Any>>()
            var top = last
            s.query(SimpleSQLiteQuery("SELECT docid, ref, page, body FROM page_text WHERE docid > ? ORDER BY docid LIMIT 300", arrayOf<Any>(last))).use { c ->
                while (c.moveToNext()) {
                    top = c.getLong(0)
                    rows += arrayOf(c.getLong(1) * SPAN + c.getLong(2), c.getString(3) ?: "")
                }
            }
            if (rows.isEmpty()) break
            s.beginTransaction()
            try {
                for (r in rows) s.execSQL("INSERT OR REPLACE INTO page_words(docid, body) VALUES (?, ?)", r)
                s.execSQL("DELETE FROM text_upgrade")
                s.execSQL("INSERT INTO text_upgrade(last) VALUES (?)", arrayOf<Any>(top))
                s.setTransactionSuccessful()
            } finally {
                s.endTransaction()
            }
            last = top
            progress((last.toFloat() / total).coerceIn(0f, 1f))
        }
        s.execSQL("DROP TABLE page_text")
        s.execSQL("DELETE FROM text_upgrade")
        version++
    }

    fun replace(docId: Long, pages: List<PageText>) = synchronized(lock) {
        val s = sql
        s.beginTransaction()
        try {
            s.execSQL("DELETE FROM page_words WHERE docid BETWEEN ? AND ?", arrayOf<Any>(docId * SPAN, docId * SPAN + SPAN - 1))
            if (oldThere(s)) s.execSQL("DELETE FROM page_text WHERE ref = ?", arrayOf<Any>(docId))
            val insert = s.compileStatement("INSERT OR REPLACE INTO page_words(docid, body) VALUES (?, ?)")
            for (p in pages) {
                if (p.page < 0 || p.page >= SPAN) continue
                insert.clearBindings()
                insert.bindLong(1, docId * SPAN + p.page)
                insert.bindString(2, p.body)
                insert.executeInsert()
            }
            s.setTransactionSuccessful()
        } finally {
            s.endTransaction()
        }
        version++
    }

    fun clear(docId: Long) = replace(docId, emptyList())

    /** Puts [pages] in, each in place of what that page had, leaving the file's other pages as they are. */
    fun putPages(docId: Long, pages: List<PageText>) = synchronized(lock) {
        val s = sql
        s.beginTransaction()
        try {
            val insert = s.compileStatement("INSERT OR REPLACE INTO page_words(docid, body) VALUES (?, ?)")
            for (p in pages) {
                if (p.page < 0 || p.page >= SPAN) continue
                insert.clearBindings()
                insert.bindLong(1, docId * SPAN + p.page)
                insert.bindString(2, p.body)
                insert.executeInsert()
            }
            s.setTransactionSuccessful()
        } finally {
            s.endTransaction()
        }
        version++
    }

    /** The words of each file's first two pages, up to [chars] of them, for telling files apart. */
    fun firstPages(docIds: List<Long>, chars: Int = 4000): Map<Long, String> {
        val out = HashMap<Long, StringBuilder>()
        for (chunk in docIds.chunked(300)) {
            val ids = chunk.flatMap { listOf(it * SPAN, it * SPAN + 1) }
            val marks = ids.joinToString(",") { "?" }
            sql.query(SimpleSQLiteQuery("SELECT docid, body FROM page_words WHERE docid IN ($marks)", ids.map { it as Any }.toTypedArray())).use { c ->
                while (c.moveToNext()) {
                    val b = out.getOrPut(c.getLong(0) / SPAN) { StringBuilder() }
                    if (b.length < chars) b.append(' ').append((c.getString(1) ?: "").take(chars - b.length))
                }
            }
        }
        return out.mapValues { it.value.toString() }
    }

    /** Whether SQLite finds the index whole. */
    fun healthy(): Boolean = runCatching { sql.execSQL("INSERT INTO page_words(page_words) VALUES('integrity-check')"); true }.getOrDefault(false)

    /** Throws the index away and starts it empty; every file is then read again. */
    fun rebuild() = synchronized(lock) {
        val s = sql
        s.execSQL("DROP TABLE IF EXISTS page_vocab")
        s.execSQL("DROP TABLE IF EXISTS page_words")
        s.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS page_words USING fts4(body, tokenize=porter)")
        s.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS page_vocab USING fts4aux(page_words)")
        s.execSQL("DELETE FROM scan_words")
        version++
    }

    /** Merges the index's pieces into one, which keeps searching quick. */
    fun optimize() = synchronized(lock) { sql.execSQL("INSERT INTO page_words(page_words) VALUES('optimize')") }

    /** Drops words, marks, bookmarks and the rest kept for files no longer in the library. Returns how many rows went. */
    fun dropOrphans(): Int = synchronized(lock) {
        val s = sql
        var n = 0
        fun gone(q: String) { n += s.compileStatement(q).executeUpdateDelete() }
        gone("DELETE FROM page_words WHERE (docid / $SPAN) NOT IN (SELECT id FROM docs)")
        gone("DELETE FROM marks WHERE doc NOT IN (SELECT id FROM docs)")
        gone("DELETE FROM marks_read WHERE doc NOT IN (SELECT id FROM docs)")
        gone("DELETE FROM bookmarks WHERE doc NOT IN (SELECT id FROM docs)")
        gone("DELETE FROM scan_words WHERE doc NOT IN (SELECT id FROM docs)")
        gone("DELETE FROM auto_tags WHERE doc NOT IN (SELECT id FROM docs) OR tag NOT IN (SELECT id FROM tags)")
        gone("DELETE FROM auto_tag_no WHERE doc NOT IN (SELECT id FROM docs) OR tag NOT IN (SELECT id FROM tags)")
        gone("DELETE FROM auto_tag_seen WHERE doc NOT IN (SELECT id FROM docs)")
        if (n > 0) version++
        n
    }

    fun search(match: String, limit: Int = 400): List<TextHit> {
        val out = ArrayList<TextHit>()
        sql.query(SimpleSQLiteQuery(
            "SELECT docid, snippet(page_words, ?, ?, ?, 0, 14) FROM page_words WHERE page_words MATCH ? LIMIT $limit",
            arrayOf<Any>(MARK_START, MARK_END, "…", match),
        )).use { c -> while (c.moveToNext()) { val id = c.getLong(0); out += TextHit(id / SPAN, (id % SPAN).toInt(), c.getString(1) ?: "") } }
        // Pages not yet moved over from an earlier PDFx's index.
        if (out.size < limit && oldThere()) sql.query(SimpleSQLiteQuery(
            "SELECT ref, page, snippet(page_text, ?, ?, ?, 0, 14) FROM page_text WHERE page_text MATCH ? LIMIT ${limit - out.size}",
            arrayOf<Any>(MARK_START, MARK_END, "…", match),
        )).use { c -> while (c.moveToNext()) out += TextHit(c.getLong(0), c.getInt(1), c.getString(2) ?: "") }
        return out
    }

    /** For each file, on how many pages [phrase] comes up ([raw]: [phrase] is already an FTS query, words OR'd). */
    fun pagesMatching(phrase: String, raw: Boolean = false): Map<Long, Int> {
        val match = if (raw) phrase else {
            val words = phrase.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
            if (words.isEmpty()) return emptyMap()
            "\"" + words.joinToString(" ") + "\""
        }
        if (match.isBlank()) return emptyMap()
        val out = HashMap<Long, Int>()
        // The row ids alone: the pages' words are never read back.
        sql.query(SimpleSQLiteQuery("SELECT docid FROM page_words WHERE page_words MATCH ?", arrayOf<Any>(match))).use { c ->
            while (c.moveToNext()) out.merge(c.getLong(0) / SPAN, 1, Int::plus)
        }
        if (oldThere()) sql.query(SimpleSQLiteQuery("SELECT ref, COUNT(*) FROM page_text WHERE page_text MATCH ? GROUP BY ref", arrayOf<Any>(match))).use { c ->
            while (c.moveToNext()) out.merge(c.getLong(0), c.getInt(1), Int::plus)
        }
        return out
    }

    /** Whether any page has a word starting with [word] (as search would find it, stems and all). */
    fun hasAny(word: String): Boolean {
        val w = word.lowercase().filter { it.isLetterOrDigit() }
        if (w.isEmpty()) return false
        return runCatching {
            sql.query(SimpleSQLiteQuery("SELECT docid FROM page_words WHERE page_words MATCH ? LIMIT 1", arrayOf<Any>("$w*"))).use { it.moveToFirst() }
        }.getOrDefault(false)
    }

    fun pagesIndexed(docId: Long): Int =
        sql.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM page_words WHERE docid BETWEEN ? AND ?", arrayOf<Any>(docId * SPAN, docId * SPAN + SPAN - 1))).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * Words in the index that are spelt nearly like [word]: one letter off for short words, two for
     * longer ones, letters swapped counting as one. Closest first, then the most used.
     */
    fun nearWords(word: String, limit: Int = 3): List<String> {
        val w = word.lowercase()
        if (w.length < 4 || w.any { !it.isLetter() }) return emptyList()
        val most = if (w.length >= 7) 2 else 1
        val found = ArrayList<Triple<String, Int, Int>>()
        // Words starting with the same letter: typing gets the first letter right nearly always.
        val from = w.substring(0, 1)
        val to = (w[0] + 1).toString()
        runCatching {
            sql.query(SimpleSQLiteQuery("SELECT term, documents FROM page_vocab WHERE col = '*' AND term >= ? AND term < ?", arrayOf<Any>(from, to))).use { c ->
                while (c.moveToNext()) {
                    val t = c.getString(0) ?: continue
                    if (kotlin.math.abs(t.length - w.length) > most || t == w) continue
                    val d = TextMath.distance(w, t, most)
                    if (d <= most) found += Triple(t, d, c.getInt(1))
                }
            }
        }
        return found.sortedWith(compareBy<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third }).take(limit).map { it.first }
    }

    object Setup : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS page_words USING fts4(body, tokenize=porter)")
            // The index's own list of words, for spelling near misses.
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS page_vocab USING fts4aux(page_words)")
            db.execSQL("CREATE TABLE IF NOT EXISTS marks(doc INTEGER NOT NULL, page INTEGER NOT NULL, kind TEXT NOT NULL, color INTEGER NOT NULL, quote TEXT NOT NULL, note TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS marks_doc ON marks(doc)")
            db.execSQL("CREATE TABLE IF NOT EXISTS marks_read(doc INTEGER PRIMARY KEY, modified INTEGER NOT NULL)")
            // Marks kept before they knew their author: add the column, and have every file's marks read again.
            val hasAuthor = db.query("PRAGMA table_info(marks)").use { c -> var found = false; while (c.moveToNext()) if (c.getString(1) == "author") found = true; found }
            if (!hasAuthor) {
                db.execSQL("ALTER TABLE marks ADD COLUMN author TEXT NOT NULL DEFAULT ''")
                db.execSQL("DELETE FROM marks_read")
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS text_upgrade(last INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(doc INTEGER NOT NULL, page INTEGER NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(doc, page))")
            // The nightly upkeep: scanned pages read into search, and tags PDFx put on by itself.
            db.execSQL("CREATE TABLE IF NOT EXISTS scan_words(doc INTEGER PRIMARY KEY, modified INTEGER NOT NULL, next INTEGER NOT NULL, found INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS auto_tags(doc INTEGER NOT NULL, tag INTEGER NOT NULL, at INTEGER NOT NULL, reviewed INTEGER NOT NULL, PRIMARY KEY(doc, tag))")
            db.execSQL("CREATE TABLE IF NOT EXISTS auto_tag_no(doc INTEGER NOT NULL, tag INTEGER NOT NULL, PRIMARY KEY(doc, tag))")
            db.execSQL("CREATE TABLE IF NOT EXISTS auto_tag_seen(doc INTEGER PRIMARY KEY, modified INTEGER NOT NULL)")
        }
    }

    companion object {
        const val MARK_START = "\u0002"
        const val MARK_END = "\u0003"
        /** Room for a million pages a file in each file's range of row ids. */
        const val SPAN = 1_000_000L
    }
}

/** Pages the user marked in each document. Keyed by the document's id, so they survive renames and moves. */
class Bookmarks(private val db: LibraryDb) {
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    fun pages(docId: Long): List<Int> =
        sql.query(SimpleSQLiteQuery("SELECT page FROM bookmarks WHERE doc = ? ORDER BY page", arrayOf<Any>(docId))).use { c ->
            val out = ArrayList<Int>()
            while (c.moveToNext()) out += c.getInt(0)
            out
        }

    fun set(docId: Long, page: Int, on: Boolean) {
        if (on) sql.execSQL("INSERT OR IGNORE INTO bookmarks(doc, page, created) VALUES (?, ?, ?)", arrayOf<Any>(docId, page, System.currentTimeMillis()))
        else sql.execSQL("DELETE FROM bookmarks WHERE doc = ? AND page = ?", arrayOf<Any>(docId, page))
    }

    fun clear(docId: Long) = sql.execSQL("DELETE FROM bookmarks WHERE doc = ?", arrayOf<Any>(docId))
}

/** Spelling arithmetic for search. */
object TextMath {
    /** Edits from [a] to [b] (a swap of two letters is one), stopping early once past [most]. */
    fun distance(a: String, b: String, most: Int = 2): Int {
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > most) return most + 1
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var best = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < best) best = v
            }
            if (best > most) return most + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }
}

/** A highlight, underline, note or text box in a file, as the library keeps it for finding again. */
data class MarkRow(val doc: Long, val page: Int, val kind: String, val color: Int, val quote: String, val note: String, val author: String = "")

/**
 * Every highlight and note across the library, read from the files themselves (where PDFx and
 * other apps keep them) whenever a file changes, so they can be listed and searched together.
 */
class Marks(private val db: LibraryDb) {
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    fun replace(docId: Long, modified: Long, marks: List<MarkRow>) {
        val s = sql
        s.beginTransaction()
        try {
            s.execSQL("DELETE FROM marks WHERE doc = ?", arrayOf<Any>(docId))
            for (m in marks) s.execSQL(
                "INSERT INTO marks(doc, page, kind, color, quote, note, author) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(docId, m.page, m.kind, m.color, m.quote, m.note, m.author),
            )
            s.execSQL("INSERT OR REPLACE INTO marks_read(doc, modified) VALUES (?, ?)", arrayOf<Any>(docId, modified))
            s.setTransactionSuccessful()
        } finally {
            s.endTransaction()
        }
    }

    /** When each file's marks were last read: the file's modified time then. */
    fun readAt(): Map<Long, Long> = sql.query("SELECT doc, modified FROM marks_read").use { c ->
        val out = HashMap<Long, Long>()
        while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1)
        out
    }

    /** Every highlight, underline and note (text boxes kept by earlier versions are left out). */
    fun all(): List<MarkRow> = rows("SELECT doc, page, kind, color, quote, note, author FROM marks WHERE kind != 'TEXT' ORDER BY doc, page", emptyArray())

    /** Marks whose words or note have every one of [words] in them. */
    fun matching(words: List<String>): List<MarkRow> {
        if (words.isEmpty()) return emptyList()
        val where = words.joinToString(" AND ") { "(quote || ' ' || note) LIKE ? ESCAPE '\\'" }
        val args = words.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }.toTypedArray<Any>()
        return rows("SELECT doc, page, kind, color, quote, note, author FROM marks WHERE kind != 'TEXT' AND $where ORDER BY doc, page LIMIT 500", args)
    }

    fun clear(docId: Long) {
        sql.execSQL("DELETE FROM marks WHERE doc = ?", arrayOf<Any>(docId))
        sql.execSQL("DELETE FROM marks_read WHERE doc = ?", arrayOf<Any>(docId))
    }

    private fun rows(q: String, args: Array<Any>): List<MarkRow> = sql.query(SimpleSQLiteQuery(q, args)).use { c ->
        val out = ArrayList<MarkRow>()
        while (c.moveToNext()) out += MarkRow(c.getLong(0), c.getInt(1), c.getString(2) ?: "", c.getInt(3), c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "")
        out
    }
}
