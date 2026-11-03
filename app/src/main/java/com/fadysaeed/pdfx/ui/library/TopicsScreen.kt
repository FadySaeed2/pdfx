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
import com.fadysaeed.pdfx.data.TopicSuggestion
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.components.IconButton
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type

/**
 * Topics found in the files, each with how many files it would tag and a few of them. Tap the ones
 * worth a tag, then add them all at once.
 */
@Composable
fun TopicsScreen(load: suspend () -> List<TopicSuggestion>, onAdd: (List<TopicSuggestion>) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var topics by remember { mutableStateOf<List<TopicSuggestion>?>(null) }
    val picked = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { topics = runCatching { load() }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize().background(Ink.Black).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.BACK, "Back", onClose)
            Text("Suggested tags", style = Type.title, modifier = Modifier.weight(1f))
        }
        Text(
            "Topics that run through your files. A file counts when its title names the topic or it comes up on a good share of its pages, not for a passing mention. Pick the ones you want as tags.",
            style = Type.note, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule)
        val list = topics
        when {
            list == null -> Text("Looking through your files…", style = Type.note, modifier = Modifier.padding(20.dp))
            list.isEmpty() -> Text("No topics found yet. PDFx needs to have read your files first; try again once the reading line at the top of the library is gone.", style = Type.note, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.term }) { t ->
                    val on = t.term in picked
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) picked.remove(t.term) else picked.add(t.term) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(22.dp).border(1.5.dp, if (on) Ink.Amber else Ink.Edge, RoundedCornerShape(4.dp)).background(if (on) Ink.Amber else Ink.Black, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) { if (on) GlyphIcon(Glyph.CHECK, Ink.Black, size = 16.dp) }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.term, style = Type.bodyStrong)
                            Text(t.examples.joinToString(" · "), style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${t.docIds.size}", style = Type.figure)
                    }
                }
            }
        }
        if (!list.isNullOrEmpty()) {
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp)) {
                AmberButton(
                    if (picked.isEmpty()) "Pick topics to tag" else "Add ${picked.size} ${if (picked.size == 1) "tag" else "tags"}",
                    { onAdd(list.filter { it.term in picked }) }, Modifier.fillMaxWidth(), enabled = picked.isNotEmpty(),
                )
            }
        }
    }
}
