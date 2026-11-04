package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.engine.Bidi
import org.junit.Assert.assertEquals
import org.junit.Test

/** Arabic lines put in reading order, whichever order the PDF stored the letters in. */
class BidiTest {
    /** Each letter one unit wide, side by side from the left in the order given. */
    private fun line(stored: String, xs: List<Float> = stored.indices.map { it.toFloat() }): String =
        Bidi.logical(stored, BooleanArray(stored.length), BooleanArray(stored.length), xs.toFloatArray(), FloatArray(stored.length) { xs[it] + 1f }, 1f).text

    /** Letters as the engine gave them from a real Arabic book (Yakout type), where each one starts and ends. */
    private fun real(stored: String, x0: FloatArray, x1: FloatArray, size: Float): String =
        Bidi.logical(stored, BooleanArray(stored.length), BooleanArray(stored.length), x0, x1, size).text.trim()

    @Test
    fun aLineStoredAsDrawnLeftToRightReadsRightToLeft() {
        // "عدد 40 مريض" drawn from the left: the last word first, its letters backwards, the number as it is.
        assertEquals("عدد 40 مريض", line("ضيرم 40 ددع"))
    }

    @Test
    fun aLineStoredInReadingOrderStaysAsItIs() {
        val stored = "عدد 40 مريض"
        // Drawn right to left, the number's digits left to right inside it.
        assertEquals(stored, line(stored, listOf(10f, 9f, 8f, 7f, 5f, 6f, 4f, 3f, 2f, 1f, 0f)))
    }

    @Test
    fun aLatinWordInsideStaysTheRightWayRound() {
        // "ملف PDF جديد" drawn from the left: "ديدج", "PDF", "فلم".
        assertEquals("ملف PDF جديد", line("ديدج PDF فلم"))
    }

    @Test
    fun shapedLettersBecomeLetters() {
        // مرحبا as presentation forms, in reading order, drawn right to left.
        assertEquals("مرحبا", line("ﻣﺮﺣﺒﺎ", listOf(4f, 3f, 2f, 1f, 0f)))
    }

    /** Stored as drawn, a bracket that looks like ")" at the right is the opening one. */
    @Test
    fun bracketsInALineStoredAsDrawnAreTurned() {
        // "اختصارا (م)" drawn from the left: "(", "م", ")", space, then the word backwards.
        assertEquals("اختصارا (م)", line("(م) اراصتخا"))
    }

    @Test
    fun anEnglishLineIsLeftAlone() {
        val stored = "Lithium levels, 12 hours"
        assertEquals(stored, line(stored, stored.indices.map { (stored.length - it).toFloat() }))
    }

    @Test
    fun shortVowelsStayWithTheirLetters() {
        // كَتَبَ drawn from the left, each mark with no width straight after its letter.
        val stored = "بَتَكَ"
        val x0 = floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f, 2.5f)
        val x1 = floatArrayOf(1f, 0.5f, 2f, 1.5f, 3f, 2.5f)
        assertEquals("كَتَبَ", Bidi.logical(stored, BooleanArray(6), BooleanArray(6), x0, x1, 1f).text)
    }

    /** A page that gives its brackets and quotes as drawn has them turned round; one that does not keeps them. */
    @Test
    fun aPageThatGivesBracketsAsDrawnHasThemTurned() {
        val drawn = listOf(")هي KRRS(، وجمعياتِ صيادي", "إلى »الملتقى الدولي« الشهير")
        assertEquals(true, Bidi.bracketsAsDrawn(drawn))
        assertEquals(false, Bidi.bracketsAsDrawn(listOf("(هي KRRS)، وجمعياتِ", "«الملتقى»")))
        assertEquals(null, Bidi.bracketsAsDrawn(listOf("سطر بلا أقواس", "Plain (English) line")))
        val lines = drawn.map { com.fadysaeed.pdfx.engine.RawLine(it, BooleanArray(it.length), BooleanArray(it.length), 12f, 0f, 12f) } +
            com.fadysaeed.pdfx.engine.RawLine("See (p. 4)", BooleanArray(10), BooleanArray(10), 12f, 20f, 32f)
        val block = com.fadysaeed.pdfx.engine.RawBlock(com.fadysaeed.pdfx.engine.Box(0f, 0f, 100f, 32f), lines, false)
        val out = Bidi.readBrackets(listOf(block), true).single().lines.map { it.text }
        assertEquals(listOf("(هي KRRS)، وجمعياتِ صيادي", "إلى «الملتقى الدولي» الشهير", "See (p. 4)"), out)
    }

    /** A word gap the file leaves as a gap, narrower than a fifth of the type: بشنِّ الحروب, not بشنِّالحروب. */
    @Test
    fun aNarrowGapBetweenWordsIsASpace() {
        val got = real(
            "\u0645 \u0625\u0645\u0627 \u0628\u0634\u0646\u0651\u0650 \u0627\u0644\u062D\u0631\u0648\u0628 \u0627",
            floatArrayOf(288.28f, 286.05f, 283.66f, 278.06f, 275.48f, 273.27f, 269.16f, 261.46f, 255.28f, 256.6f, 256.6f, 256.6f, 250.64f, 247.17f, 239.97f, 235.09f, 229.89f, 222.63f, 220.4f, 218.0f),
            floatArrayOf(293.8f, 288.28f, 286.05f, 283.66f, 278.06f, 275.48f, 273.27f, 269.16f, 261.46f, 256.6f, 256.6f, 256.6f, 253.04f, 250.64f, 247.17f, 239.97f, 235.6f, 230.82f, 222.63f, 220.4f),
            12.95f,
        )
        assertEquals("م إما بشنِّ الحروب ا", got)
    }

    /** Latin words with numbers inside an Arabic line: J18 and N30 as written. */
    @Test
    fun latinWordsWithNumbersStayAsWritten() {
        val got = real(
            "\u062C\u0627\u0631\u0629N30\u0648J18",
            floatArrayOf(67.52f, 64.94f, 59.54f, 55.64f, 350.86f, 357.97f, 363.72f, 369.49f, 378.86f, 382.85f, 388.61f),
            floatArrayOf(74.72f, 67.52f, 64.94f, 60.17f, 357.97f, 363.72f, 369.48f, 375.2f, 382.85f, 388.61f, 394.36f),
            12.95f,
        )
        assertEquals("J18 وN30 جارة", got)
    }

    /** Two years with a dash between them read right to left: from 1905 to 1914. */
    @Test
    fun aRangeOfYearsReadsFromTheFirst() {
        val got = real(
            "\u0627\u0644\u0641\u0631\u062A\u0629 \u0665\u0660\u0669\u0661\u2013\u0664\u0661\u0669\u0661\u0645 \u0643\u0627\u0646 ",
            floatArrayOf(359.12f, 356.18f, 352.14f, 352.14f, 346.0f, 342.69f, 340.28f, 335.02f, 329.76f, 324.5f, 319.24f, 313.76f, 308.5f, 303.24f, 297.98f, 292.72f, 288.05f, 285.64f, 280.88f, 278.7f, 273.01f, 270.61f),
            floatArrayOf(361.15f, 359.12f, 356.18f, 352.14f, 352.14f, 346.53f, 342.69f, 340.28f, 335.02f, 329.76f, 324.5f, 319.24f, 313.76f, 308.5f, 303.24f, 297.98f, 292.72f, 288.05f, 285.64f, 280.88f, 278.7f, 273.01f),
            10.96f,
        )
        assertEquals("الفترة ١٩٠٥–١٩١٤م كان", got)
    }

    /** The yeh-reh shape and the tanween given after the space that follows the word. */
    @Test
    fun aTanweenAfterTheSpaceStaysOnItsWord() {
        val got = real(
            "\u0631\u0624\u064A\u0629 \u062A\u063A\u064A\u0631\u064A \u064D\u062B\u0648\u0631\u064A ",
            floatArrayOf(378.55f, 373.34f, 369.53f, 365.25f, 362.05f, 357.87f, 352.37f, 348.81f, 348.81f, 341.56f, 338.34f, 338.34f, 334.15f, 329.06f, 324.1f, 316.66f, 313.44f),
            floatArrayOf(383.95f, 379.05f, 373.64f, 369.53f, 365.25f, 362.05f, 357.87f, 352.37f, 348.81f, 348.81f, 341.56f, 338.34f, 338.34f, 334.15f, 329.5f, 324.72f, 316.66f),
            12.95f,
        )
        assertEquals("رؤية تغييرٍ ثوري", got)
    }

    /** Shadda with a vowel given as one shape (a space with no width and the marks); shadda with the two fathas named as the two kasras. */
    @Test
    fun shaddaWithAVowelStaysOnItsLetter() {
        val got = real(
            "\u062A\u062A\u062D\u062F\u0649 \u062C\u062F\u0651\u0650 \u064A\u0651\u064D\u0627",
            floatArrayOf(99.46f, 95.9f, 88.7f, 84.36f, 76.8f, 74.38f, 66.66f, 62.32f, 62.95f, 62.95f, 62.95f, 58.22f, 58.22f, 58.22f, 55.64f),
            floatArrayOf(103.65f, 99.46f, 95.9f, 88.7f, 84.87f, 76.8f, 74.38f, 66.66f, 62.95f, 62.95f, 62.95f, 62.33f, 58.22f, 58.22f, 58.22f),
            12.95f,
        )
        assertEquals("تتحدى جدِّيًّا", got)
    }

    /** إلى: the lam-yeh shape, its yeh with no width at the shape's far edge, given before the lam. */
    @Test
    fun theFarLetterOfAShapeComesAfterTheFirst() {
        val got = real(
            "\u0627\u0644\u0633\u0639\u064A \u0625\u0649\u0644 \u0627\u0644\u0639\u062F\u0627\u0644\u0629 ",
            floatArrayOf(289.94f, 286.47f, 278.76f, 273.27f, 266.36f, 263.94f, 261.54f, 261.54f, 254.3f, 251.88f, 249.48f, 246.01f, 240.52f, 236.18f, 233.79f, 230.31f, 226.03f, 223.62f),
            floatArrayOf(292.34f, 289.94f, 286.47f, 278.76f, 273.27f, 266.36f, 263.94f, 261.54f, 261.54f, 254.3f, 251.88f, 249.48f, 246.01f, 240.52f, 236.18f, 233.79f, 230.31f, 226.03f),
            12.95f,
        )
        assertEquals("السعي إلى العدالة", got)
    }

    /** على and في: shapes of two letters inside a whole stretch of a line. */
    @Test
    fun aWholeStretchOfALineReadsAsPrinted() {
        val got = real(
            "\u0643\u0627\u0646 \u0627\u0644\u064A\u0633\u0627\u0631 \u0627\u0645\u0644\u0627\u0631\u0643\u064A\u0633 \u064A\u0641 \u0645\u0639\u0638\u0645 \u0627\u0644\u0628\u064F\u0644\u062F\u0627\u0646 \u0639\u0649\u0644",
            floatArrayOf(280.88f, 278.7f, 273.01f, 270.61f, 268.59f, 265.65f, 262.63f, 256.11f, 253.93f, 249.36f, 246.96f, 244.94f, 244.94f, 240.9f, 238.72f, 234.15f, 229.92f, 229.92f, 220.03f, 217.62f, 217.62f, 211.52f, 209.11f, 204.38f, 199.73f, 193.43f, 189.31f, 186.91f, 184.88f, 181.94f, 178.93f, 178.91f, 176.5f, 172.82f, 170.8f, 165.11f, 162.71f, 157.72f, 157.72f, 151.65f),
            floatArrayOf(285.64f, 280.88f, 278.7f, 273.01f, 270.61f, 268.59f, 265.65f, 262.63f, 256.11f, 253.93f, 249.36f, 246.96f, 244.94f, 244.94f, 240.9f, 238.72f, 234.68f, 229.92f, 229.92f, 220.03f, 217.62f, 217.62f, 211.52f, 209.11f, 204.38f, 199.73f, 193.43f, 189.31f, 186.91f, 184.88f, 181.94f, 178.91f, 178.91f, 176.5f, 172.82f, 170.8f, 165.11f, 162.71f, 157.72f, 157.72f),
            10.96f,
        )
        assertEquals("كان اليسار الماركسي في معظم البُلدان على", got)
    }
}

/** Pieces of one printed line, read as separate lines or blocks, joined in reading order. */
class BaselinesTest {
    private fun line(t: String, x0: Float, x1: Float, y0: Float = 100f, y1: Float = 112f) =
        com.fadysaeed.pdfx.engine.RawLine(t, BooleanArray(t.length), BooleanArray(t.length), 12f, y0, y1, x0, x1)
    private fun block(vararg ls: com.fadysaeed.pdfx.engine.RawLine) =
        com.fadysaeed.pdfx.engine.RawBlock(com.fadysaeed.pdfx.engine.Box(ls.minOf { it.x0 }, ls.minOf { it.y0 }, ls.maxOf { it.x1 }, ls.maxOf { it.y1 }), ls.toList(), false)

    @Test
    fun arabicBlocksSideBySideJoinRightToLeft() {
        // Drawn from the left: "مريض" then "عدد", each a block of its own.
        val out = com.fadysaeed.pdfx.engine.joinBaselines(listOf(block(line("مريض", 0f, 40f)), block(line("عدد", 50f, 80f))))
        assertEquals(1, out.size)
        assertEquals("عدد مريض", out[0].lines.single().text)
    }

    @Test
    fun piecesOfALineInOneBlockJoin() {
        val out = com.fadysaeed.pdfx.engine.joinBaselines(listOf(block(line("مريض", 0f, 40f), line("عدد", 50f, 80f), line("سطر تاني", 0f, 80f, 130f, 142f))))
        assertEquals(listOf("عدد مريض", "سطر تاني"), out.single().lines.map { it.text })
    }

    @Test
    fun twoColumnsStayApart() {
        val out = com.fadysaeed.pdfx.engine.joinBaselines(listOf(block(line("العمود الأول", 0f, 200f)), block(line("العمود الثاني", 320f, 520f))))
        assertEquals(2, out.size)
    }

    @Test
    fun englishLinesAreLeftAsTheyAre() {
        val b = block(line("First line", 0f, 200f), line("Second line", 0f, 200f, 130f, 142f))
        val out = com.fadysaeed.pdfx.engine.joinBaselines(listOf(b))
        assertEquals(listOf("First line", "Second line"), out.single().lines.map { it.text })
    }
}
