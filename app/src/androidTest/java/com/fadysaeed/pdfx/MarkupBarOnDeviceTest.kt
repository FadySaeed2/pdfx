package com.fadysaeed.pdfx

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.ui.viewer.Reader
import com.fadysaeed.pdfx.ui.viewer.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The marking bar folds away as the page is worked on, opens on a touch, and stays as it was left. */
@RunWith(AndroidJUnit4::class)
class MarkupBarOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theBarFoldsWhileDrawingAndRemembers() {
        val prefs = ctx.getSharedPreferences("markup-bar-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val r = Reader(null, 3, scope, prefs = prefs)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                r.startMarking()
                r.pickTool(Tool.PEN)
            }
            assertFalse("whole while tools are picked", r.barSlim)
            assertTrue("the first times, it says how", r.showHints)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { r.begin(0, 100f, 100f, eraser = false) }
            assertTrue("folded once the pen is on the page", r.barSlim)
            r.slimBar(false)
            assertFalse(r.barSlim)
            // A tool that puts something where you tap folds it too.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { r.pickTool(Tool.TICK); r.tapWithTool(0, 50f, 50f) }
            assertTrue(r.barSlim)
            // Next time, as it was left; and after a few times, no telling how.
            val again = Reader(null, 3, scope, prefs = prefs)
            assertTrue(again.barSlim)
            repeat(3) { InstrumentationRegistry.getInstrumentation().runOnMainSync { again.startMarking() } }
            assertFalse(again.showHints)
        } finally {
            scope.cancel()
        }
    }
}
