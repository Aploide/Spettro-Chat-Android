package to.eyed.spettro.chat.data.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** current-time (live device clock) and device-info (battery, network, locale). */
internal class DeviceTools(private val context: Context) {

    fun currentTime(argumentsJson: String): ToolResult {
        val zone = ToolArgs.string(argumentsJson, "timezone")?.let {
            runCatching { ZoneId.of(it) }.getOrNull()
                ?: return ToolResult("unknown timezone: $it", isError = true)
        } ?: ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val fmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' HH:mm:ss zzz", Locale.getDefault())
        val utc = now.withZoneSameInstant(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))
        return ToolResult("${now.format(fmt)} (${zone.id})\n$utc")
    }

    fun deviceInfo(): ToolResult {
        val lines = mutableListOf<String>()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                lines += "Battery: ${level * 100 / scale}%" + if (charging) " (charging)" else ""
            }
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        lines += "Network: " + when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "connected"
        }
        lines += "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        lines += "Locale: ${Locale.getDefault().toLanguageTag()}"
        lines += "Timezone: ${ZoneId.systemDefault().id}"
        return ToolResult(lines.joinToString("\n"))
    }
}
