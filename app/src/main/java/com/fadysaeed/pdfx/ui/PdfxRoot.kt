package com.fadysaeed.pdfx.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.ui.home.HomeContent
import com.fadysaeed.pdfx.ui.viewer.ViewerScreen

/**
 * Two places for now: the library and an open document. A file handed over by another app
 * ([incoming]) opens straight away.
 */
@Composable
fun PdfxRoot(incoming: Uri?, onIncomingHandled: () -> Unit) {
    val context = LocalContext.current
    val recents = (context.applicationContext as PdfxApp).recents
    val items by recents.items.collectAsState()
    var open by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(incoming) {
        if (incoming != null) {
            recents.holdPermission(incoming)
            status = null
            open = incoming
            onIncomingHandled()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            recents.holdPermission(uri)
            status = null
            open = uri
        }
    }

    val current = open
    if (current == null) {
        HomeContent(
            recents = items,
            status = status,
            onOpenRecent = { f -> status = null; open = Uri.parse(f.uri) },
            onPick = { picker.launch(arrayOf("application/pdf")) },
        )
    } else {
        ViewerScreen(current) { failure ->
            if (failure != null) {
                status = "$failure If it came from another app, open it again from there."
            }
            open = null
        }
    }
}
