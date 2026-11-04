package com.fadysaeed.pdfx

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.fadysaeed.pdfx.ui.PdfxRoot
import com.fadysaeed.pdfx.ui.theme.PdfxTheme

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<List<Uri>?>(null)
    /** A shortcut held on the app's icon: "scan", "last" or "search". */
    private val shortcut = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) { incoming.value = documentFrom(intent); shortcut.value = shortcutFrom(intent) }
        setContent {
            PdfxTheme {
                PdfxRoot(incoming.value, { incoming.value = null }, shortcut.value) { shortcut.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        documentFrom(intent)?.let { incoming.value = it }
        shortcutFrom(intent)?.let { shortcut.value = it }
    }

    private fun shortcutFrom(intent: Intent?): String? = when (intent?.action) {
        "com.fadysaeed.pdfx.action.SCAN" -> "scan"
        "com.fadysaeed.pdfx.action.LAST" -> "last"
        "com.fadysaeed.pdfx.action.SEARCH" -> "search"
        else -> null
    }

    /** What another app hands over: a PDF, a book, or one or more pictures to make into a PDF. */
    private fun documentFrom(intent: Intent?): List<Uri>? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data?.let { listOf(it) }
        Intent.ACTION_SEND ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { listOf(it) }
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.toList()?.takeIf { it.isNotEmpty() }
        else -> null
    }
}
