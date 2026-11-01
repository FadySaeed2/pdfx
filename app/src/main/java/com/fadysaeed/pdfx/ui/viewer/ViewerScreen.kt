package com.fadysaeed.pdfx.ui.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.ui.library.LibraryModel
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Corner
import com.fadysaeed.pdfx.ui.components.GhostButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.components.IconButton
import com.fadysaeed.pdfx.ui.components.InkField
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private sealed interface Load {
    data object Opening : Load
    data class Locked(val session: PdfSession, val wrong: Boolean) : Load
    data class Ready(val session: PdfSession) : Load
    data class Failed(val reason: String) : Load
}

/** A document to open, and the page to start on when it should not be the last one read. */
data class OpenRequest(val uri: Uri, val page: Int? = null, val id: Long = System.nanoTime())

/** Opens a document, asks for a password when needed, and shows it. */
@OptIn(FlowPreview::class)
@Composable
fun ViewerScreen(request: OpenRequest, onClose: (failure: String?) -> Unit) {
    val context = LocalContext.current
    val library = (context.applicationContext as PdfxApp).library
    var doc by remember(request) { mutableStateOf<Doc?>(null) }
    var load by remember(request) { mutableStateOf<Load>(Load.Opening) }
    val scope = rememberCoroutineScope()
    val fallbackName = remember(request) { library.describe(request.uri).first.removeSuffix(".pdf") }
    val name = doc?.let { LibraryModel.display(it) } ?: fallbackName

    LaunchedEffect(request) {
        doc = runCatching { library.resolve(request.uri) }.getOrNull()
        load = try {
            val s = PdfSession.open(context, request.uri)
            if (s.needsPassword) Load.Locked(s, wrong = false) else Load.Ready(s)
        } catch (t: Throwable) {
            Load.Failed(t.message?.takeIf { it.isNotBlank() } ?: "The file is not a readable PDF.")
        }
    }
    DisposableEffect(request) {
        onDispose {
            when (val l = load) {
                is Load.Ready -> l.session.close()
                is Load.Locked -> l.session.close()
                else -> Unit
            }
        }
    }
    BackHandler { onClose(null) }

    when (val l = load) {
        Load.Opening -> Box(Modifier.fillMaxSize().background(Ink.Black))
        is Load.Failed -> LaunchedEffect(l) { onClose("Couldn't open $name. ${l.reason}") }
        is Load.Locked -> LockedPanel(name, l.wrong, onCancel = { onClose(null) }) { pw ->
            scope.launch {
                load = if (l.session.unlock(pw)) Load.Ready(l.session) else Load.Locked(l.session, wrong = true)
            }
        }
        is Load.Ready -> {
            val session = l.session
            val last = (session.pageCount - 1).coerceAtLeast(0)
            val start = remember(request) { (request.page ?: doc?.lastPage ?: 0).coerceIn(0, last) }
            LaunchedEffect(request, doc?.id) {
                doc?.let { library.recordOpen(it.id, session.pageCount, locked = false) }
            }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
            val images = remember(session) { PageImages(session, scope) }
            LaunchedEffect(listState, doc?.id) {
                val id = doc?.id ?: return@LaunchedEffect
                snapshotFlow { centreItem(listState) }
                    .distinctUntilChanged()
                    .debounce(400)
                    .collect { library.updatePage(id, it) }
            }
            ViewerContent(
                name = name,
                pageCount = session.pageCount,
                aspects = images.aspects,
                image = { images.images[it] },
                listState = listState,
                onWant = images::want,
                onBack = { onClose(null) },
            )
        }
    }
}

@Composable
fun ViewerContent(
    name: String,
    pageCount: Int,
    aspects: List<Float>,
    image: (Int) -> ImageBitmap?,
    listState: LazyListState,
    onWant: (Want) -> Unit,
    onBack: () -> Unit,
    chromeInitiallyVisible: Boolean = true,
) {
    var chrome by remember { mutableStateOf(chromeInitiallyVisible) }
    val current by remember { derivedStateOf { centreItem(listState) } }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(Ink.Black)) {
        DocumentView(
            pageCount = pageCount,
            aspects = aspects,
            image = image,
            listState = listState,
            onWant = onWant,
            onTap = { chrome = !chrome },
            contentPadding = PaddingValues(top = top + 64.dp, bottom = bottom + 72.dp),
        )

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().background(Ink.Black).windowInsetsPadding(WindowInsets.statusBars)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Glyph.BACK, "Back to library", onBack)
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(name, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (pageCount == 1) "1 page" else "$pageCount pages", style = Type.small)
                    }
                }
                Hairline(color = Ink.Rule)
            }
        }

        // Where you are in the document: a thin amber line along the bottom edge, always visible.
        val fraction = if (pageCount <= 1) 1f else current / (pageCount - 1f)
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                .height(2.dp)
                .background(Ink.Amber),
        )

        AnimatedVisibility(
            chrome, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 18.dp),
        ) {
            Box(
                Modifier
                    .background(Ink.Black, Corner)
                    .border(1.dp, Ink.Edge, Corner)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("${current + 1} / $pageCount", style = Type.figure)
            }
        }
    }
}

@Composable
internal fun LockedPanel(name: String, wrong: Boolean, onCancel: () -> Unit, onUnlock: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Black)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(Glyph.LOCK, Ink.Amber, size = 32.dp)
        Spacer(Modifier.height(18.dp))
        Text("LOCKED", style = Type.eyebrow)
        Spacer(Modifier.height(6.dp))
        Text(name, style = Type.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(
            if (wrong) "That password didn't open it. Check capitals and try again." else "This PDF needs its password to open.",
            style = Type.note.copy(color = if (wrong) Ink.Amber else Ink.Dust),
        )
        Spacer(Modifier.height(20.dp))
        InkField(pw, { pw = it }, "Password", secret = true, onDone = { if (pw.isNotEmpty()) onUnlock(pw) })
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Cancel", onCancel, Modifier.weight(1f), minHeight = 52.dp)
            AmberButton("Unlock", { onUnlock(pw) }, Modifier.weight(1f), enabled = pw.isNotEmpty())
        }
        Spacer(Modifier.width(1.dp))
    }
}
