package com.fadysaeed.pdfx

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.data.Library
import com.fadysaeed.pdfx.data.LibraryDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A file renamed in PDFx stays one entry in the phone's own list of files (what Samsung's My
 * Files and every other app show): under its new name, with nothing left under the old one.
 */
@RunWith(AndroidJUnit4::class)
class MediaIndexOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = LibraryDb.inMemory(ctx)
    private val storage = Environment.getExternalStorageDirectory()
    private val made = ArrayList<Uri>()

    @After
    fun tearDown() {
        made.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        db.close()
    }

    /** A PDF put on the phone the way apps do it now, through the phone's list of files. */
    private fun onPhone(name: String): File {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/PDFxIndexTest${System.nanoTime() % 100000}/")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)!!
        made += uri
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write("%PDF-1.4\n% PDFx index test\n".toByteArray() + ByteArray(2048) { 32 }) }
        val path = ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use { c -> c.moveToFirst(); c.getString(0) }
        return File(path)
    }

    @Test
    fun aRenamedFileIsOneEntryUnderItsNewName() = runBlocking {
        val f = onPhone("Clinic letter draft.pdf")
        assertTrue(f.isFile)
        val lib = Library(ctx, db, storage, allFilesAccess = { true })
        assertNotNull("the phone lists it", lib.media.entryOf(f.absolutePath))
        val d = lib.resolve(Uri.fromFile(f))
        val renamed = lib.rename(d, "Clinic letter final").getOrThrow()
        val now = File(renamed.path!!)
        assertEquals("Clinic letter final.pdf", now.name)
        assertTrue("the file is there under its new name", now.isFile)
        assertFalse("and not under the old one", f.exists())
        assertNull("no dead entry left under the old name", lib.media.entryOf(f.absolutePath))
        assertNotNull("the phone lists it under the new name", lib.media.entryOf(now.absolutePath))
    }
}
