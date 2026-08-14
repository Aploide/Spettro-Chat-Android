package to.eyed.spettro.chat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import to.eyed.spettro.chat.data.api.SpettroApi
import to.eyed.spettro.chat.data.api.SpettroWebApi
import to.eyed.spettro.chat.data.store.ConversationStore
import to.eyed.spettro.chat.data.tools.ToolRegistry

/** Manual DI: one instance of each service, shared by the ViewModels. */
class AppContainer(context: Context) {
    val prefs = AppPrefs(context.applicationContext)
    val api = SpettroApi(
        baseUrl = debugOverride(context, "spettro_api_url") ?: SpettroApi.DEFAULT_BASE_URL,
        apiKeyProvider = { prefs.apiKey },
    )
    val webApi = SpettroWebApi(
        baseUrl = debugOverride(context, "spettro_web_url") ?: SpettroWebApi.DEFAULT_BASE_URL,
    )
    val conversations = ConversationStore(context.applicationContext)
    val tools = ToolRegistry(context.applicationContext)

    /** Emitted when any API call returns 401 — the session must be re-established. */
    val unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    companion object {
        /**
         * Debug-only URL overrides, like the CLI's SPETTRO_API_URL:
         * `adb shell settings put global spettro_api_url http://10.0.2.2:8787`
         * `adb shell settings put global spettro_web_url http://10.0.2.2:3000`
         */
        private fun debugOverride(context: Context, setting: String): String? {
            if (!to.eyed.spettro.chat.BuildConfig.DEBUG) return null
            return runCatching {
                android.provider.Settings.Global.getString(context.contentResolver, setting)
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
