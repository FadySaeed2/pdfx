package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.ui.viewer.ScrollSpeed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * When the viewer counts a scroll as fast (soft pictures only) and when as slow (sharp pictures),
 * with looks at the scroll arriving unevenly, as they do on a phone: two frames' worth at once,
 * then a gap.
 */
class ScrollSpeedTest {
    private val screen = 2000f
    private val frame = 8_333_333L // 120 frames a second

    /** Feeds [seconds] of scrolling at [screensPerSecond], frames arriving unevenly. Returns whether it was ever fast. */
    private fun run(s: ScrollSpeed, screensPerSecond: Float, seconds: Float, seed: Int = 1, start: Pair<Long, Float> = 0L to 0f): Triple<Boolean, Long, Float> {
        val rnd = Random(seed)
        var t = start.first
        var pos = start.second
        var ever = false
        val frames = (seconds * 120).toInt()
        var k = 0
        while (k < frames) {
            // Now and then two frames land together, and the timing wobbles by a few milliseconds.
            val step = if (rnd.nextInt(6) == 0) 2 else 1
            k += step
            t += frame * step + rnd.nextLong(-2_000_000L, 2_000_000L)
            pos += screensPerSecond * screen * (frame * step) / 1e9f
            if (s.add(t, pos, screen)) ever = true
        }
        return Triple(ever, t, pos)
    }

    @Test
    fun aSlowScrollIsNeverFastEvenWithUnevenFrames() {
        for (seed in 1..20) for (speed in listOf(0.3f, 0.8f, 1.5f, 2.5f)) {
            assertFalse("$speed screens a second (seed $seed) read as fast", run(ScrollSpeed(), speed, 3f, seed).first)
        }
    }

    @Test
    fun aFlingIsFastAndSlowsBackToSharp() {
        val s = ScrollSpeed()
        val (ever, t, pos) = run(s, 8f, 0.6f)
        assertTrue("eight screens a second is fast", ever)
        assertTrue(s.fast)
        // Slowing to one screen a second: sharp again within a fifth of a second or so.
        run(s, 1f, 0.4f, start = t to pos)
        assertFalse(s.fast)
    }

    @Test
    fun betweenTheTwoItHoldsWhatItWas() {
        val s = ScrollSpeed()
        val (_, t, pos) = run(s, 6f, 0.5f)
        assertTrue(s.fast)
        // Three screens a second: under the step up, over the step down; it stays fast rather than wavering.
        run(s, 3f, 1f, start = t to pos)
        assertTrue(s.fast)
    }
}
