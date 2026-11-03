package com.fadysaeed.pdfx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A topic that runs through several files: a tag to make, and the files it would go on. */
class TopicSuggestion(val term: String, val docIds: List<Long>, val examples: List<String>)

/**
 * Topics worth a tag, found in the files themselves: the subjects of a psychiatrist's reading (a
 * starting list) and words that keep coming back in the files' titles. A file counts as being about
 * a topic when its title names it, or when it comes up on a fair share of its pages; a passing
 * mention does not count.
 */
object Topics {
    private val seeds = listOf(
        "ADHD", "Bipolar", "Lithium", "Clozapine", "Schizophrenia", "Psychosis", "Depression", "Anxiety", "PTSD", "Trauma",
        "Autism", "Borderline", "Personality disorder", "Dementia", "Delirium", "Perinatal", "Pregnancy", "Eating disorder",
        "Anorexia", "Substance use", "Alcohol", "Opioid", "Methamphetamine", "Cannabis", "Ketamine", "ECT", "Suicide",
        "Self-harm", "Antipsychotic", "Antidepressant", "SSRI", "Benzodiazepine", "CBT", "DBT", "Psychotherapy", "Sleep",
        "Insomnia", "OCD", "Catatonia", "Serotonin syndrome", "Neuroleptic malignant syndrome", "QTc", "Metabolic", "Child and adolescent",
        "Older adults", "Forensic", "Mental Health Act", "Capacity", "Risk assessment", "Agitation", "Long-acting injectable",
        "Tardive dyskinesia", "Valproate", "Lamotrigine", "Psychopharmacology", "Supervision", "Stimulant", "Melatonin",
    )
    private val stop = setOf(
        "the", "and", "for", "with", "from", "into", "of", "in", "on", "to", "a", "an", "by", "at", "or", "as", "is", "are", "be", "its", "their",
        "study", "review", "systematic", "meta", "analysis", "trial", "randomised", "randomized", "controlled", "clinical", "patients", "people",
        "adults", "people's", "use", "using", "effect", "effects", "role", "case", "report", "update", "guide", "guideline", "guidelines",
        "handbook", "manual", "chapter", "part", "edition", "volume", "new", "versus", "vs", "among", "between", "within", "after", "during",
        "treatment", "management", "care", "health", "mental", "disorder", "disorders", "outcomes", "evidence", "practice", "based",
        "secondary", "primary", "approach", "towards", "toward", "how", "what", "why", "when", "does", "can", "not", "an", "this", "that",
    )

    suspend fun suggest(lib: Library, existing: Set<String>, max: Int = 40): List<TopicSuggestion> = withContext(Dispatchers.IO) {
        val docs = lib.dao.all().filter { !it.inTrash && it.isFile }
        if (docs.isEmpty()) return@withContext emptyList()
        val byId = docs.associateBy { it.id }
        // Words and pairs of words that come back in titles.
        val counts = HashMap<String, MutableSet<Long>>()
        val shown = HashMap<String, String>()
        for (d in docs) {
            val t = d.title ?: continue
            val words = t.split(Regex("[^\\p{L}\\p{N}'-]+")).filter { it.isNotBlank() }
            val seen = HashSet<String>()
            for (i in words.indices) {
                val w = words[i].trim('\'', '-')
                if (w.length >= 4 && w.lowercase() !in stop && !w.all { it.isDigit() }) seen += w
                if (i + 1 < words.size) {
                    val a = words[i].lowercase(); val b = words[i + 1].lowercase()
                    if (a !in stop && b !in stop && a.length >= 3 && b.length >= 3) seen += "${words[i]} ${words[i + 1]}"
                }
            }
            for (w in seen) { counts.getOrPut(w.lowercase()) { HashSet() } += d.id; shown.putIfAbsent(w.lowercase(), w) }
        }
        val fromTitles = counts.filter { it.value.size >= 3 && it.value.size <= docs.size * 0.5 }.keys.sortedByDescending { counts[it]!!.size }.take(30)
        val terms = (seeds.map { it.lowercase() } + fromTitles).distinct().filter { it !in existing }
        val out = ArrayList<TopicSuggestion>()
        for (term in terms) {
            val pages = lib.text.pagesMatching(term)
            val ids = docs.filter { d ->
                val inTitle = d.title?.contains(term, ignoreCase = true) == true || d.name.contains(term, ignoreCase = true)
                val hit = pages[d.id] ?: 0
                inTitle || (d.pages in 1..3 && hit >= 1) || (hit >= 2 && hit >= d.pages * 0.15)
            }.map { it.id }
            if (ids.size < 2) continue
            val label = seeds.firstOrNull { it.equals(term, true) } ?: shown[term]?.replaceFirstChar { it.uppercase() } ?: term
            out += TopicSuggestion(label, ids, ids.take(3).mapNotNull { id -> byId[id]?.let { it.title ?: it.name.removeSuffix(".pdf") } })
        }
        // A topic whose files are all inside a bigger one's ("Lithium" in "Lithium toxicity") says little more.
        out.sortedByDescending { it.docIds.size }.filterIndexed { i, t ->
            out.sortedByDescending { it.docIds.size }.take(i).none { big -> t.term.contains(big.term, true) && big.docIds.containsAll(t.docIds) }
        }.take(max)
    }
}
