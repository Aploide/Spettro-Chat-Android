package to.eyed.spettro.chat.data.tools

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * get-location: one coarse fix via the platform LocationManager (no Play
 * Services dependency). Coordinates are rounded — the permission is
 * ACCESS_COARSE_LOCATION, precision is not on offer anyway.
 */
internal class LocationTools(private val context: Context) {

    @SuppressLint("MissingPermission") // gated by PermissionBridge before dispatch
    suspend fun current(appVisible: Boolean): ToolResult {
        // Background reads need ACCESS_BACKGROUND_LOCATION, which this app
        // deliberately never requests; a dataSync service confers none.
        if (!appVisible) {
            return ToolResult(
                "error: get-location: location is only readable while the app is on screen. " +
                    "Ask the user to return to the app first.",
                isError = true,
            )
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ToolResult("location unavailable on this device", isError = true)
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            return ToolResult("location is turned off in the device settings", isError = true)
        }
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER
            lm.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> lm.allProviders.firstOrNull()
                ?: return ToolResult("no location provider available", isError = true)
        }

        val executor = Executors.newSingleThreadExecutor()
        val fix: Location? = try {
            withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(lm, provider, signal, executor) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }
            } ?: lm.getLastKnownLocation(provider)
        } finally {
            executor.shutdown()
        }
        fix ?: return ToolResult("could not determine the location right now", isError = true)

        val lat = String.format(Locale.US, "%.2f", fix.latitude)
        val lon = String.format(Locale.US, "%.2f", fix.longitude)
        val place = reverseGeocode(fix)
        return ToolResult(
            buildString {
                append("Approximate location: $lat, $lon")
                if (place != null) append(" — $place")
            },
        )
    }

    private fun reverseGeocode(fix: Location): String? = runCatching {
        @Suppress("DEPRECATION")
        val addr = Geocoder(context, Locale.getDefault())
            .getFromLocation(fix.latitude, fix.longitude, 1)
            ?.firstOrNull() ?: return null
        listOfNotNull(addr.locality ?: addr.subAdminArea, addr.adminArea, addr.countryName)
            .distinct()
            .joinToString(", ")
            .ifBlank { null }
    }.getOrNull()
}
