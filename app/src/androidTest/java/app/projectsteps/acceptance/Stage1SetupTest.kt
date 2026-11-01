package app.projectsteps.acceptance

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.projectsteps.MainActivity
import app.projectsteps.acceptance.Acceptance.DEMO_PROJECT
import app.projectsteps.acceptance.Acceptance.TASK_TO_COMPLETE
import app.projectsteps.acceptance.Acceptance.clickText
import app.projectsteps.acceptance.Acceptance.screenshot
import app.projectsteps.acceptance.Acceptance.scrollAndClick
import app.projectsteps.acceptance.Acceptance.waitDescription
import app.projectsteps.acceptance.Acceptance.waitText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 1 (fresh install): first-time setup with clearly-labelled demo data, then complete a task.
 * Uses demo data only, never real project documents.
 */
@RunWith(AndroidJUnit4::class)
class Stage1SetupTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstRunSetupThenCompleteATask() {
        // Step 1: Welcome
        waitText(rule, "Get started")
        screenshot(rule, "01-setup-welcome")
        clickText(rule, "Get started")

        // Step 2: Import (demo data)
        waitText(rule, "Choose what to import")
        screenshot(rule, "02-setup-import")
        rule.onNodeWithText("Skip and use demo data (clearly marked DEMO)").performScrollTo().performClick()

        // Step 3: Review import (editable preview)
        waitText(rule, "Review import")
        waitText(rule, "8 tasks", substring = true)
        screenshot(rule, "03-setup-review")
        scrollAndClick(rule, "Save 1 project(s)")
        waitText(rule, "Import saved")
        clickText(rule, "Continue")

        // Step 4: Reminder preferences
        waitText(rule, "Default reminder time")
        screenshot(rule, "04-setup-reminders")
        clickText(rule, "Next")

        // Step 5: Backup
        waitText(rule, "MANUAL BACKUP")
        clickText(rule, "Next")

        // Step 6: Finish -> Today
        waitText(rule, "You're set")
        clickText(rule, "Go to Today")
        waitText(rule, "NEXT ACTION")
        screenshot(rule, "05-today")

        // Open the demo project and complete one task.
        clickText(rule, "Projects")
        clickText(rule, DEMO_PROJECT)
        waitText(rule, "1 / 8 completed · 12%")
        screenshot(rule, "06-project-before")
        waitDescription(rule, "Complete: $TASK_TO_COMPLETE")
        rule.onNodeWithContentDescription("Complete: $TASK_TO_COMPLETE").performClick()
        waitText(rule, "2 / 8 completed · 25%")
        screenshot(rule, "07-project-after-complete")

        // Task detail screen
        scrollAndClick(rule, "Then try Complete from the notification")
        waitText(rule, "Add reminder")
        screenshot(rule, "08-task-detail")
        Espresso.pressBack()
        waitText(rule, "2 / 8 completed · 25%")

        // Recreating the activity (rotation-style config change) keeps state.
        rule.activityRule.scenario.recreate()
        waitText(rule, "2 / 8 completed · 25%")
        rule.onNode(hasText("2 / 8 completed · 25%")).assertExists()
    }
}
