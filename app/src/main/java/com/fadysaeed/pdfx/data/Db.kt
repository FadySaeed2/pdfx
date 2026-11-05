package com.fadysaeed.pdfx.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One PDF PDFx knows about. Files on the phone are keyed by their path; files handed over by
 * other apps that have no path (a WhatsApp share, a Drive file) are keyed by their Uri.
 */
@Entity(tableName = "docs", indices = [Index(value = ["key"], unique = true), Index("folder"), Index("fingerprint")])
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
    /**
     * What the file is, whatever it is called and wherever it sits: its size and a hash of its
     * beginning, middle and end. A file moved or renamed by another app is known again by it.
     */
    val fingerprint: String? = null,
    /** Its real title, first author (with "et al." when there are more), year and DOI, read from the file. */
    val title: String? = null,
    val author: String? = null,
    @ColumnInfo(defaultValue = "0") val year: Int = 0,
    val doi: String? = null,
    /** The [modified] value [title] and the rest were read at; 0 means not yet. */
    @ColumnInfo(defaultValue = "0") val infoAt: Long = 0,
    /** When PDFx first saw it. */
    @ColumnInfo(defaultValue = "0") val addedAt: Long = 0,
    /** Its kind (a [com.fadysaeed.pdfx.engine.DocKind] name), read from it; null until read. */
    val kind: String? = null,
    /** What the user said about reading it: 0 nothing, 1 to read, 2 done. */
    @ColumnInfo(defaultValue = "0") val reading: Int = 0,
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

data class TextHit(val docId: Long, val page: Int, val snippet: String)

data class TagCount(val id: Long, val name: String, val color: Int, val count: Int)

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM docs")
    abstract fun observeAll(): Flow<List<Doc>>

    @Query("SELECT * FROM docs")
    abstract suspend fun all(): List<Doc>

    @Query("SELECT * FROM docs WHERE id = :id")
    abstract suspend fun byId(id: Long): Doc?

    @Query("SELECT * FROM docs WHERE `key` = :key")
    abstract suspend fun byKey(key: String): Doc?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(doc: Doc): Long

    /** Writes a whole row as given. Use [update], which keeps what the user did meanwhile. */
    @Update
    abstract suspend fun updateRow(doc: Doc)

    /**
     * Writes [doc], made from a copy read a moment (or a whole look round the phone) ago. Where the
     * user opened the file or read on in it since that copy was read, that stays: a write about
     * something else (a title read, a size changed) never takes the file out of Recent or back to
     * an old page. Only a write that says it was opened later than now moves those on.
     */
    @Transaction
    open suspend fun update(doc: Doc) {
        val now = byId(doc.id)
        updateRow(if (now == null || doc.lastOpened > now.lastOpened) doc else doc.copy(lastOpened = now.lastOpened, lastPage = now.lastPage))
    }

    @Query("UPDATE docs SET lastPage = :page WHERE id = :id")
    abstract suspend fun setPage(id: Long, page: Int)

    @Query("UPDATE docs SET starred = :on WHERE id = :id")
    abstract suspend fun setStarred(id: Long, on: Boolean)

    @Delete
    abstract suspend fun delete(doc: Doc)

    @Query("SELECT * FROM docs WHERE fingerprint = :fp")
    abstract suspend fun byFingerprint(fp: String): List<Doc>

    @Query("SELECT * FROM docs WHERE trashedAt = 0 AND path IS NOT NULL AND fingerprint IS NULL LIMIT :n")
    abstract suspend fun withoutFingerprint(n: Int): List<Doc>

    @Query("SELECT * FROM docs WHERE trashedAt = 0 AND path IS NOT NULL AND (indexedAt != modified OR infoAt != modified OR kind IS NULL) ORDER BY lastOpened DESC, size ASC LIMIT 1")
    abstract suspend fun nextToIndex(): Doc?

    @Query("SELECT COUNT(*) FROM docs WHERE trashedAt = 0 AND path IS NOT NULL AND (indexedAt != modified OR infoAt != modified OR kind IS NULL)")
    abstract fun observeUnindexed(): Flow<Int>

    // Tags
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    abstract fun observeTags(): Flow<List<Tag>>

    @Query(
        "SELECT t.id AS id, t.name AS name, t.color AS color, " +
            "(SELECT COUNT(*) FROM doc_tags dt JOIN docs d ON d.id = dt.docId WHERE dt.tagId = t.id AND d.trashedAt = 0) AS count " +
            "FROM tags t ORDER BY t.name COLLATE NOCASE",
    )
    abstract fun observeTagCounts(): Flow<List<TagCount>>

    @Query("SELECT * FROM doc_tags")
    abstract fun observeDocTags(): Flow<List<DocTag>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTag(tag: Tag): Long

    @Query("DELETE FROM tags WHERE id = :id")
    abstract suspend fun deleteTag(id: Long)

    @Query("DELETE FROM doc_tags WHERE tagId = :id")
    abstract suspend fun untagAll(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun tag(link: DocTag)

    @Query("DELETE FROM doc_tags WHERE docId = :docId AND tagId = :tagId")
    abstract suspend fun untag(docId: Long, tagId: Long)

    @Query("DELETE FROM doc_tags WHERE docId = :docId")
    abstract suspend fun untagDoc(docId: Long)

    @Query("SELECT * FROM tags")
    abstract suspend fun allTags(): List<Tag>

    @Query("SELECT tagId FROM doc_tags WHERE docId = :docId")
    abstract suspend fun tagIdsOf(docId: Long): List<Long>
}

@Database(entities = [Doc::class, Tag::class, DocTag::class], version = 3, exportSchema = true)
abstract class LibraryDb : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        /** Version 2: each file's fingerprint, real title, author, year and DOI, and when it was first seen. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE docs ADD COLUMN fingerprint TEXT")
                db.execSQL("ALTER TABLE docs ADD COLUMN title TEXT")
                db.execSQL("ALTER TABLE docs ADD COLUMN author TEXT")
                db.execSQL("ALTER TABLE docs ADD COLUMN year INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE docs ADD COLUMN doi TEXT")
                db.execSQL("ALTER TABLE docs ADD COLUMN infoAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE docs ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_docs_fingerprint ON docs(fingerprint)")
                // Files already known count as added when they last changed.
                db.execSQL("UPDATE docs SET addedAt = modified")
            }
        }

        /** Version 3: each file's kind, and whether it is to be read or done. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE docs ADD COLUMN kind TEXT")
                db.execSQL("ALTER TABLE docs ADD COLUMN reading INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun open(context: Context): LibraryDb = open(context, "library.db")

        fun open(context: Context, name: String): LibraryDb =
            Room.databaseBuilder(context, LibraryDb::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3).addCallback(TextIndex.Setup).build()

        fun inMemory(context: Context): LibraryDb =
            Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).addCallback(TextIndex.Setup).allowMainThreadQueries().build()
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
