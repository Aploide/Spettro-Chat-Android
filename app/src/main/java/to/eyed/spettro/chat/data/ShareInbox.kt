package to.eyed.spettro.chat.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Content handed to the app by another app (share sheet or text selection). */
data class SharedPayload(
    val text: String = "",
    val imageUris: List<Uri> = emptyList(),
    val fileUris: List<Uri> = emptyList(),
)

/**
 * Relays share/process-text intents from MainActivity to the composer. State
 * rather than an event so a payload posted before the UI is composed (cold
 * start straight from the share sheet) still lands; the UI clears it after
 * consuming exactly once.
 */
class ShareInbox {
    private val _pending = MutableStateFlow<SharedPayload?>(null)
    val pending: StateFlow<SharedPayload?> = _pending.asStateFlow()

    fun post(payload: SharedPayload) {
        _pending.value = payload
    }

    fun clear() {
        _pending.value = null
    }
}
