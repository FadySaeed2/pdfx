package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.data.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clear name stays what the file is called; only a vague one gives way to the title inside. */
class NamesTest {
    @Test
    fun clearNamesAreKept() {
        for (n in listOf(
            "Lithium guidelines 2024", "تقرير الأشعة", "Lithium_guidelines_final_v2", "Report", "Report (2)", "Final report",
            "Fady CV", "Lithium", "Malhi et al 2023 lithium", "تقرير", "Bipolar-disorder-review", "Taranaki_house_inspection",
            "Rental agreement signed", "الأناركية_والثورة_والإنسان", "KidsPlatformerDesign",
        )) assertTrue("clear: $n", Names.isClear(n))
    }

    @Test
    fun vagueNamesAreKnown() {
        for (n in listOf(
            "s41380-023-02145-x", "document(3)", "Scan 2024-05-01", "IMG_20231014_123456", "1-s2.0-S0165032721002123-main",
            "WhatsApp Document 2024-03-01 at 10.15.32", "CamScanner 05-03-2024 10.22", "PXL_20240101_123456789",
            "Screenshot_20240101-123456_Chrome", "Untitled document", "document", "new 1", "IMG-20240301-WA0012",
            "Scanned_20240501", "12345", "download (1)", "mmc1", "nihms-1234567", "fpsyt-14-1123456", "bjp.2024.172",
        )) assertFalse("vague: $n", Names.isClear(n))
    }

    @Test
    fun theTitleInsideShowsOnlyForAVagueName() {
        fun doc(name: String) = Doc(key = name, path = "/x/$name", uri = "file:///x/$name", name = name, folder = "/x", size = 1, modified = 1, title = "Lithium in bipolar disorder")
        assertEquals("Lithium in bipolar disorder", Names.shown(doc("s41380-023-02145-x.pdf")))
        assertEquals("تقرير الأشعة", Names.shown(doc("تقرير الأشعة.pdf")))
        assertEquals("document(3)", Names.shown(doc("document(3).pdf").copy(title = null)))
    }
}
