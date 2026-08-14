package to.eyed.spettro.chat.data.tools

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * read-notifications: what's sitting in the status bar right now — "what did
 * I miss?". Read-only by design: no dismissing, no replying.
 *
 * Notification access is its own OS-level special grant (not a runtime
 * permission): the user must flip Spettro on under Settings → Notification
 * access. The in-app consent card still runs first on every use, like every
 * other sensitive tool; the OS grant is the second gate.
 */
internal class NotificationTools(private val context: Context) {

    fun read(argumentsJson: String): ToolResult {
        if (!SpettroNotificationListener.isEnabled(context)) {
            return ToolResult(
                "error: notification access is not enabled for Spettro. The user must turn it on under " +
                    "Settings → Device → Notification access (or the row in this app's Settings sheet). " +
                    "Do not retry until they have.",
                isError = true,
            )
        }
        val active = SpettroNotificationListener.instance?.let {
            runCatching { it.activeNotifications }.getOrNull()
        } ?: return ToolResult(
            "error: the notification listener is not connected yet — it can take a few seconds after " +
                "enabling access. Ask the user to try again in a moment.",
            isError = true,
        )

        val max = (ToolArgs.int(argumentsJson, "max_results") ?: 25).coerceIn(1, 50)
        val pm = context.packageManager
        val timeFmt = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
        val lines = active
            .filter { it.packageName != context.packageName }
            .filterNot { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
            .sortedByDescending { it.postTime }
            .take(max)
            .mapNotNull { sbn -> describe(sbn, pm, timeFmt) }
        return if (lines.isEmpty()) {
            ToolResult("No notifications in the status bar right now.")
        } else {
            ToolResult("Current notifications, newest first:\n" + lines.joinToString("\n"))
        }
    }

    private fun describe(
        sbn: StatusBarNotification,
        pm: android.content.pm.PackageManager,
        timeFmt: DateTimeFormatter,
    ): String? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        val app = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val at = Instant.ofEpochMilli(sbn.postTime).atZone(ZoneId.systemDefault()).format(timeFmt)
        val ongoing = if (sbn.isOngoing) " [ongoing]" else ""
        val body = listOf(title, text.take(300)).filter { it.isNotEmpty() }.joinToString(": ")
        return "- [$at] $app$ongoing — $body"
    }
}

/**
 * The read-only listener behind read-notifications. Android binds it once the
 * user grants notification access; until then [instance] stays null. It
 * observes nothing and forwards nothing — the tool pulls the active list on
 * demand, and only ever after the consent card.
 */
class SpettroNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    companion object {
        @Volatile
        var instance: SpettroNotificationListener? = null
            private set

        /** Whether the user has granted Spettro notification access in the OS. */
        fun isEnabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(":")
                ?.mapNotNull { ComponentName.unflattenFromString(it) }
                ?.any { it.packageName == context.packageName } == true

        /** The OS screen where the user flips access on or off. */
        fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
