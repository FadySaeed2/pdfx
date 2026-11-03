package com.fadysaeed.pdfx

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.data.LibraryDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A library made by an earlier PDFx (database version 1, as on Fady's phone) opens in this one with
 * everything kept: the file, its tag, its star and its reading place; the new details start empty.
 */
@RunWith(AndroidJUnit4::class)
class MigrationOnDeviceTest {
    @Test
    fun aVersionOneLibraryOpensWithEverythingKept() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migrate-${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Version 1 exactly as Room made it.
            db.execSQL("CREATE TABLE IF NOT EXISTS `docs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `key` TEXT NOT NULL, `path` TEXT, `uri` TEXT NOT NULL, `name` TEXT NOT NULL, `folder` TEXT, `size` INTEGER NOT NULL, `modified` INTEGER NOT NULL, `pages` INTEGER NOT NULL, `locked` INTEGER NOT NULL, `lastOpened` INTEGER NOT NULL, `lastPage` INTEGER NOT NULL, `starred` INTEGER NOT NULL, `trashedAt` INTEGER NOT NULL, `trashOrigin` TEXT, `indexedAt` INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_docs_key` ON `docs` (`key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_docs_folder` ON `docs` (`folder`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `doc_tags` (`docId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, PRIMARY KEY(`docId`, `tagId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_doc_tags_tagId` ON `doc_tags` (`tagId`)")
            db.execSQL("INSERT INTO docs(id, `key`, path, uri, name, folder, size, modified, pages, locked, lastOpened, lastPage, starred, trashedAt, trashOrigin, indexedAt) VALUES (1, '/sdcard/a.pdf', '/sdcard/a.pdf', 'file:///sdcard/a.pdf', 'a.pdf', '/sdcard', 1234, 1700000000000, 12, 0, 1700000001000, 5, 1, 0, NULL, 1700000000000)")
            db.execSQL("INSERT INTO tags(id, name, color) VALUES (1, 'Lithium', 2)")
            db.execSQL("INSERT INTO doc_tags(docId, tagId) VALUES (1, 1)")
            db.version = 1
        }
        val lib = LibraryDb.open(ctx, name)
        try {
            val d = lib.dao().byId(1)!!
            assertEquals("a.pdf", d.name)
            assertEquals(true, d.starred)
            assertEquals(5, d.lastPage)
            assertEquals("counted as added when it last changed", 1700000000000, d.addedAt)
            assertNull(d.fingerprint)
            assertNull(d.title)
            assertEquals(listOf(1L), lib.dao().tagIdsOf(1))
        } finally {
            lib.close()
            ctx.deleteDatabase(name)
        }
    }
}
