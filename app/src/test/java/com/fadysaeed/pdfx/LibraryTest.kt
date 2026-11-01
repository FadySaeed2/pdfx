package com.fadysaeed.pdfx

import androidx.test.core.app.ApplicationProvider
import com.fadysaeed.pdfx.data.Library
import com.fadysaeed.pdfx.data.LibraryDb
import com.fadysaeed.pdfx.data.PageText
import com.fadysaeed.pdfx.data.ftsQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.nio.file.Files

/** The library's file handling, run against a throwaway folder that stands in for phone storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LibraryTest {
    private lateinit var root: File
    private lateinit var db: LibraryDb
    private lateinit var lib: Library

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pdfx-root").toFile()
        db = LibraryDb.inMemory(ApplicationProvider.getApplicationContext())
        lib = Library(ApplicationProvider.getApplicationContext(), db, root, allFilesAccess = { true })
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun pdf(rel: String, bytes: Int = 2048): File =
        File(root, rel).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

    @Test
    fun scanFindsPdfsAndSkipsHiddenAndAppData() = runBlocking {
        pdf("Download/a.pdf")
        pdf("Documents/Work/b.PDF")
        pdf("Download/notes.txt")
        pdf(".hidden/c.pdf")
        pdf("Android/data/com.x/d.pdf")
        pdf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/e.pdf")
        val r = lib.scan()
        assertEquals(3, r.found)
        val names = db.dao().all().map { it.name }.toSet()
        assertEquals(setOf("a.pdf", "b.PDF", "e.pdf"), names)
        assertEquals("WhatsApp Documents", lib.folderLabel(db.dao().all().first { it.name == "e.pdf" }.folder))
    }

    @Test
    fun scanForgetsFilesThatDisappearButKeepsUserState() = runBlocking {
        val a = pdf("Download/a.pdf")
        pdf("Download/b.pdf")
        lib.scan()
        val b = db.dao().all().first { it.name == "b.pdf" }
        db.dao().update(b.copy(starred = true, lastPage = 4))
        a.delete()
        val r = lib.scan()
        assertEquals(1, r.removed)
        val left = db.dao().all()
        assertEquals(listOf("b.pdf"), left.map { it.name })
        assertTrue(left[0].starred)
        assertEquals(4, left[0].lastPage)
    }

    @Test
    fun renameMoveCopy() = runBlocking {
        pdf("Download/report.pdf")
        lib.scan()
        var d = db.dao().all().single()

        d = lib.rename(d, "Report final").getOrThrow()
        assertEquals("Report final.pdf", d.name)
        assertTrue(File(root, "Download/Report final.pdf").exists())
        assertFalse(File(root, "Download/report.pdf").exists())

        pdf("Download/taken.pdf")
        assertTrue(lib.rename(d, "taken").isFailure)

        val dest = File(root, "Documents/Clinic")
        d = lib.move(d, dest).getOrThrow()
        assertEquals(dest.absolutePath, d.folder)
        assertTrue(File(dest, "Report final.pdf").exists())

        val c = lib.copy(d, dest).getOrThrow()
        assertEquals("Report final (2).pdf", c.name)
        assertEquals(File(dest, "Report final.pdf").length(), File(dest, "Report final (2).pdf").length())
    }

    @Test
    fun trashRestoreAndDeleteForever() = runBlocking {
        pdf("Download/x.pdf")
        lib.scan()
        val d = db.dao().all().single()
        val t = lib.trash(d).getOrThrow()
        assertTrue(t.inTrash)
        assertFalse(File(root, "Download/x.pdf").exists())
        assertTrue(File(t.path!!).exists())
        assertTrue(File(lib.trashDir, ".nomedia").exists())

        // A rescan must not lose or duplicate the file while it sits in the trash.
        lib.scan()
        assertEquals(1, db.dao().all().size)

        val r = lib.restore(t).getOrThrow()
        assertFalse(r.inTrash)
        assertTrue(File(root, "Download/x.pdf").exists())

        val t2 = lib.trash(r).getOrThrow()
        lib.deleteForever(t2).getOrThrow()
        assertFalse(File(t2.path!!).exists())
        assertNull(db.dao().byId(d.id))
    }

    @Test
    fun oldTrashIsPurged() = runBlocking {
        pdf("Download/old.pdf")
        lib.scan()
        val t = lib.trash(db.dao().all().single()).getOrThrow()
        db.dao().update(t.copy(trashedAt = System.currentTimeMillis() - 31L * 86_400_000))
        lib.purgeTrash(30)
        assertTrue(db.dao().all().isEmpty())
        assertFalse(File(t.path!!).exists())
    }

    @Test
    fun tagsAndTextSearch() = runBlocking {
        pdf("Download/guide.pdf")
        pdf("Download/bill.pdf")
        lib.scan()
        val guide = db.dao().all().first { it.name == "guide.pdf" }
        val tagId = lib.createTag("Clinic", 1)
        lib.setTag(guide.id, tagId, true)
        assertEquals(guide.id, db.dao().observeDocTags().first().single().docId)

        db.dao().replaceText(guide.id, listOf(
            PageText(docId = guide.id, page = 0, body = "Introduction to mood disorders"),
            PageText(docId = guide.id, page = 7, body = "Monitor lithium levels twelve hours after the last dose"),
        ))
        val hits = lib.searchText("lith")
        assertEquals(1, hits.size)
        assertEquals(7, hits[0].page)
        assertTrue(hits[0].snippet.contains("\u0002"))
        assertTrue(lib.searchText("twelve hours").isNotEmpty())
        assertTrue(lib.searchText("aspirin").isEmpty())

        lib.deleteTag(tagId)
        assertTrue(db.dao().observeDocTags().first().isEmpty())
    }

    @Test
    fun ftsQueryIsSafe() {
        assertEquals("lith* level*", ftsQuery("Lith  level"))
        assertEquals("o* brien*", ftsQuery("O'Brien"))
        assertNull(ftsQuery("  \"*:  "))
    }

    @Test
    fun uniqueNames() {
        val dir = File(root, "u").apply { mkdirs() }
        File(dir, "a.pdf").writeText("x")
        File(dir, "a (2).pdf").writeText("x")
        assertEquals("a (3).pdf", Library.uniqueTarget(dir, "a.pdf").name)
        assertNotNull(Library.uniqueTarget(dir, "b.pdf"))
    }
}
