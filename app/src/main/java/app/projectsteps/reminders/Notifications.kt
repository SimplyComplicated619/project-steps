package app.projectsteps.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.projectsteps.MainActivity
import app.projectsteps.R

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"
    private const val TAG = "reminder"

    const val ACTION_COMPLETE = "app.projectsteps.action.COMPLETE"
    const val ACTION_SNOOZE = "app.projectsteps.action.SNOOZE"
    const val EXTRA_REMINDER_ID = "reminderId"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_PROJECT_ID = "projectId"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.channel_reminders),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_reminders_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** True when Android will actually show our notifications. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_REMINDERS)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    data class Content(
        val reminderId: String,
        val projectName: String,
        val taskTitle: String?,
        val sectionName: String?,
        val dueText: String,
        val taskId: String?,
        val projectId: String?,
        val snoozeMinutes: Int,
        val showDetailsOnLockScreen: Boolean,
    )

    @SuppressLint("MissingPermission") // checked in canPost()
    fun show(context: Context, c: Content): Boolean {
        if (!canPost(context)) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("projectsteps://open/${c.taskId ?: c.projectId}")
            putExtra(EXTRA_TASK_ID, c.taskId)
            putExtra(EXTRA_PROJECT_ID, c.projectId)
        }
        val open = PendingIntent.getActivity(
            context, ("open:" + c.reminderId).hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("You have a scheduled project task.")
            .build()

        val headline = c.taskTitle ?: c.projectName
        val body = listOfNotNull(headline, c.sectionName?.let { "Section: $it" }, c.dueText).joinToString("\n")
        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setContentTitle(c.projectName)
            .setContentText(headline)
            .setSubText(c.dueText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Complete", actionIntent(context, ACTION_COMPLETE, c.reminderId))
            .addAction(0, "Snooze ${c.snoozeMinutes} min", actionIntent(context, ACTION_SNOOZE, c.reminderId))
        if (c.showDetailsOnLockScreen) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
        }
        return try {
            NotificationManagerCompat.from(context).notify(TAG, notificationId(c.reminderId), builder.build())
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun cancel(context: Context, reminderId: String) {
        NotificationManagerCompat.from(context).cancel(TAG, notificationId(reminderId))
    }

    private fun notificationId(reminderId: String) = reminderId.hashCode()

    private fun actionIntent(context: Context, action: String, reminderId: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("projectsteps://reminder/$reminderId"))
            .putExtra(EXTRA_REMINDER_ID, reminderId)
        return PendingIntent.getBroadcast(
            context, (action + reminderId).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
