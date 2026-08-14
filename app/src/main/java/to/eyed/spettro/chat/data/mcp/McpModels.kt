package to.eyed.spettro.chat.data.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A user-configured remote MCP server (Streamable HTTP transport). */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    /** Sent as `Authorization: Bearer …` when non-blank. */
    val bearerToken: String = "",
    /** Optional extra header, e.g. an API-key header some servers use. */
    val headerName: String = "",
    val headerValue: String = "",
    val enabled: Boolean = true,
)

/** One tool as advertised by a server's tools/list. */
@Serializable
data class McpToolDef(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject? = null,
)

// --- JSON-RPC 2.0 wire types (the subset MCP needs) ---

@Serializable
internal data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val method: String,
    val params: JsonObject? = null,
)

@Serializable
internal data class JsonRpcError(val code: Int = 0, val message: String = "")

@Serializable
internal data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    /** Present on server-initiated messages, which this client ignores. */
    val method: String? = null,
)

class McpException(message: String) : Exception(message)
