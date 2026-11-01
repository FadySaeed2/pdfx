package com.fadysaeed.pdfx.ui.viewer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import com.fadysaeed.pdfx.data.Bookmarks
import com.fadysaeed.pdfx.engine.Box
import com.fadysaeed.pdfx.engine.BoxAlign
import com.fadysaeed.pdfx.engine.BoxDir
import com.fadysaeed.pdfx.engine.BoxFont
import com.fadysaeed.pdfx.engine.BoxStyle
import com.fadysaeed.pdfx.engine.Hit
import com.fadysaeed.pdfx.engine.Mark
import com.fadysaeed.pdfx.engine.MarkKind
import com.fadysaeed.pdfx.engine.OutlineEntry
import com.fadysaeed.pdfx.engine.PageLink
import com.fadysaeed.pdfx.engine.Para
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

/** Reading fonts for reflowed text. Arabic paragraphs always use IBM Plex Sans Arabic. */
enum class ReflowFont(val label: String) { SANS("Sans"), SERIF("Serif") }

/** Line spacing and side margins for reflowed text, in three steps each. */
val ReflowSpacings = listOf(1.35f, 1.6f, 1.9f)
val ReflowMargins = listOf(14, 24, 40)

/** The marking tools. */
enum class Tool(val label: String) {
    SELECT("Select"), HIGHLIGHT("Highlight"), UNDERLINE("Underline"), STRIKE("Strike"), PEN("Pen"), MARKER("Marker"),
    SHAPE("Shapes"), TEXT("Text"), NOTE("Note"), ERASER("Eraser"),
}

enum class ShapeKind(val label: String, val kind: MarkKind) {
    RECT("Box", MarkKind.SQUARE), ELLIPSE("Oval", MarkKind.CIRCLE), ARROW("Arrow", MarkKind.ARROW), LINE("Line", MarkKind.LINE),
}

/** Colours that read well on paper and print well: soft ones for marking text, firm ones for pens. */
val MarkColors = listOf(0xFFFFD54A, 0xFF7ED36F, 0xFF6FB4FF, 0xFFFF7FAE, 0xFFFFA94D).map { it.toInt() }
val PenColors = listOf(0xFF1A1A1A, 0xFFE5484D, 0xFF2F6FED, 0xFF2E9E5B, 0xFFE0A43B).map { it.toInt() }
/** How much handwriting a tap picks up: the stroke touched, the word it is part of, or the whole line. */
enum class InkScope(val label: String) { STROKE("Stroke"), WORD("Word"), LINE("Line") }

/** How strong a highlight looks: light, medium or strong. */
val HighlightStrengths = listOf(0.35f, 0.6f, 1f)
val StrengthLabels = listOf("Light", "Medium", "Strong")
/** The strength step closest to a mark's opacity. */
fun strengthStep(opacity: Float): Int = HighlightStrengths.indices.minByOrNull { kotlin.math.abs(HighlightStrengths[it] - opacity) } ?: 2
/** Pen widths in PDF points, and text sizes for text boxes. */
val PenWidths = listOf(1.0f, 2.0f, 3.6f)
val TextSizes = listOf(11f, 15f, 20f)

/** A mark being made, shown live under the finger before it is written into the file. */
sealed interface Draft {
    val page: Int
    data class Words(override val page: Int, val boxes: List<Box>, val color: Int) : Draft
    data class Ink(override val page: Int, val points: List<Pair<Float, Float>>, val color: Int, val width: Float, val opacity: Float) : Draft
    data class Shape(override val page: Int, val kind: ShapeKind, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val color: Int, val width: Float) : Draft
    /** A loop drawn round marks to pick them. */
    data class Lasso(override val page: Int, val points: List<Pair<Float, Float>>) : Draft
}

/** Asking for words: a new text box or note at a point, or the note of an existing mark. */
data class TextAsk(val page: Int, val x: Float, val y: Float, val note: Boolean, val mark: Mark? = null)

/** A text box being written or changed: [mark] is null for a new box at ([x], [y]) on [page]. */
data class BoxEdit(val page: Int, val x: Float, val y: Float, val mark: Mark? = null)

/** Saves a text box style in one line for preferences. */
internal fun styleToPref(s: BoxStyle): String = listOf(
    s.font.name, s.size, s.bold, s.italic, s.underline, s.align.name, s.dir.name, s.color, s.fill ?: "", s.border, s.spacing,
).joinToString("|")

internal fun styleFromPref(p: String?): BoxStyle? = runCatching {
    val f = p!!.split('|')
    BoxStyle(
        BoxFont.valueOf(f[0]), f[1].toFloat(), f[2].toBoolean(), f[3].toBoolean(), f[4].toBoolean(),
        BoxAlign.valueOf(f[5]), BoxDir.valueOf(f[6]), f[7].toInt(), f[8].takeIf { it.isNotEmpty() }?.toInt(), f[9].toBoolean(), f[10].toInt(),
    )
}.getOrNull()

/** The colour picker is open: for the tool in hand, for selected words, or to recolour [mark]. */
data class ColorPick(val initial: Int, val mark: Mark? = null, val forWords: Boolean = false)

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
    var reflowFont by mutableStateOf(prefs?.getString("reflow_font", null)?.let { runCatching { ReflowFont.valueOf(it) }.getOrNull() } ?: ReflowFont.SANS)
        private set
    var reflowSpacing by mutableIntStateOf(prefs?.getInt("reflow_spacing", 1) ?: 1)
        private set
    var reflowMargin by mutableIntStateOf(prefs?.getInt("reflow_margin", 1) ?: 1)
        private set
    /** Reflowed text set flush on both sides, with long English words broken by hyphens. */
    var reflowJustify by mutableStateOf(prefs?.getBoolean("reflow_justify", true) ?: true)
        private set
    /** A thin line with the page number where each page starts. */
    var reflowPageMarks by mutableStateOf(prefs?.getBoolean("reflow_page_marks", true) ?: true)
        private set
    /** Pictures cut from pages for reflowed text, by page, place and width. */
    val figures = mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()
    private val figureOrder = ArrayDeque<String>()
    /**
     * Where reading was when switching between pages and reflowed text: the page, and how far down
     * it the reading line was (0 to 1). The other view opens at the same place.
     */
    var readingSpot: Pair<Int, Float>? = null
    var chrome by mutableStateOf(true)
    var jump by mutableStateOf<Jump?>(null)
        private set

    // ------------------------------------------------------------ search
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
        private set
    /** Hits per page; each hit is the boxes it covers. */
    val hits = mutableStateMapOf<Int, List<List<Box>>>()
    /** The same hits with the words around each, for the list of results. */
    val snippets = mutableStateMapOf<Int, List<Hit>>()
    /** Whether the list of every result is open beside the page. */
    var resultsOpen by mutableStateOf(false)
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
    val paragraphs = mutableStateMapOf<Int, List<Para>>()

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
    fun changeReflowFont(f: ReflowFont) { reflowFont = f; prefs?.edit()?.putString("reflow_font", f.name)?.apply() }
    fun changeReflowSpacing(step: Int) { reflowSpacing = step.coerceIn(0, 2); prefs?.edit()?.putInt("reflow_spacing", reflowSpacing)?.apply() }
    fun changeReflowJustify(on: Boolean) { reflowJustify = on; prefs?.edit()?.putBoolean("reflow_justify", on)?.apply() }
    fun changeReflowPageMarks(on: Boolean) { reflowPageMarks = on; prefs?.edit()?.putBoolean("reflow_page_marks", on)?.apply() }

    fun figureKey(page: Int, box: Box, widthPx: Int) = "$page:${box.x0}:${box.y0}:$widthPx"

    /** Draws the part of a page a picture covers, [widthPx] wide, for reflowed text. Keeps the last 16. */
    fun loadFigure(page: Int, box: Box, widthPx: Int) {
        val s = session ?: return
        val key = figureKey(page, box, widthPx)
        if (key in figureOrder) return
        figureOrder.addLast(key)
        while (figureOrder.size > 16) figures.remove(figureOrder.removeFirst())
        scope.launch {
            val pw = runCatching { s.pageSize(page).width }.getOrNull() ?: return@launch
            val pagePx = (pw * widthPx / box.width.coerceAtLeast(1f)).toInt().coerceIn(1, 6000)
            val k = pagePx / pw
            val bmp = runCatching {
                s.renderRegion(page, pagePx, (box.x0 * k).toInt(), (box.y0 * k).toInt(), (box.width * k).toInt().coerceAtLeast(1), (box.height * k).toInt().coerceAtLeast(1))
            }.getOrNull() ?: return@launch
            if (key in figureOrder) figures[key] = bmp.asImageBitmap()
        }
    }

    fun changeReflowMargin(step: Int) { reflowMargin = step.coerceIn(0, 2); prefs?.edit()?.putInt("reflow_margin", reflowMargin)?.apply() }

    /** Switches between pages and reflowed text, keeping the place: [spot] is where reading was. */
    fun toggleReflow(spot: Pair<Int, Float>?) {
        readingSpot = spot
        selectedMark = null
        clearSelection()
        if (!reflow && marking) stopMarking()
        changeReflow(!reflow)
    }

    // ------------------------------------------------------------ search

    /** Searches every page, starting at [fromPage] and wrapping round, and jumps to the first hit. */
    fun search(text: String, fromPage: Int) {
        val q = text.trim()
        searchJob?.cancel()
        query = text
        hits.clear(); snippets.clear(); order = emptyList(); current = -1; searchedPages = 0
        if (q.isEmpty() || session == null) { searching = false; return }
        searching = true
        searchJob = scope.launch {
            val start = fromPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            for (k in 0 until pageCount) {
                val p = (start + k) % pageCount
                val found = runCatching { session.searchHits(p, q) }.getOrDefault(emptyList())
                searchedPages = k + 1
                if (found.isNotEmpty()) {
                    snippets[p] = found
                    hits[p] = found.map { it.boxes }
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

    /** Jumps to hit number [i] in page order (from the list of results). */
    fun showHit(i: Int) = select(i)

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
        resultsOpen = false
        query = ""
        hits.clear(); snippets.clear(); order = emptyList(); current = -1; searching = false
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
            val next = if (start) s.selectWords(sel.page, x, y, sel.bx, sel.by) else s.selectWords(sel.page, sel.ax, sel.ay, x, y)
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

    private val paragraphsAsked = HashSet<Int>()

    /** Reads a page's paragraphs once; until they arrive the page has no entry, so it shows as loading. */
    fun loadParagraphs(page: Int) {
        val s = session ?: return
        if (!paragraphsAsked.add(page)) return
        scope.launch { paragraphs[page] = runCatching { s.paragraphs(page) }.getOrDefault(emptyList()) }
    }

    // ------------------------------------------------------------ marking

    val canMark: Boolean get() = session?.editable == true || session == null
    var marking by mutableStateOf(false)
        private set
    var tool by mutableStateOf(prefs?.getString("tool", null)?.let { runCatching { Tool.valueOf(it) }.getOrNull() } ?: Tool.HIGHLIGHT)
        private set
    var shape by mutableStateOf(ShapeKind.RECT)
        private set
    var markColor by mutableIntStateOf(prefs?.getInt("mark_color", MarkColors[0]) ?: MarkColors[0])
        private set
    var penColor by mutableIntStateOf(prefs?.getInt("pen_color", PenColors[0]) ?: PenColors[0])
        private set
    var widthStep by mutableIntStateOf(prefs?.getInt("width_step", 1) ?: 1)
        private set
    /** Strength of new highlights, a step in [HighlightStrengths]. */
    var strength by mutableIntStateOf(prefs?.getInt("strength", 1) ?: 1)
        private set
    /** Colours picked with the colour picker, newest first. */
    val customColors = mutableStateListOf<Int>().also { list ->
        prefs?.getString("custom_colors", null)?.split(',')?.mapNotNull { it.toLongOrNull()?.toInt() }?.let { list.addAll(it) }
    }
    var colorPick by mutableStateOf<ColorPick?>(null)
    /** A change that can be taken back from a short notice, such as marking selected words. */
    var undoNotice by mutableStateOf<String?>(null)
    /** Once a stylus has touched the page, fingers scroll and zoom while the pen draws. */
    var stylusSeen by mutableStateOf(false)
    val marks = mutableStateMapOf<Int, List<Mark>>()
    var allMarks by mutableStateOf<List<Mark>?>(null)
        private set
    var draft by mutableStateOf<Draft?>(null)
        private set
    var selectedMark by mutableStateOf<Mark?>(null)
        private set
    /** Marks picked together with [selectedMark], which comes first; see [members]. */
    private var group by mutableStateOf<List<Mark>>(emptyList())
    var inkScope by mutableStateOf(prefs?.getString("ink_scope", null)?.let { runCatching { InkScope.valueOf(it) }.getOrNull() } ?: InkScope.WORD)
        private set
    var textAsk by mutableStateOf<TextAsk?>(null)
    /** The text box being written or changed. */
    var boxEdit by mutableStateOf<BoxEdit?>(null)
    /** The style new text boxes start with: the last one used. */
    var boxStyle by mutableStateOf(styleFromPref(prefs?.getString("box_style", null)) ?: BoxStyle())
        private set
    /** Where the selected mark is being dragged to, shown live until the page is drawn again. */
    var moving by mutableStateOf<Box?>(null)
        private set
    /** True while a finger is moving a mark or a selection handle, so the page itself stays still. */
    var holdsPage = false
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var dirty by mutableStateOf(false)
        private set
    /** A short message about the last change that went wrong. */
    var problem by mutableStateOf<String?>(null)

    private var anchor: Pair<Float, Float>? = null
    private var gesturePage = -1
    private var gestureTool = Tool.HIGHLIGHT
    private var moved = false
    private var selectJob: Job? = null
    private val erased = LinkedHashSet<Int>()

    fun startMarking() { if (canMark) { marking = true; clearSelection(); selectedMark = null } }
    fun stopMarking() { marking = false; draft = null; selectedMark = null }
    fun pickTool(t: Tool) { tool = t; prefs?.edit()?.putString("tool", t.name)?.apply() }
    fun pickShape(k: ShapeKind) { shape = k }
    fun pickColor(c: Int) {
        if (usesPenColors(tool)) { penColor = c; prefs?.edit()?.putInt("pen_color", c)?.apply() }
        else { markColor = c; prefs?.edit()?.putInt("mark_color", c)?.apply() }
    }
    /** Colour for highlighting selected words, whichever tool was last in hand. */
    fun pickWordsColor(c: Int) { markColor = c; prefs?.edit()?.putInt("mark_color", c)?.apply() }

    /** A colour chosen in the picker goes where the picker was opened from, and joins the recent ones. */
    fun applyPicked(p: ColorPick, c: Int) {
        colorPick = null
        rememberColor(c)
        when {
            p.mark != null -> recolor(p.mark, c)
            p.forWords -> pickWordsColor(c)
            else -> pickColor(c)
        }
    }

    fun pickStrength(step: Int) { strength = step.coerceIn(0, 2); prefs?.edit()?.putInt("strength", strength)?.apply() }

    /** Keeps a colour from the picker among the recent ones (up to six). */
    fun rememberColor(c: Int) {
        customColors.remove(c); customColors.add(0, c)
        while (customColors.size > 6) customColors.removeAt(customColors.size - 1)
        prefs?.edit()?.putString("custom_colors", customColors.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() })?.apply()
    }

    fun pickWidth(step: Int) { widthStep = step.coerceIn(0, 2); prefs?.edit()?.putInt("width_step", widthStep)?.apply() }
    fun colorFor(t: Tool) = if (usesPenColors(t)) penColor else markColor
    fun usesPenColors(t: Tool) = t == Tool.PEN || t == Tool.SHAPE || t == Tool.TEXT

    /** Whether a touch of this kind draws (true) or moves the page (false) in marking mode. */
    fun draws(stylus: Boolean): Boolean = marking && (stylus || !stylusSeen)

    fun loadMarks(page: Int) {
        val s = session ?: return
        if (!s.editable || marks.containsKey(page)) return
        marks[page] = emptyList()
        scope.launch { marks[page] = runCatching { s.marks(page) }.getOrDefault(emptyList()) }
    }

    fun loadAllMarks() {
        val s = session ?: return
        scope.launch { allMarks = runCatching { s.allMarks() }.getOrDefault(emptyList()) }
    }

    /**
     * The mark nearest a point, within [reach] points: measured to the stroke itself for drawings and
     * lines, to the words for highlights, and to the outline for the rest. Ties go to the smaller.
     */
    fun markAt(page: Int, x: Float, y: Float, reach: Float = 6f): Mark? = marks[page]
        ?.map { it to it.distanceTo(x, y) }
        ?.filter { it.second <= reach }
        ?.minWithOrNull(compareBy<Pair<Mark, Float>>({ it.second }, { it.first.box.width * it.first.box.height }))
        ?.first

    /** Picks a mark; handwriting brings along the rest of its word or line, as [inkScope] says. */
    fun selectMark(m: Mark?) {
        selectedMark = m
        group = if (m != null) expandInk(m) else emptyList()
        if (m != null) clearSelection()
    }

    /** Picks several marks at once (from the lasso); the first leads. */
    fun selectMarks(list: List<Mark>) {
        selectedMark = list.firstOrNull()
        group = list
        if (list.isNotEmpty()) clearSelection()
    }

    /** Every mark that is picked: the chosen one and, for handwriting or the lasso, the rest of its group. */
    val members: List<Mark>
        get() {
            val m = selectedMark ?: return emptyList()
            val g = group
            return if (g.firstOrNull()?.let { it.page == m.page && it.index == m.index } == true) listOf(m) + g.drop(1) else listOf(m)
        }

    /** The outline round everything picked. */
    val membersBox: Box?
        get() = members.takeIf { it.isNotEmpty() }?.map { it.box }?.reduce { a, b -> Box(minOf(a.x0, b.x0), minOf(a.y0, b.y0), maxOf(a.x1, b.x1), maxOf(a.y1, b.y1)) }

    val membersMovable: Boolean get() = members.isNotEmpty() && members.all { it.movable }
    val membersResizable: Boolean get() = membersMovable && (members.size > 1 || members[0].resizable)

    fun pickInkScope(sc: InkScope) {
        inkScope = sc
        prefs?.edit()?.putString("ink_scope", sc.name)?.apply()
        selectedMark?.let { group = expandInk(it) }
    }

    /**
     * The handwriting that goes with [a]: just it, its word (strokes close together on the same
     * line), or its line (strokes on the same line with no big break). Distances follow the size of
     * the writing on the page, so small and large handwriting group the same way.
     */
    private fun expandInk(a: Mark): List<Mark> {
        if (a.kind != MarkKind.INK || inkScope == InkScope.STROKE) return listOf(a)
        val inks = marks[a.page]?.filter { it.kind == MarkKind.INK } ?: return listOf(a)
        val heights = inks.map { it.box.height }.filter { it > 3f }.sorted()
        val h = (heights.getOrNull(heights.size / 2) ?: a.box.height).coerceAtLeast(6f)
        val gap = if (inkScope == InkScope.WORD) 0.45f * h else 3f * h
        val picked = mutableListOf(inks.firstOrNull { it.index == a.index } ?: a)
        var u = a.box
        var grew = true
        while (grew) {
            grew = false
            for (m in inks) {
                if (picked.any { it.index == m.index }) continue
                val cy = (m.box.y0 + m.box.y1) / 2
                val overlap = minOf(m.box.y1, u.y1) - maxOf(m.box.y0, u.y0)
                val sameRow = (cy >= u.y0 - 0.3f * h && cy <= u.y1 + 0.3f * h) || overlap >= 0.5f * minOf(m.box.height, u.height)
                val hGap = maxOf(0f, m.box.x0 - u.x1, u.x0 - m.box.x1)
                if (sameRow && hGap <= gap) {
                    picked += m
                    u = Box(minOf(u.x0, m.box.x0), minOf(u.y0, m.box.y0), maxOf(u.x1, m.box.x1), maxOf(u.y1, m.box.y1))
                    grew = true
                }
            }
        }
        return picked
    }

    // A gesture on a page in marking mode: begin, extend with points, finish.
    private var gestureReach = 6f

    fun begin(page: Int, x: Float, y: Float, eraser: Boolean, reach: Float = 6f) {
        gestureReach = reach
        gesturePage = page
        gestureTool = if (eraser) Tool.ERASER else tool
        anchor = x to y
        moved = false
        erased.clear()
        selectedMark = null
        when (gestureTool) {
            Tool.PEN -> draft = Draft.Ink(page, listOf(x to y), penColor, PenWidths[widthStep], 1f)
            Tool.MARKER -> draft = Draft.Ink(page, listOf(x to y), markColor, PenWidths[widthStep] * 4 + 6, 0.35f)
            Tool.ERASER -> eraseAt(page, x, y)
            Tool.SELECT -> draft = Draft.Lasso(page, listOf(x to y))
            else -> draft = null
        }
    }

    fun extend(points: List<Pair<Float, Float>>) {
        val page = gesturePage
        val a = anchor ?: return
        val last = points.lastOrNull() ?: return
        if (!moved && kotlin.math.hypot(last.first - a.first, last.second - a.second) > 3f) moved = true
        when (gestureTool) {
            Tool.PEN, Tool.MARKER -> (draft as? Draft.Ink)?.let { draft = it.copy(points = it.points + points) }
            Tool.SHAPE -> draft = Draft.Shape(page, shape, a.first, a.second, last.first, last.second, penColor, PenWidths[widthStep])
            Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKE -> {
                val s = session ?: return
                selectJob?.cancel()
                selectJob = scope.launch {
                    val sel = runCatching { s.select(page, a.first, a.second, last.first, last.second) }.getOrNull()
                    if (sel != null && gesturePage == page) draft = Draft.Words(page, sel.boxes, markColor)
                }
            }
            Tool.ERASER -> points.forEach { eraseAt(page, it.first, it.second) }
            Tool.SELECT -> (draft as? Draft.Lasso)?.let { draft = it.copy(points = it.points + points) }
            else -> Unit
        }
    }

    fun cancelGesture() { selectJob?.cancel(); draft = null; anchor = null; gesturePage = -1 }

    fun finish() {
        val page = gesturePage
        val a = anchor ?: return
        val s = session
        anchor = null
        // A tap (no drag) on a mark that is already there picks it, whatever the tool.
        if (!moved && gestureTool != Tool.ERASER) {
            val r = gestureReach
            val there = when (gestureTool) {
                // Pens dot i's and full stops, so only a tap right on a stroke picks it.
                Tool.PEN, Tool.MARKER -> markAt(page, a.first, a.second, 0f)
                Tool.TEXT, Tool.NOTE -> markAt(page, a.first, a.second, r)?.takeIf { it.kind == MarkKind.TEXT || it.kind == MarkKind.NOTE }
                Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKE -> markAt(page, a.first, a.second, r)?.takeIf { it.parts.isNotEmpty() }
                else -> markAt(page, a.first, a.second, r)
            }
            if (there != null) {
                draft = null
                selectMark(there)
                return
            }
        }
        when (gestureTool) {
            Tool.SELECT -> {
                val loop = (draft as? Draft.Lasso)?.points
                draft = null
                if (loop != null && loop.size >= 3) {
                    val inside = marks[page].orEmpty().filter { it.movable && lassoed(it, loop) }
                    if (inside.isNotEmpty()) selectMarks(inside)
                }
            }
            Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKE -> {
                val kind = when (gestureTool) { Tool.UNDERLINE -> MarkKind.UNDERLINE; Tool.STRIKE -> MarkKind.STRIKE; else -> MarkKind.HIGHLIGHT }
                val color = markColor
                val pending = selectJob
                change(page) {
                    pending?.join()
                    val boxes = (draft as? Draft.Words)?.boxes?.takeIf { moved }
                        ?: s?.selectWord(page, a.first, a.second)?.boxes
                    if (boxes.isNullOrEmpty()) { draft = null; return@change false }
                    draft = Draft.Words(page, boxes, color)
                    s?.addTextMark(page, kind, boxes, color, if (kind == MarkKind.HIGHLIGHT) HighlightStrengths[strength] else 1f) ?: false
                }
            }
            Tool.PEN, Tool.MARKER -> {
                val ink = draft as? Draft.Ink ?: return
                val pts = if (ink.points.size == 1) listOf(ink.points[0], ink.points[0].first + 0.4f to ink.points[0].second + 0.4f) else ink.points
                change(page) { s?.addInk(page, listOf(simplify(pts)), ink.color, ink.width, ink.opacity) ?: false }
            }
            Tool.SHAPE -> {
                val d = draft as? Draft.Shape
                if (!moved || d == null) { draft = null; return }
                change(page) { s?.addShape(page, d.kind.kind, d.x0, d.y0, d.x1, d.y1, d.color, d.width) ?: false }
            }
            Tool.TEXT -> if (!moved) boxEdit = BoxEdit(page, a.first, a.second)
            Tool.NOTE -> if (!moved) textAsk = TextAsk(page, a.first, a.second, note = true)
            Tool.ERASER -> {
                val gone = erased.toList().sortedDescending()
                erased.clear()
                if (gone.isNotEmpty()) change(page) { gone.forEach { idx -> s?.deleteMark(page, idx) }; true }
            }
        }
    }

    private fun eraseAt(page: Int, x: Float, y: Float) {
        markAt(page, x, y, gestureReach * 0.6f)?.let { erased += it.index }
    }

    /** Whether a mark sits inside a loop: most of a drawing's points, or the middle of anything else. */
    private fun lassoed(m: Mark, loop: List<Pair<Float, Float>>): Boolean {
        val pts = m.path.flatten()
        return if (pts.isNotEmpty()) pts.count { inside(it.first, it.second, loop) } * 10 >= pts.size * 6
        else inside((m.box.x0 + m.box.x1) / 2, (m.box.y0 + m.box.y1) / 2, loop)
    }

    private fun inside(x: Float, y: Float, poly: List<Pair<Float, Float>>): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** Writes the words the user typed: a new text box or note, or a mark's note. */
    fun submitText(text: String) {
        val ask = textAsk ?: return
        textAsk = null
        val s = session ?: return
        val m = ask.mark
        when {
            m != null -> change(m.page) { s.setMarkNote(m.page, m.index, text.trim()) }
            text.isBlank() -> Unit
            ask.note -> change(ask.page) { s.addNote(ask.page, ask.x, ask.y, text.trim(), markColor) }
            else -> change(ask.page) { s.addTextBox(ask.page, ask.x, ask.y, text.trimEnd(), penColor, TextSizes[widthStep]) }
        }
    }

    /** Marks the selected words, from the bar over a selection. The notice that follows can undo it. */
    fun markSelection(kind: MarkKind) {
        val sel = selection ?: return
        val color = markColor
        val opacity = if (kind == MarkKind.HIGHLIGHT) HighlightStrengths[strength] else 1f
        clearSelection()
        draft = Draft.Words(sel.page, sel.boxes, color)
        undoNotice = when (kind) { MarkKind.UNDERLINE -> "Underlined"; MarkKind.STRIKE -> "Struck through"; else -> "Highlighted" }
        change(sel.page) { session?.addTextMark(sel.page, kind, sel.boxes, color, opacity) ?: false }
    }

    /** New colour for the picked marks; they stay picked so the colour can be tried again. */
    fun recolor(m: Mark, color: Int) {
        val all = if (selectedMark?.index == m.index && selectedMark?.page == m.page) members else listOf(m)
        selectedMark = m.copy(color = color)
        group = all.map { it.copy(color = color) }
        change(m.page) { session?.setMarksColor(m.page, all.map { it.index }, color) ?: false }
    }

    /** New stroke width for the picked drawings, lines and shapes. */
    fun rewidth(width: Float) {
        val all = members.takeIf { it.isNotEmpty() } ?: return
        val page = all[0].page
        selectedMark = all[0].copy(width = width)
        group = all.map { it.copy(width = width) }
        change(page) { session?.setMarksWidth(page, all.map { it.index }, width) ?: false }
    }

    /** New strength for a highlight. */
    fun restrength(m: Mark, step: Int) {
        val o = HighlightStrengths[step.coerceIn(0, 2)]
        selectedMark = m.copy(opacity = o)
        change(m.page) { session?.setMarkOpacity(m.page, m.index, o) ?: false }
    }

    /** Every mark in [list] as plain text, page by page, to copy or share. */
    fun marksText(name: String, list: List<Mark>): String = buildString {
        append(name).append(" — marks\n")
        list.groupBy { it.page }.toSortedMap().forEach { (page, ms) ->
            append("\nPage ").append(page + 1).append('\n')
            ms.forEach { m ->
                val words = m.quoted.ifBlank { if (m.kind == MarkKind.TEXT) m.note else "" }
                append("• ").append(m.kind.label)
                if (words.isNotBlank()) append(": “").append(words.trim()).append('”')
                append('\n')
                if (m.note.isNotBlank() && m.kind != MarkKind.TEXT) append("  Note: ").append(m.note.trim()).append('\n')
            }
        }
    }
    fun delete(m: Mark) {
        val all = if (selectedMark?.index == m.index && selectedMark?.page == m.page) members else listOf(m)
        selectedMark = null
        group = emptyList()
        change(m.page) { session?.deleteMarks(m.page, all.map { it.index }) ?: false }
    }
    fun editNote(m: Mark) { selectedMark = null; textAsk = TextAsk(m.page, m.box.x0, m.box.y0, note = true, mark = m) }

    /** Opens the editor for a text box's words. */
    fun editText(m: Mark) { if (m.kind == MarkKind.TEXT) boxEdit = BoxEdit(m.page, m.box.x0, m.box.y0, m) }

    /** Writes a text box's new words, size and colour; the box stays selected. */
    /** Writes a new text box, or changes one, in [style]; the style becomes the one new boxes start with. */
    fun submitBox(e: BoxEdit, text: String, style: BoxStyle) {
        boxEdit = null
        boxStyle = style
        prefs?.edit()?.putString("box_style", styleToPref(style))?.apply()
        val m = e.mark
        val s = session ?: return
        when {
            m != null && text.isBlank() -> delete(m)
            m != null -> change(m.page) { s.editTextBox(m.page, m.index, text.trimEnd(), style) }
            text.isBlank() -> Unit
            else -> change(e.page) { s.addTextBox(e.page, e.x, e.y, text.trimEnd(), style) }
        }
    }


    /** A tap on the chosen mark: a text box opens for editing, a note shows its words. */
    fun tapSelected(m: Mark) {
        when (m.kind) {
            MarkKind.TEXT -> editText(m)
            MarkKind.NOTE -> editNote(m)
            else -> Unit
        }
    }

    /** Shows where the selected mark would go while it is dragged. */
    fun dragMark(to: Box?) { moving = to }

    /** Moves or resizes a mark to [to]; it stays selected so it can be adjusted again. */
    fun reshape(m: Mark, to: Box) {
        val all = members.takeIf { it.isNotEmpty() && selectedMark?.index == m.index } ?: listOf(m)
        if (!all.all { it.movable }) { moving = null; return }
        val from = membersBox ?: m.box
        val sx = to.width / from.width.coerceAtLeast(0.01f)
        val sy = to.height / from.height.coerceAtLeast(0.01f)
        fun map(b: Box) = if (all.size == 1) to else Box(
            to.x0 + (b.x0 - from.x0) * sx, to.y0 + (b.y0 - from.y0) * sy,
            to.x0 + (b.x1 - from.x0) * sx, to.y0 + (b.y1 - from.y0) * sy,
        )
        val moves = all.map { it.index to map(it.box) }
        selectedMark = all[0].copy(box = moves[0].second)
        group = all.mapIndexed { k, it -> it.copy(box = moves[k].second) }
        change(m.page) { session?.reshapeMarks(m.page, moves) ?: false }
    }

    fun undo() { selectedMark = null; change(null) { session?.undo() ?: false } }
    fun redo() { selectedMark = null; change(null) { session?.redo() ?: false } }
    fun flatten() = change(null) { session?.flatten() ?: false }

    /** Called after the file is saved and reopened. */
    fun markSaved() { dirty = false }

    /**
     * Runs one change to the file, then refreshes what is shown: the page is drawn again and the
     * live draft is kept on screen until the new drawing has arrived, so nothing flickers.
     */
    private fun change(page: Int?, block: suspend () -> Boolean) {
        scope.launch {
            val before = page?.let { pages?.drawCount(it) } ?: 0
            val ok = runCatching { block() }.onFailure { problem = it.message ?: "That change could not be made." }.getOrDefault(false)
            val s = session
            if (s != null) { canUndo = s.canUndo; canRedo = s.canRedo; dirty = s.dirty }
            if (ok) {
                if (page == null) { marks.clear(); pages?.invalidate(null) } else { marks.remove(page); pages?.invalidate(listOf(page)) }
                // Keep the chosen mark chosen, with its new outline, size and colour.
                val keep = selectedMark
                if (s != null && page != null && keep != null && keep.page == page) {
                    val was = members.map { it.index }
                    val fresh = runCatching { s.marks(page) }.getOrNull()
                    selectedMark = fresh?.getOrNull(keep.index)?.takeIf { it.kind == keep.kind }
                    group = if (selectedMark == null || fresh == null) emptyList() else was.mapNotNull { fresh.getOrNull(it) }
                }
                if (page != null) loadMarks(page) else marks.keys.toList().forEach { loadMarks(it) }
                if (allMarks != null) loadAllMarks()
                if (page != null) pages?.awaitRedraw(page, before)
            }
            if (page == null || draft?.page == page) draft = null
            if (page == null || selectedMark == null || selectedMark?.page == page) moving = null
        }
    }

    /** Thins a stroke to points at least half a point apart; the file stays small and the line smooth. */
    private fun simplify(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (pts.size < 3) return pts
        val out = ArrayList<Pair<Float, Float>>(pts.size)
        out += pts.first()
        for (p in pts.drop(1)) {
            val l = out.last()
            if (kotlin.math.hypot(p.first - l.first, p.second - l.second) >= 0.5f) out += p
        }
        if (out.last() != pts.last()) out += pts.last()
        return out
    }

    /** For screenshot tests. */
    fun showMarking(t: Tool, d: Draft?, selected: Mark? = null) { marking = true; tool = t; draft = d; selectedMark = selected }
    fun showSelectedForTest(m: Mark?, movingTo: Box? = null, with: List<Mark> = emptyList()) { selectedMark = m; group = listOfNotNull(m) + with; moving = movingTo }
    fun setMarksForTest(page: Int, list: List<Mark>) { marks[page] = list }
    fun showReflowForTest(on: Boolean, paras: Map<Int, List<Para>>) { reflow = on; paragraphs.clear(); paragraphs.putAll(paras) }
    fun setAllMarksForTest(list: List<Mark>) { allMarks = list }
    fun showSelectionlessDraftForTest() { selection = null }
    fun stopMarkingKeepSelectionForTest() { marking = false }

    /** For screenshot tests. */
    fun showHits(q: String, found: Map<Int, List<List<Box>>>, at: Int, words: Map<Int, List<Hit>> = emptyMap(), list: Boolean = false) {
        query = q; searchOpen = true; resultsOpen = list
        hits.clear(); hits.putAll(found)
        snippets.clear(); snippets.putAll(words)
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
