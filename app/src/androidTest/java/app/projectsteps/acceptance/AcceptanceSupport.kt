package app.projectsteps.acceptance

import android.graphics.Bitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Helpers for the on-device acceptance tests. They run in order on a freshly installed app
 * (see ci/acceptance.sh), with a real force-stop between stages.
 */
object Acceptance {
    const val DEMO_PROJECT = "DEMO – Sample checklist"
    const val TASK_TO_COMPLETE = "Tick this box to complete a task"

    fun waitText(rule: AndroidComposeTestRule<*, *>, text: String, substring: Boolean = false, timeoutMs: Long = 15_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    fun waitDescription(rule: AndroidComposeTestRule<*, *>, desc: String, timeoutMs: Long = 15_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
        }
    }

    fun clickText(rule: AndroidComposeTestRule<*, *>, text: String) {
        waitText(rule, text)
        val node = rule.onNodeWithText(text)
        runCatching { node.performScrollTo() } // bring it on screen if it's inside a scrolling column
        node.performClick()
        rule.waitForIdle()
    }

    /** Scrolls the (single) lazy list on screen until [text] is composed, then clicks it. */
    fun scrollAndClick(rule: AndroidComposeTestRule<*, *>, text: String) {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        rule.onNodeWithText(text).performClick()
        rule.waitForIdle()
    }

    fun scrollTo(rule: AndroidComposeTestRule<*, *>, matcher: SemanticsMatcher) {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        rule.waitForIdle()
    }

    /** Saves a screenshot inside the app's private files (pulled by ci/acceptance.sh via run-as). */
    fun screenshot(rule: AndroidComposeTestRule<*, *>?, name: String) {
        rule?.waitForIdle()
        Thread.sleep(400)
        val instr = InstrumentationRegistry.getInstrumentation()
        val bmp = instr.uiAutomation.takeScreenshot() ?: return
        val dir = File(instr.targetContext.filesDir, "screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }
}
