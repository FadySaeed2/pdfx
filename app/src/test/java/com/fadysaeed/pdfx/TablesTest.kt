package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.engine.PageInput
import com.fadysaeed.pdfx.engine.TChar
import com.fadysaeed.pdfx.engine.TShape
import com.fadysaeed.pdfx.engine.TableFinder
import com.fadysaeed.pdfx.engine.TableKind
import com.fadysaeed.pdfx.engine.TableModel
import com.fadysaeed.pdfx.engine.goldenText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.zip.GZIPInputStream

/**
 * The table finder against the golden tables of two real files Fady reads: the Handbook of Good
 * Psychiatric Management (24 tables: captioned, uncaptioned, lists, landscape) and a BJPsych
 * Cochrane secondary analysis (framed, shaded tables, one running over three pages). The page
 * data is what MuPDF gives for those pages (tools/dump.py); the golden files were checked by eye
 * against the pages. Every table must come out exactly as golden, and pages without tables must
 * give none.
 */
@RunWith(RobolectricTestRunner::class)
class TablesTest {

    private fun resource(name: String) = TableFixtures.resource(name)

    private fun pages(name: String) = TableFixtures.pages(name)

    private fun golden(t: TableModel): JSONObject = JSONObject().apply {
        put("page", t.page)
        put("number", t.number)
        put("caption", goldenText(t.caption))
        put("kind", t.kind.name.lowercase())
        put("header", JSONArray(t.header.map { goldenText(it) }))
        put("rows", JSONArray(t.rows.map { r -> JSONObject().put("group", r.group).put("cells", JSONArray(r.cells.map { goldenText(it) })) }))
        put("notes", JSONArray(t.notes.map { goldenText(it) }))
    }

    private fun strip(o: JSONObject): JSONObject = JSONObject(o.toString()).apply { remove("pages") }

    private fun check(file: String) {
        val input = pages("$file-pages.json.gz")
        val expected = JSONArray(resource("$file-tables.json").bufferedReader().readText())
        val byPage = (0 until expected.length()).map { strip(expected.getJSONObject(it)) }.groupBy { it.getInt("page") }
        var count = 0
        for (page in input.keys.sorted()) {
            val found = TableFinder.find(page, input.getValue(page))
            val want = byPage[page] ?: emptyList()
            assertEquals("$file page ${page + 1}: how many tables (found ${found.map { it.number + ":" + it.kind + " " + it.why }})", want.size, found.size)
            for ((w, f) in want.zip(found)) {
                val got = golden(f)
                for (key in listOf("number", "caption", "kind")) assertEquals("$file page ${page + 1} $key", w.get(key).toString(), got.get(key).toString())
                assertEquals("$file page ${page + 1} header", w.getJSONArray("header").toString(), got.getJSONArray("header").toString())
                val wr = w.getJSONArray("rows")
                val gr = got.getJSONArray("rows")
                for (i in 0 until minOf(wr.length(), gr.length())) assertEquals("$file page ${page + 1} row ${i + 1}", wr.get(i).toString(), gr.get(i).toString())
                assertEquals("$file page ${page + 1} rows", wr.length(), gr.length())
                assertEquals("$file page ${page + 1} notes", w.getJSONArray("notes").toString(), got.getJSONArray("notes").toString())
                assert(f.kind != TableKind.IMAGE)
                count++
            }
        }
        assertEquals("$file: every golden table met", expected.length(), count)
    }

    @Test fun handbookTables() = check("gpm")

    @Test fun cochraneTables() = check("coch")

    @Test fun ruledGrids() = check("grid")
}
