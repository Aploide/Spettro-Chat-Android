package to.eyed.spettro.chat.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import to.eyed.spettro.chat.data.api.Account
import to.eyed.spettro.chat.data.api.ModelInfo
import to.eyed.spettro.chat.data.auth.SecureStore

private val Context.dataStore by preferencesDataStore(name = "spettro_chat")

/**
 * Persistent app state: the encrypted ep_ key, cached account info for
 * instant first paint (like the CLI's config.json), and UI settings.
 */
class AppPrefs(private val context: Context) {
    private object Keys {
        val apiKeyEnc = stringPreferencesKey("api_key_enc")
        val apiKeyId = stringPreferencesKey("api_key_id")
        val email = stringPreferencesKey("spettro_email")
        val plan = stringPreferencesKey("spettro_plan")
        val planStatus = stringPreferencesKey("spettro_plan_status")
        val accountJson = stringPreferencesKey("account_json")
        val modelsJson = stringPreferencesKey("models_json")
        val selectedModel = stringPreferencesKey("selected_model")
        val thinkingLevel = stringPreferencesKey("thinking_level")
        val streamingAnimations = booleanPreferencesKey("streaming_animations")
        val hapticFeedback = booleanPreferencesKey("haptic_feedback")
        val autoCompact = booleanPreferencesKey("auto_compact")
        val consentAlways = stringSetPreferencesKey("tool_consent_always")
        val remindersJson = stringPreferencesKey("reminders_json")
        val scheduledTasksJson = stringPreferencesKey("scheduled_tasks_json")
        val mcpServersJson = stringPreferencesKey("mcp_servers")
        val mcpToolsCacheJson = stringPreferencesKey("mcp_tools_cache")
    }

    private val json = Json { ignoreUnknownKeys = true }

    data class Snapshot(
        val apiKey: String?,
        val email: String,
        val plan: String,
        val planStatus: String,
        val account: Account?,
        val models: List<ModelInfo>,
        val selectedModel: String,
        val thinkingLevel: String,
        val streamingAnimations: Boolean,
        val hapticFeedback: Boolean,
        val autoCompact: Boolean,
    )

    // The API key is needed synchronously by the request interceptor; keep an
    // in-memory copy after load.
    @Volatile
    var apiKey: String? = null
        private set

    /** Server-side id of the current ep_ key, kept so sign-out can revoke it. */
    @Volatile
    var apiKeyId: String? = null
        private set

    suspend fun load(): Snapshot {
        val p = context.dataStore.data.first()
        val key = p[Keys.apiKeyEnc]?.let { SecureStore.decrypt(it) }
        apiKey = key
        apiKeyId = p[Keys.apiKeyId]
        // Cached payloads are best-effort: a parse failure (e.g. after a schema
        // change) just means an empty first paint until the refresh lands.
        val account = p[Keys.accountJson]?.let {
            runCatching { json.decodeFromString<Account>(it) }.getOrNull()
        }
        val models = p[Keys.modelsJson]?.let {
            runCatching { json.decodeFromString<List<ModelInfo>>(it) }.getOrNull()
        } ?: emptyList()
        return Snapshot(
            apiKey = key,
            email = p[Keys.email] ?: "",
            plan = p[Keys.plan] ?: "",
            planStatus = p[Keys.planStatus] ?: "",
            account = account,
            models = models,
            selectedModel = p[Keys.selectedModel] ?: "",
            thinkingLevel = p[Keys.thinkingLevel] ?: "high",
            streamingAnimations = p[Keys.streamingAnimations] ?: true,
            hapticFeedback = p[Keys.hapticFeedback] ?: true,
            autoCompact = p[Keys.autoCompact] ?: true,
        )
    }

    /** Persist the ep_ key immediately — the backend returns it exactly once. */
    fun saveApiKeyBlocking(key: String, keyId: String? = null) {
        apiKey = key
        apiKeyId = keyId
        runBlocking {
            context.dataStore.edit {
                it[Keys.apiKeyEnc] = SecureStore.encrypt(key)
                if (keyId != null) it[Keys.apiKeyId] = keyId else it.remove(Keys.apiKeyId)
            }
        }
    }

    suspend fun clearApiKeyAndAccount() {
        apiKey = null
        apiKeyId = null
        context.dataStore.edit {
            it.remove(Keys.apiKeyEnc)
            it.remove(Keys.apiKeyId)
            it.remove(Keys.email)
            it.remove(Keys.plan)
            it.remove(Keys.planStatus)
            it.remove(Keys.accountJson)
            it.remove(Keys.modelsJson)
            it.remove(Keys.selectedModel)
        }
    }

    suspend fun saveAccount(account: Account) {
        context.dataStore.edit {
            it[Keys.email] = account.email
            it[Keys.plan] = account.planOrFree
            it[Keys.planStatus] = account.planStatus
            it[Keys.accountJson] = json.encodeToString(account)
        }
    }

    suspend fun saveModels(models: List<ModelInfo>) {
        context.dataStore.edit { it[Keys.modelsJson] = json.encodeToString(models) }
    }

    suspend fun saveSelectedModel(model: String) {
        context.dataStore.edit { it[Keys.selectedModel] = model }
    }

    suspend fun saveThinkingLevel(level: String) {
        context.dataStore.edit { it[Keys.thinkingLevel] = level }
    }

    suspend fun saveStreamingAnimations(on: Boolean) {
        context.dataStore.edit { it[Keys.streamingAnimations] = on }
    }

    suspend fun saveHapticFeedback(on: Boolean) {
        context.dataStore.edit { it[Keys.hapticFeedback] = on }
    }

    /** Whether long chats are summarized automatically; read per turn by the engine. */
    suspend fun autoCompact(): Boolean =
        context.dataStore.data.first()[Keys.autoCompact] ?: true

    suspend fun saveAutoCompact(on: Boolean) {
        context.dataStore.edit { it[Keys.autoCompact] = on }
    }

    // --- Consent gate ("always allow" decisions for sensitive tools) ---

    fun consentAlwaysFlow(): Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.consentAlways] ?: emptySet() }

    suspend fun consentAlways(): Set<String> =
        context.dataStore.data.first()[Keys.consentAlways] ?: emptySet()

    suspend fun grantConsentAlways(key: String) {
        context.dataStore.edit { it[Keys.consentAlways] = (it[Keys.consentAlways] ?: emptySet()) + key }
    }

    suspend fun revokeConsentAlways(key: String) {
        context.dataStore.edit { it[Keys.consentAlways] = (it[Keys.consentAlways] ?: emptySet()) - key }
    }

    // --- Scheduled reminders (JSON list, reloaded by BootReceiver) ---

    suspend fun remindersJson(): String? = context.dataStore.data.first()[Keys.remindersJson]

    suspend fun saveRemindersJson(json: String) {
        context.dataStore.edit { it[Keys.remindersJson] = json }
    }

    // --- Scheduled agent tasks (JSON list; WorkManager owns the timing) ---

    suspend fun scheduledTasksJson(): String? = context.dataStore.data.first()[Keys.scheduledTasksJson]

    suspend fun saveScheduledTasksJson(json: String) {
        context.dataStore.edit { it[Keys.scheduledTasksJson] = json }
    }

    // --- MCP server configs and cached tool lists ---

    suspend fun mcpServersJson(): String? = context.dataStore.data.first()[Keys.mcpServersJson]

    suspend fun saveMcpServersJson(json: String) {
        context.dataStore.edit { it[Keys.mcpServersJson] = json }
    }

    suspend fun mcpToolsCacheJson(): String? = context.dataStore.data.first()[Keys.mcpToolsCacheJson]

    suspend fun saveMcpToolsCacheJson(json: String) {
        context.dataStore.edit { it[Keys.mcpToolsCacheJson] = json }
    }
}
