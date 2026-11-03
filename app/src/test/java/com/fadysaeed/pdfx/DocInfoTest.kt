package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.engine.DocInfo
import com.fadysaeed.pdfx.engine.InfoLine
import com.fadysaeed.pdfx.engine.InfoPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocInfoTest {
    private fun page(vararg lines: Pair<Float, String>): InfoPage {
        var y = 40f
        val ls = lines.map { (size, t) -> InfoLine(t, size, y, y + size).also { y += size * 1.4f } }
        return InfoPage(842f, ls, ls.joinToString("\n") { it.text })
    }

    @Test fun aJournalPaper() {
        val f = DocInfo.find(emptyMap(), listOf(page(
            8f to "Molecular Psychiatry (2023) 28:1234–1245",
            8f to "https://doi.org/10.1038/s41380-023-02145-x",
            18f to "Lithium in the treatment of bipolar disorder:",
            18f to "a reappraisal of the evidence",
            10f to "Gin S. Malhi1,2, Michelle Outhred3 and Tim Boyce4",
            9f to "Lithium remains the gold standard maintenance treatment for bipolar disorder and this review",
            9f to "examines the evidence for its use across the phases of the illness in adults and older people",
        )))
        assertEquals("Lithium in the treatment of bipolar disorder: a reappraisal of the evidence", f.title)
        assertEquals("Malhi et al.", f.author)
        assertEquals(2023, f.year)
        assertEquals("10.1038/s41380-023-02145-x", f.doi)
    }

    @Test fun theFilesOwnDetailsWhenTheyAreReal() {
        val f = DocInfo.find(mapOf("Title" to "Clozapine prescribing guideline", "Author" to "Malhi GS; Bell E", "CreationDate" to "D:20200101"), listOf(page(11f to "Some words")))
        assertEquals("Clozapine prescribing guideline", f.title)
        assertEquals("Malhi et al.", f.author)
        assertEquals(2020, f.year)
    }

    @Test fun wordProcessorLeftoversAreIgnored() {
        val f = DocInfo.find(mapOf("Title" to "Microsoft Word - referral.docx", "Author" to "jsmith", "File" to "referral.pdf"), listOf(page(11f to "Dear Dr Saeed,", 11f to "Thank you for seeing this gentleman")))
        assertNull(f.title)
        assertNull(f.author)
    }

    @Test fun authorsWrittenTheJournalWay() {
        assertEquals("Smith et al.", DocInfo.firstAuthorOf("Smith J, Brown K, Lee T"))
        assertEquals("Malhi", DocInfo.firstAuthorOf("Malhi, Gin S."))
        assertEquals("Malhi", DocInfo.firstAuthorOf("Gin S. Malhi"))
    }

    @Test fun yearsFromTheDoiOrCopyright() {
        assertEquals(2024, DocInfo.yearIn("published in the Cochrane Library in 2018", "10.1192/bjp.2024.172"))
        assertEquals(2024, DocInfo.yearIn("© The Author(s), 2024. Published by Cambridge University Press"))
    }
}
