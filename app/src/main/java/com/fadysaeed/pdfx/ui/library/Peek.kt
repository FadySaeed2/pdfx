package com.fadysaeed.pdfx.ui.library

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.GhostButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How many of a file's first pages a quick look shows. */
private const val PEEK_PAGES = 6

/**
 * A quick look at a file without opening it (a long press in the library): its title and details,
 * its first pages side by side to swipe through, and what to do next. A tap on a page opens the
 * file there.
 */
@Composable
fun PeekBody(d: Doc, place: String, onOpen: (Int?) -> Unit, onSelect: () -> Unit, onStar: () -> Unit, onMore: () -> Unit) {
    val context = LocalContext.current
    val pages by produceState<List<ImageBitmap>?>(null, d.key, d.modified) {
        val got = ArrayList<ImageBitmap>()
        withContext(Dispatchers.IO) {
            runCatching {
                val s = PdfSession.open(context, Uri.parse(d.uri))
                try {
                    if (!s.needsPassword) for (i in 0 until minOf(PEEK_PAGES, s.pageCount)) {
                        got += s.render(i, 620).asImageBitmap()
                        value = got.toList()
                    }
                } finally {
                    s.close()
                }
            }
        }
        value = got.toList()
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(LibraryModel.titleOf(d), style = Type.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
            // Called by the title inside it: its own name too, so the file can be found by it.
            if (LibraryModel.titleOf(d) != LibraryModel.display(d)) Text(d.name, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val about = listOfNotNull(
                LibraryModel.byline(d).takeIf { it.isNotBlank() },
                if (d.pages > 0) (if (d.pages == 1) "1 page" else "${d.pages} pages") else null,
                humanSize(d.size), place,
            )
            Text(about.joinToString("  ·  "), style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val list = pages
        Box(Modifier.fillMaxWidth().height(330.dp), contentAlignment = Alignment.Center) {
            when {
                d.locked -> Row(verticalAlignment = Alignment.CenterVertically) { GlyphIcon(Glyph.LOCK, Ink.Ash, size = 20.dp); Spacer(Modifier.width(10.dp)); Text("Locked with a password: open it to see inside.", style = Type.note) }
                list == null -> Text("Drawing its pages…", style = Type.note)
                list.isEmpty() -> Text("PDFx could not draw this file's pages.", style = Type.note)
                else -> LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)) {
                    itemsIndexed(list) { i, img ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val ratio = img.width.toFloat() / img.height.coerceAtLeast(1)
                            Image(
                                img, null, contentScale = ContentScale.Fit,
                                modifier = Modifier.height(300.dp).width((300 * ratio).dp).background(Ink.Paper).border(1.dp, Ink.Rule)
                                    .clickable { onOpen(i) }.semantics { contentDescription = "Page ${i + 1}" },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("${i + 1}", style = Type.figure)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AmberButton(if (d.lastPage > 0) "Open at p. ${d.lastPage + 1}" else "Open", { onOpen(null) }, Modifier.weight(1f))
            GhostButton("Select", onSelect, Modifier.weight(1f), minHeight = 52.dp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Row(Modifier.clickable(onClick = onStar).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(if (d.starred) Glyph.STAR_FILLED else Glyph.STAR, Ink.Amber, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (d.starred) "Starred" else "Star", style = Type.bodyStrong.copy(color = Ink.Amber))
            }
            Row(Modifier.clickable(onClick = onMore).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.MORE, Ink.Amber, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text("More", style = Type.bodyStrong.copy(color = Ink.Amber))
            }
        }
    }
}
