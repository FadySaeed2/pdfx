package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.engine.PageInput
import com.fadysaeed.pdfx.engine.TChar
import com.fadysaeed.pdfx.engine.TShape
import com.fadysaeed.pdfx.engine.TableFinder
import com.fadysaeed.pdfx.engine.TableModel
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/** Pages of real files as MuPDF reads them (tools/dump.py), for the table tests and screenshots. */
object TableFixtures {
    fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("tables/$name")!!

    fun pages(name: String): Map<Int, PageInput> {
        val text = GZIPInputStream(resource(name)).bufferedReader().readText()
        val all = JSONObject(text).getJSONObject("pages")
        val out = HashMap<Int, PageInput>()
        for (key in all.keys()) {
            val p = all.getJSONObject(key)
            val chars = p.getJSONArray("chars")
            val cs = ArrayList<TChar>(chars.length())
            for (i in 0 until chars.length()) {
                val c = chars.getJSONArray(i)
                cs += TChar(
                    c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), c.getDouble(3).toFloat(),
                    c.getDouble(4).toFloat(), c.getDouble(5).toFloat(), c.getInt(6), c.getDouble(7).toFloat(), c.getInt(8) == 1,
                    c.optString(9, ""), c.getInt(10), c.getDouble(11).toFloat(), c.getDouble(12).toFloat(),
                )
            }
            val shapes = p.getJSONArray("shapes")
            val ss = ArrayList<TShape>()
            for (i in 0 until shapes.length()) {
                val s = shapes.getJSONArray(i)
                val kind = s.getString(0)
                val x0 = s.getDouble(1).toFloat(); val y0 = s.getDouble(2).toFloat(); val x1 = s.getDouble(3).toFloat(); val y1 = s.getDouble(4).toFloat()
                when (kind) {
                    "fill" -> ss += TShape("fill", x0, y0, x1, y1, s.getDouble(5).toFloat(), s.getDouble(6).toFloat(), s.getDouble(7).toFloat())
                    "line" -> ss += TShape("line", x0, y0, x1, y1)
                    "box" -> {
                        // A stroked rectangle reaches the app as four lines.
                        ss += TShape("line", x0, y0, x1, y0); ss += TShape("line", x0, y1, x1, y1)
                        ss += TShape("line", x0, y0, x0, y1); ss += TShape("line", x1, y0, x1, y1)
                    }
                }
            }
            out[key.toInt()] = PageInput(p.getDouble("w").toFloat(), p.getDouble("h").toFloat(), cs, ss)
        }
        return out
    }

    private val cache = HashMap<String, Map<Int, PageInput>>()

    /** The tables found on one page of a fixture file ("gpm", "coch", "grid"). */
    fun tables(file: String, page: Int): List<TableModel> =
        TableFinder.find(page, cache.getOrPut(file) { pages("$file-pages.json.gz") }.getValue(page))
}
