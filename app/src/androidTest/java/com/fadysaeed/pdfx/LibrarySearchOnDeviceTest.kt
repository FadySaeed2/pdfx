package com.fadysaeed.pdfx

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Typing in the library's search box, a letter at a time and quickly, keeps every letter. */
@RunWith(AndroidJUnit4::class)
class LibrarySearchOnDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyTypedLetterStays() {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            val box = hasContentDescription("Search") and hasSetTextAction()
            compose.waitUntil(20_000) { compose.onAllNodes(box).fetchSemanticsNodes().isNotEmpty() }
            val node = compose.onNode(box)
            node.performClick()
            for (c in "lithium levels") node.performTextInput(c.toString())
            compose.waitForIdle()
            val text = compose.onNode(box).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            assertEquals("lithium levels", text)
        }
    }
}
