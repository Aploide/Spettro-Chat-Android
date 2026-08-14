package to.eyed.spettro.chat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import to.eyed.spettro.chat.data.api.SpettroApi
import to.eyed.spettro.chat.data.api.SpettroWebApi
import to.eyed.spettro.chat.data.mcp.McpRegistry
import to.eyed.spettro.chat.data.skills.SkillsRepository
import to.eyed.spettro.chat.data.store.ChatDatabase
import to.eyed.spettro.chat.data.store.ConversationStore
import to.eyed.spettro.chat.data.tools.ToolRegistry
import to.eyed.spettro.chat.engine.ChatEngine
import to.eyed.spettro.chat.engine.ConsentGate
import to.eyed.spettro.chat.engine.PermissionBridge

/** Manual DI: one instance of each service, shared by the ViewModels. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val prefs = AppPrefs(context.applicationContext)
    val api = SpettroApi(
        baseUrl = debugOverride(context, "spettro_api_url") ?: SpettroApi.DEFAULT_BASE_URL,
        apiKeyProvider = { prefs.apiKey },
    )
    val webApi = SpettroWebApi(
        baseUrl = debugOverride(context, "spettro_web_url") ?: SpettroWebApi.DEFAULT_BASE_URL,
    )
    private val db = ChatDatabase.build(context.applicationContext)
    val conversations = ConversationStore(context.applicationContext, db.conversations())
    val skills = SkillsRepository(db.skills())
    val memory = to.eyed.spettro.chat.data.memory.MemoryStore(db.memories())
    val tools = ToolRegistry(context.applicationContext, prefs, memory)

    /** In-app approval for sensitive tools, and the runtime-permission relay. */
    val consent = ConsentGate(prefs)
    val permissions = PermissionBridge(context.applicationContext)

    /** User-configured remote MCP servers and their discovered tools. */
    val mcp = McpRegistry(prefs)

    /** Content shared into the app from other apps, awaiting the composer. */
    val shareInbox = ShareInbox()

    /** Emitted when any API call returns 401 — the session must be re-established. */
    val unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted after a backup import rewrote settings, so UI state reloads them. */
    val settingsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The headless agent loop behind background and scheduled tasks. */
    val runner = to.eyed.spettro.chat.engine.AgentRunner(
        api, tools, mcp, memory, consent, permissions, prefs,
    )

    /** Concurrent background agent tasks (spawned and scheduled). */
    val taskManager = to.eyed.spettro.chat.engine.TaskManager(
        context.applicationContext, runner, conversations,
    )

    /** The app-scoped agent loop; built last so it can take everything above. */
    val engine = ChatEngine(
        context.applicationContext, api, conversations, tools, mcp, skills, memory,
        consent, permissions, prefs, unauthorized,
    )

    /** Whole-app export/import: chats, skills, memory, MCP servers, settings. */
    val backup = to.eyed.spettro.chat.data.store.BackupManager(
        context.applicationContext, conversations, skills, memory, mcp, prefs, settingsChanged,
    )

    init {
        tools.appVisibleProvider = { engine.appVisible }
        taskManager.appVisibleProvider = { engine.appVisible }
        // Finished tasks write their result chat straight to the store; the
        // engine re-reads so it appears in the sidebar without a restart.
        taskManager.onConversationsChanged = { engine.refreshConversations() }
        tools.taskSpawner = { title, prompt ->
            val task = taskManager.spawn(title, prompt)
            to.eyed.spettro.chat.data.tools.ToolResult(
                "Background task \"${task.title}\" started (id ${task.id}). It runs on its own; " +
                    "the result will arrive as a new chat (and a notification if the app is closed). " +
                    "Do not wait for it — answer the user now.",
            )
        }
    }

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
