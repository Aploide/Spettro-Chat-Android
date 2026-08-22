package to.eyed.spettro.chat.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import to.eyed.spettro.chat.MainActivity

/**
 * Notification channels and builders for agent runs and reminders. Channels
 * are created once at app start; creation is idempotent.
 */
object AgentNotifications {
    const val CHANNEL_PROGRESS = "agent_progress"
    const val CHANNEL_DONE = "agent_done"
    const val CHANNEL_INPUT = "agent_input"
    const val CHANNEL_REMINDERS = "reminders"

    const val PROGRESS_ID = 100
    const val DONE_ID = 101
    const val INPUT_ID = 102

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Task progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Spettro is working on a task"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished tasks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A task finished while the app was in the background"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INPUT, "Needs your input", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Spettro is waiting for an answer or an approval"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you asked Spettro to set"
            },
        )
    }

    fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        // setPackage on top of the explicit component: keeps the intent
        // provably non-implicit for static analysis (CodeQL misses the
        // Kotlin ::class.java constructor argument).
        Intent(context, MainActivity::class.java)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The ongoing foreground-service notification; [text] is the current activity label. */
    fun progress(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(to.eyed.spettro.chat.R.drawable.ic_launcher_foreground)
            .setContentTitle("Spettro is working")
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /** Deliberately content-free: names the chat, never the answer or reasoning. */
    fun done(context: Context, chatTitle: String, failed: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(
                to.eyed.spettro.chat.R.drawable.ic_launcher_foreground,
            )
            .setContentTitle(if (failed) "Spettro hit a problem" else "Spettro finished")
            .setContentText(
                if (failed) "“${chatTitle.take(60)}” stopped on an error — tap to see what happened."
                else "Done working on “${chatTitle.take(60)}” — tap to see the answer.",
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()

    /**
     * A background or scheduled task finished. Content-free like [done]:
     * the task's title, never its result.
     */
    fun taskDone(context: Context, taskTitle: String, failed: Boolean, scheduled: Boolean): Notification {
        val what = if (scheduled) "Scheduled task" else "Background task"
        return NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(to.eyed.spettro.chat.R.drawable.ic_launcher_foreground)
            .setContentTitle(if (failed) "$what hit a problem" else "$what finished")
            .setContentText(
                if (failed) "“${taskTitle.take(60)}” stopped on an error — tap to see what happened."
                else "“${taskTitle.take(60)}” is done — tap to read the result.",
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
    }

    fun needsInput(context: Context, reason: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_INPUT)
            .setSmallIcon(to.eyed.spettro.chat.R.drawable.ic_launcher_foreground)
            .setContentTitle("Spettro needs your input")
            .setContentText(reason)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()

    fun reminder(context: Context, message: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(to.eyed.spettro.chat.R.drawable.ic_launcher_foreground)
            .setContentTitle("Reminder")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()

    /** notify() that quietly no-ops when POST_NOTIFICATIONS was denied on 33+. */
    fun notifySafely(context: Context, id: Int, notification: Notification) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
