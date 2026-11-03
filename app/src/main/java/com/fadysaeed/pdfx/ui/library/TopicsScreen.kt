package com.fadysaeed.pdfx.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fadysaeed.pdfx.data.TopicGroup
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.components.IconButton
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type

/**
 * Suggested tags by area: each area ("Mood disorders") can be a tag of its own, and each topic in it
 * ("Bipolar", "Lithium") with how many files and a few of them. Pick any mix, then add them at once.
 */
@Composable
fun TopicsScreen(load: suspend () -> List<TopicGroup>, onAdd: (List<Pair<String, List<Long>>>) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var groups by remember { mutableStateOf<List<TopicGroup>?>(null) }
    val picked = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { groups = runCatching { load() }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize().background(Ink.Black).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.BACK, "Back", onClose)
            Text("Suggested tags", style = Type.title, modifier = Modifier.weight(1f))
        }
        Text(
            "Read from inside your files, not just their names: a file counts for a topic when its title says so or the topic's words and medicines come up on a good share of its pages. Each file gets its four strongest topics at most. Areas can be tags too.",
            style = Type.note, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule)
        val list = groups
        when {
            list == null -> Text("Reading through your files…", style = Type.note, modifier = Modifier.padding(20.dp))
            list.isEmpty() -> Text("No topics found yet. PDFx needs to have read your files first; try again once the reading line at the top of the library is gone.", style = Type.note, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(Modifier.weight(1f)) {
                for (g in list) {
                    item(key = "a:" + g.area) {
                        val key = "area:" + g.area
                        val on = key in picked
                        Row(
                            Modifier.fillMaxWidth().background(Ink.Field)
                                .then(if (g.areaDocIds.isNotEmpty()) Modifier.clickable { if (on) picked.remove(key) else picked.add(key) } else Modifier)
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (g.areaDocIds.isNotEmpty()) { Tick(on); Spacer(Modifier.width(16.dp)) }
                            Column(Modifier.weight(1f)) {
                                Text(g.area.uppercase(), style = Type.eyebrow.copy(color = Ink.Sand))
                                if (g.areaDocIds.isNotEmpty()) Text("The whole area as one tag", style = Type.small)
                            }
                            if (g.areaDocIds.isNotEmpty()) Text("${g.areaDocIds.size}", style = Type.figure)
                        }
                    }
                    items(g.topics, key = { g.area + "/" + it.term }) { t ->
                        val on = t.term in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) picked.remove(t.term) else picked.add(t.term) }.padding(start = 36.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tick(on)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.term, style = Type.bodyStrong)
                                Text(t.examples.joinToString(" · "), style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("${t.docIds.size}", style = Type.figure)
                        }
                    }
                    item(key = "h:" + g.area) { Hairline(color = Ink.Rule) }
                }
            }
        }
        if (!list.isNullOrEmpty()) {
            val chosen = list.flatMap { g ->
                (if ("area:" + g.area in picked) listOf(g.area to g.areaDocIds) else emptyList()) +
                    g.topics.filter { it.term in picked }.map { it.term to it.docIds }
            }.distinctBy { it.first }
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp)) {
                AmberButton(
                    if (chosen.isEmpty()) "Pick topics to tag" else "Add ${chosen.size} ${if (chosen.size == 1) "tag" else "tags"}",
                    { onAdd(chosen) }, Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun Tick(on: Boolean) {
    Box(
        Modifier.size(22.dp).border(1.5.dp, if (on) Ink.Amber else Ink.Edge, RoundedCornerShape(4.dp)).background(if (on) Ink.Amber else Ink.Black, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) { if (on) GlyphIcon(Glyph.CHECK, Ink.Black, size = 16.dp) }
}
