package to.eyed.spettro.chat.data.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Lenient readers for the JSON-encoded argument strings tool calls carry. */
internal object ToolArgs {
    private val json = Json { ignoreUnknownKeys = true }

    fun obj(argumentsJson: String): JsonObject? =
        runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()

    fun string(argumentsJson: String, key: String): String? =
        (obj(argumentsJson)?.get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    fun int(argumentsJson: String, key: String): Int? =
        (obj(argumentsJson)?.get(key) as? JsonPrimitive)?.intOrNull
}
