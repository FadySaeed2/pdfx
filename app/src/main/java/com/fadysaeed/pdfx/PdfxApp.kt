package com.fadysaeed.pdfx

import android.app.Application
import com.fadysaeed.pdfx.data.Recents

class PdfxApp : Application() {
    val recents: Recents by lazy { Recents(this) }
}
