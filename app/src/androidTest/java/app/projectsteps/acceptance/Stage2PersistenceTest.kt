package app.projectsteps.acceptance

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.projectsteps.MainActivity
import app.projectsteps.acceptance.Acceptance.DEMO_PROJECT
import app.projectsteps.acceptance.Acceptance.clickText
import app.projectsteps.acceptance.Acceptance.screenshot
import app.projectsteps.acceptance.Acceptance.waitText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 2: runs after `am force-stop`. The app must open straight to Today (setup not repeated)
 * and the completion from stage 1 must still be there. Dark mode is on for this stage.
 */
@RunWith(AndroidJUnit4::class)
class Stage2PersistenceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun reopensToTodayAndCompletionPersisted() {
        waitText(rule, "NEXT ACTION")
        assertTrue("setup must not run again", rule.onAllNodesWithText("Get started").fetchSemanticsNodes().isEmpty())
        screenshot(rule, "10-today-dark-after-force-stop")

        clickText(rule, "Projects")
        clickText(rule, DEMO_PROJECT)
        waitText(rule, "2 / 8 completed · 25%")
        screenshot(rule, "11-project-dark-persisted")
        Espresso.pressBack()

        // Next Action focus view
        clickText(rule, "Today")
        waitText(rule, "NEXT ACTION")
        rule.onNodeWithContentDescription("Next action focus view").performClick()
        waitText(rule, "COMPLETE")
        screenshot(rule, "12-next-action")
        Espresso.pressBack()

        // Settings › Reminder Reliability
        rule.onNodeWithContentDescription("Settings").performClick()
        clickText(rule, "Reminder Reliability")
        waitText(rule, "Battery optimization")
        screenshot(rule, "13-reliability")
    }
}
