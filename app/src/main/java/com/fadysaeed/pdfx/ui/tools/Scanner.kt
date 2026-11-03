package com.fadysaeed.pdfx.ui.tools

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * The phone's document scanner (Google's, inside Play services): it finds the paper's edges,
 * straightens it and cleans it up, page after page, photos from the gallery too. Returns a
 * function that starts it; [onPages] gets the pages as JPEG files in the app's own space, in order.
 */
@Composable
fun rememberScanner(onPages: (List<File>) -> Unit, onProblem: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pages = GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages.orEmpty().map { it.imageUri }
        if (pages.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) { copyIn(context, pages) }
            if (files.size == pages.size) onPages(files) else onProblem("Some scanned pages could not be read. Please scan again.")
        }
    }
    return {
        val activity = context.activity()
        if (activity == null) onProblem("The scanner could not start here.")
        else {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener { e ->
                    onProblem(
                        "The scanner could not start" + (e.message?.let { ": $it" } ?: "") +
                            ". It comes with Google Play services, which may still be getting it ready; try again in a minute.",
                    )
                }
        }
    }
}

/** The scanned pages, kept as files of the app's own before the scanner's copies go. */
private fun copyIn(context: Context, pages: List<Uri>): List<File> {
    val dir = File(context.cacheDir, "scans/${System.nanoTime()}").apply { mkdirs() }
    return pages.mapIndexedNotNull { i, u ->
        runCatching {
            val f = File(dir, "page-${i + 1}.jpg")
            context.contentResolver.openInputStream(u)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
            f.takeIf { it.length() > 0 }
        }.getOrNull()
    }
}
