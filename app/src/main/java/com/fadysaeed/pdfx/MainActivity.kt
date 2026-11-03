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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming.value = documentFrom(intent)
        setContent {
            PdfxTheme {
                PdfxRoot(incoming.value) { incoming.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        documentFrom(intent)?.let { incoming.value = it }
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
