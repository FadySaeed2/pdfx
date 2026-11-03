package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.engine.DocKind
import com.fadysaeed.pdfx.engine.KindClues
import com.fadysaeed.pdfx.engine.Kinds
import org.junit.Assert.assertEquals
import org.junit.Test

class KindsTest {
    private fun kind(text: String, pages: Int = 10, title: String? = null, doi: String? = null, fields: Int = 0, landscape: Boolean = false, creator: String = "", name: String = "file.pdf") =
        Kinds.of(KindClues(name, "/sdcard/Download", pages, text, title, doi, fields, landscape, creator))

    private val words = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor ".repeat(20)

    @Test fun papers() {
        assertEquals(DocKind.PAPER, kind(words, 12, doi = "10.1038/s41380-023-02145-x"))
        assertEquals(DocKind.PAPER, kind("Abstract $words Introduction $words Methods $words", 9))
    }
    @Test fun books() {
        assertEquals(DocKind.BOOK, kind("Contents Preface $words", 181))
        assertEquals(DocKind.BOOK, kind(words, 400))
    }
    @Test fun guidelines() {
        assertEquals(DocKind.GUIDELINE, kind(words, 40, title = "RANZCP clinical practice guidelines for mood disorders", doi = "10.1177/0004867420979353"))
    }
    @Test fun letters() {
        assertEquals(DocKind.LETTER, kind("Armadale Mental Health Service\nDear Dr Saeed,\nRe: John Example\nThank you for seeing him. $words\nKind regards", 2))
    }
    @Test fun formsReceiptsSlidesScans() {
        assertEquals(DocKind.FORM, kind(words, 3, fields = 12))
        assertEquals(DocKind.RECEIPT, kind("TAX INVOICE ABN 12 345 678 901 Consultation Total $250.00 GST included", 1))
        assertEquals(DocKind.SLIDES, kind("Lithium update", 30, landscape = true, creator = "Microsoft PowerPoint"))
        assertEquals(DocKind.SCAN, kind("", 3, name = "Scan 2026-11-03 16.40.pdf"))
    }
}
