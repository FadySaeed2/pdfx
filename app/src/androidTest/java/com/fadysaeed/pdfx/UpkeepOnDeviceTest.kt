package com.fadysaeed.pdfx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.data.DocTag
import com.fadysaeed.pdfx.data.Library
import com.fadysaeed.pdfx.data.LibraryDb
import com.fadysaeed.pdfx.data.MyNames
import com.fadysaeed.pdfx.data.PageText
import com.fadysaeed.pdfx.data.ScanWords
import com.fadysaeed.pdfx.data.TextIndexer
import com.fadysaeed.pdfx.data.Upkeep
import com.fadysaeed.pdfx.data.ftsQuery
import com.fadysaeed.pdfx.engine.Arranger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The nightly upkeep on a real Android system, against a throwaway folder standing in for phone
 * storage: extra copies to the trash, tags learnt from the user's own, the backup brought back on a
 * fresh install, and scanned pages read into search without touching the file.
 */
@RunWith(AndroidJUnit4::class)
class UpkeepOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var db: LibraryDb
    private lateinit var lib: Library
    private lateinit var upkeep: Upkeep
    private val prefsName = "upkeep-test-${System.nanoTime()}"

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "upkeep-root-${System.nanoTime()}").apply { mkdirs() }
        ctx.getSharedPreferences("library_titles", 0).edit().clear().commit()
        db = LibraryDb.inMemory(ctx)
        lib = Library(ctx, db, root, allFilesAccess = { true })
        upkeep = Upkeep(ctx, lib, TextIndexer(ctx, lib), MyNames(ctx) { emptyList() }, prefsName = prefsName)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
        ctx.getSharedPreferences(prefsName, 0).edit().clear().commit()
    }

    private fun file(rel: String, seed: Int, bytes: Int = 4096): File =
        File(root, rel).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes) { ((it * 31 + seed * 17) % 251).toByte() }) }

    @Test
    fun anExtraCopyGoesToTheTrashAndTheOriginalKeepsItsTags() = runBlocking {
        val orig = file("Download/Report.pdf", 1)
        orig.copyTo(File(root, "Download/Report (2).pdf"))
        file("Download/Diff.pdf", 2)
        file("Download/Diff (2).pdf", 3)
        file("Download/Solo (2).pdf", 4)
        lib.scan()
        val tag = lib.createTag("Work", 1)
        val copy = db.dao().all().first { it.name == "Report (2).pdf" }
        lib.setTag(copy.id, tag, true)
        assertEquals(1, upkeep.extraCopies())
        val all = db.dao().all()
        assertTrue("the copy is in the trash", all.first { it.id == copy.id }.inTrash)
        val kept = all.first { it.name == "Report.pdf" }
        assertFalse(kept.inTrash)
        assertTrue("the original took the copy's tag", tag in db.dao().tagIdsOf(kept.id))
        assertTrue(orig.exists())
        assertFalse(File(root, "Download/Report (2).pdf").exists())
        assertFalse("a different file stays", all.first { it.name == "Diff (2).pdf" }.inTrash)
        assertFalse("a copy-like name alone stays", all.first { it.name == "Solo (2).pdf" }.inTrash)
    }

    @Test
    fun aNewFileTakesTheTagOfTheFilesMostLikeIt() = runBlocking {
        val lithium = "lithium serum level renal function thyroid monitoring bipolar maintenance toxicity tremor polyuria nephrogenic"
        val lease = "lease tenant landlord bond rent agreement clause property inspection notice vacate tribunal"
        val docs = ArrayList<Pair<String, String>>()
        for (i in 1..5) docs += "Papers/lithium-$i.pdf" to "$lithium study $i cohort${i}x"
        for (i in 1..5) docs += "Home/lease-$i.pdf" to "$lease house $i street${i}y"
        docs += "Papers/other-1.pdf" to "ketamine infusion anaesthesia dissociation dosing ward"
        docs += "Papers/other-2.pdf" to "sleep hygiene insomnia melatonin circadian rhythm"
        docs += "Download/new-lithium.pdf" to "$lithium elderly dose reduction"
        docs += "Download/garden.pdf" to "roses compost mulch pruning seedlings watering"
        docs.forEachIndexed { k, (rel, _) -> file(rel, 10 + k) }
        lib.scan()
        val byName = db.dao().all().associateBy { it.name }
        for ((rel, text) in docs) {
            val d = byName.getValue(rel.substringAfterLast('/'))
            db.dao().update(d.copy(indexedAt = d.modified, infoAt = d.modified, title = text.split(' ').take(4).joinToString(" ")))
            lib.text.replace(d.id, listOf(PageText(0, text)))
        }
        val bipolar = lib.createTag("Bipolar", 2)
        val legal = lib.createTag("Legal", 3)
        for (i in 1..5) {
            db.dao().tag(DocTag(byName.getValue("lithium-$i.pdf").id, bipolar))
            db.dao().tag(DocTag(byName.getValue("lease-$i.pdf").id, legal))
        }
        // Files added from the start count as new for this test.
        ctx.getSharedPreferences(prefsName, 0).edit().putLong("tags_since", 1L).commit()
        assertEquals(1, upkeep.learnTags())
        val fresh = byName.getValue("new-lithium.pdf")
        assertEquals(listOf(bipolar), db.dao().tagIdsOf(fresh.id))
        assertTrue(db.dao().tagIdsOf(byName.getValue("garden.pdf").id).isEmpty())
        assertTrue(db.dao().tagIdsOf(byName.getValue("other-1.pdf").id).isEmpty())
        assertEquals(1, lib.autoTags.pending().size)
        // Taken off, it stays off.
        lib.setTag(fresh.id, bipolar, false)
        assertTrue((fresh.id to bipolar) in lib.autoTags.refused())
        db.dao().update(db.dao().byId(fresh.id)!!.copy(modified = fresh.modified + 1000, indexedAt = fresh.modified + 1000))
        upkeep.learnTags()
        assertTrue(db.dao().tagIdsOf(fresh.id).isEmpty())
    }

    @Test
    fun aFreshInstallFindsItsTagsAndStarsInTheBackup() = runBlocking {
        file("Papers/a.pdf", 1)
        file("Papers/b.pdf", 2)
        lib.scan()
        val a = db.dao().all().first { it.name == "a.pdf" }
        val tag = lib.createTag("Keep", 4)
        lib.setTag(a.id, tag, true)
        db.dao().update(db.dao().byId(a.id)!!.copy(starred = true, reading = 1))
        lib.bookmarks.set(a.id, 3, true)
        ctx.getSharedPreferences("reader", 0).edit().putString("stamps", "SEEN BY FS|4290047488").commit()
        val backup = upkeep.backUp()
        assertTrue(backup.isFile)
        // Reading settings go with it (own stamps among them).
        val saved = org.json.JSONObject(backup.readText()).getJSONObject("prefs").getJSONObject("reader")
        assertEquals("SEEN BY FS|4290047488", saved.getJSONObject("stamps").getString("v"))

        // The app removed and put back: an empty library over the same files.
        val db2 = LibraryDb.inMemory(ctx)
        try {
            val lib2 = Library(ctx, db2, root, allFilesAccess = { true })
            lib2.scan()
            val up2 = Upkeep(ctx, lib2, TextIndexer(ctx, lib2), MyNames(ctx) { emptyList() }, prefsName = "$prefsName-2")
            assertEquals(1, up2.restoreIfNew())
            val back = db2.dao().all().first { it.name == "a.pdf" }
            assertTrue(back.starred)
            assertEquals(1, back.reading)
            assertEquals(listOf("Keep"), db2.dao().tagIdsOf(back.id).map { id -> db2.dao().allTags().first { it.id == id }.name })
            assertEquals(listOf(3), lib2.bookmarks.pages(back.id))
            // Once per install: a library emptied on purpose later is left as it is.
            assertEquals(0, up2.restoreIfNew())
        } finally {
            db2.close()
            ctx.getSharedPreferences("$prefsName-2", 0).edit().clear().commit()
        }
    }

    @Test
    fun scannedPagesAreReadIntoSearchWithoutTouchingTheFile() = runBlocking {
        val photo = File(ctx.cacheDir, "upkeep-ocr-book.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("ocr-book.jpg").use { i -> photo.outputStream().use { i.copyTo(it) } }
        val scan = File(root, "Scans/scan.pdf").apply { parentFile?.mkdirs() }
        assertEquals(1, Arranger.fromPictures(listOf(photo.absolutePath), scan.absolutePath))
        val before = scan.readBytes()
        lib.scan()
        val d = db.dao().all().single()
        TextIndexer(ctx, lib).index(d)
        assertEquals(0, lib.text.pagesIndexed(d.id))
        val (read, left) = upkeep.readScans { false }
        assertEquals(1, read)
        assertEquals(0, left)
        assertEquals(1, lib.text.pagesIndexed(d.id))
        val words = lib.text.firstPages(listOf(d.id)).getValue(d.id).split(Regex("[^\\p{L}]+")).filter { it.length >= 6 }
        assertTrue("words were read: $words", words.size > 20)
        assertTrue(lib.text.search(ftsQuery(words[words.size / 2])!!).any { it.docId == d.id })
        assertEquals(ScanWords.NEXT_DONE, lib.scanWords.all().getValue(d.id).next)
        assertTrue("the file itself is untouched", before.contentEquals(scan.readBytes()))
        assertEquals(0 to 0, upkeep.readScans { false })
    }

    @Test
    fun theMonthlyCheckDropsWhatIsKeptForForgottenFiles() = runBlocking {
        lib.text.replace(999, listOf(PageText(0, "orphan words")))
        lib.bookmarks.set(999, 1, true)
        val (cleared, rebuilt) = upkeep.health()
        assertFalse(rebuilt)
        assertTrue(cleared >= 2)
        assertEquals(0, lib.text.pagesIndexed(999))
    }
}
