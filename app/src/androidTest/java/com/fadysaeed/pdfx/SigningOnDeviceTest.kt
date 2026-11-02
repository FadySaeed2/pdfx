package com.fadysaeed.pdfx

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.data.Certificates
import com.fadysaeed.pdfx.data.SavedSign
import com.fadysaeed.pdfx.data.Signatures
import com.fadysaeed.pdfx.engine.Box
import com.fadysaeed.pdfx.engine.FieldKind
import com.fadysaeed.pdfx.engine.P12Signer
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.engine.SignState
import com.fadysaeed.pdfx.ui.viewer.Reader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Digital signatures for real, with files made the way other software makes them: a letter with
 * an empty signature field, a doctor's certificate from an authority the phone does not know, a
 * file signed elsewhere (pyHanko) and the same file altered by one letter.
 */
@RunWith(AndroidJUnit4::class)
class SigningOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }
    private val tag = "PdfxSigning"

    private fun asset(name: String): File {
        val f = File(ctx.cacheDir, "${System.nanoTime()}-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun bytes(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    /** Saves the way the app does: appended to an exact copy of the original when possible. */
    private suspend fun saveCopy(s: PdfSession, original: File, name: String): File {
        val out = File(ctx.cacheDir, "${System.nanoTime()}-$name")
        val inc = s.canSaveIncrementally
        if (inc) original.copyTo(out, overwrite = true)
        val r = runCatching { s.writeTo(out.absolutePath, inc) }
        Log.i(tag, "$name saved, incremental=$inc, ${out.length()} bytes")
        if (out.exists()) out.copyTo(File(shots, name), overwrite = true) // for a look from outside
        r.getOrThrow()
        return out
    }

    @Test
    fun readsSignaturesMadeElsewhere() = runBlocking<Unit> {
        val s = PdfSession.openFile(asset("signed-elsewhere.pdf").absolutePath)
        try {
            val sigs = s.signatures()
            assertEquals(1, sigs.size)
            val c = sigs[0].check
            Log.i(tag, "elsewhere: $c reason=${sigs[0].reason} place=${sigs[0].location}")
            assertEquals("Dr Elsewhere", c.signer.name)
            assertEquals("Elsewhere Clinic", c.signer.org)
            assertEquals("PDFx Test Authority", c.issuer.name)
            assertTrue("unchanged since signing: ${c.problem}", c.intact)
            assertFalse("the test authority is not one the phone knows", c.trusted)
            assertFalse("issued by an authority, not self-signed", c.selfSigned)
            assertFalse(c.changedAfter)
            assertEquals(SignState.UNVERIFIED, c.state)
            assertEquals("Referral approved", sigs[0].reason)
            assertEquals("Perth", sigs[0].location)
            val t = c.time
            assertNotNull("signing time", t)
            assertTrue("signed recently", abs(t!! - System.currentTimeMillis()) < 400L * 86_400_000L)
        } finally {
            s.close()
        }
        val t = PdfSession.openFile(asset("tampered.pdf").absolutePath)
        try {
            val c = t.signatures().single().check
            Log.i(tag, "tampered: $c")
            assertFalse("one changed letter breaks it", c.intact)
            assertEquals(SignState.BROKEN, c.state)
        } finally {
            t.close()
        }
        // A file with no form has no signatures, and asking costs nothing.
        val plain = PdfSession.openFile(asset("form.pdf").absolutePath)
        try { assertTrue(plain.signatures().isEmpty()) } finally { plain.close() }
    }

    @Test
    fun keepsCertificates() = runBlocking<Unit> {
        val store = Certificates(File(ctx.cacheDir, "certs-${System.nanoTime()}"))
        val mine = store.create("Fady Test", "fady@example.org", "PDFx Clinic", "secret9".toCharArray(), remember = true)
        assertTrue(mine.selfSigned)
        assertTrue(mine.remembered)
        assertEquals("Fady Test", mine.name)
        assertEquals("secret9", String(store.open(mine.id).second!!))
        val doctor = store.add(bytes("doctor.p12"), "secret1".toCharArray(), remember = false)
        assertEquals("Dr Elsewhere", doctor.name)
        assertEquals("elsewhere@example.org", doctor.email)
        assertFalse(doctor.selfSigned)
        assertEquals("PDFx Test Authority", doctor.issuer)
        assertEquals(null, store.open(doctor.id).second)
        assertTrue("a wrong password is refused", runCatching { store.add(bytes("doctor.p12"), "nope".toCharArray(), false) }.isFailure)
        assertEquals(2, store.list().size)
        store.forget(mine.id)
        assertFalse(store.list().first { it.id == mine.id }.remembered)
        store.delete(doctor.id)
        assertEquals(listOf(mine.id), store.list().map { it.id })
    }

    @Test
    fun signsTwiceThenMarksAndKeepsBothValid() = runBlocking<Unit> {
        val store = Certificates(File(ctx.cacheDir, "certs-${System.nanoTime()}"))
        val doctor = store.add(bytes("doctor.p12"), "secret1".toCharArray(), remember = false)
        val mine = store.create("Fady Test", "", "PDFx Clinic", "secret9".toCharArray(), remember = false)

        // 1. Fill the name, then sign in the letter's own signature field with the doctor's certificate.
        val letter = asset("signable.pdf")
        var s = PdfSession.openFile(letter.absolutePath)
        val field = s.fields(0).first { it.kind == FieldKind.SIGNATURE }
        assertTrue(s.setFieldText(0, s.fields(0).first { it.name == "patient_name" }.index, "Jane Citizen"))
        assertTrue(s.signDigitally(0, null, field.index, P12Signer.open(store.open(doctor.id).first, "secret1".toCharArray()), null, "Referral approved", "Perth"))
        val first = saveCopy(s, letter, "signed-once.pdf")
        s.close()

        s = PdfSession.openFile(first.absolutePath)
        var sigs = s.signatures()
        Log.i(tag, "after one: $sigs")
        assertEquals(1, sigs.size)
        assertEquals("Dr Elsewhere", sigs[0].check.signer.name)
        assertTrue("first signature unchanged: ${sigs[0].check.problem}", sigs[0].check.intact)
        assertFalse(sigs[0].check.changedAfter)
        assertEquals("Referral approved", sigs[0].reason)
        assertEquals("Perth", sigs[0].location)
        assertTrue(s.hasSignatures())
        // Other readers look for the value on the field itself, not on the widget that draws it.
        val raw = first.readText(Charsets.ISO_8859_1)
        assertTrue("the field holds the signature", Regex("/T\\(doctor_signature\\)[^>]*/V \\d+ 0 R|/V \\d+ 0 R[^>]*/T\\(doctor_signature\\)").containsMatchIn(raw))
        assertEquals("Jane Citizen", s.fields(0).first { it.name == "patient_name" }.value)

        // 2. A second signer, in a new place, with a drawn signature as its picture.
        val sign = SavedSign("t", false, listOf(listOf(0f to 0.2f, 0.3f to 0f, 0.5f to 0.25f, 0.8f to 0.05f, 1f to 0.22f)), 4f, Color.rgb(20, 40, 140))
        val picture = File(ctx.cacheDir, "sign-${System.nanoTime()}.png")
        FileOutputStream(picture).use { Signatures.drawn(sign, 600)!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val box = Box(320f, 380f, 540f, 450f)
        assertTrue(s.signDigitally(0, box, null, P12Signer.open(store.open(mine.id).first, "secret9".toCharArray()), picture.absolutePath, "Seen", ""))
        val second = saveCopy(s, first, "signed-twice.pdf")
        s.close()

        s = PdfSession.openFile(second.absolutePath)
        sigs = s.signatures()
        Log.i(tag, "after two: $sigs")
        assertEquals(2, sigs.size)
        val doc1 = sigs.first { it.check.signer.name == "Dr Elsewhere" }
        val doc2 = sigs.first { it.check.signer.name == "Fady Test" }
        assertTrue("first still holds", doc1.check.intact)
        assertTrue("first was signed before more was saved", doc1.check.changedAfter)
        assertTrue("second holds", doc2.check.intact)
        assertTrue(doc2.check.selfSigned)
        assertEquals(SignState.UNVERIFIED, doc2.check.state)
        val page = s.render(0, 595)
        save(page, "40-two-signatures.png")
        val inBox = Bitmap.createBitmap(page, box.x0.toInt(), box.y0.toInt(), box.width.toInt(), box.height.toInt())
        assertTrue("the second signature shows on the page", inked(inBox) > 200)

        // 3. A highlight-style mark afterwards: both signatures still hold, and say more came later.
        assertTrue(s.addInk(0, listOf(listOf(60f to 520f, 300f to 520f)), Color.RED, 2f))
        val third = saveCopy(s, second, "signed-then-marked.pdf")
        s.close()
        s = PdfSession.openFile(third.absolutePath)
        sigs = s.signatures()
        Log.i(tag, "after a mark: $sigs")
        assertEquals(2, sigs.size)
        assertTrue("both hold after a mark", sigs.all { it.check.intact && it.check.changedAfter })
        s.close()
    }

    @Test
    fun signsWhenTheFileIsWrittenAfresh() = runBlocking<Unit> {
        val store = Certificates(File(ctx.cacheDir, "certs-${System.nanoTime()}"))
        val mine = store.create("Fresh Writer", "", "", "secret9".toCharArray(), remember = false)
        val f = asset("form.pdf")
        val s = PdfSession.openFile(f.absolutePath)
        assertTrue(s.signDigitally(0, Box(300f, 700f, 520f, 770f), null, P12Signer.open(store.open(mine.id).first, "secret9".toCharArray()), null, "", ""))
        val out = File(ctx.cacheDir, "fresh-${System.nanoTime()}.pdf")
        val r = runCatching { s.writeTo(out.absolutePath, incremental = false) }
        if (out.exists()) out.copyTo(File(shots, "signed-fresh.pdf"), overwrite = true)
        r.getOrThrow()
        s.close()
        val again = PdfSession.openFile(out.absolutePath)
        try {
            val c = again.signatures().single().check
            Log.i(tag, "fresh: $c")
            assertTrue("fresh: ${c.problem}", c.intact)
            assertEquals("Fresh Writer", c.signer.name)
            save(again.render(0, 595), "41-signed-by-name.png")
        } finally {
            again.close()
        }
    }

    @Test
    fun readerSignsAndAsksToSave() = runBlocking<Unit> {
        val store = Certificates(File(ctx.cacheDir, "certs-${System.nanoTime()}"))
        val mine = store.create("Reader Test", "", "", "secret9".toCharArray(), remember = false)
        val f = asset("signable.pdf")
        val s = PdfSession.openFile(f.absolutePath)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reader = Reader(s, s.pageCount, scope)
            var saved: String? = null
            reader.saveNow = { saved = it }
            reader.certs.add(mine)
            reader.pickDigital(true)
            reader.placeSign(0, 400f, 600f)
            val ask = reader.digitalAsk
            assertNotNull("tapping with Digital on opens the sign dialog", ask)
            assertTrue(ask!!.box.contains(400f, 600f))
            assertTrue(reader.signDigitally(ask, P12Signer.open(store.open(mine.id).first, "secret9".toCharArray()), null, "", ""))
            assertEquals("Signed digitally and saved.", saved)
            assertEquals(null, reader.digitalAsk)
            // The letter's empty field: tapping it while it is empty opens the field panel, not the details.
            var waited = 0
            while (!reader.hasForm && waited < 50) { delay(50); waited++ }
            val field = s.fields(0).first { it.kind == FieldKind.SIGNATURE }
            reader.tapField(field)
            assertFalse(reader.signaturesOpen)
        } finally {
            scope.cancel()
            s.close()
        }
    }

    private fun inked(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            if (Color.red(c) + Color.green(c) + Color.blue(c) < 480) n++
        }
        return n
    }

    private fun save(b: Bitmap, name: String) {
        FileOutputStream(File(shots, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
