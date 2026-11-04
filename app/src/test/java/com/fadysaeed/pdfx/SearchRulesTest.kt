package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.data.Ranking
import com.fadysaeed.pdfx.data.TextMath
import com.fadysaeed.pdfx.engine.keepsHyphen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules behind search (spelling, weighing) and behind reading a page as text (hyphens). */
class SearchRulesTest {
    private fun doc(title: String?, author: String? = null, name: String = "x.pdf", year: Int = 0) =
        Doc(key = name, path = "/$name", uri = "file:///$name", name = name, folder = "/", size = 1, modified = 1, title = title, author = author, year = year)

    @Test fun slipsOfTheFingerAreNear() {
        assertEquals(1, TextMath.distance("lithuim", "lithium"))
        assertEquals(1, TextMath.distance("clozapne", "clozapine"))
        assertEquals(2, TextMath.distance("schizofrenia", "schizophrenia"))
        assertTrue(TextMath.distance("lithium", "clozapine") > 2)
    }

    @Test fun aTitleWeighsMoreThanAName() {
        val words = Ranking.words("lithium toxicity")
        val inTitle = doc("Lithium toxicity in older adults")
        val inName = doc(null, name = "lithium toxicity notes.pdf")
        val a = Ranking.inDetails(words, "lithium toxicity", inTitle, Ranking.fields(inTitle, emptyList()))
        val b = Ranking.inDetails(words, "lithium toxicity", inName, Ranking.fields(inName, emptyList()))
        assertTrue(a.second && b.second)
        assertTrue(a.first > b.first)
        // A word in neither: not every word found.
        val c = doc("Clozapine and myocarditis")
        assertFalse(Ranking.inDetails(words, "lithium toxicity", c, Ranking.fields(c, emptyList())).second)
        // Words inside count by pages and by share of the file.
        assertTrue(Ranking.inside(10, 12) > Ranking.inside(10, 400))
        assertTrue(Ranking.inside(0, 10) == 0.0)
        // A tag counts as a detail.
        val t = doc("Untitled notes")
        assertTrue(Ranking.inDetails(Ranking.words("bipolar"), "bipolar", t, Ranking.fields(t, listOf("Bipolar"))).second)
    }

    @Test fun hyphensThatBelongToTheWordStay() {
        assertTrue(keepsHyphen("is at risk of self-", "endangering behaviour", 0))
        assertTrue(keepsHyphen("a follow-up-", "based plan", 0))
        assertTrue(keepsHyphen("during COVID-", "related closures", 0))
        assertTrue(keepsHyphen("a non-", "adherent patient", 0))
        assertFalse(keepsHyphen("the treat-", "ment was", 0))
        assertFalse(keepsHyphen("psychi-", "atrist", 0))
    }
}
