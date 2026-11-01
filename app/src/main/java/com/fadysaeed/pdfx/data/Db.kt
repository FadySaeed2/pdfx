package com.fadysaeed.pdfx.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One PDF PDFx knows about. Files on the phone are keyed by their path; files handed over by
 * other apps that have no path (a WhatsApp share, a Drive file) are keyed by their Uri.
 */
@Entity(tableName = "docs", indices = [Index(value = ["key"], unique = true), Index("folder")])
data class Doc(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    /** Absolute path, or null when the file only exists behind another app's Uri. */
    val path: String?,
    /** What to hand the engine: file:// for paths, the original content:// otherwise. */
    val uri: String,
    val name: String,
    /** Parent folder path, null for Uri-only files. */
    val folder: String?,
    val size: Long,
    val modified: Long,
    val pages: Int = 0,
    val locked: Boolean = false,
    val lastOpened: Long = 0,
    val lastPage: Int = 0,
    val starred: Boolean = false,
    /** When it went to the trash; 0 means it is not in the trash. */
    val trashedAt: Long = 0,
    /** Where to put it back when restored from the trash. */
    val trashOrigin: String? = null,
    /** The [modified] value its text was read at; 0 means not read yet. */
    val indexedAt: Long = 0,
) {
    val inTrash: Boolean get() = trashedAt > 0
    val isFile: Boolean get() = path != null
}

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Index into the tag palette. */
    val color: Int,
)

@Entity(tableName = "doc_tags", primaryKeys = ["docId", "tagId"], indices = [Index("tagId")])
data class DocTag(val docId: Long, val tagId: Long)

/** The words on every page, for search inside documents. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_PORTER, notIndexed = ["docId", "page"])
@Entity(tableName = "page_text")
data class PageText(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Int = 0,
    val docId: Long,
    val page: Int,
    val body: String,
)

data class TextHit(val docId: Long, val page: Int, val snippet: String)

data class TagCount(val id: Long, val name: String, val color: Int, val count: Int)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM docs")
    fun observeAll(): Flow<List<Doc>>

    @Query("SELECT * FROM docs")
    suspend fun all(): List<Doc>

    @Query("SELECT * FROM docs WHERE id = :id")
    suspend fun byId(id: Long): Doc?

    @Query("SELECT * FROM docs WHERE `key` = :key")
    suspend fun byKey(key: String): Doc?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(doc: Doc): Long

    @Update
    suspend fun update(doc: Doc)

    @Delete
    suspend fun delete(doc: Doc)

    @Query("SELECT * FROM docs WHERE trashedAt = 0 AND path IS NOT NULL AND (indexedAt != modified) ORDER BY lastOpened DESC, size ASC LIMIT 1")
    suspend fun nextToIndex(): Doc?

    @Query("SELECT COUNT(*) FROM docs WHERE trashedAt = 0 AND path IS NOT NULL AND (indexedAt != modified)")
    fun observeUnindexed(): Flow<Int>

    @Query("DELETE FROM page_text WHERE docId = :docId")
    suspend fun clearText(docId: Long)

    @Insert
    suspend fun insertText(rows: List<PageText>)

    @Transaction
    suspend fun replaceText(docId: Long, rows: List<PageText>) {
        clearText(docId)
        rows.chunked(200).forEach { insertText(it) }
    }

    @Query(
        "SELECT docId, page, snippet(page_text, '\u0002', '\u0003', '…', 2, 14) AS snippet " +
            "FROM page_text WHERE page_text MATCH :match LIMIT 400",
    )
    suspend fun searchText(match: String): List<TextHit>

    // Tags
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeTags(): Flow<List<Tag>>

    @Query(
        "SELECT t.id AS id, t.name AS name, t.color AS color, " +
            "(SELECT COUNT(*) FROM doc_tags dt JOIN docs d ON d.id = dt.docId WHERE dt.tagId = t.id AND d.trashedAt = 0) AS count " +
            "FROM tags t ORDER BY t.name COLLATE NOCASE",
    )
    fun observeTagCounts(): Flow<List<TagCount>>

    @Query("SELECT * FROM doc_tags")
    fun observeDocTags(): Flow<List<DocTag>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: Tag): Long

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query("DELETE FROM doc_tags WHERE tagId = :id")
    suspend fun untagAll(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun tag(link: DocTag)

    @Query("DELETE FROM doc_tags WHERE docId = :docId AND tagId = :tagId")
    suspend fun untag(docId: Long, tagId: Long)

    @Query("DELETE FROM doc_tags WHERE docId = :docId")
    suspend fun untagDoc(docId: Long)
}

@Database(entities = [Doc::class, Tag::class, DocTag::class, PageText::class], version = 1, exportSchema = true)
abstract class LibraryDb : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun open(context: Context): LibraryDb =
            Room.databaseBuilder(context, LibraryDb::class.java, "library.db").build()

        fun inMemory(context: Context): LibraryDb =
            Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).allowMainThreadQueries().build()
    }
}

/** Turns what the user typed into an FTS query: every word must appear, each as a prefix. */
fun ftsQuery(input: String): String? {
    val words = input.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
    if (words.isEmpty()) return null
    return words.joinToString(" ") { "$it*" }
}
