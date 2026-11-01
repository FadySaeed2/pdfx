package com.fadysaeed.pdfx.ui.viewer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorMatrix
import com.fadysaeed.pdfx.data.Bookmarks
import com.fadysaeed.pdfx.engine.Box
import com.fadysaeed.pdfx.engine.OutlineEntry
import com.fadysaeed.pdfx.engine.PageLink
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.engine.Selection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How pages are shown: as printed, white text on black for the night, or warm paper. */
enum class PaperMode(val label: String, val note: String) {
    PAPER("Paper", "The page as it was made"),
    NIGHT("Night", "Light text on black; colours keep their hue"),
    SEPIA("Sepia", "Warm paper, easier on the eyes in the evening"),
}

/** Scroll the document so a point on a page sits a third of the way down the screen. */
data class Jump(val page: Int, val y: Float = 0f, val x: Float? = null, val id: Long = System.nanoTime())

/**
 * Everything the reader shows on top of the pages, and the actions on it. One per open document.
 * [session] is null only in screenshot tests, where state is set by hand.
 */
class Reader(
    private val session: PdfSession?,
    val pageCount: Int,
    private val scope: CoroutineScope,
    private val bookmarksStore: Bookmarks? = null,
    private val docId: Long? = null,
    private val prefs: android.content.SharedPreferences? = null,
) {
    val pages: PageImages? = session?.let { PageImages(it, scope) }

    var mode by mutableStateOf(prefs?.getString("mode", null)?.let { runCatching { PaperMode.valueOf(it) }.getOrNull() } ?: PaperMode.PAPER)
        private set
    var reflow by mutableStateOf(prefs?.getBoolean("reflow", false) ?: false)
        private set
    var reflowSize by mutableIntStateOf(prefs?.getInt("reflow_size", 18) ?: 18)
        private set
    var chrome by mutableStateOf(true)
    var jump by mutableStateOf<Jump?>(null)
        private set

    // ------------------------------------------------------------ search
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
        private set
    /** Hits per page; each hit is the boxes it covers. */
    val hits = mutableStateMapOf<Int, List<List<Box>>>()
    /** Every hit in page order, as (page, index on that page). */
    var order by mutableStateOf<List<Pair<Int, Int>>>(emptyList())
        private set
    var current by mutableIntStateOf(-1)
        private set
    var searching by mutableStateOf(false)
        private set
    var searchedPages by mutableIntStateOf(0)
        private set
    private var searchJob: Job? = null

    // ------------------------------------------------------------ selection, links, contents
    var selection by mutableStateOf<Selection?>(null)
        private set
    val links = mutableStateMapOf<Int, List<PageLink>>()
    var outline by mutableStateOf<List<OutlineEntry>?>(null)
        private set
    val bookmarks = mutableStateListOf<Int>()
    val paragraphs = mutableStateMapOf<Int, List<String>>()

    init {
        if (bookmarksStore != null && docId != null) {
            scope.launch {
                val list = withContext(Dispatchers.IO) { bookmarksStore.pages(docId) }
                bookmarks.clear(); bookmarks.addAll(list)
            }
        }
    }

    fun go(page: Int, y: Float = 0f, x: Float? = null) {
        jump = Jump(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), y, x)
    }

    fun changeMode(m: PaperMode) { mode = m; prefs?.edit()?.putString("mode", m.name)?.apply() }
    fun changeReflow(on: Boolean) { reflow = on; prefs?.edit()?.putBoolean("reflow", on)?.apply() }
    fun changeReflowSize(sp: Int) { reflowSize = sp.coerceIn(14, 28); prefs?.edit()?.putInt("reflow_size", reflowSize)?.apply() }

    // ------------------------------------------------------------ search

    /** Searches every page, starting at [fromPage] and wrapping round, and jumps to the first hit. */
    fun search(text: String, fromPage: Int) {
        val q = text.trim()
        searchJob?.cancel()
        query = text
        hits.clear(); order = emptyList(); current = -1; searchedPages = 0
        if (q.isEmpty() || session == null) { searching = false; return }
        searching = true
        searchJob = scope.launch {
            val start = fromPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            for (k in 0 until pageCount) {
                val p = (start + k) % pageCount
                val found = runCatching { session.search(p, q) }.getOrDefault(emptyList())
                searchedPages = k + 1
                if (found.isNotEmpty()) {
                    hits[p] = found
                    order = hits.keys.sorted().flatMap { pg -> hits[pg]!!.indices.map { pg to it } }
                    if (current < 0) select(order.indexOfFirst { it.first == p })
                    else current = order.indexOf(lastShown ?: (p to 0)).coerceAtLeast(0)
                }
            }
            searching = false
        }
    }

    private var lastShown: Pair<Int, Int>? = null

    fun next() { if (order.isNotEmpty()) select((current + 1) % order.size) }
    fun previous() { if (order.isNotEmpty()) select((current - 1 + order.size) % order.size) }

    private fun select(i: Int) {
        if (i !in order.indices) return
        current = i
        val (p, h) = order[i]
        lastShown = p to h
        val b = hits[p]?.getOrNull(h)?.firstOrNull() ?: return
        go(p, b.y0, (b.x0 + b.x1) / 2)
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchOpen = false
        query = ""
        hits.clear(); order = emptyList(); current = -1; searching = false
    }

    /** The hit the arrows are on, as (page, index on that page). */
    fun currentHit(): Pair<Int, Int>? = order.getOrNull(current)

    // ------------------------------------------------------------ selection

    fun selectWordAt(page: Int, x: Float, y: Float) {
        val s = session ?: return
        scope.launch { selection = runCatching { s.selectWord(page, x, y) }.getOrNull() }
    }

    /** Moves one end of the selection; [start] says which. */
    fun dragSelection(start: Boolean, x: Float, y: Float) {
        val sel = selection ?: return
        val s = session ?: return
        scope.launch {
            val next = if (start) s.select(sel.page, x, y, sel.bx, sel.by) else s.select(sel.page, sel.ax, sel.ay, x, y)
            if (next != null) selection = next
        }
    }

    fun clearSelection() { selection = null }

    /** For screenshot tests. */
    fun showSelection(sel: Selection?) { selection = sel }

    fun copySelection(context: Context): Boolean {
        val text = selection?.text?.takeIf { it.isNotBlank() } ?: return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("PDFx", text))
        return true
    }

    // ------------------------------------------------------------ links, contents, bookmarks, reflow

    fun loadLinks(page: Int) {
        val s = session ?: return
        if (links.containsKey(page)) return
        links[page] = emptyList()
        scope.launch { links[page] = runCatching { s.links(page) }.getOrDefault(emptyList()) }
    }

    fun linkAt(page: Int, x: Float, y: Float): PageLink? = links[page]?.firstOrNull { it.box.contains(x, y) }

    fun loadOutline() {
        val s = session ?: return
        if (outline != null) return
        scope.launch { outline = runCatching { s.outline() }.getOrDefault(emptyList()) }
    }

    fun toggleBookmark(page: Int) {
        val on = page !in bookmarks
        if (on) { bookmarks.add(page); bookmarks.sort() } else bookmarks.remove(page)
        val store = bookmarksStore ?: return
        val id = docId ?: return
        scope.launch { withContext(Dispatchers.IO) { store.set(id, page, on) } }
    }

    fun loadParagraphs(page: Int) {
        val s = session ?: return
        if (paragraphs.containsKey(page)) return
        paragraphs[page] = emptyList()
        scope.launch { paragraphs[page] = runCatching { s.paragraphs(page) }.getOrDefault(emptyList()) }
    }

    /** For screenshot tests. */
    fun showHits(q: String, found: Map<Int, List<List<Box>>>, at: Int) {
        query = q; searchOpen = true
        hits.clear(); hits.putAll(found)
        order = hits.keys.sorted().flatMap { pg -> hits[pg]!!.indices.map { pg to it } }
        current = at; searching = false; searchedPages = pageCount
    }

    fun setOutlineForTest(entries: List<OutlineEntry>) { outline = entries }
}

/**
 * Night: invert, then turn the hues back round so red stays red, then map black-to-white onto
 * CalPal's near-black and bone. Sepia: a warm tint over the page as printed.
 */
fun pageFilter(mode: PaperMode): ColorMatrix? = when (mode) {
    PaperMode.PAPER -> null
    PaperMode.NIGHT -> {
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        // Inverting then rotating hue by 180 degrees: luminance flips, hue stays.
        val m = floatArrayOf(
            -(2 * lr - 1), -(2 * lg), -(2 * lb), 0f, 255f,
            -(2 * lr), -(2 * lg - 1), -(2 * lb), 0f, 255f,
            -(2 * lr), -(2 * lg), -(2 * lb - 1), 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
        // Rows of the hue rotation each sum to 1, so 255 in the offset keeps white -> black.
        tint(m, dark = floatArrayOf(12f, 11f, 9f), light = floatArrayOf(226f, 218f, 202f))
    }
    PaperMode.SEPIA -> tint(
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f),
        dark = floatArrayOf(40f, 30f, 20f), light = floatArrayOf(244f, 232f, 206f),
    )
}

/** Applies out = dark + in * (light - dark) / 255 after [m]. */
private fun tint(m: FloatArray, dark: FloatArray, light: FloatArray): ColorMatrix {
    val out = m.copyOf()
    for (row in 0 until 3) {
        val s = (light[row] - dark[row]) / 255f
        for (col in 0 until 5) out[row * 5 + col] = m[row * 5 + col] * s
        out[row * 5 + 4] += dark[row]
    }
    return ColorMatrix(out)
}
