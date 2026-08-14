package to.eyed.spettro.chat.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import to.eyed.spettro.chat.data.AppPrefs

/** A pending in-app approval for a sensitive tool or an MCP server. */
data class ConsentRequest(
    /** Stable identity of the grant: `tool:<name>` or `mcp:<serverId>`. */
    val consentKey: String,
    /** e.g. "Allow access to your location?" */
    val title: String,
    /** What the model asked for, so the user decides informed. */
    val detail: String,
)

enum class ConsentDecision { AllowOnce, AlwaysAllow, Deny }

/**
 * The mandatory in-app approval layer for tools touching personal data:
 * every sensitive call is either covered by a persisted "always allow" or
 * suspends until the user answers the consent card. Modeled on the ask-user
 * bridge: no timeout (it waits on a person) and a deny is an explicit tool
 * error, never silence. This gate sits in front of — not instead of — the
 * Android runtime permission.
 */
class ConsentGate(private val prefs: AppPrefs) {
    private val _pending = MutableStateFlow<ConsentRequest?>(null)
    val pending: StateFlow<ConsentRequest?> = _pending.asStateFlow()
    private var reply: CompletableDeferred<ConsentDecision>? = null

    /** The persisted grants, for the Settings revoke list. */
    val alwaysAllowed: Flow<Set<String>> = prefs.consentAlwaysFlow()

    /** Engine-side: true when the user allowed the call (once or always). */
    suspend fun require(request: ConsentRequest): Boolean {
        if (request.consentKey in prefs.consentAlways()) return true
        val deferred = CompletableDeferred<ConsentDecision>()
        reply = deferred
        _pending.value = request
        try {
            return when (deferred.await()) {
                ConsentDecision.AllowOnce -> true
                ConsentDecision.AlwaysAllow -> {
                    prefs.grantConsentAlways(request.consentKey)
                    true
                }
                ConsentDecision.Deny -> false
            }
        } finally {
            _pending.value = null
            reply = null
        }
    }

    /** UI-side: the user tapped one of the consent card's buttons. */
    fun resolve(decision: ConsentDecision) {
        _pending.value = null
        reply?.complete(decision)
    }

    suspend fun revoke(consentKey: String) = prefs.revokeConsentAlways(consentKey)
}
