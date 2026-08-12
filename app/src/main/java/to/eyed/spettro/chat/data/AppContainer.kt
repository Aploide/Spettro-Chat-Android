package to.eyed.spettro.chat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import to.eyed.spettro.chat.data.api.SpettroApi
import to.eyed.spettro.chat.data.store.ConversationStore

/** Manual DI: one instance of each service, shared by the ViewModels. */
class AppContainer(context: Context) {
    val prefs = AppPrefs(context.applicationContext)
    val api = SpettroApi(apiKeyProvider = { prefs.apiKey })
    val conversations = ConversationStore(context.applicationContext)

    /** Emitted when any API call returns 401 — the session must be re-established. */
    val unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
