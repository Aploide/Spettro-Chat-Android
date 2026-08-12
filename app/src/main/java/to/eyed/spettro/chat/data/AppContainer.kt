package to.eyed.spettro.chat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import to.eyed.spettro.chat.data.api.SpettroApi
import to.eyed.spettro.chat.data.store.ConversationStore

/** Manual DI: one instance of each service, shared by the ViewModels. */
class AppContainer(context: Context) {
    val prefs = AppPrefs(context.applicationContext)
    val api = SpettroApi(
        baseUrl = debugApiOverride(context) ?: SpettroApi.DEFAULT_BASE_URL,
        apiKeyProvider = { prefs.apiKey },
    )
    val conversations = ConversationStore(context.applicationContext)

    /** Emitted when any API call returns 401 — the session must be re-established. */
    val unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    companion object {
        /**
         * Debug-only equivalent of the CLI's SPETTRO_API_URL override:
         * `adb shell settings put global spettro_api_url http://10.0.2.2:8787`
         */
        private fun debugApiOverride(context: Context): String? {
            if (!to.eyed.spettro.chat.BuildConfig.DEBUG) return null
            return runCatching {
                android.provider.Settings.Global.getString(context.contentResolver, "spettro_api_url")
            }.getOrNull()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        }

        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
