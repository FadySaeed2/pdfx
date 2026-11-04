package com.fadysaeed.pdfx.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.fadysaeed.pdfx.engine.PdfSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** First-page pictures for the list and the grid, drawn once and kept on disk until the file changes. */
class Thumbs(context: Context, private val library: Library) {
    private val appContext = context.applicationContext
    private val dir = File(context.cacheDir, "thumbs").apply {
        mkdirs()
        // Pictures drawn at an earlier size are never asked for again: clear them once.
        val prefs = context.getSharedPreferences("thumbs", Context.MODE_PRIVATE)
        if (prefs.getInt("width", 0) != WIDTH_PX) {
            listFiles()?.forEach { it.delete() }
            prefs.edit().putInt("width", WIDTH_PX).apply()
        }
    }
    // Held by size: about 300 first pages at grid sharpness.
    private val memory = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val gate = Semaphore(2)

    private fun name(d: Doc): String {
        val md = MessageDigest.getInstance("SHA-1").digest("${d.key}|${d.modified}|${d.size}|$WIDTH_PX".toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun cached(d: Doc): ImageBitmap? = memory.get(name(d))

    suspend fun load(d: Doc): ImageBitmap? = withContext(Dispatchers.IO) {
        val n = name(d)
        memory.get(n)?.let { return@withContext it }
        if (d.locked) return@withContext null
        val file = File(dir, "$n.webp")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()?.also { memory.put(n, it); return@withContext it }
        }
        gate.withPermit {
            val s = runCatching { PdfSession.open(appContext, Uri.parse(d.uri)) }.getOrNull() ?: return@withPermit null
            try {
                if (s.needsPassword) {
                    library.dao.byId(d.id)?.let { library.dao.update(it.copy(locked = true)) }
                    return@withPermit null
                }
                if (s.pageCount == 0) return@withPermit null
                val bmp = s.render(0, WIDTH_PX)
                runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, it) } }
                if (d.pages != s.pageCount) library.dao.byId(d.id)?.let { library.dao.update(it.copy(pages = s.pageCount)) }
                bmp.asImageBitmap().also { memory.put(n, it) }
            } catch (t: Throwable) {
                null
            } finally {
                s.close()
            }
        }
    }

    // Sharp enough for the grid's three a row; the list's small ones are this drawn smaller.
    private companion object { const val WIDTH_PX = 320 }
}
