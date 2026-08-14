package to.eyed.spettro.chat.data.tools

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * calendar-events: read upcoming events from the device calendar, or open the
 * calendar app's editor pre-filled to create one. Creation deliberately goes
 * through ACTION_INSERT instead of WRITE_CALENDAR — the user confirms the
 * exact event in their own calendar app, and the app never needs write access.
 */
internal class CalendarTools(private val context: Context) {

    fun run(argumentsJson: String, appVisible: Boolean): ToolResult =
        when (val action = ToolArgs.string(argumentsJson, "action") ?: "read") {
            "read" -> read(argumentsJson)
            "create" -> create(argumentsJson, appVisible)
            else -> ToolResult("unknown action: $action (use \"read\" or \"create\")", isError = true)
        }

    private fun read(argumentsJson: String): ToolResult {
        val days = (ToolArgs.int(argumentsJson, "days") ?: 7).coerceIn(1, 60)
        val begin = System.currentTimeMillis()
        val end = begin + days * 24L * 60 * 60 * 1000

        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, begin)
        ContentUris.appendId(uriBuilder, end)
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val lines = mutableListOf<String>()
        context.contentResolver.query(
            uriBuilder.build(), projection, null, null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val zone = ZoneId.systemDefault()
            val fmt = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.getDefault())
            val dayFmt = DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault())
            while (cursor.moveToNext() && lines.size < 50) {
                val startAt = Instant.ofEpochMilli(cursor.getLong(0)).atZone(zone)
                val endAt = Instant.ofEpochMilli(cursor.getLong(1)).atZone(zone)
                val title = cursor.getString(2)?.ifBlank { null } ?: "(untitled)"
                val allDay = cursor.getInt(3) == 1
                val location = cursor.getString(4)?.takeIf { it.isNotBlank() }
                val calendar = cursor.getString(5)?.takeIf { it.isNotBlank() }
                val at = if (allDay) {
                    "${startAt.format(dayFmt)} (all day)"
                } else {
                    "${startAt.format(fmt)}–${endAt.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                }
                lines += listOfNotNull(at, title, location, calendar?.let { "[$it]" }).joinToString(" — ")
            }
        } ?: return ToolResult("calendar unavailable on this device", isError = true)

        return ToolResult(
            if (lines.isEmpty()) "No events in the next $days day(s)."
            else "Events in the next $days day(s):\n" + lines.joinToString("\n"),
        )
    }

    private fun create(argumentsJson: String, appVisible: Boolean): ToolResult {
        val title = ToolArgs.string(argumentsJson, "title")
            ?: return ToolResult("create requires a title", isError = true)
        // Opening the editor is launching an activity, which Android blocks
        // from the background; the user must have the app on screen.
        if (!appVisible) {
            return ToolResult(
                "error: calendar-events: creating an event opens the calendar editor, " +
                    "which requires the app to be on screen. Ask the user to return to the app first.",
                isError = true,
            )
        }
        val zone = ZoneId.systemDefault()
        fun parseLocal(key: String): Long? = ToolArgs.string(argumentsJson, key)?.let {
            runCatching { LocalDateTime.parse(it).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        }
        val start = parseLocal("start")
            ?: return ToolResult(
                "create requires start as an ISO local datetime, e.g. 2026-08-14T15:30",
                isError = true,
            )
        val end = parseLocal("end") ?: (start + 60L * 60 * 1000)

        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.Events.TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ToolArgs.string(argumentsJson, "location")?.let {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it)
        }
        ToolArgs.string(argumentsJson, "description")?.let {
            intent.putExtra(CalendarContract.Events.DESCRIPTION, it)
        }
        return try {
            context.startActivity(intent)
            ToolResult(
                "Opened the calendar editor pre-filled with \"$title\"; " +
                    "the user reviews and saves the event there.",
            )
        } catch (e: Exception) {
            ToolResult("no calendar app available to create the event", isError = true)
        }
    }
}
