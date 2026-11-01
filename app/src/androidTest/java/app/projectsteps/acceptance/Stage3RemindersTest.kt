package app.projectsteps.acceptance

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.projectsteps.ProjectStepsApp
import app.projectsteps.data.ReminderEntity
import app.projectsteps.data.SettingKeys
import app.projectsteps.data.TaskEntity
import app.projectsteps.data.newId
import app.projectsteps.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Stage 3: real reminders on the device. A one-time reminder is scheduled through WorkManager,
 * waits for the actual notification, then uses the notification's own Snooze and Complete
 * buttons. Also checks recurring completion, cancellation, and a precise (exact-alarm) reminder.
 * POST_NOTIFICATIONS and exact-alarm access are granted by ci/acceptance.sh beforehand.
 */
@RunWith(AndroidJUnit4::class)
class Stage3RemindersTest {
    private val app = ApplicationProvider.getApplicationContext<ProjectStepsApp>()
    private val c = app.container
    private val nm = app.getSystemService(NotificationManager::class.java)

    private fun task(prefix: String): TaskEntity = runBlocking {
        c.repo.snapshot.first().tasks.first { it.title.startsWith(prefix) }
    }

    /** One-time reminder ~[minutes] from now (whole minutes, as the editor stores HH:mm). */
    private fun reminderFor(t: TaskEntity, minutes: Long, precise: Boolean = false, repeat: String = "NONE", time: LocalDateTime? = null): ReminderEntity {
        val at = time ?: LocalDateTime.now().plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES)
        val now = System.currentTimeMillis()
        return ReminderEntity(
            id = newId(), taskId = t.id, projectId = t.projectId, startDate = at.toLocalDate().toString(),
            time = at.toLocalTime().toString(), repeatType = repeat, precise = precise, createdAt = now, updatedAt = now,
        )
    }

    private fun waitForNotification(reminderId: String, timeoutMs: Long): Notification? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            nm.activeNotifications.firstOrNull { it.tag == "reminder" && it.id == reminderId.hashCode() }?.let { return it.notification }
            Thread.sleep(2_000)
        }
        return null
    }

    private fun waitUntil(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(500)
        }
        return check()
    }

    private fun uniqueWorkStates(id: String): List<WorkInfo.State> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(ReminderScheduler.workName(id)).get().map { it.state }

    @Test
    fun reminderFiresSnoozesAndCompletesFromNotification() = runBlocking {
        c.settings.set(SettingKeys.NOTIFICATIONS_ENABLED, true)
        val t = task("Open a task and add a reminder")
        val r = reminderFor(t, minutes = 2)
        c.repo.saveReminder(r)

        val saved = c.db.reminders().get(r.id)!!
        assertNotNull("next trigger computed", saved.nextFireAt)
        assertTrue("WorkManager job enqueued", WorkInfo.State.ENQUEUED in uniqueWorkStates(r.id))

        val n = waitForNotification(r.id, timeoutMs = 5 * 60_000)
        assertNotNull("reminder notification appeared", n)
        Acceptance.shell("cmd statusbar expand-notifications")
        Thread.sleep(1_500)
        Acceptance.screenshot(null, "20-notification")
        Acceptance.shell("cmd statusbar collapse")
        val actions = n!!.actions.map { it.title.toString() }
        assertEquals(listOf("Complete", "Snooze 15 min"), actions)
        assertNotNull("fired time recorded", c.db.reminders().get(r.id)!!.lastFiredAt)

        // SNOOZE button (real PendingIntent -> NotificationActionReceiver)
        n.actions[1].actionIntent.send()
        assertTrue("notification removed after snooze", waitUntil(10_000) { nm.activeNotifications.none { it.id == r.id.hashCode() } })
        assertTrue("snoozed ~15 min", waitUntil(10_000) { c.db.reminders().get(r.id)?.snoozedUntil != null })
        val snoozed = c.db.reminders().get(r.id)!!
        val minutesAhead = (snoozed.nextFireAt!! - System.currentTimeMillis()) / 60_000.0
        assertTrue("next trigger is the snooze time, was $minutesAhead min", minutesAhead in 13.0..16.0)

        // COMPLETE button
        n.actions[0].actionIntent.send()
        assertTrue("task completed from notification", waitUntil(10_000) { c.db.tasks().get(t.id)?.status == "DONE" })
        val done = c.db.reminders().get(r.id)!!
        assertTrue(done.itemDone)
        assertNull("no further trigger after completion", done.nextFireAt)
        assertTrue("job cancelled", WorkInfo.State.ENQUEUED !in uniqueWorkStates(r.id))
    }

    @Test
    fun recurringCompletionKeepsSeriesAndCancelRemovesJob() = runBlocking {
        val t = task("When the notification appears")
        // Daily at a time earlier today, so today's occurrence is due.
        val earlier = LocalDateTime.of(LocalDate.now(), LocalTime.now().minusHours(1).truncatedTo(ChronoUnit.MINUTES))
        val r = reminderFor(t, 0, repeat = "DAILY", time = earlier.minusDays(1))
        c.repo.saveReminder(r)
        c.repo.setTaskDone(t.id, true)
        val after = c.db.reminders().get(r.id)!!
        assertEquals("recurring task stays open", "NOT_STARTED", c.db.tasks().get(t.id)!!.status)
        assertNotNull("current occurrence recorded as completed", after.completedThrough)
        assertNotNull("future occurrences remain scheduled", after.nextFireAt)
        assertTrue("next occurrence is in the future", after.nextFireAt!! > System.currentTimeMillis())
        assertTrue(c.db.completions().all().any { it.taskId == t.id && it.occurrenceAt != null })

        c.repo.deleteReminder(r.id)
        assertNull(c.db.reminders().get(r.id))
        assertTrue("job cancelled on delete", WorkInfo.State.ENQUEUED !in uniqueWorkStates(r.id))
    }

    @Test
    fun preciseReminderUsesExactAlarm() = runBlocking {
        c.settings.set(SettingKeys.NOTIFICATIONS_ENABLED, true)
        assertTrue("exact alarms granted by the test script", c.scheduler.canScheduleExact())
        val t = task("Then try Complete from the notification")
        val r = reminderFor(t, minutes = 2, precise = true)
        c.repo.saveReminder(r)
        assertTrue("precise reminders don't use WorkManager", WorkInfo.State.ENQUEUED !in uniqueWorkStates(r.id))
        val dump = Acceptance.shell("dumpsys alarm")
        assertTrue("exact alarm registered", dump.contains("app.projectsteps"))
        assertNotNull("precise reminder notification appeared", waitForNotification(r.id, timeoutMs = 4 * 60_000))
        c.repo.deleteReminder(r.id)
    }
}
