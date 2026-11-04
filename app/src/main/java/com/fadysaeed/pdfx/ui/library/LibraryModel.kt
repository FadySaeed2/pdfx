package com.fadysaeed.pdfx.ui.library

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.data.DocTag
import com.fadysaeed.pdfx.data.Library
import com.fadysaeed.pdfx.data.Tag
import com.fadysaeed.pdfx.data.TagCount
import com.fadysaeed.pdfx.data.TextHit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class Section(val label: String) {
    RECENT("Recent"), SHELVES("Shelves"), ALL("All files"), STARRED("Starred"), HIGHLIGHTS("Highlights"), FOLDERS("Folders"), TAGS("Tags"), TRASH("Trash")
}

enum class SortBy(val label: String) { OPENED("Last opened"), NAME("Name"), MODIFIED("Date changed"), SIZE("Size") }

data class Item(val doc: Doc, val tags: List<Tag>)
data class FolderRow(val path: String?, val label: String, val count: Int, val pinned: Boolean = false)

/** What the big tidy can look at, with how many of each. */
data class TidyCounts(
    val duplicates: Int = 0,
    /** Files with code names that a real title would name better. */
    val codeNames: Int = 0,
    /** Files lying in Downloads, WhatsApp and the like that have a shelf to go to. */
    val loose: Int = 0,
    /** Files that do not open. */
    val broken: Int = 0,
    /** Scans whose words have not been read. */
    val bareScans: Int = 0,
) {
    val total get() = duplicates + codeNames + loose + broken + bareScans
}
data class DocResult(
    val doc: Doc, val nameMatch: Boolean, val hits: List<TextHit>, val totalHits: Int,
    val score: Double = 0.0,
    /** The user's own highlights and notes in it that have the words. */
    val marks: List<com.fadysaeed.pdfx.data.MarkRow> = emptyList(),
)

/** A search's results, and the spelling it used when the one typed found nothing. */
data class SearchOut(val results: List<DocResult>, val corrected: String? = null)

/** What search results are narrowed to: a kind of file, how recent, a tag. */
data class SearchFilter(val kind: String? = null, val years: Int? = null, val tagId: Long? = null) {
    val any get() = kind != null || years != null || tagId != null
}

/** A way to narrow the results, with how many it would leave. */
data class Facet(val label: String, val count: Int, val filter: SearchFilter, val on: Boolean)

/** A file's highlights and notes, for the Highlights page. */
data class MarkGroup(val doc: Doc, val marks: List<com.fadysaeed.pdfx.data.MarkRow>)

/** Where in the library the user is. */
data class Place(
    val section: Section = Section.RECENT,
    val folder: String? = null,
    val inFolder: Boolean = false,
    val tagId: Long? = null,
    /** A shelf open in Shelves: a [Shelf] name. */
    val shelf: String? = null,
)

/** The shelves: three for reading, then one for each kind of file. */
enum class Shelf(val label: String, val note: String) {
    READING("Reading now", "Opened in the last month, not finished"),
    TO_READ("To read", "Marked to read"),
    DONE("Done", "Finished, or marked done"),
    PAPER("Papers", ""), BOOK("Books", ""), GUIDELINE("Guidelines", ""), LETTER("Letters", ""), FORM("Forms", ""),
    RECEIPT("Receipts and bills", ""), SLIDES("Slides", ""), SCAN("Scans", ""), OTHER("Everything else", ""),
}

data class ShelfRow(val shelf: Shelf, val count: Int)

data class Sort(val by: SortBy = SortBy.OPENED, val descending: Boolean = true)

data class LibraryUi(
    val place: Place = Place(),
    val title: String = "Recent",
    val items: List<Item> = emptyList(),
    val folders: List<FolderRow> = emptyList(),
    val tags: List<TagCount> = emptyList(),
    val allTags: List<Tag> = emptyList(),
    /** Tag ids on each document. */
    val docTags: Map<Long, Set<Long>> = emptyMap(),
    val query: String = "",
    val results: List<DocResult>? = null,
    val sort: Sort = Sort(),
    val hasAccess: Boolean = true,
    val unindexed: Int = 0,
    val loaded: Boolean = false,
    val shelves: List<ShelfRow> = emptyList(),
    /** Every folder that holds files, for moving and copying to. */
    val allFolders: List<FolderRow> = emptyList(),
    /** Files picked to act on together. */
    val picked: Set<Long> = emptySet(),
    /** Where the open folder sits: (name, path) from the top of the phone down. */
    val crumbs: List<Pair<String, String>> = emptyList(),
    val tidy: TidyCounts = TidyCounts(),
    /** The same file more than once (by fingerprint), each group its copies; the one to keep first. */
    val duplicates: List<List<Doc>> = emptyList(),
    /** The spelling search used instead of the one typed, when that found nothing. */
    val searchNote: String? = null,
    val filter: SearchFilter = SearchFilter(),
    /** Ways to narrow the results (kinds, years, tags), for chips above them. */
    val facets: List<Facet> = emptyList(),
    /** Files shown as a grid of first pages rather than a list. */
    val grid: Boolean = false,
    /** Every file's highlights and notes, on the Highlights page. */
    val marks: List<MarkGroup> = emptyList(),
)

data class Notice(val text: String, val undo: (() -> Unit)? = null, val id: Long = System.nanoTime())

private data class Raw(val docs: List<Doc>, val links: List<DocTag>, val tags: List<Tag>, val counts: List<TagCount>, val unindexed: Int)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryModel(app: Application) : AndroidViewModel(app) {
    private val pdfx = app as PdfxApp
    val library: Library = pdfx.library
    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val place = MutableStateFlow(Place())
    private val sorts = MutableStateFlow(loadSorts())
    private val query = MutableStateFlow("")
    private val picks = MutableStateFlow<Set<Long>>(emptySet())
    private val pins = MutableStateFlow(prefs.getStringSet("pinned_folders", emptySet()).orEmpty().toSet())
    private val access = MutableStateFlow(library.hasAllFilesAccess())
    val notice = MutableStateFlow<Notice?>(null)
    /** Goes up when something the list depends on changed outside the database (names left as they are). */
    private val bump = MutableStateFlow(0)

    private val raw = combine(
        library.dao.observeAll(), library.dao.observeDocTags(), library.dao.observeTags(),
        library.dao.observeTagCounts(), library.dao.observeUnindexed(),
    ) { d, l, t, c, u -> Raw(d, l, t, c, u) }

    private val filter = MutableStateFlow(SearchFilter())
    private val grid = MutableStateFlow(prefs.getBoolean("grid", false))

    private val results = combine(query.debounce(180).distinctUntilChanged(), library.dao.observeUnindexed()) { q, _ -> q }
        .mapLatest { q -> if (q.isBlank()) null else search(q) }

    /** The Highlights page's marks, read only while it is open. */
    private val marksFlow = combine(place, library.dao.observeAll()) { p, docs -> (p.section == Section.HIGHLIGHTS) to docs }
        .mapLatest { (on, docs) -> if (!on) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { groupMarks(library.marks.all(), docs) } }

    private data class Extras(val res: SearchOut?, val access: Boolean, val picks: Set<Long>)
    private data class View(val filter: SearchFilter, val grid: Boolean, val marks: List<MarkGroup>)

    private val extras = combine(results, access, picks, pins, bump) { r, a, pk, _, _ -> Extras(r, a, pk) }
    private val view = combine(filter, grid, marksFlow) { f, g, m -> View(f, g, m) }

    val ui: StateFlow<LibraryUi> = combine(combine(raw, place, sorts) { r, p, s -> Triple(r, p, s) }, query, extras, view) { (r, p, s), q, x, v ->
        build(r, p, s[p.section] ?: defaultSort(p.section), q, x.res, x.access, v)
            .copy(picked = x.picks)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUi())

    private fun groupMarks(rows: List<com.fadysaeed.pdfx.data.MarkRow>, docs: List<Doc>): List<MarkGroup> {
        val byId = docs.filter { !it.inTrash }.associateBy { it.id }
        return rows.groupBy { it.doc }.mapNotNull { (id, ms) -> byId[id]?.let { MarkGroup(it, ms.sortedBy { m -> m.page }) } }
            .sortedWith(compareByDescending<MarkGroup> { it.doc.lastOpened }.thenBy { titleOf(it.doc).lowercase() })
    }

    fun setFilter(f: SearchFilter) { filter.value = f }

    fun setGrid(on: Boolean) { grid.value = on; prefs.edit().putBoolean("grid", on).apply() }

    fun refresh() {
        val had = access.value
        access.value = library.hasAllFilesAccess()
        // Rescan straight away when access has just been granted.
        pdfx.refresh(force = access.value && !had)
    }

    fun go(section: Section) { place.value = Place(section); picks.value = emptySet() }
    fun openFolder(path: String?) { place.value = Place(Section.FOLDERS, folder = path, inFolder = true) }
    fun openTag(id: Long) { place.value = Place(Section.TAGS, tagId = id) }
    fun openShelf(s: Shelf) { place.value = Place(Section.SHELVES, shelf = s.name) }
    /** Back inside the library: out of a folder or tag first. True when it handled the back press. */
    fun back(): Boolean {
        val p = place.value
        return when {
            picks.value.isNotEmpty() -> { picks.value = emptySet(); true }
            query.value.isNotEmpty() -> { query.value = ""; true }
            p.inFolder -> {
                // Up one folder; out of the folders from the top.
                val up = p.folder?.let { File(it).parentFile }
                place.value = if (up != null && up.absolutePath.startsWith(library.root.absolutePath) && up.absolutePath != library.root.absolutePath) Place(Section.FOLDERS, folder = up.absolutePath, inFolder = true) else Place(Section.FOLDERS)
                true
            }
            p.tagId != null -> { place.value = Place(Section.TAGS); true }
            p.shelf != null -> { place.value = Place(Section.SHELVES); true }
            p.section != Section.RECENT -> { place.value = Place(); true }
            else -> false
        }
    }

    fun setQuery(q: String) { query.value = q; if (q.isBlank()) filter.value = SearchFilter() }

    fun setSort(by: SortBy) {
        val sec = place.value.section
        val cur = sorts.value[sec] ?: defaultSort(sec)
        val next = if (cur.by == by) cur.copy(descending = !cur.descending) else Sort(by, descending = by != SortBy.NAME)
        sorts.value = sorts.value + (sec to next)
        prefs.edit().putString("sort_${sec.name}", "${next.by.name}:${next.descending}").apply()
    }

    // --------------------------------------------------------- picking many

    fun pick(d: Doc) { picks.value = if (d.id in picks.value) picks.value - d.id else picks.value + d.id }
    fun pickAll() { picks.value = ui.value.items.map { it.doc.id }.toSet() }
    fun clearPicks() { picks.value = emptySet() }
    private fun pickedDocs(): List<Doc> { val ids = picks.value; return ui.value.items.map { it.doc }.filter { it.id in ids } }

    /** Does [op] to each picked file, then says how it went: "Moved 12 files", or how many could not be. */
    private fun many(done: String, op: suspend (Doc) -> Result<*>) = launch {
        val docs = pickedDocs()
        picks.value = emptySet()
        var ok = 0
        var first: Throwable? = null
        for (d in docs) op(d).onSuccess { ok++ }.onFailure { if (first == null) first = it }
        val failed = docs.size - ok
        say("$done ${if (ok == 1) "1 file" else "$ok files"}." + if (failed > 0) " $failed could not be: ${first?.message ?: "they may have gone"}" else "")
    }

    fun moveMany(dir: File) = many("Moved to ${library.folderLabel(dir.absolutePath)}:") { library.move(it, dir) }
    fun copyMany(dir: File) = many("Copied to ${library.folderLabel(dir.absolutePath)}:") { library.copy(it, dir) }
    fun trashMany() = many("Moved to the trash:") { library.trash(it) }
    fun starMany() = many("Starred") { d -> runCatching { library.setStar(d, true) } }
    fun tagMany(tag: Tag) = many("Tagged ${tag.name}:") { d -> runCatching { library.setTag(d.id, tag.id, true) } }
    fun readingMany(state: Int) = many(if (state == 1) "On the To read shelf:" else "Marked done:") { d ->
        runCatching { library.dao.byId(d.id)?.let { library.dao.update(it.copy(reading = state)) } }
    }
    /** The picked files, for sharing or merging; picking ends. */
    fun takePicked(): List<Doc> = pickedDocs().also { picks.value = emptySet() }

    // --------------------------------------------------------- the big tidy

    /** Files whose code names a real title would name better, with the name each would get. */
    suspend fun renameSuggestions(): List<Pair<Doc, String>> = renamesOf(library.dao.all())

    /**
     * The same list the tidy counts: files with code names that have a real title, less those left
     * as they are before, and less any name two different files would get (a title read wrongly).
     */
    private fun renamesOf(docs: List<Doc>): List<Pair<Doc, String>> {
        val shared = library.sharedTitles()
        val kept = library.namesKept()
        val all = docs.filter { !it.inTrash && it.isFile && it.id !in kept && it.title?.let { t -> com.fadysaeed.pdfx.engine.DocInfo.key(t) !in shared } != false }
            .mapNotNull { d -> library.betterName(d)?.let { d to it } }
        return all.groupBy { it.second.lowercase() }.values
            .filter { g -> g.size == 1 || g.mapNotNull { it.first.fingerprint }.distinct().size == 1 }
            .flatten().sortedBy { it.second.lowercase() }
    }

    /** Renames the ticked files; the unticked ones keep their names and are not offered again. */
    fun applyRenames(list: List<Pair<Doc, String>>, left: List<Doc> = emptyList()) = launch {
        library.keepNames(left.map { it.id })
        bump.value++
        if (list.isEmpty()) { say(if (left.size == 1) "That file keeps its name." else "Those ${left.size} files keep their names."); return@launch }
        var ok = 0
        var why: String? = null
        for ((d, name) in list) {
            val now = library.dao.byId(d.id) ?: continue
            // A file of that name there already (often the same paper kept twice): "Name (2)".
            val free = library.freeName(now, name)
            library.rename(now, free).fold({ ok++ }, { if (why == null) why = it.message })
        }
        val failed = list.size - ok
        say(
            (if (ok == 1) "1 file renamed." else "$ok files renamed.") +
                (if (failed > 0) " $failed could not be" + (why?.let { ": $it" } ?: ".") else "") +
                (if (left.isNotEmpty()) " The ${left.size} left unticked keep their names." else ""),
        )
    }

    /** Files lying in Downloads, WhatsApp and the like, by the shelf folder each would go to. */
    suspend fun sortSuggestions(): Map<Shelf, List<Doc>> =
        library.dao.all().filter { d -> !d.inTrash && d.isFile && library.isLoose(d.folder) && d.kind != null && d.kind != com.fadysaeed.pdfx.engine.DocKind.OTHER.name }
            .groupBy { d -> Shelf.entries.first { it.name == d.kind } }
            .toSortedMap(compareBy { it.ordinal })

    fun folderFor(s: Shelf): String = library.folderLabel(library.shelfDir(s.label).absolutePath)

    fun applySort(chosen: Map<Shelf, List<Doc>>) = launch {
        var ok = 0
        var all = 0
        for ((shelf, docs) in chosen) for (d in docs) {
            all++
            library.dao.byId(d.id)?.let { now -> library.move(now, library.shelfDir(shelf.label)).onSuccess { ok++ } }
        }
        say("Moved $ok of $all files into Documents/Library, a folder for each kind." + if (ok < all) " The rest could not be moved." else "")
    }

    suspend fun brokenFiles(): List<Doc> =
        library.dao.all().filter { !it.inTrash && it.isFile && it.indexedAt == it.modified && it.indexedAt > 0 && it.pages == 0 && !it.locked }

    fun trashAll(docs: List<Doc>) = launch {
        var ok = 0
        for (d in docs) library.dao.byId(d.id)?.let { now -> library.trash(now).onSuccess { ok++ } }
        say(if (ok == 1) "1 file moved to the trash." else "$ok files moved to the trash. The trash keeps them 30 days.")
    }

    suspend fun bareScans(): List<Doc> {
        val done = library.scansRead()
        return library.dao.all().filter { !it.inTrash && it.isFile && it.kind == com.fadysaeed.pdfx.engine.DocKind.SCAN.name && it.id !in done && !it.locked }
    }

    /** Starts reading the words on scans in the background (see [com.fadysaeed.pdfx.data.ScanReading]). */
    fun readScans(docs: List<Doc>) = pdfx.scanReading.start(docs.map { it.id })
    fun stopReadingScans() = pdfx.scanReading.stop()
    val scanProgress get() = pdfx.scanReading.state
    val scanFinished get() = pdfx.scanReading.finished

    // --------------------------------------------------------- folders

    fun pinFolder(path: String, on: Boolean) {
        pins.value = if (on) pins.value + path else pins.value - path
        prefs.edit().putStringSet("pinned_folders", pins.value).apply()
    }

    fun renameFolder(path: String, name: String) = launch {
        val src = File(path)
        library.moveFolder(src, src.parentFile ?: return@launch, name).fold({ dst ->
            if (pins.value.contains(path)) { pinFolder(path, false); pinFolder(dst.absolutePath, true) }
            if (place.value.folder?.startsWith(path) == true) place.value = Place(Section.FOLDERS, folder = dst.absolutePath + place.value.folder!!.removePrefix(path), inFolder = true)
            say("Renamed to ${dst.name}.")
        }, ::fail)
    }

    fun moveFolder(path: String, into: File) = launch {
        library.moveFolder(File(path), into).fold({ dst ->
            if (pins.value.contains(path)) { pinFolder(path, false); pinFolder(dst.absolutePath, true) }
            say("Moved into ${library.folderLabel(into.absolutePath)}.")
        }, ::fail)
    }

    fun makeFolder(parent: String?, name: String) = launch {
        library.makeFolder(File(parent ?: library.root.absolutePath), name).fold({ f -> place.value = Place(Section.FOLDERS, folder = f.absolutePath, inFolder = true) }, ::fail)
    }

    fun deleteFolder(path: String) = launch {
        library.deleteEmptyFolder(File(path)).fold({ pinFolder(path, false); say("Folder deleted.") }, ::fail)
    }

    // --------------------------------------------------------- actions

    fun star(d: Doc) = launch { library.setStar(d, !d.starred) }

    fun rename(d: Doc, name: String) = launch {
        library.rename(d, name).fold({ say("Renamed to ${it.name.removeSuffix(".pdf")}.") }, { fail(it) })
    }

    fun move(d: Doc, dir: File) = launch {
        library.move(d, dir).fold({ say("Moved to ${library.folderLabel(it.folder)}.") }, { fail(it) })
    }

    fun copy(d: Doc, dir: File) = launch {
        library.copy(d, dir).fold({ say("Copied to ${library.folderLabel(it.folder)}.") }, { fail(it) })
    }

    fun trash(d: Doc) = launch {
        library.trash(d).fold(
            { t -> notice.value = Notice("Moved ${display(d)} to the trash.", undo = { launch { library.restore(t).onFailure { fail(it) } } }) },
            { fail(it) },
        )
    }

    fun restore(d: Doc) = launch {
        library.restore(d).fold({ say("Restored to ${library.folderLabel(it.folder)}.") }, { fail(it) })
    }

    fun deleteForever(d: Doc) = launch {
        library.deleteForever(d).fold({ say("${display(d)} is deleted for good.") }, { fail(it) })
    }

    fun emptyTrash() = launch {
        val n = library.emptyTrash()
        say(if (n == 1) "1 file deleted for good." else "$n files deleted for good.")
    }

    fun saveCopy(d: Doc) = launch {
        library.saveCopy(d).fold({ say("Saved in ${library.folderLabel(it.folder)}.") }, { fail(it) })
    }

    fun restoreVersion(d: Doc, v: com.fadysaeed.pdfx.data.Versions.Version) = launch {
        pdfx.saver.restore(d, v).fold({ say("${display(d)} is back to how it was then.") }, { fail(it) })
    }

    fun forget(d: Doc) = launch { library.forget(d); say("Removed from the list. The original is untouched.") }

    fun setTag(d: Doc, tag: Tag, on: Boolean) = launch { library.setTag(d.id, tag.id, on) }

    fun createTag(name: String, color: Int, forDoc: Doc?) = launch {
        val clean = name.trim()
        if (clean.isEmpty()) return@launch
        val existing = ui.value.allTags.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        val id = existing?.id ?: runCatching { library.createTag(clean, color) }.getOrElse { fail(it); return@launch }
        if (forDoc != null) library.setTag(forDoc.id, id, true)
    }

    fun deleteTag(id: Long) = launch {
        library.deleteTag(id)
        if (place.value.tagId == id) place.value = Place(Section.TAGS)
    }

    fun newFolder(name: String): File? {
        val clean = name.trim().replace('/', '-')
        if (clean.isEmpty()) return null
        return File(library.homeDir.parentFile, clean).also { it.mkdirs() }
    }

    /** Places a file can be moved or copied to: folders that hold PDFs plus the usual ones. */
    fun destinations(): List<FolderRow> {
        val docs = ui.value
        val known = docs.allFolders.filter { it.path != null }.map { it.path!! }
        val standard = listOf(
            File(library.root, "Download").absolutePath,
            File(library.root, "Documents").absolutePath,
            library.homeDir.absolutePath,
        )
        return (standard + known).distinct().map { FolderRow(it, library.folderLabel(it), 0) }
    }

    fun dismissNotice() { notice.value = null }

    private fun say(text: String) { notice.value = Notice(text) }
    private fun fail(t: Throwable) { notice.value = Notice(t.message ?: "That didn't work.") }
    private fun launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    // --------------------------------------------------------- shaping the list

    /**
     * Finds files by their details, their words and the user's notes, best first (see [com.fadysaeed.pdfx.data.Ranking]).
     * A word found nowhere is taken as a slip of the finger and spelt as the nearest word that is there.
     */
    private suspend fun search(q: String): SearchOut = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val docs = library.dao.all().filter { !it.inTrash }
        val tagNames = ui.value.allTags.associate { it.id to it.name }
        val tagsOf = ui.value.docTags.mapValues { (_, ids) -> ids.mapNotNull { tagNames[it] } }
        val fields = docs.associate { it.id to com.fadysaeed.pdfx.data.Ranking.fields(it, tagsOf[it.id].orEmpty()) }
        val first = rank(q, docs, fields)
        val words = com.fadysaeed.pdfx.data.Ranking.words(q)
        // Words found nowhere: in no file's details and on no page.
        val vocab = HashSet<String>()
        fields.values.forEach { f -> vocab += f.title; vocab += f.author; vocab += f.name; vocab += f.tags }
        val absent = words.filter { w -> w.length >= 4 && w.any { it.isLetter() } && vocab.none { it.startsWith(w) } && !library.text.hasAny(w) && library.marks.matching(listOf(w)).isEmpty() }
        if (absent.isEmpty()) return@withContext SearchOut(first)
        val fixes = HashMap<String, String>()
        for (w in absent) {
            val most = if (w.length >= 7) 2 else 1
            val near = vocab.asSequence().filter { kotlin.math.abs(it.length - w.length) <= most && it.firstOrNull() == w.first() }
                .map { it to com.fadysaeed.pdfx.data.TextMath.distance(w, it, most) }.filter { it.second <= most }
                .minByOrNull { it.second }
            val inside = library.text.nearWords(w, 1).firstOrNull()?.let { it to com.fadysaeed.pdfx.data.TextMath.distance(w, it, most) }
            val pick = listOfNotNull(near, inside).minByOrNull { it.second }?.first
            if (pick != null) fixes[w] = pick
        }
        if (fixes.isEmpty()) return@withContext SearchOut(first)
        val fixed = words.joinToString(" ") { fixes[it] ?: it }
        val second = rank(fixed, docs, fields)
        if (second.isEmpty() || (first.isNotEmpty() && second.size <= first.size)) SearchOut(first) else SearchOut(second, fixed)
    }

    private suspend fun rank(q: String, docs: List<Doc>, fields: Map<Long, com.fadysaeed.pdfx.data.Ranking.Fields>): List<DocResult> {
        val words = com.fadysaeed.pdfx.data.Ranking.words(q)
        if (words.isEmpty()) return emptyList()
        val phrase = words.joinToString(" ")
        val match = com.fadysaeed.pdfx.data.ftsQuery(q)
        val pages = match?.let { m -> runCatching { library.text.pagesMatching(m, raw = true) }.getOrDefault(emptyMap()) }.orEmpty()
        val snippets = library.searchText(q).groupBy { it.docId }
        val marks = runCatching { library.marks.matching(words) }.getOrDefault(emptyList()).groupBy { it.doc }
        val now = System.currentTimeMillis()
        return docs.mapNotNull { d ->
            val f = fields[d.id] ?: return@mapNotNull null
            val (details, all) = com.fadysaeed.pdfx.data.Ranking.inDetails(words, phrase, d, f)
            val hit = pages[d.id] ?: 0
            val mine = marks[d.id].orEmpty()
            if (!all && hit == 0 && mine.isEmpty()) return@mapNotNull null
            val recent = if (d.lastOpened > now - 30L * 86_400_000) 1.0 else 0.0
            val score = (if (all) details else 0.0) + com.fadysaeed.pdfx.data.Ranking.inside(hit, d.pages) + com.fadysaeed.pdfx.data.Ranking.inMarks(mine.size) + recent
            DocResult(d, all, snippets[d.id].orEmpty().sortedBy { it.page }.take(3), hit, score, mine.take(3))
        }.sortedWith(compareByDescending<DocResult> { it.score }.thenByDescending { it.doc.lastOpened })
    }

    /** The results narrowed by [f], and the ways they could be narrowed. */
    private fun narrow(res: List<DocResult>, f: SearchFilter, docTags: Map<Long, Set<Long>>, tags: List<Tag>): Pair<List<DocResult>, List<Facet>> {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        fun yearOk(d: Doc, y: Int?) = when {
            y == null -> true
            d.year <= 0 -> false
            y > 0 -> d.year >= year - y
            else -> d.year < year + y
        }
        fun pass(r: DocResult, x: SearchFilter) = (x.kind == null || r.doc.kind == x.kind) && yearOk(r.doc, x.years) && (x.tagId == null || x.tagId in docTags[r.doc.id].orEmpty())
        val shown = res.filter { pass(it, f) }
        val facets = ArrayList<Facet>()
        // Kinds among the results, as narrowed by the rest.
        val byKind = res.filter { pass(it, f.copy(kind = null)) }.groupingBy { it.doc.kind }.eachCount()
        Shelf.entries.filter { it.ordinal >= 3 }.forEach { sh -> byKind[sh.name]?.takeIf { it > 0 }?.let { n -> facets += Facet(sh.label, n, f.copy(kind = if (f.kind == sh.name) null else sh.name), f.kind == sh.name) } }
        for (y in listOf(2, 5, -5)) {
            val n = res.count { pass(it, f.copy(years = y)) }
            if (n > 0 && (n < res.size || f.years == y)) facets += Facet(if (y > 0) "Last $y years" else "Older", n, f.copy(years = if (f.years == y) null else y), f.years == y)
        }
        val byTag = HashMap<Long, Int>()
        res.filter { pass(it, f.copy(tagId = null)) }.forEach { r -> docTags[r.doc.id].orEmpty().forEach { byTag.merge(it, 1, Int::plus) } }
        tags.filter { (byTag[it.id] ?: 0) > 0 }.sortedByDescending { byTag[it.id] }.take(8).forEach { t ->
            facets += Facet(t.name, byTag.getValue(t.id), f.copy(tagId = if (f.tagId == t.id) null else t.id), f.tagId == t.id)
        }
        return shown to facets
    }

    private fun build(r: Raw, p: Place, s: Sort, q: String, out: SearchOut?, access: Boolean, v: View): LibraryUi {
        val tagsById = r.tags.associateBy { it.id }
        val tagsOf = r.links.groupBy({ it.docId }, { tagsById[it.tagId] }).mapValues { e -> e.value.filterNotNull().sortedBy { it.name.lowercase() } }
        val live = r.docs.filter { !it.inTrash }
        val docTagIds = r.links.groupBy({ it.docId }, { it.tagId }).mapValues { it.value.toSet() }
        val narrowed = if (q.isBlank() || out == null) null else narrow(out.results, v.filter, docTagIds, r.tags)
        val docs: List<Doc> = when (p.section) {
            Section.RECENT -> live.filter { it.lastOpened > 0 }
            Section.ALL -> live.filter { it.isFile }
            Section.STARRED -> live.filter { it.starred }
            Section.HIGHLIGHTS -> emptyList()
            Section.FOLDERS -> if (p.inFolder) live.filter { it.folder == p.folder } else emptyList()
            Section.TAGS -> if (p.tagId != null) live.filter { d -> tagsOf[d.id].orEmpty().any { it.id == p.tagId } } else emptyList()
            Section.TRASH -> r.docs.filter { it.inTrash }
            Section.SHELVES -> p.shelf?.let { name -> Shelf.entries.firstOrNull { it.name == name } }?.let { sh -> live.filter { onShelf(it, sh) } } ?: emptyList()
        }
        val allFolders = live.groupBy { it.folder }
            .map { (f, l) -> FolderRow(f, library.folderLabel(f), l.size) }
            .sortedWith(compareBy<FolderRow> { it.path == null }.thenBy { it.label.lowercase() })
        val folders = if (p.section == Section.FOLDERS) folderLevel(live, if (p.inFolder) p.folder else null) else emptyList()
        val title = when {
            p.inFolder -> library.folderLabel(p.folder).substringAfterLast('/')
            p.tagId != null -> tagsById[p.tagId]?.name ?: "Tag"
            p.shelf != null -> Shelf.entries.firstOrNull { it.name == p.shelf }?.label ?: "Shelf"
            else -> p.section.label
        }
        return LibraryUi(
            place = p,
            title = title,
            items = sorted(docs, if (p.section == Section.TRASH) Sort(SortBy.OPENED, true) else s, p.section).map { Item(it, tagsOf[it.id].orEmpty()) },
            folders = folders,
            allFolders = allFolders,
            crumbs = if (p.inFolder) crumbsOf(p.folder) else emptyList(),
            tidy = tidyOf(live),
            tags = r.counts,
            allTags = r.tags,
            docTags = docTagIds,
            query = q,
            results = narrowed?.first,
            facets = narrowed?.second.orEmpty(),
            searchNote = out?.corrected?.takeIf { q.isNotBlank() },
            filter = v.filter,
            grid = v.grid,
            marks = v.marks,
            sort = s,
            hasAccess = access,
            unindexed = r.unindexed,
            loaded = true,
            duplicates = duplicatesOf(live),
            shelves = if (p.section == Section.SHELVES && p.shelf == null) Shelf.entries.map { sh -> ShelfRow(sh, live.count { onShelf(it, sh) }) }.filter { it.count > 0 || it.shelf.ordinal < 3 } else emptyList(),
        )
    }

    private fun sorted(list: List<Doc>, s: Sort, section: Section): List<Doc> {
        val c: Comparator<Doc> = when {
            section == Section.TRASH -> compareBy { it.trashedAt }
            s.by == SortBy.NAME -> compareBy { titleOf(it).lowercase() }
            s.by == SortBy.MODIFIED -> compareBy { it.modified }
            s.by == SortBy.SIZE -> compareBy { it.size }
            else -> compareBy { it.lastOpened }
        }
        return if (s.descending) list.sortedWith(c.reversed()) else list.sortedWith(c)
    }

    private fun defaultSort(section: Section) = when (section) {
        Section.RECENT -> Sort(SortBy.OPENED, true)
        else -> Sort(SortBy.NAME, false)
    }

    private fun loadSorts(): Map<Section, Sort> = Section.entries.mapNotNull { sec ->
        val v = prefs.getString("sort_${sec.name}", null) ?: return@mapNotNull null
        val by = runCatching { SortBy.valueOf(v.substringBefore(':')) }.getOrNull() ?: return@mapNotNull null
        sec to Sort(by, v.substringAfter(':').toBoolean())
    }.toMap()

    /**
     * The folders to show at one level: inside [base], its folders that hold PDFs somewhere below
     * (with how many); at the top, pinned folders and the usual places first, then the phone's
     * top-level folders, then files from other apps.
     */
    private fun folderLevel(live: List<Doc>, base: String?): List<FolderRow> {
        val rootPath = library.root.absolutePath
        val counts = HashMap<String, Int>()
        for (d in live) {
            var f = d.folder?.let(::File) ?: continue
            while (true) {
                counts.merge(f.absolutePath, 1, Int::plus)
                val up = f.parentFile ?: break
                if (!up.absolutePath.startsWith(rootPath) || up.absolutePath == rootPath) break
                f = up
            }
        }
        if (base != null) {
            return counts.keys.filter { File(it).parent == base }
                .map { FolderRow(it, File(it).name, counts.getValue(it), it in pins.value) }
                .sortedWith(compareByDescending<FolderRow> { it.pinned }.thenBy { it.label.lowercase() })
        }
        val usual = (counts.keys.filter { it.endsWith("/WhatsApp Documents") } + listOf(
            File(library.root, "Download").absolutePath, File(library.homeDir, "Scans").absolutePath,
            File(library.homeDir, "Books").absolutePath, library.receivedDir.absolutePath, File(library.root, "Documents/Library").absolutePath,
        )).filter { (counts[it] ?: 0) > 0 }
        val pinned = pins.value.filter { File(it).isDirectory }
        val top = counts.keys.filter { File(it).parent == rootPath }
        val out = LinkedHashMap<String, FolderRow>()
        for (f in pinned) out[f] = FolderRow(f, library.folderLabel(f), counts[f] ?: 0, pinned = true)
        for (f in usual) if (f !in out) out[f] = FolderRow(f, library.folderLabel(f), counts.getValue(f))
        for (f in top.sortedBy { File(it).name.lowercase() }) if (f !in out) out[f] = FolderRow(f, File(f).name, counts.getValue(f))
        val loose = live.count { it.folder == rootPath }
        if (loose > 0) out[rootPath] = FolderRow(rootPath, "Phone storage (top level)", loose)
        val shared = live.count { it.folder == null }
        val rows = out.values.toMutableList()
        if (shared > 0) rows += FolderRow(null, "From other apps", shared)
        return rows
    }

    /** Phone › Documents › Papers, each part a place to go back to. */
    private fun crumbsOf(folder: String?): List<Pair<String, String>> {
        val f = folder ?: return emptyList()
        val rootPath = library.root.absolutePath
        if (!f.startsWith(rootPath)) return emptyList()
        val parts = f.removePrefix(rootPath).trim('/').split('/').filter { it.isNotEmpty() }
        return parts.indices.map { i -> parts[i] to rootPath + "/" + parts.take(i + 1).joinToString("/") }
    }

    private fun tidyOf(live: List<Doc>): TidyCounts {
        val files = live.filter { it.isFile }
        return TidyCounts(
            duplicates = duplicatesOf(live).size,
            codeNames = renamesOf(files).size,
            loose = files.count { library.isLoose(it.folder) && it.kind != null && it.kind != com.fadysaeed.pdfx.engine.DocKind.OTHER.name },
            broken = files.count { it.indexedAt == it.modified && it.indexedAt > 0 && it.pages == 0 && !it.locked },
            bareScans = library.scansRead().let { done -> files.count { it.kind == com.fadysaeed.pdfx.engine.DocKind.SCAN.name && it.id !in done && !it.locked } },
        )
    }

    private val month = 30L * 24 * 3600 * 1000

    /** Whether a file belongs on a shelf. */
    private fun onShelf(d: Doc, sh: Shelf): Boolean = when (sh) {
        Shelf.READING -> d.reading != 2 && d.pages > 1 && d.lastOpened > System.currentTimeMillis() - month && progressOf(d) in 0.01f..0.95f
        Shelf.TO_READ -> d.reading == 1
        Shelf.DONE -> d.reading == 2 || (d.pages > 1 && progressOf(d) > 0.95f)
        else -> d.isFile && d.kind == sh.name
    }

    fun setReading(d: Doc, state: Int) = launch {
        library.dao.byId(d.id)?.let { library.dao.update(it.copy(reading = state)) }
        say(when (state) { 1 -> "On the To read shelf."; 2 -> "Marked done."; else -> "Taken off the reading shelves." })
    }

    /** Topics that run through the files, to become tags; worked out on asking (a few seconds for a big library). */
    suspend fun topicSuggestions(progress: (Float, String) -> Unit = { _, _ -> }): List<com.fadysaeed.pdfx.data.TopicGroup> =
        com.fadysaeed.pdfx.data.Topics.suggest(library, ui.value.allTags.map { it.name.lowercase() }.toSet(), progress)

    /** Makes the chosen tags (topics and whole areas) and puts each on its files. */
    fun addTopics(chosen: List<Pair<String, List<Long>>>) = launch {
        val files = HashSet<Long>()
        for ((name, ids) in chosen) { library.applyTopic(name, ids); files += ids }
        say(if (chosen.size == 1) "Tag ${chosen[0].first} put on ${chosen[0].second.size} files." else "${chosen.size} tags put on ${files.size} files.")
    }

    /**
     * Files that are the same file (the same fingerprint) more than once, the copy to keep first:
     * the one opened most recently, then the one not in Received or WhatsApp, then the oldest.
     */
    private fun duplicatesOf(live: List<Doc>): List<List<Doc>> =
        live.filter { it.isFile && it.fingerprint != null }.groupBy { it.fingerprint }.values.filter { it.size > 1 }
            .map { g -> g.sortedWith(compareByDescending<Doc> { it.lastOpened }.thenBy { inside(it) }.thenBy { it.addedAt.takeIf { a -> a > 0 } ?: it.modified }) }
            .sortedByDescending { it.first().size }

    private fun inside(d: Doc): Int {
        val f = d.folder.orEmpty()
        return if (f.contains("/WhatsApp/") || f.endsWith("/Received") || f.contains("/Download")) 1 else 0
    }

    /** Keeps the first of a group and moves the rest to the trash, the kept one taking their tags, stars and bookmarks. */
    fun keepOne(group: List<Doc>, keep: Doc) = launch {
        library.mergeDuplicates(keep, group.filter { it.id != keep.id }).fold(
            { n -> say(if (n == 1) "1 copy moved to the trash; ${titleOf(keep)} keeps its tags and bookmarks." else "$n copies moved to the trash; ${titleOf(keep)} keeps their tags and bookmarks.") },
            ::fail,
        )
    }

    /** Every group at once, keeping the suggested copy of each. */
    fun keepSuggested(groups: List<List<Doc>>) = launch {
        var n = 0
        for (g in groups) library.mergeDuplicates(g.first(), g.drop(1)).onSuccess { n += it }
        say(if (n == 1) "1 copy moved to the trash." else "$n copies moved to the trash. The trash keeps them 30 days.")
    }

    companion object {
        fun display(d: Doc) = d.name.removeSuffix(".pdf").removeSuffix(".PDF")

        /** What a file is called in lists: its real title when one was read from it, else its name. */
        fun titleOf(d: Doc) = d.title?.takeIf { it.isNotBlank() } ?: display(d)

        /** How far through it the reader got, 0 to 1. */
        fun progressOf(d: Doc): Float = if (d.pages > 1) (d.lastPage + 1).toFloat() / d.pages else 0f

        /** "Malhi et al. · 2023". */
        fun byline(d: Doc): String = listOfNotNull(d.author?.takeIf { it.isNotBlank() }, d.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
    }
}
