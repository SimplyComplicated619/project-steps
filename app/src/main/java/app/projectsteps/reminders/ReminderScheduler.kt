package app.projectsteps.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.projectsteps.ProjectStepsApp
import app.projectsteps.data.AppDatabase
import app.projectsteps.data.AppSettings
import app.projectsteps.data.ReminderEntity
import app.projectsteps.data.SettingsRepository
import app.projectsteps.data.isDone
import app.projectsteps.data.isRecurring
import app.projectsteps.data.toRuntime
import app.projectsteps.data.toSpec
import app.projectsteps.core.schedule.ReminderPlanner
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Turns reminder definitions into scheduled work.
 *
 *  - Normal reminders use WorkManager (Android may deliver them a little late to save battery).
 *  - "Precise" reminders use AlarmManager.setExactAndAllowWhileIdle, only if the user granted
 *    exact-alarm access; otherwise they fall back to WorkManager.
 * Each reminder has exactly one unique job / alarm keyed by its ID, so rescheduling replaces
 * the previous one and never creates duplicates.
 */
class ReminderScheduler(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    data class TargetState(val active: Boolean, val done: Boolean)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    suspend fun reschedule(reminderId: String) {
        val r = db.reminders().get(reminderId)
        if (r == null) {
            cancel(reminderId)
            return
        }
        schedule(r, settings.current())
    }

    suspend fun rescheduleForTask(taskId: String) {
        val s = settings.current()
        db.reminders().forTask(taskId).forEach { schedule(it, s) }
    }

    suspend fun rescheduleAll() {
        val s = settings.current()
        db.reminders().all().forEach { schedule(it, s) }
    }

    /** Computes the next trigger, stores it, and (re)creates the job or alarm. Returns the trigger. */
    private suspend fun schedule(r: ReminderEntity, s: AppSettings): Instant? {
        val target = targetState(r)
        val next = if (!target.active) null else ReminderPlanner.nextTrigger(
            r.toSpec(),
            r.toRuntime(itemDone = r.itemDone || (!r.isRecurring && target.done)),
            Instant.now(),
            ZoneId.systemDefault(),
            s.quietHours,
        )
        val millis = next?.toEpochMilli()
        if (r.nextFireAt != millis) db.reminders().setNextFireAt(r.id, millis)
        if (next == null) cancel(r.id) else enqueue(r, next)
        return next
    }

    suspend fun targetState(r: ReminderEntity): TargetState {
        val projectId: String
        var done = false
        if (r.taskId != null) {
            val t = db.tasks().get(r.taskId) ?: return TargetState(false, false)
            if (t.deletedAt != null) return TargetState(false, false)
            val section = db.sections().get(t.sectionId)
            if (section == null || section.deletedAt != null) return TargetState(false, false)
            projectId = t.projectId
            done = t.isDone
        } else {
            projectId = r.projectId ?: return TargetState(false, false)
        }
        val p = db.projects().get(projectId) ?: return TargetState(false, false)
        if (p.deletedAt != null || p.archivedAt != null) return TargetState(false, false)
        if (r.taskId == null) done = p.status == "DONE"
        return TargetState(true, done)
    }

    private fun enqueue(r: ReminderEntity, at: Instant) {
        val delay = (at.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
        if (r.precise && canScheduleExact()) {
            cancelWork(r.id)
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), alarmIntent(r.id))
                return
            } catch (e: SecurityException) {
                // Exact alarm access was revoked between the check and the call: fall back below.
            }
        }
        cancelAlarm(r.id)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(Notifications.EXTRA_REMINDER_ID to r.id))
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(r.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(reminderId: String) {
        cancelWork(reminderId)
        cancelAlarm(reminderId)
    }

    private fun cancelWork(reminderId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(reminderId))
    }

    private fun cancelAlarm(reminderId: String) {
        alarmManager.cancel(alarmIntent(reminderId))
    }

    private fun alarmIntent(reminderId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .setData(Uri.parse("projectsteps://alarm/$reminderId"))
            .putExtra(Notifications.EXTRA_REMINDER_ID, reminderId)
        return PendingIntent.getBroadcast(
            context, reminderId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val WORK_TAG = "reminder"
        fun workName(reminderId: String) = "reminder-$reminderId"
    }
}

/** Runs when a normal (WorkManager) reminder is due. */
class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(Notifications.EXTRA_REMINDER_ID) ?: return Result.success()
        val container = (applicationContext as ProjectStepsApp).container
        container.engine.fire(id)
        return Result.success()
    }
}
