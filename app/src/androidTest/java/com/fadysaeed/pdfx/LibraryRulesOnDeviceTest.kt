package com.fadysaeed.pdfx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.File

/**
 * The library's rules on a real Android system, against a throwaway folder in the app's cache
 * that stands in for phone storage: what a scan finds and forgets, file operations, the trash,
 * tags and the full-text index.
 */
@RunWith(AndroidJUnit4::class)
class LibraryRulesOnDeviceTest {
    private lateinit var root: File
    private lateinit var db: LibraryDb
    private lateinit var lib: Library

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(ctx.cacheDir, "pdfx-root-${System.nanoTime()}").apply { mkdirs() }
        // What the library remembers outside its database (banner titles, names kept, scans read).
        ctx.getSharedPreferences("library_titles", 0).edit().clear().commit()
        db = LibraryDb.inMemory(ctx)
        lib = Library(ctx, db, root, allFilesAccess = { true })
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

        lib.text.replace(guide.id, listOf(
            PageText(page = 0, body = "Introduction to mood disorders"),
            PageText(page = 7, body = "Monitor lithium levels twelve hours after the last dose"),
        ))
        val hits = lib.searchText("lith")
        assertEquals(1, hits.size)
        assertEquals(7, hits[0].page)
        assertTrue(hits[0].snippet.contains("\u0002"))
        assertTrue(lib.searchText("twelve hours").isNotEmpty())
        assertTrue(lib.searchText("aspirin").isEmpty())
        // Porter stemming: "monitoring" finds "Monitor".
        assertEquals(7, lib.searchText("monitoring").single().page)

        // Deleting a file takes its words out of the index.
        lib.deleteForever(guide).getOrThrow()
        assertTrue(lib.searchText("lithium").isEmpty())

        lib.deleteTag(tagId)
        assertTrue(db.dao().observeDocTags().first().isEmpty())
        assertEquals(1, db.dao().all().size)
    }

    private fun unique(rel: String, seed: Int, bytes: Int = 300_000): File =
        File(root, rel).apply { parentFile?.mkdirs(); writeBytes(kotlin.random.Random(seed).nextBytes(bytes)) }

    @Test
    fun aFileMovedOrRenamedElsewhereKeepsItsTagsStarAndPlace() = runBlocking {
        val a = unique("Download/s41380-023-02145-x.pdf", 1)
        unique("Download/other.pdf", 2)
        lib.scan()
        val dao = db.dao()
        val d = dao.byKey(a.absolutePath)!!
        assertNotNull("a fingerprint is read on arrival", d.fingerprint)
        val tag = lib.createTag("Bipolar", 1)
        lib.setTag(d.id, tag, true)
        lib.setStar(d, true)
        lib.updatePage(d.id, 7)
        lib.bookmarks.set(d.id, 3, true)
        // Moved and renamed by another app (Files, a sync app).
        val moved = File(root, "Documents/Papers/Lithium review.pdf").apply { parentFile?.mkdirs() }
        assertTrue(a.renameTo(moved))
        val r = lib.scan()
        assertEquals("known again, not added", 1, r.moved)
        assertEquals(0, r.added)
        assertEquals(0, r.removed)
        val now = dao.byKey(moved.absolutePath)!!
        assertEquals("the same document", d.id, now.id)
        assertEquals("Lithium review.pdf", now.name)
        assertTrue(now.starred)
        assertEquals(7, now.lastPage)
        assertEquals(listOf(3), lib.bookmarks.pages(now.id))
        assertEquals(listOf(tag), dao.tagIdsOf(now.id))
    }

    @Test
    fun copiesOfOneFileMergeIntoOne() = runBlocking {
        val a = unique("Documents/guideline.pdf", 5)
        val b = File(root, "Download/guideline (1).pdf").apply { parentFile?.mkdirs(); writeBytes(a.readBytes()) }
        val c = File(root, "WhatsApp Documents/guideline.pdf").apply { parentFile?.mkdirs(); writeBytes(a.readBytes()) }
        lib.scan()
        val dao = db.dao()
        val docs = listOf(a, b, c).map { dao.byKey(it.absolutePath)!! }
        assertEquals("one fingerprint", 1, docs.map { it.fingerprint }.toSet().size)
        val t1 = lib.createTag("Clozapine", 2)
        val t2 = lib.createTag("Guidelines", 3)
        lib.setTag(docs[1].id, t1, true)
        lib.setTag(docs[2].id, t2, true)
        lib.setStar(docs[2], true)
        lib.bookmarks.set(docs[1].id, 12, true)
        assertEquals(2, lib.mergeDuplicates(docs[0], docs.drop(1)).getOrThrow())
        val kept = dao.byId(docs[0].id)!!
        assertTrue(kept.starred)
        assertEquals(setOf(t1, t2), dao.tagIdsOf(kept.id).toSet())
        assertEquals(listOf(12), lib.bookmarks.pages(kept.id))
        assertTrue("the others are in the trash", docs.drop(1).all { dao.byId(it.id)!!.inTrash })
        assertTrue(a.exists())
        assertFalse(b.exists() || c.exists())
    }

    @Test
    fun topicsThatRunThroughFilesBecomeSuggestedTags() = runBlocking {
        val dao = db.dao()
        val files = (1..5).map { unique("Download/p$it.pdf", 100 + it) }
        lib.scan()
        val docs = files.map { dao.byKey(it.absolutePath)!! }
        // Two about lithium by title, one with lithium on most of its pages, one mentioning it once.
        dao.update(docs[0].copy(title = "Lithium toxicity in older adults", pages = 8))
        dao.update(docs[1].copy(title = "Lithium and the kidney", pages = 6))
        dao.update(docs[2].copy(title = "Mood stabilisers compared", pages = 4))
        dao.update(docs[3].copy(title = "Sleep in shift workers", pages = 10))
        lib.text.replace(docs[2].id, (0 until 4).map { com.fadysaeed.pdfx.data.PageText(it, "lithium levels and lithium dosing on page $it") })
        lib.text.replace(docs[3].id, listOf(com.fadysaeed.pdfx.data.PageText(0, "a passing mention of lithium")) + (1 until 10).map { com.fadysaeed.pdfx.data.PageText(it, "sleep hygiene") })
        val found = com.fadysaeed.pdfx.data.Topics.suggest(lib, emptySet())
        val lithium = found.flatMap { it.topics }.first { it.term == "Lithium" }
        assertEquals("grouped under its area", "Mood disorders", found.first { g -> g.topics.any { it.term == "Lithium" } }.area)
        assertEquals(setOf(docs[0].id, docs[1].id, docs[2].id), lithium.docIds.toSet())
        assertEquals(3, lib.applyTopic("Lithium", lithium.docIds))
        val tag = dao.allTags().first { it.name == "Lithium" }
        assertEquals(listOf(tag.id), dao.tagIdsOf(docs[2].id))
        assertTrue(dao.tagIdsOf(docs[3].id).isEmpty())
        // A tag that exists is not suggested again.
        assertTrue(com.fadysaeed.pdfx.data.Topics.suggest(lib, setOf("lithium")).flatMap { it.topics }.none { it.term == "Lithium" })
    }

    @Test
    fun highlightsAndNotesAreKeptAndFoundAndSpellingSlipsAreNear() = runBlocking {
        pdf("Download/a.pdf"); pdf("Download/b.pdf")
        lib.scan()
        val (a, b) = db.dao().all().sortedBy { it.name }
        lib.marks.replace(a.id, a.modified, listOf(
            com.fadysaeed.pdfx.data.MarkRow(a.id, 3, "HIGHLIGHT", 0xF2C14E, "Check lithium levels weekly", ""),
            com.fadysaeed.pdfx.data.MarkRow(a.id, 9, "NOTE", 0xF2C14E, "", "Ask about the 50% discount"),
        ))
        lib.marks.replace(b.id, b.modified, listOf(com.fadysaeed.pdfx.data.MarkRow(b.id, 1, "UNDERLINE", 0, "Clozapine and myocarditis", "")))
        assertEquals(3, lib.marks.all().size)
        assertEquals(listOf(3), lib.marks.matching(listOf("lithium", "weekly")).map { it.page })
        assertEquals("a % in a note is a %, not a wildcard", listOf(9), lib.marks.matching(listOf("50%")).map { it.page })
        assertEquals(a.modified, lib.marks.readAt()[a.id])
        lib.deleteForever(b).getOrThrow()
        assertTrue(lib.marks.matching(listOf("clozapine")).isEmpty())
        // Spelling: a word typed with a slip finds the word in the index.
        lib.text.replace(a.id, listOf(PageText(0, "lithium toxicity and the kidney"), PageText(1, "schizophrenia")))
        assertTrue(lib.text.hasAny("lith"))
        assertFalse(lib.text.hasAny("lithuim"))
        assertEquals(listOf("lithium"), lib.text.nearWords("lithuim", 1))
        assertTrue(lib.text.nearWords("schizofrenia", 1).isNotEmpty())
    }

    @Test
    fun marksAreReadFromTheFilesThemselves() = runBlocking {
        // A file with a highlight and a note made in it, as PDFx or another app saves them.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(root, "Download/marked.pdf").apply { parentFile?.mkdirs() }
        val d = com.artifex.mupdf.fitz.PDFDocument()
        try {
            val font = d.addSimpleFont(com.artifex.mupdf.fitz.Font("Helvetica"), 0)
            val res = d.newDictionary().apply { put("Font", d.newDictionary().apply { put("F", font) }) }
            val page = d.addPage(com.artifex.mupdf.fitz.Rect(0f, 0f, 400f, 300f), 0, res, "BT /F 14 Tf 40 240 Td (Lithium levels weekly) Tj ET")
            d.insertPage(-1, page)
            val p = d.loadPage(0) as com.artifex.mupdf.fitz.PDFPage
            val hl = p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_HIGHLIGHT)
            hl.setQuadPoints(arrayOf(com.artifex.mupdf.fitz.Quad(com.artifex.mupdf.fitz.Rect(40f, 46f, 200f, 64f))))
            hl.update()
            val note = p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_TEXT)
            note.rect = com.artifex.mupdf.fitz.Rect(300f, 20f, 320f, 40f)
            note.contents = "Ask the GP about ramipril"
            note.update()
            p.destroy()
            d.save(f.absolutePath, "")
        } finally {
            d.destroy()
        }
        lib.scan()
        val doc = db.dao().all().single { it.name == "marked.pdf" }
        com.fadysaeed.pdfx.data.TextIndexer(ctx, lib).runAll()
        val marks = lib.marks.all()
        assertEquals(marks.toString(), 2, marks.size)
        assertTrue(marks.any { it.kind == "HIGHLIGHT" && it.quote.contains("Lithium levels") })
        assertTrue(marks.any { it.kind == "NOTE" && it.note == "Ask the GP about ramipril" })
        assertEquals(db.dao().byId(doc.id)!!.modified, lib.marks.readAt()[doc.id])
    }

    @Test
    fun suggestedTagsForABigLibraryComeInSeconds() = runBlocking {
        val dao = db.dao()
        val words = com.fadysaeed.pdfx.data.Concepts.all.flatMap { it.terms } + List(3000) { "word$it" }
        val r = kotlin.random.Random(7)
        fun line(n: Int) = List(n) { words[r.nextInt(words.size)] }.joinToString(" ")
        // 4000 files, each with a title and a few pages of words: a library of tons of files.
        for (i in 0 until 4000) {
            val id = dao.insert(com.fadysaeed.pdfx.data.Doc(key = "/big/$i.pdf", path = "/big/$i.pdf", uri = "file:///big/$i.pdf", name = "$i.pdf", folder = "/big", size = 1000L + i, modified = 1, pages = 6, title = line(9)))
            lib.text.replace(id, (0 until 6).map { PageText(it, line(120)) })
        }
        val t0 = System.currentTimeMillis()
        var steps = 0
        val found = com.fadysaeed.pdfx.data.Topics.suggest(lib, emptySet()) { _, _ -> steps++ }
        val first = System.currentTimeMillis() - t0
        val t1 = System.currentTimeMillis()
        com.fadysaeed.pdfx.data.Topics.suggest(lib, emptySet())
        val again = System.currentTimeMillis() - t1
        android.util.Log.i("PDFxTopics", "4000 files: first ${first} ms, again ${again} ms, ${found.size} areas")
        assertTrue(found.isNotEmpty())
        assertTrue("it tells how far it is", steps >= com.fadysaeed.pdfx.data.Concepts.all.size)
        // Every page here is thick with topic words, far more than real papers: a hard case.
        assertTrue("first time $first ms", first < 45_000)
        assertTrue("again $again ms", again < 5_000)
    }

    @Test
    fun foldersMoveAndRenameWithTheirFiles() = runBlocking {
        val a = unique("Documents/Papers/Mood/a.pdf", 11)
        unique("Documents/Papers/b.pdf", 12)
        lib.scan()
        val dao = db.dao()
        val d = dao.byKey(a.absolutePath)!!
        val tag = lib.createTag("Mood", 1)
        lib.setTag(d.id, tag, true)
        // Renamed in place.
        val renamed = lib.moveFolder(File(root, "Documents/Papers"), File(root, "Documents"), "Reading").getOrThrow()
        assertEquals("Reading", renamed.name)
        val moved = dao.byId(d.id)!!
        assertEquals(File(root, "Documents/Reading/Mood/a.pdf").absolutePath, moved.path)
        assertEquals(File(root, "Documents/Reading/Mood").absolutePath, moved.folder)
        assertEquals(listOf(tag), dao.tagIdsOf(d.id))
        // Into another folder; not into itself.
        lib.makeFolder(root, "Archive").getOrThrow()
        lib.moveFolder(File(root, "Documents/Reading/Mood"), File(root, "Archive")).getOrThrow()
        assertEquals(File(root, "Archive/Mood/a.pdf").absolutePath, dao.byId(d.id)!!.path)
        assertTrue(lib.moveFolder(File(root, "Archive"), File(root, "Archive/Mood")).isFailure)
        // A scan afterwards changes nothing.
        val r = lib.scan()
        assertEquals(0, r.added); assertEquals(0, r.removed)
        // Only an empty folder can be deleted.
        assertTrue(lib.deleteEmptyFolder(File(root, "Archive")).isFailure)
        lib.makeFolder(root, "Empty").getOrThrow()
        assertTrue(lib.deleteEmptyFolder(File(root, "Empty")).isSuccess)
        assertFalse(File(root, "Empty").exists())
    }

    @Test
    fun codeNamesGetTitlesAndLooseFilesAreKnown() = runBlocking {
        val code = unique("Download/s41380-023-02145-x.pdf", 21)
        val fine = unique("Documents/Lithium review.pdf", 22)
        val doc3 = unique("Download/document(3).pdf", 23)
        lib.scan()
        val dao = db.dao()
        val a = dao.byKey(code.absolutePath)!!.copy(title = "Lithium in bipolar disorder: a reappraisal", author = "Malhi et al.", year = 2023)
        val b = dao.byKey(fine.absolutePath)!!.copy(title = "Something else entirely")
        val c = dao.byKey(doc3.absolutePath)!!.copy(title = "Referral: clozapine titration", author = null, year = 0)
        assertEquals("Malhi 2023 - Lithium in bipolar disorder - a reappraisal", lib.betterName(a))
        assertNull("a fine name is left alone", lib.betterName(b))
        assertEquals("Referral - clozapine titration", lib.betterName(c))
        assertTrue(lib.isLoose(a.folder))
        assertFalse(lib.isLoose(b.folder))
        assertEquals(File(root, "Documents/Library/Papers"), lib.shelfDir("Papers"))
    }

    @Test
    fun aNameTakenGetsANumberAndNamesLeftAloneAreNotOfferedAgain() = runBlocking {
        val code = unique("Download/s41380-023-02145-x.pdf", 41)
        unique("Download/Malhi 2023 - Lithium in bipolar disorder.pdf", 42)
        val other = unique("Download/document(4).pdf", 43)
        lib.scan()
        val dao = db.dao()
        val a = dao.byKey(code.absolutePath)!!.copy(title = "Lithium in bipolar disorder", author = "Malhi et al.", year = 2023)
        dao.update(a)
        val name = lib.betterName(a)!!
        assertEquals("Malhi 2023 - Lithium in bipolar disorder", name)
        // The same paper kept under its real name already: this one becomes "… (2)", not a failure.
        assertEquals("$name (2)", lib.freeName(a, name))
        assertEquals("Malhi 2023 - Lithium in bipolar disorder (2).pdf", lib.rename(a, lib.freeName(a, name)).getOrThrow().name)
        val o = dao.byKey(other.absolutePath)!!
        assertTrue(o.id !in lib.namesKept())
        lib.keepNames(listOf(o.id))
        assertTrue(o.id in lib.namesKept())
    }

    @Test
    fun anOldWordsIndexMovesOverAndAnswersFromTheIndexAlone() = runBlocking {
        val s = db.openHelper.writableDatabase
        // The index as PDFx kept it before: file and page as columns.
        s.execSQL("CREATE VIRTUAL TABLE page_text USING fts4(body, ref, page, tokenize=porter, notindexed=ref, notindexed=page)")
        val rows = listOf(Triple(5L, 0, "lithium levels"), Triple(9L, 2, "lithium and the kidney"), Triple(9L, 7, "lithium toxicity"), Triple(9L, 8, "sleep"))
        for ((ref, page, body) in rows) s.execSQL("INSERT INTO page_text(body, ref, page) VALUES (?, ?, ?)", arrayOf<Any>(body, ref, page))
        // Found before the move as well.
        assertEquals(mapOf(5L to 1, 9L to 2), lib.text.pagesMatching("lithium"))
        val v = lib.text.version
        var last = 0f
        lib.text.upgrade { last = it }
        assertEquals(1f, last, 0.001f)
        assertTrue(lib.text.version > v)
        assertFalse(s.query("SELECT 1 FROM sqlite_master WHERE name = 'page_text'").use { it.moveToFirst() })
        assertEquals(mapOf(5L to 1, 9L to 2), lib.text.pagesMatching("lithium"))
        assertEquals(listOf(9L to 7), lib.text.search("toxicity").map { it.docId to it.page })
        assertEquals(3, lib.text.pagesIndexed(9))
        lib.text.replace(9, listOf(PageText(1, "clozapine")))
        assertEquals(mapOf(5L to 1), lib.text.pagesMatching("lithium"))
        assertEquals(listOf(9L to 1), lib.text.search("clozapine").map { it.docId to it.page })
        lib.text.clear(5)
        assertTrue(lib.text.pagesMatching("lithium").isEmpty())
        lib.text.upgrade()
    }

    @Test
    fun aTitleSeveralFilesShareIsABannerAndIsReadAgain() = runBlocking {
        val files = (1..4).map { unique("Download/paper$it.pdf", 300 + it) }
        lib.scan()
        val dao = db.dao()
        val docs = files.map { dao.byKey(it.absolutePath)!! }
        docs.take(3).forEach { dao.update(it.copy(title = "Journal of Affective Disorders", infoAt = it.modified)) }
        dao.update(docs[3].copy(title = "Lithium and the kidney", infoAt = docs[3].modified))
        assertEquals(3, lib.fixSharedTitles())
        assertTrue("journal of affective disorders" in lib.sharedTitles())
        docs.take(3).forEach { d -> val now = dao.byId(d.id)!!; assertNull(now.title); assertEquals("read again", 0L, now.infoAt) }
        assertEquals("Lithium and the kidney", dao.byId(docs[3].id)!!.title)
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

    /** A file as another app (Gmail, WhatsApp) hands it over: a content Uri that is not a path PDFx knows. */
    private fun shared(name: String, bytes: Int): android.net.Uri {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(ctx.cacheDir, "outside-${System.nanoTime()}/$name").apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes) { ((it * 7) % 253).toByte() }) }
        return androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }

    @Test
    fun filesFromOtherAppsBecomeRealFiles() = runBlocking {
        // Handed over: kept as a real file in Documents/PDFx/Received, with its own name.
        val uri = shared("Referral.pdf", 5000)
        val d = lib.receive(uri)!!
        val received = File(root, "Documents/PDFx/Received")
        assertEquals(File(received, "Referral.pdf").absolutePath, d.path)
        assertEquals(5000L, File(d.path!!).length())
        // The same file handed over again is the same copy, not a second one.
        assertEquals(d.path, lib.receive(uri)!!.path)
        assertEquals(1, received.listFiles()!!.count { it.name.endsWith(".pdf") })
        // A different file with the same name gets a name of its own.
        val other = lib.receive(shared("Referral.pdf", 6000))!!
        assertEquals(File(received, "Referral (2).pdf").absolutePath, other.path)

        // Already on the phone (WhatsApp keeps a copy in WhatsApp Documents): that file is used as it is.
        pdf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/Plan.pdf", 3000)
        lib.scan()
        val plan = lib.receive(shared("Plan.pdf", 3000))!!
        assertTrue(plan.path!!.contains("WhatsApp Documents"))

        // Known only through another app: becomes a real file while that app still allows reading it.
        val letter = lib.resolve(shared("Letter.pdf", 4000))
        assertNull(letter.path)
        lib.adoptShared()
        val all = db.dao().all()
        assertTrue("nothing left that only another app can read: ${all.map { it.name to it.path }}", all.none { it.path == null })
        assertTrue(all.any { it.path == File(received, "Letter.pdf").absolutePath })
    }
}
