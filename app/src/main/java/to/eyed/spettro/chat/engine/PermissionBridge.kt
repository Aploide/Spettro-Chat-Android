package to.eyed.spettro.chat.engine

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A runtime-permission request the engine is waiting on. */
data class PermissionRequest(val permissions: List<String>, val rationale: String)

/**
 * Bridges the engine (which may be running under the foreground service with
 * no Activity on screen) to Android's runtime permission dialog, which only
 * an Activity can show. The engine suspends on [ensure]; ChatRoot observes
 * [pending] and fires the ActivityResult launcher whenever the app is
 * resumed — so a request made while backgrounded simply waits until the user
 * returns (the service posts a "needs your input" notification meanwhile).
 */
class PermissionBridge(private val appContext: Context) {
    private val _pending = MutableStateFlow<PermissionRequest?>(null)
    val pending: StateFlow<PermissionRequest?> = _pending.asStateFlow()
    private var reply: CompletableDeferred<Map<String, Boolean>>? = null

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /** Concurrent chats may ask at once; dialogs are fired one at a time. */
    private val mutex = Mutex()

    /** Engine-side: true when every permission is (or becomes) granted. */
    suspend fun ensure(permissions: List<String>, rationale: String): Boolean = mutex.withLock {
        val missing = permissions.filter { !granted(it) }
        if (missing.isEmpty()) return@withLock true
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        reply = deferred
        _pending.value = PermissionRequest(missing, rationale)
        try {
            val result = deferred.await()
            missing.all { result[it] == true || granted(it) }
        } finally {
            _pending.value = null
            reply = null
        }
    }

    /** Activity-side: outcome of RequestMultiplePermissions. */
    fun resolve(result: Map<String, Boolean>) {
        _pending.value = null
        reply?.complete(result)
    }
}
