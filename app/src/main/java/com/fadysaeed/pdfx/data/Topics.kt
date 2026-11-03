package com.fadysaeed.pdfx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A topic that runs through several files: a tag to make, and the files it would go on. */
class TopicSuggestion(val term: String, val docIds: List<Long>, val examples: List<String>)

/** A topic found in titles: its words (each a family key), how it is written most often, and the files whose titles have it. */
class TitleTopic(val keys: List<String>, val label: String, val docs: Set<Long>)

/**
 * Finding topics in titles, as words and phrases a person would call one topic:
 * words of one family count as one ("psychiatry", "psychiatrist", "psychiatric"; "antipsychotic",
 * "antipsychotics"); a phrase stays whole ("New Zealand" never leaves "Zealand" behind; "borderline
 * personality disorder" is one topic, not three); and words that are in a good share of the
 * library, or that only say "this is medicine" ("clinical", "patients", "psychiatry" for a
 * psychiatrist), are not topics at all.
 */
object TopicMath {
    val stop = setOf(
        "the", "and", "for", "with", "from", "into", "of", "in", "on", "to", "a", "an", "by", "at", "or", "as", "is", "are", "be", "its", "their",
        "his", "her", "our", "your", "new", "old", "how", "what", "why", "when", "does", "can", "not", "this", "that", "these", "those", "who",
        "after", "before", "during", "among", "between", "within", "without", "versus", "vs", "via", "per", "about", "over", "under", "towards",
        "toward", "across", "through", "beyond", "than", "more", "less", "most", "best", "first", "second", "third", "part", "parts", "one", "two",
        "study", "studies", "review", "reviews", "systematic", "meta", "analysis", "analyses", "trial", "trials", "randomised", "randomized",
        "controlled", "clinical", "clinician", "clinicians", "patient", "patients", "people", "person", "use", "using", "used", "effect", "effects",
        "role", "case", "cases", "report", "reports", "update", "updated", "guide", "guideline", "guidelines", "guidance", "handbook", "manual",
        "chapter", "edition", "volume", "issue", "journal", "treatment", "treatments", "management", "managing", "care", "health", "healthcare",
        "mental", "disorder", "disorders", "outcome", "outcomes", "evidence", "practice", "practical", "based", "secondary", "primary",
        "approach", "approaches", "assessment", "medicine", "medical", "psychiatry", "psychiatric", "psychiatrist", "psychiatrists",
        "psychological", "psychology", "service", "services", "adult", "adults", "introduction", "overview", "principles", "current",
        "future", "recent", "factors", "associated", "association", "risk", "results", "response", "patterns", "related", "general",
        "international", "national", "college", "good", "high", "low", "early", "late", "long", "short", "major", "minor", "common",
        "rare", "key", "main", "novel", "specific", "non", "multiple", "other", "prescribing", "prescription", "prescriptions",
        "diagnosis", "diagnostic", "prevalence", "symptoms", "intervention", "interventions", "pharmacological", "therapy", "therapies",
        "therapeutic", "monitoring", "screening", "assessing", "comparison", "comparing", "compared", "experience", "experiences",
        "perspective", "perspectives", "implications", "challenges", "issues", "considerations", "recommendations", "statement",
        "consensus", "position", "protocol", "policy", "framework", "model", "models", "programme", "program", "setting", "settings",
    )

    /** First words of names that are one name with the next word: New Zealand, South Australia, United Kingdom. */
    private val nameStarts = setOf("new", "south", "north", "west", "east", "united", "saint", "st", "hong", "sri", "san", "los", "las")

    /** Words in a title, as they are written; a two-word name ("New Zealand") is one word. */
    fun words(title: String): List<String> {
        val raw = title.split(Regex("[^\\p{L}\\p{N}'’-]+")).map { it.trim('\'', '’', '-') }.filter { it.isNotBlank() }
        val out = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            val w = raw[i]
            val next = raw.getOrNull(i + 1)
            if (next != null && w.lowercase() in nameStarts && w.first().isUpperCase() && next.first().isUpperCase()) { out += "$w $next"; i += 2 }
            else { out += w; i++ }
        }
        return out
    }

    /** One key for a word's family: two words share it when they start alike for most of their length. */
    fun familyKeys(all: Collection<String>): Map<String, String> {
        val list = all.map { it.lowercase() }.distinct().sortedBy { it.length }
        val key = HashMap<String, String>()
        // Families share at least their first six letters, so only words that start alike are compared.
        val byStart = HashMap<String, MutableList<String>>()
        for (w in list) {
            val bucket = byStart.getOrPut(w.take(6)) { ArrayList() }
            val near = if (w.length >= 6) bucket.firstOrNull { k -> sameFamily(k, w) } else null
            key[w] = near?.let { key[it]!! } ?: w
            bucket += w
        }
        return key
    }

    fun sameFamily(a: String, b: String): Boolean {
        if (a == b) return true
        val n = a.commonPrefixWith(b).length
        val short = minOf(a.length, b.length)
        return n >= 6 && n >= short * 0.7 && (a.length >= 6 && b.length >= 6)
    }

    /**
     * Topics in [titles] (file id to title): single words and two- and three-word phrases with no
     * filler word in them, in at least [minDocs] files and at most [maxShare] of them.
     */
    fun fromTitles(titles: Map<Long, String>, minDocs: Int = 3, maxShare: Double = 0.3): List<TitleTopic> {
        if (titles.isEmpty()) return emptyList()
        val split = titles.mapValues { words(it.value) }
        val keys = familyKeys(split.values.flatten() + stop)
        val stopKeys = stop.map { keys[it] ?: it }.toSet()
        // A filler word, or one of the same family as a filler ("psychiatrists" with "psychiatry").
        fun ok(w: String) = w.length >= 3 && w.lowercase() !in stop && !w.all { it.isDigit() } && (keys[w.lowercase()] ?: w.lowercase()) !in stopKeys
        val docsOf = HashMap<List<String>, MutableSet<Long>>()
        val writings = HashMap<List<String>, HashMap<String, Int>>()
        for ((id, ws) in split) {
            val seen = HashSet<List<String>>()
            for (n in 1..3) for (i in 0..ws.size - n) {
                val gram = ws.subList(i, i + n)
                if (!gram.all(::ok)) continue
                if (n == 1 && gram[0].length < 4) continue
                val k = gram.map { keys[it.lowercase()] ?: it.lowercase() }
                if (seen.add(k)) {
                    docsOf.getOrPut(k) { HashSet() } += id
                    writings.getOrPut(k) { HashMap() }.merge(gram.joinToString(" "), 1, Int::plus)
                }
            }
        }
        val limit = maxOf(minDocs.toDouble(), titles.size * maxShare)
        var found = docsOf.filter { it.value.size >= minDocs && it.value.size <= limit }
            .map { (k, d) -> TitleTopic(k, label(writings.getValue(k)), d) }
        // A word inside a phrase: keep the phrase when the word hardly comes without it ("Zealand"
        // in "New Zealand"); keep the word when the phrase adds nothing ("Bipolar" and "Bipolar disorder"
        // on the same files).
        val drop = HashSet<List<String>>()
        for (a in found) for (b in found) {
            if (a === b || b.keys.size <= a.keys.size || !b.keys.containsAll(a.keys)) continue
            val outside = a.docs.count { it !in b.docs }
            when {
                outside <= a.docs.size * 0.2 -> drop += a.keys
                jaccard(a.docs, b.docs) >= 0.85 -> drop += b.keys
            }
        }
        found = found.filter { it.keys !in drop }
        return found.sortedByDescending { it.docs.size }
    }

    private fun jaccard(a: Set<Long>, b: Set<Long>): Double {
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter).coerceAtLeast(1)
    }

    /** The way a topic is most often written, capitals kept ("ADHD", "New Zealand"), else the first letter raised. */
    private fun label(ways: Map<String, Int>): String {
        // The word most often used, whatever its capitals; then its most usual capitals.
        val byWord = ways.entries.groupBy({ it.key.lowercase() }, { it }).mapValues { e -> e.value.sumOf { it.value } }
        val word = byWord.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length }).first().key
        val written = ways.entries.filter { it.key.lowercase() == word }.maxByOrNull { it.value }!!.key
        return written.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Topics worth a tag, found in the files themselves: the subjects of a psychiatrist's reading (a
 * starting list) and topics that keep coming back in the files' titles (see [TopicMath]). A file
 * counts as being about a topic when its title names it, or when it comes up on a fair share of its
 * pages; a passing mention does not count.
 */
object Topics {
    private val seeds = listOf(
        "ADHD", "Bipolar", "Lithium", "Clozapine", "Schizophrenia", "Psychosis", "Depression", "Anxiety", "PTSD", "Trauma",
        "Autism", "Borderline personality", "Dementia", "Delirium", "Perinatal", "Pregnancy", "Eating disorders",
        "Anorexia", "Substance use", "Alcohol", "Opioids", "Methamphetamine", "Cannabis", "Ketamine", "ECT", "Suicide",
        "Self-harm", "Antipsychotics", "Antidepressants", "SSRIs", "Benzodiazepines", "CBT", "DBT", "Psychotherapy", "Sleep",
        "OCD", "Catatonia", "Serotonin syndrome", "Neuroleptic malignant syndrome", "QTc", "Metabolic", "Child and adolescent",
        "Older people", "Forensic", "Mental Health Act", "Capacity", "Agitation", "Long-acting injectables",
        "Tardive dyskinesia", "Valproate", "Lamotrigine", "Psychopharmacology", "Supervision", "Stimulants", "Melatonin",
    )

    /** A label's words as family keys, to tell a seed and a title topic apart or alike. */
    private fun keysOf(label: String, keys: Map<String, String>) =
        TopicMath.words(label).map { it.lowercase() }.filter { it !in TopicMath.stop }.map { keys[it] ?: it }.toSet()

    suspend fun suggest(lib: Library, existing: Set<String>, max: Int = 40): List<TopicSuggestion> = withContext(Dispatchers.IO) {
        val docs = lib.dao.all().filter { !it.inTrash && it.isFile }
        if (docs.isEmpty()) return@withContext emptyList()
        val byId = docs.associateBy { it.id }
        val titles = docs.mapNotNull { d -> d.title?.let { d.id to it } }.toMap()
        val fromTitles = TopicMath.fromTitles(titles).take(40)
        val keys = TopicMath.familyKeys(TopicMath.words((seeds + titles.values + existing).joinToString(" ")))
        val taken = existing.map { keysOf(it, keys) }.toMutableList()
        // Seeds first (their spelling is the one people use), then title topics they do not already cover.
        val labels = ArrayList<String>()
        for (l in seeds + fromTitles.map { it.label }) {
            val k = keysOf(l, keys)
            if (k.isEmpty() || taken.any { it == k }) continue
            taken += k
            labels += l
        }
        val out = ArrayList<TopicSuggestion>()
        for (label in labels) {
            val k = keysOf(label, keys)
            val pages = lib.text.pagesMatching(label)
            val ids = docs.filter { d ->
                val title = d.title ?: d.name.removeSuffix(".pdf")
                val inTitle = keysOf(title, keys).containsAll(k)
                val hit = pages[d.id] ?: 0
                inTitle || (d.pages in 1..3 && hit >= 1) || (hit >= 2 && hit >= d.pages * 0.15)
            }.map { it.id }
            // On almost every file, it describes the library, not a topic in it.
            if (ids.size < 2 || (docs.size >= 20 && ids.size > docs.size * 0.4)) continue
            out += TopicSuggestion(label, ids, ids.take(3).mapNotNull { id -> byId[id]?.let { it.title ?: it.name.removeSuffix(".pdf") } })
        }
        // Two suggestions on the very same files say the same thing: the first (the bigger, or a seed) stays.
        val sorted = out.sortedByDescending { it.docIds.size }
        sorted.filterIndexed { i, t ->
            sorted.take(i).none { big -> big.docIds.toSet() == t.docIds.toSet() }
        }.take(max)
    }
}
