package to.eyed.spettro.chat.data.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import to.eyed.spettro.chat.data.AppPrefs
import to.eyed.spettro.chat.data.api.ToolSpec
import to.eyed.spettro.chat.data.tools.ToolResult
import java.util.concurrent.TimeUnit

/**
 * User-configured remote MCP servers and their tools. Configs and the last
 * known tool lists persist in AppPrefs (same JSON-cache pattern as models),
 * so tools are offered instantly on later launches; live (re)listing happens
 * lazily on the first send and explicitly from the settings sheet. A dead
 * server records an error and contributes nothing — it never blocks a send.
 */
class McpRegistry(private val prefs: AppPrefs) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    private val _toolsByServer = MutableStateFlow<Map<String, List<McpToolDef>>>(emptyMap())
    val toolsByServer: StateFlow<Map<String, List<McpToolDef>>> = _toolsByServer.asStateFlow()

    /** Last error per server id, for the settings sheet. */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val clients = mutableMapOf<String, McpClient>()

    /** Namespaced tool name → (server id, original tool name). */
    private val reverseMap = mutableMapOf<String, Pair<String, String>>()

    private val loadLock = Mutex()

    @Volatile
    private var loaded = false

    private companion object {
        const val LIST_BUDGET_MS = 10_000L
        const val MAX_TOOLS_PER_SERVER = 50
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            _servers.value = prefs.mcpServersJson()?.let {
                runCatching { json.decodeFromString<List<McpServerConfig>>(it) }.getOrNull()
            } ?: emptyList()
            _toolsByServer.value = prefs.mcpToolsCacheJson()?.let {
                runCatching { json.decodeFromString<Map<String, List<McpToolDef>>>(it) }.getOrNull()
            } ?: emptyMap()
            rebuildReverseMap()
            loaded = true
        }
    }

    // --- Config management (settings sheet) ---

    suspend fun addServer(config: McpServerConfig) {
        ensureLoaded()
        persistServers(_servers.value.filter { it.id != config.id } + config)
    }

    suspend fun updateServer(config: McpServerConfig) {
        ensureLoaded()
        persistServers(_servers.value.map { if (it.id == config.id) config else it })
        // A changed URL or auth invalidates the session and the cached tools.
        clients.remove(config.id)?.let { scope.launch { it.close() } }
        persistTools(_toolsByServer.value - config.id)
    }

    suspend fun removeServer(id: String) {
        ensureLoaded()
        persistServers(_servers.value.filter { it.id != id })
        persistTools(_toolsByServer.value - id)
        _errors.value = _errors.value - id
        clients.remove(id)?.let { scope.launch { it.close() } }
        prefs.revokeConsentAlways("mcp:$id")
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        ensureLoaded()
        persistServers(_servers.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** Explicit re-list from the settings sheet; errors surface in [errors]. */
    suspend fun refreshTools(id: String) {
        ensureLoaded()
        val config = _servers.value.firstOrNull { it.id == id } ?: return
        listServer(config)
    }

    // --- Engine-facing surface ---

    /**
     * The namespaced specs of every enabled server, listing any server not
     * yet listed this launch. Never throws; a failing server just records an
     * error and is skipped.
     */
    suspend fun activeSpecs(): List<ToolSpec> {
        ensureLoaded()
        val enabled = _servers.value.filter { it.enabled }
        for (config in enabled) {
            if (_toolsByServer.value[config.id] == null && _errors.value[config.id] == null) {
                listServer(config)
            }
        }
        return enabled.flatMap { config ->
            (_toolsByServer.value[config.id] ?: emptyList()).map { tool ->
                ToolSpec(
                    name = namespacedName(config, tool.name),
                    description = "[${config.name}] ${tool.description}".take(1024),
                    parametersJson = tool.inputSchema?.toString() ?: """{"type":"object","properties":{}}""",
                )
            }
        }
    }

    fun isMcpTool(name: String): Boolean = name.startsWith("mcp__")

    /** Consent identity + copy for a call to this namespaced tool. */
    fun consentInfoFor(namespacedName: String): Triple<String, String, String>? {
        val (serverId, toolName) = reverseMap[namespacedName] ?: return null
        val server = _servers.value.firstOrNull { it.id == serverId } ?: return null
        return Triple(
            "mcp:$serverId",
            "Allow tools from “${server.name}”?",
            "The assistant wants to call “$toolName” on the connected MCP server ${server.name} " +
                "(${server.url}). Allowing covers every tool on this server.",
        )
    }

    fun runningLabel(namespacedName: String): String {
        val (serverId, toolName) = reverseMap[namespacedName] ?: return "Calling a connected tool…"
        val server = _servers.value.firstOrNull { it.id == serverId }
        return "Calling $toolName on ${server?.name ?: "a connected server"}…"
    }

    fun doneLabel(namespacedName: String): String {
        val (serverId, toolName) = reverseMap[namespacedName] ?: return "Called a connected tool"
        val server = _servers.value.firstOrNull { it.id == serverId }
        return "Called $toolName on ${server?.name ?: "a connected server"}"
    }

    suspend fun call(namespacedName: String, argumentsJson: String): ToolResult {
        ensureLoaded()
        val (serverId, toolName) = reverseMap[namespacedName]
            ?: return ToolResult("unknown MCP tool: $namespacedName", isError = true)
        val config = _servers.value.firstOrNull { it.id == serverId && it.enabled }
            ?: return ToolResult("the MCP server for this tool is disabled or gone", isError = true)
        return try {
            clientFor(config).callTool(toolName, argumentsJson)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult("MCP call failed: ${e.message?.take(200) ?: e.javaClass.simpleName}", isError = true)
        }
    }

    // --- Internals ---

    private suspend fun listServer(config: McpServerConfig) {
        try {
            val tools = withTimeout(LIST_BUDGET_MS) {
                clientFor(config).listTools()
            }.take(MAX_TOOLS_PER_SERVER)
            persistTools(_toolsByServer.value + (config.id to tools))
            _errors.value = _errors.value - config.id
        } catch (e: kotlinx.coroutines.CancellationException) {
            // withTimeout cancellation is this server's failure, not the turn's.
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                _errors.value = _errors.value + (config.id to "timed out after ${LIST_BUDGET_MS / 1000}s")
            } else {
                throw e
            }
        } catch (e: Exception) {
            _errors.value = _errors.value + (config.id to (e.message?.take(200) ?: "connection failed"))
        }
    }

    private fun clientFor(config: McpServerConfig): McpClient =
        clients.getOrPut(config.id) { McpClient(config, http, json) }.also { it.config = config }

    private suspend fun persistServers(servers: List<McpServerConfig>) {
        _servers.value = servers
        rebuildReverseMap()
        prefs.saveMcpServersJson(json.encodeToString(servers))
    }

    private suspend fun persistTools(tools: Map<String, List<McpToolDef>>) {
        _toolsByServer.value = tools
        rebuildReverseMap()
        prefs.saveMcpToolsCacheJson(json.encodeToString(tools))
    }

    private fun rebuildReverseMap() {
        reverseMap.clear()
        for (config in _servers.value) {
            for (tool in _toolsByServer.value[config.id] ?: emptyList()) {
                reverseMap[namespacedName(config, tool.name)] = config.id to tool.name
            }
        }
    }

    /**
     * `mcp__<serverslug>__<tool>`, sanitized to the OpenAI function-name
     * charset and 64-char cap. The reverse map is the source of truth for
     * dispatch, so truncation collisions only cost a duplicate-name tool
     * being shadowed, never a mis-route.
     */
    private fun namespacedName(config: McpServerConfig, toolName: String): String {
        val slug = config.name.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { config.id.take(6) }
        val tool = toolName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "mcp__${slug}__$tool".take(64)
    }
}
