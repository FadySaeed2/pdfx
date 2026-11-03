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
 */
class TextIndex(private val db: LibraryDb) {
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    fun replace(docId: Long, pages: List<PageText>) {
        val s = sql
        s.beginTransaction()
        try {
            s.execSQL("DELETE FROM page_text WHERE ref = ?", arrayOf<Any>(docId))
            val insert = s.compileStatement("INSERT INTO page_text(body, ref, page) VALUES (?, ?, ?)")
            for (p in pages) {
                insert.clearBindings()
                insert.bindString(1, p.body)
                insert.bindLong(2, docId)
                insert.bindLong(3, p.page.toLong())
                insert.executeInsert()
            }
            s.setTransactionSuccessful()
        } finally {
            s.endTransaction()
        }
    }

    fun clear(docId: Long) = sql.execSQL("DELETE FROM page_text WHERE ref = ?", arrayOf<Any>(docId))

    fun search(match: String, limit: Int = 400): List<TextHit> {
        val q = SimpleSQLiteQuery(
            "SELECT ref, page, snippet(page_text, ?, ?, ?, 0, 14) FROM page_text WHERE page_text MATCH ? LIMIT $limit",
            arrayOf<Any>(MARK_START, MARK_END, "…", match),
        )
        val out = ArrayList<TextHit>()
        sql.query(q).use { c ->
            while (c.moveToNext()) out += TextHit(c.getLong(0), c.getInt(1), c.getString(2) ?: "")
        }
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
        val q = SimpleSQLiteQuery("SELECT ref, COUNT(*) FROM page_text WHERE page_text MATCH ? GROUP BY ref", arrayOf<Any>(match))
        val out = HashMap<Long, Int>()
        sql.query(q).use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1) }
        return out
    }

    fun pagesIndexed(docId: Long): Int =
        sql.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM page_text WHERE ref = ?", arrayOf<Any>(docId))).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    object Setup : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL(
                // "docid" is FTS4's own name for the row id, so the document column is "ref".
                "CREATE VIRTUAL TABLE IF NOT EXISTS page_text USING fts4(" +
                    "body, ref, page, tokenize=porter, notindexed=ref, notindexed=page)",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(doc INTEGER NOT NULL, page INTEGER NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(doc, page))")
        }
    }

    companion object {
        const val MARK_START = "\u0002"
        const val MARK_END = "\u0003"
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
