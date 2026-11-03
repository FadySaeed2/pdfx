package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.data.TopicMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Topics from titles read the way a person would: one per word family, phrases whole, filler out. */
class TopicMathTest {
    private val titles = listOf(
        "Lithium in the treatment of bipolar disorder: a reappraisal",
        "Lithium toxicity in older adults",
        "Lithium and the kidney: a review",
        "Bipolar disorder in New Zealand: prevalence and services",
        "Royal Australian and New Zealand College of Psychiatrists clinical practice guidelines for mood disorders",
        "RANZCP clinical practice guidelines for the management of schizophrenia",
        "Psychiatry in New Zealand: a workforce report",
        "The psychiatrist's role in capacity assessment",
        "Psychiatric emergencies in New Zealand emergency departments",
        "Borderline personality disorder: good psychiatric management",
        "Handbook of good psychiatric management for borderline personality disorder",
        "Pharmacological interventions for co-occurring psychopathology in borderline personality disorder",
        "Antipsychotic prescribing in New Zealand",
        "Antipsychotics and metabolic syndrome",
        "Long-acting injectable antipsychotics in schizophrenia",
        "Clozapine-resistant schizophrenia",
        "Clozapine and myocarditis monitoring",
        "Clozapine rechallenge after myocarditis",
        "ADHD in adults: diagnosis and stimulants",
        "Adult ADHD and substance use",
        "Stimulant prescribing for ADHD in Australia",
        "Depression in primary care",
        "Treatment-resistant depression and ketamine",
        "Depressive symptoms after stroke",
    ).withIndex().associate { (i, t) -> i.toLong() to t }

    @Test fun aTitleNamesTopicsByTheirWordsPluralsAndCapitals() {
        val ix = com.fadysaeed.pdfx.data.Topics.TitleIndex(com.fadysaeed.pdfx.data.Concepts.all)
        assertEquals(setOf("Antipsychotics", "Metabolic health"), ix.topicsIn("Antipsychotics and metabolic syndrome"))
        assertTrue("Bipolar" in ix.topicsIn("Lithium in bipolar disorder"))
        assertTrue("Lithium" in ix.topicsIn("Lithium in bipolar disorder"))
        assertTrue("Suicide and self-harm" in ix.topicsIn("Self harm in young people"))
        assertTrue("Suicide and self-harm" in ix.topicsIn("Deliberate self-harm presentations"))
        assertTrue("ECT and brain stimulation" in ix.topicsIn("ECT for catatonia"))
        // Short capitals only as written, never inside a word.
        assertFalse("ECT and brain stimulation" in ix.topicsIn("The effect of exercise on mood"))
        assertFalse("ECT and brain stimulation" in ix.topicsIn("Direct elect sect"))
        assertTrue("Antidepressants" in ix.topicsIn("SSRIs in pregnancy"))
        assertTrue(ix.topicsIn("Something else entirely").isEmpty())
    }

    @Test fun topicsAreWhatAPersonWouldCallTopics() {
        val found = TopicMath.fromTitles(titles, minDocs = 2, maxShare = 0.5).map { it.label }
        assertTrue(found.toString(), "New Zealand" in found)
        assertFalse("never half a name: $found", "Zealand" in found)
        assertFalse("a psychiatrist's library is all psychiatry: $found", found.any { it.startsWith("Psychiatr", true) })
        assertEquals("one family, one topic: $found", 1, found.count { it.startsWith("Depress", true) })
        assertTrue("the word people use, not a rarer one: $found", "Depression" in found)
        assertEquals(1, found.count { it.startsWith("Antipsychotic", true) })
        assertFalse("filler is not a topic: $found", found.any { it.equals("Good", true) || it.equals("Prescribing", true) })
        assertTrue("Lithium" in found && "Clozapine" in found && "ADHD" in found)
    }

    @Test fun familiesAreWordsThatStartAlikeForMostOfTheirLength() {
        assertTrue(TopicMath.sameFamily("psychiatry", "psychiatrist"))
        assertTrue(TopicMath.sameFamily("antipsychotic", "antipsychotics"))
        assertTrue(TopicMath.sameFamily("depression", "depressive"))
        assertFalse(TopicMath.sameFamily("schizophrenia", "schizoaffective"))
        assertFalse(TopicMath.sameFamily("medication", "medicine"))
    }
}
