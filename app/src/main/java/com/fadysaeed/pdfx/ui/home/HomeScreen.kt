package com.fadysaeed.pdfx.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fadysaeed.pdfx.data.RecentFile
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeContent(
    recents: List<RecentFile>,
    status: String?,
    onOpenRecent: (RecentFile) -> Unit,
    onPick: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Black)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PDFx", style = Type.brand)
            Spacer(Modifier.weight(1f))
            Text("ON THIS PHONE ONLY", style = Type.eyebrow)
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Text("LIBRARY", style = Type.eyebrow)
            Spacer(Modifier.height(6.dp))
            Text("Recent", style = Type.display)
            Spacer(Modifier.height(4.dp))
            Text(
                if (recents.isEmpty()) "Nothing opened yet." else "${recents.size} ${if (recents.size == 1) "document" else "documents"}",
                style = Type.note,
            )
            if (status != null) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(3.dp).heightIn(min = 34.dp).background(Ink.Amber))
                    Spacer(Modifier.width(12.dp))
                    Text(status, style = Type.note.copy(color = Ink.Sand))
                }
            }
            Spacer(Modifier.height(22.dp))
            Hairline(color = Ink.Rule)
        }

        Box(Modifier.weight(1f)) {
            if (recents.isEmpty()) {
                EmptyLibrary(Modifier.padding(horizontal = 20.dp, vertical = 28.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(recents, key = { it.uri }) { f ->
                        RecentRow(f, now) { onOpenRecent(f) }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Ink.Black)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            AmberButton("Open a PDF", onPick, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Open a PDF from your phone, or choose PDFx from Open with or Share in any other app.", style = Type.body.copy(color = Ink.Sand))
        Text("Every file is read where it lives. PDFx has no internet access, so nothing you open here can leave the phone.", style = Type.note)
    }
}

@Composable
private fun RecentRow(f: RecentFile, now: Long, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.PAGE, Ink.Dust, size = 26.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(meta(f, now), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (f.lastPage > 0 && f.pages > 0) {
                Spacer(Modifier.width(12.dp))
                Text("p. ${f.lastPage + 1}", style = Type.figure)
            }
        }
        Hairline(Modifier.padding(start = 60.dp))
    }
}

private fun meta(f: RecentFile, now: Long): String {
    val parts = mutableListOf<String>()
    if (f.pages > 0) parts += if (f.pages == 1) "1 page" else "${f.pages} pages"
    if (f.sizeBytes >= 0) parts += humanSize(f.sizeBytes)
    if (f.lastOpened > 0) parts += whenOpened(f.lastOpened, now)
    return parts.joinToString("  ·  ")
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 1024.0).let { if (it < 10) String.format(Locale.US, "%.1f", it) else it.toInt().toString() }} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

fun whenOpened(millis: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val d: LocalDate = t.toLocalDate()
    return when (d) {
        today -> "Today ${t.format(timeFmt)}"
        today.minusDays(1) -> "Yesterday ${t.format(timeFmt)}"
        else -> t.format(dayFmt)
    }
}
