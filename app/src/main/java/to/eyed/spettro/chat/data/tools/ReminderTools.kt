package to.eyed.spettro.chat.data.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.eyed.spettro.chat.data.AppPrefs
import to.eyed.spettro.chat.engine.AgentNotifications
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class StoredReminder(val id: Int, val message: String, val atMillis: Long)

/**
 * set-reminder: schedule a local notification. Alarms are deliberately
 * inexact (setAndAllowWhileIdle): exact alarms need a special-access
 * permission on API 31+ that is denied by default since 14 — a reminder
 * landing within the OS batching window is the right trade for a chat
 * assistant, and the tool description says delivery is approximate.
 */
internal class ReminderTools(private val context: Context, private val prefs: AppPrefs) {

    suspend fun set(argumentsJson: String): ToolResult {
        val message = ToolArgs.string(argumentsJson, "message")
            ?: return ToolResult("set-reminder requires a message", isError = true)

        val zone = ZoneId.systemDefault()
        val inMinutes = ToolArgs.int(argumentsJson, "in_minutes")
        val atMillis = when {
            inMinutes != null -> System.currentTimeMillis() + inMinutes.coerceAtLeast(1) * 60_000L
            else -> ToolArgs.string(argumentsJson, "at")?.let {
                runCatching { LocalDateTime.parse(it).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            } ?: return ToolResult(
                "set-reminder needs either in_minutes or at (ISO local datetime, e.g. 2026-08-14T15:30)",
                isError = true,
            )
        }
        if (atMillis <= System.currentTimeMillis()) {
            return ToolResult("that time is in the past", isError = true)
        }

        val reminder = StoredReminder(
            id = (atMillis % Int.MAX_VALUE).toInt(),
            message = message.take(500),
            atMillis = atMillis,
        )
        Reminders.schedule(context, reminder)
        Reminders.persist(prefs) { pending -> pending.filter { it.id != reminder.id } + reminder }

        val display = Instant.ofEpochMilli(atMillis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEE MMM d 'at' HH:mm", Locale.getDefault()))
        return ToolResult("Reminder set for $display (delivery time is approximate).")
    }
}

/** Scheduling + persistence shared by the tool and the boot receiver. */
internal object Reminders {
    private val json = Json { ignoreUnknownKeys = true }

    const val EXTRA_ID = "reminder_id"
    const val EXTRA_MESSAGE = "reminder_message"

    fun schedule(context: Context, reminder: StoredReminder) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMillis, pendingIntent(context, reminder))
    }

    private fun pendingIntent(context: Context, reminder: StoredReminder): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminder.id,
            Intent(context, ReminderReceiver::class.java)
                .putExtra(EXTRA_ID, reminder.id)
                .putExtra(EXTRA_MESSAGE, reminder.message),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun load(prefs: AppPrefs): List<StoredReminder> = runBlocking {
        prefs.remindersJson()?.let {
            runCatching { json.decodeFromString<List<StoredReminder>>(it) }.getOrNull()
        } ?: emptyList()
    }

    fun persist(prefs: AppPrefs, transform: (List<StoredReminder>) -> List<StoredReminder>) = runBlocking {
        prefs.saveRemindersJson(json.encodeToString(transform(load(prefs))))
    }
}

/** Delivers a due reminder as a notification and drops it from the store. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Reminders.EXTRA_ID, 0)
        val message = intent.getStringExtra(Reminders.EXTRA_MESSAGE) ?: return
        AgentNotifications.notifySafely(
            context,
            // Offset keeps reminder ids clear of the agent-run notification ids.
            1000 + (id % 100_000),
            AgentNotifications.reminder(context, message),
        )
        val prefs = to.eyed.spettro.chat.data.AppContainer.get(context).prefs
        Reminders.persist(prefs) { pending -> pending.filter { it.id != id } }
    }
}

/** Alarms don't survive a reboot; re-schedule what is still in the future. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = to.eyed.spettro.chat.data.AppContainer.get(context).prefs
        val now = System.currentTimeMillis()
        val pending = Reminders.load(prefs).filter { it.atMillis > now }
        pending.forEach { Reminders.schedule(context, it) }
        Reminders.persist(prefs) { pending }
    }
}
