package com.fadysaeed.pdfx.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * When PDFx closes unexpectedly, what went wrong is kept on the phone (nothing is sent anywhere),
 * so the next time it opens it can offer the report to share by hand.
 */
object CrashReports {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")
    private fun prefs(context: Context) = context.getSharedPreferences("crash", Context.MODE_PRIVATE)

    /** Catches crashes in the app's own code: the details are written down, then Android closes the app as usual. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file(app).writeText(header(app) + "Thread: ${thread.name}\n\n" + error.stackTraceToString().take(60_000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun header(context: Context): String {
        val v = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        return "PDFx $v · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL} · $time\n"
    }

    /**
     * The report from the last time PDFx closed unexpectedly, if it has not been shown yet. Crashes
     * inside the PDF engine (native code) are read from Android's own record of how the app ended.
     */
    fun pending(context: Context): String? {
        val seen = prefs(context).getLong("seen", 0L)
        var report: String? = null
        var at = 0L
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR }
            if (last != null && last.timestamp > seen) {
                at = last.timestamp
                val kind = when (last.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Crash in the PDF engine (native)"
                    ApplicationExitInfo.REASON_ANR -> "Stopped responding"
                    else -> "Crash"
                }
                val trace = runCatching { last.traceInputStream?.use { it.readBytes() } }.getOrNull()
                val readable = trace?.let { printable(it) }.orEmpty()
                report = buildString {
                    append(header(context))
                    append("$kind at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(last.timestamp))}\n")
                    last.description?.let { append("Android says: $it\n") }
                    val own = file(context).takeIf { it.exists() && it.lastModified() >= last.timestamp - 60_000 }?.readText()
                    if (own != null) append("\n").append(own)
                    if (readable.isNotBlank()) append("\n\n").append(readable.take(40_000))
                }
            }
        }
        if (report == null) {
            // Android did not keep a record: fall back to what the app wrote itself.
            val f = file(context)
            if (f.exists() && f.lastModified() > seen) { report = f.readText(); at = f.lastModified() }
        }
        if (report != null) prefs(context).edit().putLong("seen", maxOf(at, System.currentTimeMillis())).apply()
        return report
    }

    /** Lines of readable text from a crash record (native records are binary, with names inside). */
    private fun printable(bytes: ByteArray): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() { if (run.length >= 6) out.append(run).append('\n'); run.setLength(0) }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126 || c == 9) run.append(c.toChar()) else if (c == 10) { flush() } else flush()
        }
        flush()
        return out.toString()
    }
}
