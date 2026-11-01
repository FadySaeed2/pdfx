package com.fadysaeed.pdfx.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.data.Tag
import com.fadysaeed.pdfx.data.TagCount
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.GhostButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.components.InkField
import com.fadysaeed.pdfx.ui.components.SheetRow
import com.fadysaeed.pdfx.ui.components.TagColors
import com.fadysaeed.pdfx.ui.components.tagColor
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type
import kotlinx.coroutines.delay
import java.io.File

private sealed interface Sheet {
    data class Actions(val doc: Doc) : Sheet
    data class Destination(val doc: Doc, val copy: Boolean) : Sheet
    data class Tags(val doc: Doc) : Sheet
    data object Sorting : Sheet
    data class TagMenu(val tag: TagCount) : Sheet
}

private sealed interface Ask {
    data class Rename(val doc: Doc) : Ask
    data class DeleteForever(val doc: Doc) : Ask
    data object EmptyTrash : Ask
    data class NewFolder(val doc: Doc, val copy: Boolean) : Ask
    data class NewTag(val doc: Doc?) : Ask
    data class DeleteTag(val tag: TagCount) : Ask
}

/** The library with all its menus. [onOpen] opens a document, optionally at a page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryModel, onOpen: (Doc, Int?) -> Unit, onPick: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PdfxApp
    val ui by vm.ui.collectAsState()
    val notice by vm.notice.collectAsState()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }

    BackHandler(enabled = ui.place != Place() || ui.query.isNotEmpty()) { vm.back() }
    LaunchedEffect(notice?.id) {
        if (notice != null) { delay(if (notice?.undo != null) 6000 else 3500); vm.dismissNotice() }
    }

    LibraryContent(
        ui = ui,
        notice = notice,
        label = vm.library::folderLabel,
        thumb = { d ->
            val img by produceState(app.thumbs.cached(d), d.key, d.modified, d.locked) { value = app.thumbs.load(d) }
            img
        },
        actions = LibraryActions(
            open = onOpen,
            more = { sheet = Sheet.Actions(it) },
            section = vm::go,
            folder = vm::openFolder,
            tag = vm::openTag,
            tagMore = { sheet = Sheet.TagMenu(it) },
            back = { vm.back() },
            query = vm::setQuery,
            sort = { sheet = Sheet.Sorting },
            pick = onPick,
            grantAccess = { askForAllFiles(context) },
            emptyTrash = { ask = Ask.EmptyTrash },
            newTag = { ask = Ask.NewTag(null) },
            undo = { notice?.undo?.invoke(); vm.dismissNotice() },
            dismissNotice = vm::dismissNotice,
        ),
    )

    val current = sheet
    if (current != null) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = state,
            containerColor = Ink.Field,
            scrimColor = Ink.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
            dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).width(36.dp).height(3.dp).background(Ink.Edge)) },
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 8.dp)) {
                when (current) {
                    is Sheet.Actions -> ActionsSheet(
                        d = current.doc,
                        label = vm.library.folderLabel(current.doc.folder),
                        pick = { a ->
                            sheet = null
                            val d = current.doc
                            when (a) {
                                DocAction.OPEN -> onOpen(d, null)
                                DocAction.STAR -> vm.star(d)
                                DocAction.RENAME -> ask = Ask.Rename(d)
                                DocAction.MOVE -> sheet = Sheet.Destination(d, copy = false)
                                DocAction.COPY -> sheet = Sheet.Destination(d, copy = true)
                                DocAction.TAGS -> sheet = Sheet.Tags(d)
                                DocAction.SHARE -> share(context, d)
                                DocAction.TRASH -> vm.trash(d)
                                DocAction.RESTORE -> vm.restore(d)
                                DocAction.DELETE -> ask = Ask.DeleteForever(d)
                                DocAction.SAVE_COPY -> vm.saveCopy(d)
                                DocAction.FORGET -> vm.forget(d)
                                DocAction.FOLDER -> d.folder?.let { vm.openFolder(it) }
                            }
                        },
                    )
                    is Sheet.Destination -> DestinationSheet(
                        doc = current.doc,
                        copy = current.copy,
                        places = vm.destinations(),
                        currentFolder = current.doc.folder,
                        onPick = { dir -> sheet = null; if (current.copy) vm.copy(current.doc, dir) else vm.move(current.doc, dir) },
                        onNewFolder = { sheet = null; ask = Ask.NewFolder(current.doc, current.copy) },
                    )
                    is Sheet.Tags -> TagsSheet(
                        tags = ui.allTags,
                        on = ui.docTags[current.doc.id].orEmpty(),
                        doc = current.doc,
                        toggle = { t, on -> vm.setTag(current.doc, t, on) },
                        newTag = { sheet = null; ask = Ask.NewTag(current.doc) },
                    )
                    Sheet.Sorting -> SortSheet(ui.sort) { by -> vm.setSort(by); sheet = null }
                    is Sheet.TagMenu -> {
                        SheetTitle(current.tag.name, "${current.tag.count} ${if (current.tag.count == 1) "document" else "documents"}")
                        SheetRow(Glyph.OPEN, "Show documents", { sheet = null; vm.openTag(current.tag.id) })
                        SheetRow(Glyph.TRASH, "Delete tag", { sheet = null; ask = Ask.DeleteTag(current.tag) }, note = "Files keep everything else; only the tag goes.")
                    }
                }
            }
        }
    }

    when (val a = ask) {
        null -> Unit
        is Ask.Rename -> TextAsk(
            title = "Rename",
            initial = LibraryModel.display(a.doc),
            confirm = "Rename",
            onDismiss = { ask = null },
        ) { vm.rename(a.doc, it); ask = null }
        is Ask.NewFolder -> TextAsk(
            title = "New folder",
            note = "Made inside Documents.",
            initial = "",
            confirm = if (a.copy) "Copy here" else "Move here",
            onDismiss = { ask = null },
        ) { name ->
            val dir = vm.newFolder(name)
            if (dir != null) { if (a.copy) vm.copy(a.doc, dir) else vm.move(a.doc, dir) }
            ask = null
        }
        is Ask.NewTag -> NewTagAsk(onDismiss = { ask = null }) { name, color -> vm.createTag(name, color, a.doc); ask = null }
        is Ask.DeleteForever -> ConfirmAsk(
            title = "Delete for good?",
            body = "${LibraryModel.display(a.doc)} will be removed from the phone. This can't be undone.",
            confirm = "Delete",
            onDismiss = { ask = null },
        ) { vm.deleteForever(a.doc); ask = null }
        Ask.EmptyTrash -> ConfirmAsk(
            title = "Empty the trash?",
            body = "Every file in the trash will be removed from the phone. This can't be undone.",
            confirm = "Empty trash",
            onDismiss = { ask = null },
        ) { vm.emptyTrash(); ask = null }
        is Ask.DeleteTag -> ConfirmAsk(
            title = "Delete the tag ${a.tag.name}?",
            body = "The files stay where they are; only the tag is removed.",
            confirm = "Delete tag",
            onDismiss = { ask = null },
        ) { vm.deleteTag(a.tag.id); ask = null }
    }
}

enum class DocAction { OPEN, STAR, RENAME, MOVE, COPY, TAGS, SHARE, TRASH, RESTORE, DELETE, SAVE_COPY, FORGET, FOLDER }

@Composable
private fun SheetTitle(title: String, note: String?) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 12.dp)) {
        Text(title, style = Type.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (note != null) Text(note, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Hairline(color = Ink.Rule)
}

@Composable
fun ActionsSheet(d: Doc, label: String, pick: (DocAction) -> Unit) {
    SheetTitle(LibraryModel.display(d), if (d.isFile) label else "Shared from another app")
    Column(Modifier.verticalScroll(rememberScrollState())) {
        when {
            d.inTrash -> {
                SheetRow(Glyph.RESTORE, "Restore", { pick(DocAction.RESTORE) }, note = "Back to ${d.trashOrigin?.let { File(it).parentFile?.name } ?: "where it was"}")
                SheetRow(Glyph.TRASH, "Delete for good", { pick(DocAction.DELETE) }, color = Ink.Amber)
            }
            !d.isFile -> {
                SheetRow(Glyph.OPEN, "Open", { pick(DocAction.OPEN) })
                SheetRow(Glyph.COPY, "Save a copy on the phone", { pick(DocAction.SAVE_COPY) }, note = "Into Documents/PDFx, so it stays after the other app forgets it")
                SheetRow(if (d.starred) Glyph.STAR_FILLED else Glyph.STAR, if (d.starred) "Remove star" else "Star", { pick(DocAction.STAR) })
                SheetRow(Glyph.TAG, "Tags", { pick(DocAction.TAGS) })
                SheetRow(Glyph.SHARE, "Share", { pick(DocAction.SHARE) })
                SheetRow(Glyph.CLOSE, "Remove from the list", { pick(DocAction.FORGET) }, note = "The original stays in the other app")
            }
            else -> {
                SheetRow(Glyph.OPEN, "Open", { pick(DocAction.OPEN) })
                SheetRow(if (d.starred) Glyph.STAR_FILLED else Glyph.STAR, if (d.starred) "Remove star" else "Star", { pick(DocAction.STAR) })
                SheetRow(Glyph.TAG, "Tags", { pick(DocAction.TAGS) })
                SheetRow(Glyph.RENAME, "Rename", { pick(DocAction.RENAME) })
                SheetRow(Glyph.MOVE, "Move to…", { pick(DocAction.MOVE) })
                SheetRow(Glyph.COPY, "Copy to…", { pick(DocAction.COPY) })
                SheetRow(Glyph.FOLDER, "Show its folder", { pick(DocAction.FOLDER) }, note = label)
                SheetRow(Glyph.SHARE, "Share", { pick(DocAction.SHARE) })
                SheetRow(Glyph.TRASH, "Move to trash", { pick(DocAction.TRASH) }, note = "Kept for 30 days")
            }
        }
    }
}

@Composable
private fun DestinationSheet(doc: Doc, copy: Boolean, places: List<FolderRow>, currentFolder: String?, onPick: (File) -> Unit, onNewFolder: () -> Unit) {
    SheetTitle(if (copy) "Copy to" else "Move to", LibraryModel.display(doc))
    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
        places.forEach { f ->
            val here = f.path == currentFolder
            SheetRow(Glyph.FOLDER, f.label, { if (!here || copy) onPick(File(f.path!!)) }, note = if (here) "It's here now" else null, color = if (here && !copy) Ink.Ash else Ink.Bone)
        }
        SheetRow(Glyph.PLUS, "New folder…", onNewFolder, color = Ink.Amber)
    }
}

@Composable
private fun TagsSheet(tags: List<Tag>, on: Set<Long>, doc: Doc, toggle: (Tag, Boolean) -> Unit, newTag: () -> Unit) {
    var chosen by remember { mutableStateOf(on) }
    SheetTitle("Tags", LibraryModel.display(doc))
    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
        tags.forEach { t ->
            val isOn = t.id in chosen
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                    chosen = if (isOn) chosen - t.id else chosen + t.id
                    toggle(t, !isOn)
                }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).background(tagColor(t.color)))
                Spacer(Modifier.width(18.dp))
                Text(t.name, style = Type.body, modifier = Modifier.weight(1f))
                if (isOn) GlyphIcon(Glyph.CHECK, Ink.Amber, size = 20.dp)
            }
        }
        SheetRow(Glyph.PLUS, "New tag…", newTag, color = Ink.Amber)
    }
}

@Composable
private fun SortSheet(current: Sort, onPick: (SortBy) -> Unit) {
    SheetTitle("Sort by", "Tap the same one again to reverse it")
    SortBy.entries.forEach { by ->
        val sel = current.by == by
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onPick(by) }.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(by.label, style = Type.body.copy(color = if (sel) Ink.Amber else Ink.Bone), modifier = Modifier.weight(1f))
            if (sel) Text(if (current.descending) "↓" else "↑", style = Type.title.copy(color = Ink.Amber))
        }
    }
}

@Composable
private fun TextAsk(title: String, initial: String, confirm: String, onDismiss: () -> Unit, note: String? = null, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text(title, style = Type.title) },
        text = {
            Column {
                if (note != null) { Text(note, style = Type.note); Spacer(Modifier.height(10.dp)) }
                InkField(text, { text = it }, "Name", onDone = { if (text.isNotBlank()) onConfirm(text) })
            }
        },
        confirmButton = { AmberButton(confirm, { onConfirm(text) }, enabled = text.isNotBlank()) },
        dismissButton = { GhostButton("Cancel", onDismiss, minHeight = 52.dp) },
    )
}

@Composable
private fun NewTagAsk(onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("New tag", style = Type.title) },
        text = {
            Column {
                InkField(text, { text = it }, "Tag name, like Clinic or Bills", onDone = { if (text.isNotBlank()) onConfirm(text, color) })
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TagColors.indices.forEach { i ->
                        Box(
                            Modifier.size(34.dp)
                                .border(2.dp, if (i == color) Ink.Bone else Ink.Field)
                                .padding(5.dp)
                                .background(tagColor(i))
                                .clickable { color = i },
                        )
                    }
                }
            }
        },
        confirmButton = { AmberButton("Add tag", { onConfirm(text, color) }, enabled = text.isNotBlank()) },
        dismissButton = { GhostButton("Cancel", onDismiss, minHeight = 52.dp) },
    )
}

@Composable
private fun ConfirmAsk(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text(title, style = Type.title) },
        text = { Text(body, style = Type.note) },
        confirmButton = { AmberButton(confirm, onConfirm) },
        dismissButton = { GhostButton("Cancel", onDismiss, minHeight = 52.dp) },
    )
}

fun askForAllFiles(context: Context) {
    val pkg = Uri.parse("package:${context.packageName}")
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

fun share(context: Context, d: Doc) {
    val uri = if (d.path != null) {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(d.path))
    } else Uri.parse(d.uri)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, LibraryModel.display(d))) }
}
