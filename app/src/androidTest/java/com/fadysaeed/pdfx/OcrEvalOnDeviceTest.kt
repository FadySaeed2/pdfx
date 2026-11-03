package com.fadysaeed.pdfx

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Which engine reads scanned pages best, measured rather than guessed: Google's on-device reader
 * (ML Kit) and Tesseract with its fast and its best English models, on the same pages made to
 * look scanned or photographed (tilted, noisy, unevenly lit, a two-column paper, a table). For
 * each, the share of the page's real words it found and of its words that are real, and the time.
 */
@RunWith(AndroidJUnit4::class)
class OcrEvalOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun norm(w: String) = w.trim().trim { !it.isLetterOrDigit() }.lowercase()

    /** Words found that are on the page (recall) and words given that are real (precision), as multisets. */
    private fun score(truth: List<String>, got: List<String>): Pair<Double, Double> {
        val want = HashMap<String, Int>()
        truth.map(::norm).filter { it.isNotEmpty() }.forEach { want[it] = (want[it] ?: 0) + 1 }
        val total = want.values.sum()
        val g = got.map(::norm).filter { it.isNotEmpty() }
        var hit = 0
        val left = HashMap(want)
        for (w in g) { val n = left[w] ?: 0; if (n > 0) { hit++; left[w] = n - 1 } }
        return hit.toDouble() / total.coerceAtLeast(1) to hit.toDouble() / g.size.coerceAtLeast(1)
    }

    private fun tessDir(model: String): File? {
        val dir = File(ctx.filesDir, "tess-$model")
        val data = File(dir, "tessdata/eng.traineddata")
        if (!data.exists()) {
            val ok = runCatching {
                data.parentFile!!.mkdirs()
                assets.open("tessdata/eng-$model.traineddata").use { i -> data.outputStream().use { i.copyTo(it) } }
            }.isSuccess
            if (!ok) return null
        }
        return dir
    }

    @Test
    fun compareEngines() {
        val truth = JSONArray(assets.open("ocr-truth.json").bufferedReader().readText())
        val report = StringBuilder()
        // ML Kit finds its parts through the manifest of the package that carries it: here the test's.
        runCatching { MlKitContext.initializeIfNeeded(InstrumentationRegistry.getInstrumentation().context) }
            .onFailure { report.append("ML Kit init: $it\n") }
        val recognizer = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
            .onFailure { report.append("ML Kit client: $it\n") }.getOrNull()
        val totals = LinkedHashMap<String, DoubleArray>() // recall, precision, ms
        for (i in 0 until truth.length()) {
            val s = truth.getJSONObject(i)
            val name = s.getString("name")
            val words = s.getJSONArray("words").let { a -> List(a.length()) { a.getString(it) } }
            val bmp = assets.open(s.getString("image")).use { BitmapFactory.decodeStream(it) }
            report.append("== $name: ${words.size} words, ${bmp.width}x${bmp.height}\n")

            if (recognizer != null) runCatching {
                val t0 = System.nanoTime()
                val ml = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
                val mlMs = (System.nanoTime() - t0) / 1e6
                val mlWords = ml.textBlocks.flatMap { b -> b.lines.flatMap { l -> l.elements.map { it.text } } }
                val (r1, p1) = score(words, mlWords)
                report.append(String.format("ML Kit          recall %.3f  precision %.3f  %6.0f ms\n", r1, p1, mlMs))
                totals.getOrPut("ML Kit") { DoubleArray(3) }.let { it[0] += r1; it[1] += p1; it[2] += mlMs }
            }.onFailure { report.append("ML Kit: $it\n") }

            for (model in listOf("fast", "best")) {
                val dir = tessDir(model)
                if (dir == null) { report.append("Tesseract $model: no model\n"); continue }
                runCatching {
                    val tess = TessBaseAPI()
                    try {
                        if (!tess.init(dir.absolutePath, "eng")) {
                            report.append("Tesseract $model: init failed\n")
                        } else {
                            val t0 = System.nanoTime()
                            tess.setImage(bmp)
                            val text = tess.getUTF8Text() ?: ""
                            val ms = (System.nanoTime() - t0) / 1e6
                            val (r, p) = score(words, text.split(Regex("\\s+")))
                            report.append(String.format("Tesseract %-5s recall %.3f  precision %.3f  %6.0f ms\n", model, r, p, ms))
                            totals.getOrPut("Tesseract $model") { DoubleArray(3) }.let { it[0] += r; it[1] += p; it[2] += ms }
                        }
                    } finally {
                        tess.recycle()
                    }
                }.onFailure { report.append("Tesseract $model: $it\n") }
            }
            bmp.recycle()
        }
        report.append("\n== average over ${truth.length()} pages\n")
        for ((k, v) in totals) report.append(String.format("%-16s recall %.3f  precision %.3f  %6.0f ms\n", k, v[0] / truth.length(), v[1] / truth.length(), v[2] / truth.length()))
        File(shots, "ocr_eval.txt").writeText(report.toString())
        // A measurement, not a gate: the report is what matters. It must at least have run.
        assertTrue("no engine ran:\n$report", totals.isNotEmpty())
    }
}
