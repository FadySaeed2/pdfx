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

    fun pagesIndexed(docId: Long): Int =
        sql.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM page_words WHERE docid BETWEEN ? AND ?", arrayOf<Any>(docId * SPAN, docId * SPAN + SPAN - 1))).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    object Setup : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS page_words USING fts4(body, tokenize=porter)")
            db.execSQL("CREATE TABLE IF NOT EXISTS text_upgrade(last INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(doc INTEGER NOT NULL, page INTEGER NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(doc, page))")
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
