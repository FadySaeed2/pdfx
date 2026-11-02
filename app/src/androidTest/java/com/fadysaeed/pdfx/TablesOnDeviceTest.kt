package com.fadysaeed.pdfx

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.engine.TableKind
import com.fadysaeed.pdfx.engine.TableModel
import com.fadysaeed.pdfx.engine.goldenText
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tables on real files, read by the app itself on a phone: every page of the Handbook of Good
 * Psychiatric Management and of a BJPsych Cochrane analysis, and two ruled grids. Each table must
 * come out exactly as the golden files (checked by eye against the pages), no page may give a
 * table the golden files do not have, and in the reflowed text a table's words are the table's,
 * not loose paragraphs.
 */
@RunWith(AndroidJUnit4::class)
class TablesOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun copy(name: String): File {
        val f = File(ctx.cacheDir, name)
        assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun golden(t: TableModel): JSONObject = JSONObject().apply {
        put("number", t.number)
        put("caption", goldenText(t.caption))
        put("kind", t.kind.name.lowercase())
        put("header", JSONArray(t.header.map { goldenText(it) }))
        put("rows", JSONArray(t.rows.map { r -> JSONObject().put("group", r.group).put("cells", JSONArray(r.cells.map { goldenText(it) })) }))
        put("notes", JSONArray(t.notes.map { goldenText(it) }))
    }

    private fun check(file: String) = runBlocking {
        val want = JSONArray(assets.open("$file-tables.json").bufferedReader().readText())
        val byPage = (0 until want.length()).map { want.getJSONObject(it) }.groupBy { it.getInt("page") }
        val figs = JSONArray(assets.open("$file-figures.json").bufferedReader().readText())
        val figsByPage = (0 until figs.length()).map { figs.getJSONObject(it) }.groupBy { it.getInt("page") }
        val pdf = copy("$file.pdf")
        val report = StringBuilder()
        val problems = ArrayList<String>()
        val s = PdfSession.open(ctx, Uri.fromFile(pdf))
        try {
            var slowest = 0L
            for (page in 0 until s.pageCount) {
                val t0 = System.nanoTime()
                val found = s.tables(page)
                val ms = (System.nanoTime() - t0) / 1_000_000
                slowest = maxOf(slowest, ms)
                val expected = byPage[page].orEmpty()
                if (found.isNotEmpty() || expected.isNotEmpty()) report.append("page ${page + 1}: ${found.size} found (${found.joinToString { it.number + " " + it.kind + " " + it.why }}), ${expected.size} expected, $ms ms\n")
                if (found.size != expected.size) { problems += "page ${page + 1}: found ${found.size}, expected ${expected.size}"; continue }
                for ((w, f) in expected.zip(found)) {
                    val got = golden(f)
                    for (key in listOf("number", "caption", "kind", "header", "rows", "notes")) {
                        val a = w.get(key).toString()
                        val b = got.get(key).toString()
                        if (a != b) {
                            problems += "page ${page + 1} $key"
                            report.append("  $key differs\n    want $a\n    got  $b\n")
                        }
                    }
                }
                // Figures: the drawing's outline, the caption and the notes.
                val figures = s.figures(page)
                val wantFigs = figsByPage[page].orEmpty()
                if (figures.isNotEmpty() || wantFigs.isNotEmpty()) report.append("page ${page + 1}: ${figures.size} figures (${figures.joinToString { it.number + " " + it.box }}), ${wantFigs.size} expected\n")
                if (figures.size != wantFigs.size) problems += "page ${page + 1}: found ${figures.size} figures, expected ${wantFigs.size}"
                else for ((w, f) in wantFigs.zip(figures)) {
                    val b = w.getJSONArray("box")
                    val got = listOf(f.box.x0, f.box.y0, f.box.x1, f.box.y1)
                    if ((0 until 4).any { kotlin.math.abs(b.getDouble(it) - got[it]) > 3.0 }) { problems += "page ${page + 1} figure box"; report.append("  box want $b got $got\n") }
                    if (w.getString("caption") != f.caption) { problems += "page ${page + 1} figure caption"; report.append("  caption want ${w.getString("caption")}\n          got  ${f.caption}\n") }
                    if (w.getJSONArray("notes").toString() != JSONArray(f.notes).toString()) { problems += "page ${page + 1} figure notes"; report.append("  notes want ${w.getJSONArray("notes")}\n        got  ${JSONArray(f.notes)}\n") }
                }
                if (wantFigs.isNotEmpty()) {
                    val paras = s.paragraphs(page)
                    if (paras.count { it.figureInfo != null } != figures.size) problems += "page ${page + 1}: reflow shows ${paras.count { it.figureInfo != null }} figures"
                    for (f in figures) {
                        val probe = f.caption.split(' ').drop(2).take(4).joinToString(" ")
                        if (probe.length >= 10 && paras.any { it.figureInfo == null && it.text.contains(probe) }) problems += "page ${page + 1}: figure caption '$probe' also left as loose text"
                    }
                }
                // In reflowed text the table stands in for its words.
                if (expected.isNotEmpty()) {
                    val paras = s.paragraphs(page)
                    val tables = paras.mapNotNull { it.table }.filter { it.kind != TableKind.IMAGE }
                    if (tables.size != found.count { it.kind != TableKind.IMAGE }) problems += "page ${page + 1}: reflow shows ${tables.size} tables"
                    for (t in found) {
                        val cell = t.rows.flatMap { it.cells }.map { it.replace(Regex("\\^[^^]*\\^|⟦[^⟧]*⟧"), "").trim() }.filter { it.length >= 12 }.maxByOrNull { it.length } ?: continue
                        val probe = cell.take(24)
                        if (paras.any { it.table == null && it.text.contains(probe) }) problems += "page ${page + 1}: '$probe' also left as loose text"
                    }
                }
            }
            report.append("slowest page: $slowest ms\n")
        } finally {
            s.close()
            File(shots, "tables_$file.txt").writeText(report.toString() + "\nPROBLEMS\n" + problems.joinToString("\n"))
        }
        assertTrue("$file: ${problems.take(12)}", problems.isEmpty())
    }

    @Test fun handbook() = check("gpm")

    @Test fun cochrane() = check("coch")

    @Test fun ruledGrids() = runBlocking {
        for ((name, page) in listOf("grid-ruled" to 0, "grid-cells" to 1)) {
            val want = JSONArray(assets.open("grid-tables.json").bufferedReader().readText())
            val w = (0 until want.length()).map { want.getJSONObject(it) }.first { it.getInt("page") == page }
            val s = PdfSession.open(ctx, Uri.fromFile(copy("$name.pdf")))
            try {
                val found = s.tables(0)
                assertEquals("$name: one table", 1, found.size)
                val got = golden(found[0])
                for (key in listOf("kind", "header", "rows")) assertEquals("$name $key", w.get(key).toString(), got.get(key).toString())
                val paras = s.paragraphs(0)
                assertTrue("$name: the words around the table stay", paras.count { it.table == null && it.text.isNotBlank() } >= 2)
            } finally {
                s.close()
            }
        }
    }
}
