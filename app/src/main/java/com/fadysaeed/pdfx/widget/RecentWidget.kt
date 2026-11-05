package com.fadysaeed.pdfx.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.fadysaeed.pdfx.MainActivity
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.R
import com.fadysaeed.pdfx.data.Doc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The home-screen widget: the four files opened last, each a tap from where it was left, and a
 * Scan button. It is filled from the library whenever a file is opened.
 */
class RecentWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val docs = recent(context)
                ids.forEach { manager.updateAppWidget(it, views(context, docs)) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ROWS = 4
        private val rowIds = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3)
        private val titleIds = intArrayOf(R.id.title0, R.id.title1, R.id.title2, R.id.title3)
        private val noteIds = intArrayOf(R.id.note0, R.id.note1, R.id.note2, R.id.note3)

        private suspend fun recent(context: Context): List<Doc> =
            (context.applicationContext as PdfxApp).library.dao.all()
                .filter { !it.inTrash && it.lastOpened > 0 }
                .sortedByDescending { it.lastOpened }
                .take(ROWS)

        private fun views(context: Context, docs: List<Doc>): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_recent)
            v.setOnClickPendingIntent(R.id.scan, action(context, "com.fadysaeed.pdfx.action.SCAN", 1))
            v.setOnClickPendingIntent(R.id.app, action(context, Intent.ACTION_MAIN, 2))
            v.setViewVisibility(R.id.empty, if (docs.isEmpty()) View.VISIBLE else View.GONE)
            for (k in 0 until ROWS) {
                val d = docs.getOrNull(k)
                if (d == null) { v.setViewVisibility(rowIds[k], View.GONE); continue }
                v.setViewVisibility(rowIds[k], View.VISIBLE)
                v.setTextViewText(titleIds[k], com.fadysaeed.pdfx.data.Names.shown(d))
                v.setTextViewText(noteIds[k], if (d.pages > 0) "Page ${d.lastPage + 1} of ${d.pages}" else "Page ${d.lastPage + 1}")
                val open = Intent(context, MainActivity::class.java)
                    .setAction("com.fadysaeed.pdfx.action.OPEN")
                    // A different address for each file, so Android keeps their taps apart.
                    .setData(Uri.parse("pdfx://doc/${d.id}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                v.setOnClickPendingIntent(rowIds[k], PendingIntent.getActivity(context, 100 + k, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            return v
        }

        private fun action(context: Context, action: String, code: Int): PendingIntent {
            val i = Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Fills every PDFx widget again (after a file is opened). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecentWidget::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val docs = recent(context)
                    ids.forEach { manager.updateAppWidget(it, views(context, docs)) }
                }
            }
        }
    }
}
