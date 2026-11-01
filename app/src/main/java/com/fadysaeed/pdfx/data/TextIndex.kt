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
            s.execSQL("DELETE FROM page_text WHERE docId = ?", arrayOf<Any>(docId))
            val insert = s.compileStatement("INSERT INTO page_text(body, docId, page) VALUES (?, ?, ?)")
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

    fun clear(docId: Long) = sql.execSQL("DELETE FROM page_text WHERE docId = ?", arrayOf<Any>(docId))

    fun search(match: String, limit: Int = 400): List<TextHit> {
        val q = SimpleSQLiteQuery(
            "SELECT docId, page, snippet(page_text, ?, ?, ?, 0, 14) FROM page_text WHERE page_text MATCH ? LIMIT $limit",
            arrayOf<Any>(MARK_START, MARK_END, "…", match),
        )
        val out = ArrayList<TextHit>()
        sql.query(q).use { c ->
            while (c.moveToNext()) out += TextHit(c.getLong(0), c.getInt(1), c.getString(2) ?: "")
        }
        return out
    }

    fun pagesIndexed(docId: Long): Int =
        sql.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM page_text WHERE docId = ?", arrayOf<Any>(docId))).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    object Setup : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS page_text USING fts4(" +
                    "body, docId, page, tokenize=porter, notindexed=docId, notindexed=page)",
            )
        }
    }

    companion object {
        const val MARK_START = "\u0002"
        const val MARK_END = "\u0003"
    }
}
