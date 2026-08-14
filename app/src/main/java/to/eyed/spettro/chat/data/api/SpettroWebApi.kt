package to.eyed.spettro.chat.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client for the spettro.app website API. These routes are authenticated with a
 * Clerk session JWT (from the native Clerk SDK) sent as a Bearer token:
 *  - POST /api/sync-user     — upsert the user row + free subscription after sign-in
 *  - POST /api/keys/generate — mint an ep_ API key (returned exactly once)
 *  - POST /api/keys/revoke   — delete a key by id
 */
class SpettroWebApi(private val baseUrl: String = DEFAULT_BASE_URL) {
    companion object {
        const val DEFAULT_BASE_URL = "https://spettro.app"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun request(path: String, sessionToken: String, body: String): Request =
        Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $sessionToken")
            .header("User-Agent", SpettroApi.USER_AGENT)
            .post(body.toRequestBody(jsonMedia))
            .build()

    /** Idempotent: creates the users row and a free subscription on first login. */
    suspend fun syncUser(sessionToken: String) {
        client.newCall(request("/api/sync-user", sessionToken, "{}")).await().use { resp ->
            if (resp.code == 401) throw UnauthorizedException()
            if (!resp.isSuccessful) throw ApiException(resp.code, "account sync failed (HTTP ${resp.code})")
        }
    }

    /** The raw ep_ key is returned exactly once; only its hash is stored server-side. */
    suspend fun generateApiKey(sessionToken: String, label: String): ApiKeyGrant {
        val body = buildJsonObject { put("label", label) }.toString()
        client.newCall(request("/api/keys/generate", sessionToken, body)).await().use { resp ->
            if (resp.code == 401) throw UnauthorizedException()
            if (!resp.isSuccessful) throw ApiException(resp.code, "could not create an API key (HTTP ${resp.code})")
            val grant = json.decodeFromString(ApiKeyGrant.serializer(), resp.body.string())
            if (grant.key.isBlank()) throw ApiException(resp.code, "server returned no API key")
            return grant
        }
    }

    suspend fun revokeApiKey(sessionToken: String, keyId: String) {
        val body = buildJsonObject { put("id", keyId) }.toString()
        client.newCall(request("/api/keys/revoke", sessionToken, body)).await().use { resp ->
            if (resp.code == 401) throw UnauthorizedException()
            if (!resp.isSuccessful) throw ApiException(resp.code, "could not revoke the API key (HTTP ${resp.code})")
        }
    }
}
