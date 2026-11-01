package app.projectsteps.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.projectsteps.ProjectStepsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** Precise (exact-alarm) reminders arrive here. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(Notifications.EXTRA_REMINDER_ID) ?: return
        val container = (context.applicationContext as ProjectStepsApp).container
        runAsync { container.engine.fire(id) }
    }

    companion object {
        const val ACTION_FIRE = "app.projectsteps.action.FIRE_REMINDER"
    }
}

/** COMPLETE and SNOOZE buttons on reminder notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Notifications.EXTRA_REMINDER_ID) ?: return
        val container = (context.applicationContext as ProjectStepsApp).container
        when (intent.action) {
            Notifications.ACTION_COMPLETE -> runAsync {
                Notifications.cancel(context, id)
                container.repo.completeFromReminder(id)
            }
            Notifications.ACTION_SNOOZE -> runAsync {
                Notifications.cancel(context, id)
                val minutes = container.settings.current().snoozeMinutes
                container.repo.snoozeReminder(id, minutes)
            }
        }
    }
}

/**
 * Reboot, app update, manual clock change, time-zone change, or exact-alarm permission change:
 * recompute every reminder from its stored definition. Daylight-saving changes need no event:
 * triggers are always computed from wall-clock time in the reminder's zone.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> {
                val container = (context.applicationContext as ProjectStepsApp).container
                runAsync { container.scheduler.rescheduleAll() }
            }
        }
    }
}
