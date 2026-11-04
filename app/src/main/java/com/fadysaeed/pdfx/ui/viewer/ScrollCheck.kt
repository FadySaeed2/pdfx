package com.fadysaeed.pdfx.ui.viewer

import android.app.Activity
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Measures how smoothly the pages scroll on this phone, for twenty seconds of scrolling: every
 * frame Android drew and how long each part of it took, beside what PDFx did at that moment (a
 * page's picture arriving, a page composed). The first half runs as the app is; the second half
 * without drawing into old pictures and without sending pictures ahead to the graphics memory, so
 * the two halves show which of them, if either, makes the stutter. The report is plain text to
 * copy and paste.
 */
object ScrollCheck {
    const val HALF_MS = 10_000L

    @Volatile var running = false
        private set
    /** Draw new pages into pictures that left the screen. Off in the second half. */
    @Volatile var reuse = true
    /** Send each new picture to the graphics memory before it shows. Off in the second half. */
    @Volatile var prepare = true

    private class Frame(val vsync: Long, val total: Long, val input: Long, val anim: Long, val layout: Long, val draw: Long, val sync: Long, val command: Long, val swap: Long, val gpu: Long, val half: Int)

    private val frames = ConcurrentLinkedQueue<Frame>()
    private val events = ConcurrentLinkedQueue<Pair<Long, String>>()
    @Volatile private var half = 0
    private var started = 0L
    private var window: Window? = null
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var refresh = 60f
    private val gcs = LongArray(4)

    /** Something PDFx did, to set beside the frames. Costs nothing when no check runs. */
    fun event(what: String) { if (running) events.add(System.nanoTime() to what) }

    fun start(activity: Activity) {
        if (running) return
        frames.clear(); events.clear()
        reuse = true; prepare = true; half = 0
        refresh = runCatching { activity.display?.refreshRate ?: 60f }.getOrDefault(60f)
        val t = HandlerThread("scroll-check").also { it.start() }
        thread = t
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            frames.add(Frame(
                vsync = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                total = m.getMetric(FrameMetrics.TOTAL_DURATION),
                input = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                anim = m.getMetric(FrameMetrics.ANIMATION_DURATION),
                layout = m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                draw = m.getMetric(FrameMetrics.DRAW_DURATION),
                sync = m.getMetric(FrameMetrics.SYNC_DURATION),
                command = m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                swap = m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                gpu = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else 0L,
                half = half,
            ))
        }
        listener = l
        window = activity.window
        activity.window.addOnFrameMetricsAvailableListener(l, Handler(t.looper))
        started = System.nanoTime()
        gcs[0] = gcCount(); gcs[1] = blockingGcCount()
        running = true
    }

    /** Halfway: the second way of drawing. */
    fun secondHalf() {
        half = 1
        reuse = false
        prepare = false
        gcs[2] = gcCount(); gcs[3] = blockingGcCount()
        events.add(System.nanoTime() to "— second half: no drawing into old pictures, no sending ahead —")
    }

    /** Stops, puts everything back as it was, and returns the report. */
    fun stop(): String {
        val endGc = gcCount()
        val endBlocking = blockingGcCount()
        running = false
        reuse = true; prepare = true
        runCatching { listener?.let { window?.removeOnFrameMetricsAvailableListener(it) } }
        thread?.quitSafely()
        listener = null; window = null; thread = null
        return report(endGc, endBlocking)
    }

    private fun gcCount() = runCatching { Debug.getRuntimeStat("art.gc.gc-count").toLong() }.getOrDefault(-1L)
    private fun blockingGcCount() = runCatching { Debug.getRuntimeStat("art.gc.blocking-gc-count").toLong() }.getOrDefault(-1L)

    private fun ms(ns: Long) = String.format(Locale.ENGLISH, "%.1f", ns / 1e6)

    private fun report(endGc: Long, endBlocking: Long): String {
        val budget = (1e9 / refresh).toLong()
        val all = frames.toList().filter { it.vsync >= started }
        val ev = events.toList()
        val sb = StringBuilder()
        sb.append("PDFx scroll check · ").append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" · ").append(refresh.toInt()).append(" Hz (").append(ms(budget)).append(" ms a frame)\n")
        for (h in 0..1) {
            val fs = all.filter { it.half == h }
            val late = fs.filter { it.total > budget * 3 / 2 }
            val gc = if (h == 0) gcs[2] - gcs[0] else endGc - gcs[2]
            val blocking = if (h == 0) gcs[3] - gcs[1] else endBlocking - gcs[3]
            sb.append("\n== ").append(if (h == 0) "First half (as the app is)" else "Second half (no reuse, no sending ahead)").append('\n')
            sb.append("frames ").append(fs.size).append(", late ").append(late.size)
            if (fs.isNotEmpty()) sb.append(" (").append(late.size * 100 / fs.size).append("%)")
            sb.append(", memory clean-ups ").append(gc).append(" (blocking ").append(blocking).append(")\n")
            if (fs.isNotEmpty()) {
                fun med(sel: (Frame) -> Long) = fs.map(sel).sorted()[fs.size / 2]
                sb.append("typical frame: total ").append(ms(med { it.total })).append(", layout ").append(ms(med { it.layout }))
                    .append(", draw ").append(ms(med { it.draw })).append(", sync ").append(ms(med { it.sync }))
                    .append(", gpu ").append(ms(med { it.gpu })).append('\n')
                // How the late frames' time splits, on average: which part makes them late.
                if (late.isNotEmpty()) {
                    fun avg(sel: (Frame) -> Long) = ms(late.sumOf(sel) / late.size)
                    sb.append("late frames on average: total ").append(avg { it.total }).append(", input ").append(avg { it.input })
                        .append(", animation ").append(avg { it.anim }).append(", layout ").append(avg { it.layout })
                        .append(", draw ").append(avg { it.draw }).append(", sync ").append(avg { it.sync })
                        .append(", command ").append(avg { it.command }).append(", swap ").append(avg { it.swap })
                        .append(", gpu ").append(avg { it.gpu }).append('\n')
                }
                for (f in late.sortedByDescending { it.total }.take(10)) {
                    sb.append("· ").append(ms(f.total)).append(" ms at ").append(ms(f.vsync - started)).append(": layout ").append(ms(f.layout))
                        .append(", draw ").append(ms(f.draw)).append(", sync ").append(ms(f.sync)).append(", command ").append(ms(f.command))
                        .append(", gpu ").append(ms(f.gpu))
                    val near = ev.filter { (t, _) -> t >= f.vsync - 2 * budget && t <= f.vsync + f.total }.map { it.second }
                    if (near.isNotEmpty()) sb.append(" | ").append(near.groupingBy { it }.eachCount().entries.joinToString("; ") { (k, n) -> if (n > 1) "$k ×$n" else k })
                    sb.append('\n')
                }
            }
            val evHalf = ev.filter { (t, w) -> !w.startsWith("—") && if (h == 0) t < (ev.firstOrNull { it.second.startsWith("—") }?.first ?: Long.MAX_VALUE) else t >= (ev.firstOrNull { it.second.startsWith("—") }?.first ?: Long.MAX_VALUE) }
            sb.append("what happened: ").append(evHalf.groupingBy { it.second.substringBefore(" p") }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }).append('\n')
        }
        return sb.toString()
    }
}
