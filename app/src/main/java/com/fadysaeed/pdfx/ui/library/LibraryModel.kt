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
    RECENT("Recent"), ALL("All files"), STARRED("Starred"), FOLDERS("Folders"), TAGS("Tags"), TRASH("Trash")
}

enum class SortBy(val label: String) { OPENED("Last opened"), NAME("Name"), MODIFIED("Date changed"), SIZE("Size") }

data class Item(val doc: Doc, val tags: List<Tag>)
data class FolderRow(val path: String?, val label: String, val count: Int)
data class DocResult(val doc: Doc, val nameMatch: Boolean, val hits: List<TextHit>, val totalHits: Int)

/** Where in the library the user is. */
data class Place(
    val section: Section = Section.RECENT,
    val folder: String? = null,
    val inFolder: Boolean = false,
    val tagId: Long? = null,
)

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
    /** The same file more than once (by fingerprint), each group its copies; the one to keep first. */
    val duplicates: List<List<Doc>> = emptyList(),
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
    private val access = MutableStateFlow(library.hasAllFilesAccess())
    val notice = MutableStateFlow<Notice?>(null)

    private val raw = combine(
        library.dao.observeAll(), library.dao.observeDocTags(), library.dao.observeTags(),
        library.dao.observeTagCounts(), library.dao.observeUnindexed(),
    ) { d, l, t, c, u -> Raw(d, l, t, c, u) }

    private val results = combine(query.debounce(180).distinctUntilChanged(), library.dao.observeUnindexed()) { q, _ -> q }
        .mapLatest { q -> if (q.isBlank()) null else search(q) }

    val ui: StateFlow<LibraryUi> = combine(raw, place, sorts, query, combine(results, access) { r, a -> r to a }) { r, p, s, q, (res, a) ->
        build(r, p, s[p.section] ?: defaultSort(p.section), q, res, a)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUi())

    fun refresh() {
        val had = access.value
        access.value = library.hasAllFilesAccess()
        // Rescan straight away when access has just been granted.
        pdfx.refresh(force = access.value && !had)
    }

    fun go(section: Section) { place.value = Place(section) }
    fun openFolder(path: String?) { place.value = Place(Section.FOLDERS, folder = path, inFolder = true) }
    fun openTag(id: Long) { place.value = Place(Section.TAGS, tagId = id) }
    /** Back inside the library: out of a folder or tag first. True when it handled the back press. */
    fun back(): Boolean {
        val p = place.value
        return when {
            query.value.isNotEmpty() -> { query.value = ""; true }
            p.inFolder -> { place.value = Place(Section.FOLDERS); true }
            p.tagId != null -> { place.value = Place(Section.TAGS); true }
            p.section != Section.RECENT -> { place.value = Place(); true }
            else -> false
        }
    }

    fun setQuery(q: String) { query.value = q }

    fun setSort(by: SortBy) {
        val sec = place.value.section
        val cur = sorts.value[sec] ?: defaultSort(sec)
        val next = if (cur.by == by) cur.copy(descending = !cur.descending) else Sort(by, descending = by != SortBy.NAME)
        sorts.value = sorts.value + (sec to next)
        prefs.edit().putString("sort_${sec.name}", "${next.by.name}:${next.descending}").apply()
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
        val known = docs.folders.filter { it.path != null }.map { it.path!! }
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

    private suspend fun search(q: String): List<DocResult> {
        val docs = library.dao.all().filter { !it.inTrash }
        val byId = docs.associateBy { it.id }
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        // The name, and the real title, author, year and DOI read from the file.
        val nameHits = docs.filter { d ->
            val about = listOfNotNull(d.name, d.title, d.author, d.doi, d.year.takeIf { it > 0 }?.toString()).joinToString(" ").lowercase()
            words.all { about.contains(it) }
        }.map { it.id }.toSet()
        val text = library.searchText(q).filter { it.docId in byId }.groupBy { it.docId }
        val ids = (nameHits + text.keys)
        return ids.mapNotNull { id ->
            val d = byId[id] ?: return@mapNotNull null
            val hits = text[id].orEmpty().sortedBy { it.page }
            DocResult(d, id in nameHits, hits.take(3), hits.size)
        }.sortedWith(compareByDescending<DocResult> { it.nameMatch }.thenByDescending { it.totalHits }.thenByDescending { it.doc.lastOpened })
    }

    private fun build(r: Raw, p: Place, s: Sort, q: String, res: List<DocResult>?, access: Boolean): LibraryUi {
        val tagsById = r.tags.associateBy { it.id }
        val tagsOf = r.links.groupBy({ it.docId }, { tagsById[it.tagId] }).mapValues { e -> e.value.filterNotNull().sortedBy { it.name.lowercase() } }
        val live = r.docs.filter { !it.inTrash }
        val docs: List<Doc> = when (p.section) {
            Section.RECENT -> live.filter { it.lastOpened > 0 }
            Section.ALL -> live.filter { it.isFile }
            Section.STARRED -> live.filter { it.starred }
            Section.FOLDERS -> if (p.inFolder) live.filter { it.folder == p.folder } else emptyList()
            Section.TAGS -> if (p.tagId != null) live.filter { d -> tagsOf[d.id].orEmpty().any { it.id == p.tagId } } else emptyList()
            Section.TRASH -> r.docs.filter { it.inTrash }
        }
        val folders = live.groupBy { it.folder }
            .map { (f, l) -> FolderRow(f, library.folderLabel(f), l.size) }
            .sortedWith(compareBy<FolderRow> { it.path == null }.thenBy { it.label.lowercase() })
        val title = when {
            p.inFolder -> library.folderLabel(p.folder).substringAfterLast('/')
            p.tagId != null -> tagsById[p.tagId]?.name ?: "Tag"
            else -> p.section.label
        }
        return LibraryUi(
            place = p,
            title = title,
            items = sorted(docs, if (p.section == Section.TRASH) Sort(SortBy.OPENED, true) else s, p.section).map { Item(it, tagsOf[it.id].orEmpty()) },
            folders = folders,
            tags = r.counts,
            allTags = r.tags,
            docTags = r.links.groupBy({ it.docId }, { it.tagId }).mapValues { it.value.toSet() },
            query = q,
            results = if (q.isBlank()) null else res,
            sort = s,
            hasAccess = access,
            unindexed = r.unindexed,
            loaded = true,
            duplicates = duplicatesOf(live),
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

        /** "Malhi et al. · 2023". */
        fun byline(d: Doc): String = listOfNotNull(d.author?.takeIf { it.isNotBlank() }, d.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
    }
}
