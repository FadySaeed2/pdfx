package com.fadysaeed.pdfx.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val pages: Int,
    val lastOpened: Long,
    val lastPage: Int,
)

/**
 * The files the user has opened, newest first, with the page they were on.
 * A small JSON list for now; the full library with folders and search arrives in phase 1.
 */
class Recents(private val context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<RecentFile>> = _items

    fun find(uri: Uri): RecentFile? = _items.value.firstOrNull { it.uri == uri.toString() }

    fun record(file: RecentFile) {
        val rest = _items.value.filterNot { it.uri == file.uri }
        save((listOf(file) + rest).take(MAX))
    }

    fun updatePage(uri: Uri, page: Int) {
        val list = _items.value
        val i = list.indexOfFirst { it.uri == uri.toString() }
        if (i < 0 || list[i].lastPage == page) return
        save(list.toMutableList().also { it[i] = it[i].copy(lastPage = page) })
    }

    fun remove(uri: String) = save(_items.value.filterNot { it.uri == uri })

    /** Keeps read access across restarts when the source allows it (the system picker does). */
    fun holdPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        return name to size
    }

    private fun save(list: List<RecentFile>) {
        _items.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri).put("name", it.name).put("size", it.sizeBytes)
                    .put("pages", it.pages).put("opened", it.lastOpened).put("page", it.lastPage),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(): List<RecentFile> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentFile(
                    uri = o.getString("uri"),
                    name = o.optString("name", "Document"),
                    sizeBytes = o.optLong("size", -1),
                    pages = o.optInt("pages", 0),
                    lastOpened = o.optLong("opened", 0),
                    lastPage = o.optInt("page", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY = "items"
        const val MAX = 60
    }
}
