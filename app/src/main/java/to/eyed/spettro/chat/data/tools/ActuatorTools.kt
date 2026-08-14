package to.eyed.spettro.chat.data.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.view.KeyEvent

/**
 * Device actuators: hand off an action to the right app via intents, never
 * act invisibly. Messages open pre-filled in the messaging app (the user
 * taps send), alarms open pre-filled in the clock app (the user confirms),
 * so the OS-level confirmation is part of the flow on top of the consent
 * card. Media keys are the exception — play/pause is immediate — because
 * that's what a remote control is.
 *
 * Everything that launches an activity requires the app to be on screen:
 * Android blocks background activity starts, and silently queuing one for
 * later would be worse than saying so.
 */
internal class ActuatorTools(private val context: Context) {

    // --- compose-message: SMS or WhatsApp, pre-filled, user hits send ---

    fun composeMessage(argumentsJson: String, appVisible: Boolean): ToolResult {
        val channel = ToolArgs.string(argumentsJson, "channel") ?: "sms"
        val message = ToolArgs.string(argumentsJson, "message")
            ?: return ToolResult("compose-message requires a message", isError = true)
        val phone = ToolArgs.string(argumentsJson, "phone")?.filter { it.isDigit() || it == '+' }
        if (!appVisible) {
            return ToolResult(
                "error: compose-message only works while the app is on screen — tell the user to try again with the app open",
                isError = true,
            )
        }
        val intent = when (channel) {
            "sms" -> {
                if (phone.isNullOrBlank()) return ToolResult("sms requires a phone number", isError = true)
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).putExtra("sms_body", message)
            }
            "whatsapp" -> {
                // The wa.me deep link opens the chat pre-filled; without a
                // number WhatsApp shows its contact picker.
                val url = if (phone.isNullOrBlank()) {
                    "https://wa.me/?text=${Uri.encode(message)}"
                } else {
                    "https://wa.me/${phone.trimStart('+')}?text=${Uri.encode(message)}"
                }
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            else -> return ToolResult("channel must be sms or whatsapp", isError = true)
        }
        return launch(intent, "No app can handle $channel messages on this phone")
            ?: ToolResult(
                "Opened the ${if (channel == "sms") "messaging" else "WhatsApp"} composer pre-filled — " +
                    "the user reviews and sends it themselves.",
            )
    }

    // --- set-alarm: alarms and countdown timers via the clock app ---

    fun setAlarm(argumentsJson: String, appVisible: Boolean): ToolResult {
        if (!appVisible) {
            return ToolResult(
                "error: set-alarm only works while the app is on screen — tell the user to try again with the app open",
                isError = true,
            )
        }
        val message = ToolArgs.string(argumentsJson, "message")
        return when (val type = ToolArgs.string(argumentsJson, "type") ?: "alarm") {
            "alarm" -> {
                val hour = ToolArgs.int(argumentsJson, "hour")
                    ?: return ToolResult("an alarm requires hour (0-23)", isError = true)
                val minute = ToolArgs.int(argumentsJson, "minute") ?: 0
                if (hour !in 0..23 || minute !in 0..59) {
                    return ToolResult("hour must be 0-23 and minute 0-59", isError = true)
                }
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    // The clock app opens showing the new alarm, so the user
                    // sees exactly what was set.
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                message?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it.take(100)) }
                launch(intent, "No clock app can set alarms on this phone")
                    ?: ToolResult("Opened the clock app with a %02d:%02d alarm — the user confirms it there.".format(hour, minute))
            }
            "timer" -> {
                val minutes = ToolArgs.int(argumentsJson, "length_minutes")
                    ?: return ToolResult("a timer requires length_minutes", isError = true)
                if (minutes < 1) return ToolResult("length_minutes must be at least 1", isError = true)
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                message?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it.take(100)) }
                launch(intent, "No clock app can set timers on this phone")
                    ?: ToolResult("Opened the clock app with a $minutes-minute timer — the user confirms it there.")
            }
            else -> ToolResult("type must be alarm or timer", isError = true)
        }
    }

    // --- open-on-phone: launch an installed app by name, or any link ---

    fun openOnPhone(argumentsJson: String, appVisible: Boolean): ToolResult {
        if (!appVisible) {
            return ToolResult(
                "error: open-on-phone only works while the app is on screen — tell the user to try again with the app open",
                isError = true,
            )
        }
        val url = ToolArgs.string(argumentsJson, "url")
        val app = ToolArgs.string(argumentsJson, "app")
        return when {
            url != null -> {
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                if (uri?.scheme.isNullOrBlank()) return ToolResult("url must include a scheme, e.g. https:// or geo:", isError = true)
                launch(Intent(Intent.ACTION_VIEW, uri), "Nothing on this phone can open $url")
                    ?: ToolResult("Opened $url.")
            }
            app != null -> {
                val match = findApp(app)
                    ?: return ToolResult(
                        "no installed app matches \"$app\" — ask the user for the exact name",
                        isError = true,
                    )
                val intent = context.packageManager.getLaunchIntentForPackage(match.second)
                    ?: return ToolResult("\"${match.first}\" has no launchable screen", isError = true)
                launch(intent, "Couldn't open ${match.first}")
                    ?: ToolResult("Opened ${match.first}.")
            }
            else -> ToolResult("open-on-phone requires app (a name) or url (a link)", isError = true)
        }
    }

    /** Best launcher-app match for [name]: exact label, then prefix, then contains. */
    private fun findApp(name: String): Pair<String, String>? {
        val pm = context.packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val query = name.trim().lowercase()
        return launchables.firstOrNull { it.first.lowercase() == query }
            ?: launchables.firstOrNull { it.first.lowercase().startsWith(query) }
            ?: launchables.firstOrNull { query in it.first.lowercase() }
    }

    // --- media-control: the phone as a remote control ---

    fun mediaControl(argumentsJson: String): ToolResult {
        val action = ToolArgs.string(argumentsJson, "action")
            ?: return ToolResult("media-control requires an action", isError = true)
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return ToolResult("action must be play, pause, play_pause, next, previous, or stop", isError = true)
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return ToolResult("Sent the ${action.replace('_', '/')} media command to whatever is playing.")
    }

    /** Starts [intent]; null on success, an error ToolResult when nothing handles it. */
    private fun launch(intent: Intent, noHandlerMessage: String): ToolResult? = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: Exception) {
        ToolResult("$noHandlerMessage (${e.javaClass.simpleName})", isError = true)
    }
}
