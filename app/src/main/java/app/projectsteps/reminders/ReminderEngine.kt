package app.projectsteps.reminders

import android.content.Context
import app.projectsteps.data.AppDatabase
import app.projectsteps.data.SettingsRepository
import app.projectsteps.data.Snapshot
import app.projectsteps.data.toRuntime
import app.projectsteps.data.withRuntime
import app.projectsteps.core.schedule.ReminderPlanner
import app.projectsteps.ui.Format
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Shows a due reminder and advances its schedule. Never performs any task action by itself. */
class ReminderEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend fun fire(reminderId: String) {
        val r = db.reminders().get(reminderId) ?: return
        val s = settings.current()
        val now = Instant.now()
        val expected = r.nextFireAt
        // Stale or early job (the reminder was edited, snoozed or completed since): just reschedule.
        if (expected == null || expected > now.toEpochMilli() + EARLY_TOLERANCE_MS) {
            scheduler.reschedule(reminderId)
            return
        }
        // A delayed job may land inside quiet hours: postpone instead of notifying.
        if (s.quietHours.contains(LocalTime.now())) {
            scheduler.reschedule(reminderId)
            return
        }
        val target = scheduler.targetState(r)
        if (!target.active) {
            scheduler.reschedule(reminderId)
            return
        }

        val task = r.taskId?.let { db.tasks().get(it) }
        val project = db.projects().get(task?.projectId ?: r.projectId ?: return) ?: return
        val section = task?.let { db.sections().get(it.sectionId) }
        val zone = ZoneId.systemDefault()
        val due = task?.let { Snapshot.dueOf(it.dueDate, it.dueTime, it.dueZoneId, zone) }
        val dueText = when {
            due != null && due.isBefore(now.minusSeconds(60)) -> "OVERDUE since " + Format.dateTime(due, zone)
            else -> "Due now"
        }
        if (s.notificationsEnabled) {
            Notifications.show(
                context,
                Notifications.Content(
                    reminderId = r.id,
                    projectName = project.name,
                    taskTitle = task?.title,
                    sectionName = section?.title,
                    dueText = dueText,
                    taskId = task?.id,
                    projectId = project.id,
                    snoozeMinutes = s.snoozeMinutes,
                    showDetailsOnLockScreen = s.lockScreenDetails,
                ),
            )
        }
        val fired = ReminderPlanner.onFired(r.toRuntime(), now)
        db.reminders().upsert(r.withRuntime(fired, now.toEpochMilli()))
        scheduler.reschedule(reminderId)
    }

    companion object {
        const val EARLY_TOLERANCE_MS = 60_000L
    }
}
