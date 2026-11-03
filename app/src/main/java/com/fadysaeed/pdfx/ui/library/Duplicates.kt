package com.fadysaeed.pdfx.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.components.IconButton
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type

/**
 * The same file kept more than once, group by group. In each group one copy is kept (the one
 * opened most recently, else the one outside Downloads, WhatsApp and Received, else the oldest);
 * tapping another copy keeps that one instead. The rest go to the trash, and the kept copy takes
 * their tags, star and bookmarks.
 */
@Composable
fun DuplicatesScreen(
    groups: List<List<Doc>>,
    label: (String?) -> String,
    onKeep: (List<Doc>, Doc) -> Unit,
    onKeepAll: (List<List<Doc>>) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // The copy to keep in each group, by the group's fingerprint; the suggestion until changed.
    val chosen = remember { mutableStateMapOf<String, Long>() }
    fun keepOf(g: List<Doc>): Doc = g.firstOrNull { it.id == chosen[g.first().fingerprint] } ?: g.first()
    Column(Modifier.fillMaxSize().background(Ink.Black).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.BACK, "Back", onClose)
            Column(Modifier.weight(1f)) {
                Text("Files kept more than once", style = Type.title)
                val spare = groups.sumOf { it.size - 1 }
                Text("${groups.size} ${if (groups.size == 1) "file" else "files"}, $spare spare ${if (spare == 1) "copy" else "copies"}", style = Type.small)
            }
        }
        Text(
            "Each group is the very same file. The copy marked Keep stays; tap another to keep it instead. The others go to the trash for 30 days, and the kept copy takes their tags, star and bookmarks.",
            style = Type.note, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule)
        LazyColumn(Modifier.weight(1f)) {
            items(groups, key = { it.first().fingerprint ?: it.first().id.toString() }) { g ->
                val keep = keepOf(g)
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(LibraryModel.titleOf(keep), style = Type.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${g.size} copies · ${humanSize(keep.size)} each", style = Type.small)
                    Spacer(Modifier.height(8.dp))
                    for (d in g) {
                        val on = d.id == keep.id
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .border(1.dp, if (on) Ink.Amber else Ink.Rule, RoundedCornerShape(4.dp))
                                .clickable { chosen[g.first().fingerprint ?: ""] = d.id }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(LibraryModel.display(d), style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(label(d.folder), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(if (on) "Keep" else "Trash", style = Type.small.copy(color = if (on) Ink.Amber else Ink.Dust))
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Keep this one only", style = Type.bodyStrong.copy(color = Ink.Amber),
                            modifier = Modifier.clickable { onKeep(g, keep) }.padding(vertical = 6.dp),
                        )
                    }
                }
                Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule)
            }
        }
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp)) {
            AmberButton("Keep the marked copies, trash the rest", { onKeepAll(groups.map { g -> val k = keepOf(g); listOf(k) + g.filter { it.id != k.id } }) }, Modifier.fillMaxWidth())
        }
    }
}
