package com.fadysaeed.pdfx

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.fadysaeed.pdfx.data.Library
import com.fadysaeed.pdfx.data.LibraryDb
import com.fadysaeed.pdfx.data.PageText
import com.fadysaeed.pdfx.data.Saver
import com.fadysaeed.pdfx.engine.Office
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.ui.viewer.PageImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What an outside review of the code found, each shown on a real Android system: a saved file
 * no longer passing for its untouched twin, copies merged only when every byte matches, a file
 * shared in not mistaken for another of the same name and size, two writes to one file never
 * mixing, saving onto a file changed meanwhile, the library's look round and the search index
 * never waiting on each other, old page pictures let go after a save, and a Word file that swells
 * when unpacked stopped in time.
 */
@RunWith(AndroidJUnit4::class)
class FileSafetyOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var root: File
    private lateinit var db: LibraryDb
    private lateinit var lib: Library
    private lateinit var saver: Saver

    @Before
    fun setUp() {
        root = File(ctx.cacheDir, "safety-root-${System.nanoTime()}").apply { mkdirs() }
        db = LibraryDb.inMemory(ctx)
        lib = Library(ctx, db, root, allFilesAccess = { true })
        saver = Saver(lib)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun asset(name: String, to: File): File {
        to.parentFile?.mkdirs()
        assets.open(name).use { i -> to.outputStream().use { i.copyTo(it) } }
        return to
    }

    private fun pagesOf(f: File): Int = Document.openDocument(f.absolutePath).let { d -> d.countPages().also { d.destroy() } }

    private fun bytes(f: File, seed: Int, n: Int = 8192): File {
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(n) { ((it * 31 + seed * 17) % 251).toByte() })
        return f
    }

    @Test
    fun aSavedFileNoLongerPassesForItsUntouchedTwin(): Unit = runBlocking {
        val a = asset("coch.pdf", File(root, "Papers/a.pdf"))
        a.copyTo(File(root, "Papers/b.pdf"))
        lib.scan()
        val docs = db.dao().all().associateBy { it.name }
        assertEquals("twins start with one fingerprint", docs.getValue("a.pdf").fingerprint, docs.getValue("b.pdf").fingerprint)
        val gpm = asset("gpm.pdf", File(ctx.cacheDir, "safety-gpm.pdf"))
        assertTrue(saver.replace(docs.getValue("a.pdf"), Uri.fromFile(a)) { out -> gpm.copyTo(out, true); pagesOf(out) }.isSuccess)
        lib.scan()
        val after = db.dao().all().associateBy { it.name }
        assertNotEquals(after.getValue("a.pdf").fingerprint, after.getValue("b.pdf").fingerprint)
    }

    @Test
    fun copiesAreMergedOnlyWhenEveryByteIsTheSame(): Unit = runBlocking {
        bytes(File(root, "Download/one.pdf"), 1)
        bytes(File(root, "Download/one (2).pdf"), 2)
        lib.scan()
        val docs = db.dao().all().associateBy { it.name }
        val keep = docs.getValue("one.pdf")
        // The same fingerprint by mistake (it reads only parts of big files): still not merged.
        val other = docs.getValue("one (2).pdf").copy(fingerprint = keep.fingerprint)
        db.dao().update(other)
        assertEquals(0, lib.mergeDuplicates(keep, listOf(other)).getOrThrow())
        assertFalse(db.dao().byId(other.id)!!.inTrash)
        assertTrue(File(root, "Download/one (2).pdf").exists())
    }

    @Test
    fun aSharedFileIsTakenForOneOnThePhoneOnlyWhenItIsTheSameInside(): Unit = runBlocking {
        val mine = bytes(File(root, "Download/Report.pdf"), 1)
        lib.scan()
        val outside = File(ctx.cacheDir, "share-${System.nanoTime()}/Report.pdf")
        // Same name, same size, other bytes.
        bytes(outside, 2)
        assertEquals(mine.length(), outside.length())
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", outside)
        val got = lib.receive(uri)!!
        assertNotEquals(mine.absolutePath, got.path)
        assertTrue(File(got.path!!).readBytes().contentEquals(outside.readBytes()))
        // The very same bytes: the one on the phone is used, nothing added.
        mine.copyTo(outside, overwrite = true)
        val same = lib.receive(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", outside))!!
        assertEquals(mine.absolutePath, same.path)
        outside.parentFile?.deleteRecursively()
        Unit
    }

    @Test
    fun twoWritesToOneFileNeverMix(): Unit = runBlocking {
        val f = asset("coch.pdf", File(root, "Papers/a.pdf"))
        lib.scan()
        val d = db.dao().all().single()
        val gpm = asset("gpm.pdf", File(ctx.cacheDir, "safety-gpm.pdf"))
        val form = asset("form.pdf", File(ctx.cacheDir, "safety-form.pdf"))
        // A slow write (scanned pages being read) and a quick one (a save from the open file).
        val slow = async(Dispatchers.IO) { saver.replace(d, Uri.fromFile(f)) { out -> Thread.sleep(1500); gpm.copyTo(out, true); pagesOf(out) } }
        delay(300)
        val quick = async(Dispatchers.IO) { saver.replace(d, Uri.fromFile(f)) { out -> form.copyTo(out, true); pagesOf(out) } }
        assertTrue("the quick one went in", quick.await().isSuccess)
        val r = slow.await()
        assertTrue("the slow one saw the file change and left it", r.isFailure)
        assertEquals(Saver.CHANGED, r.exceptionOrNull()?.message)
        assertTrue("the file is the quick one's, whole", f.readBytes().contentEquals(form.readBytes()))
        assertTrue("no half-written files left beside it", f.parentFile!!.listFiles()!!.none { it.name.endsWith(".pdfx-saving") })
    }

    @Test
    fun savingOntoAFileChangedMeanwhileWritesItAfresh(): Unit = runBlocking {
        val f = asset("coch.pdf", File(root, "Papers/a.pdf"))
        lib.scan()
        val d = db.dao().all().single()
        val pages = pagesOf(f)
        val session = PdfSession.openFile(f.absolutePath)
        try {
            assertEquals(f.length() to f.lastModified(), session.diskStamp)
            // Something else writes the file while it is open.
            val gpm = asset("gpm.pdf", File(ctx.cacheDir, "safety-gpm.pdf"))
            assertTrue(saver.replace(d, Uri.fromFile(f)) { out -> gpm.copyTo(out, true); pagesOf(out) }.isSuccess)
            val r = saver.save(session, db.dao().byId(d.id), Uri.fromFile(f))
            assertTrue("saved: ${r.exceptionOrNull()}", r.isSuccess)
            assertEquals("what was open, whole, not added onto the other file", pages, pagesOf(f))
        } finally {
            session.close()
        }
    }

    @Test
    fun theLibrarysLookRoundAndSearchNeverWaitOnEachOther() {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val body = (0 until 60).map { PageText(it, "words of page $it ".repeat(40)) }
        val indexer = Thread {
            var k = 0
            while (!stop.get()) lib.text.replace(5000L + (k++ % 3), body)
        }
        var failed: Throwable? = null
        val scanner = Thread {
            try {
                runBlocking {
                    repeat(25) { i ->
                        val f = bytes(File(root, "Gone/f$i.pdf"), i)
                        lib.scan()
                        f.delete()
                        // Gone from the phone: forgotten, its words cleared from search.
                        lib.scan()
                    }
                }
            } catch (t: Throwable) { failed = t }
        }
        indexer.start()
        scanner.start()
        scanner.join(90_000)
        stop.set(true)
        indexer.join(10_000)
        if (scanner.isAlive) fail("the look round and the search index waited on each other")
        failed?.let { throw it }
        assertTrue(runBlocking { db.dao().all() }.isEmpty())
    }

    /**
     * A copy of the pages, then a second one taking over from it as after a save; only a weak hold
     * on the first comes back. Made in a function of its own, so nothing in the test's own frame
     * keeps the first alive by accident.
     */
    private fun replaced(session: PdfSession, parent: CoroutineScope): Pair<WeakReference<PageImages>, PageImages> {
        val first = PageImages(session, parent)
        Thread.sleep(500)
        val second = PageImages(session, parent, seed = first)
        assertEquals(first.aspects.size, second.aspects.size)
        first.close()
        return WeakReference(first) to second
    }

    @Test
    fun oldPagePicturesAreLetGoAfterASave() {
        val f = asset("coch.pdf", File(root, "Papers/a.pdf"))
        val session = runBlocking { PdfSession.openFile(f.absolutePath) }
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (ref, second) = replaced(session, parent)
            for (k in 0 until 30) {
                if (ref.get() == null) break
                Runtime.getRuntime().gc()
                System.runFinalization()
                Thread.sleep(100)
            }
            assertNull("the copy from before the save is still held", ref.get())
            second.close()
        } finally {
            parent.cancel()
            session.close()
        }
    }

    @Test
    fun aWordFileThatSwellsWhenUnpackedIsStopped() {
        val docx = File(ctx.cacheDir, "swell-${System.nanoTime()}.docx")
        ZipOutputStream(docx.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            val zeros = ByteArray(1 shl 20)
            repeat(70) { z.write(zeros) }
            z.closeEntry()
        }
        assertTrue("small on disk", docx.length() < 1_000_000)
        try {
            Office.read(docx.absolutePath, "swell.docx")
            fail("it was read")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            assertTrue("stopped with: ${e.message}", e.message?.contains("64 MB") == true)
        } finally {
            docx.delete()
        }
    }
}
