package com.aibench.llm.corp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * Acquires + caches a bearer token from an Apigee OAuth2 endpoint.
 * client_credentials grant. Token is cached until 60s before stated expiry.
 */
class ApigeeTokenProvider(
    private val cfg: CorpOpenAiConfig.ApigeeConfig,
    private val http: OkHttpClient
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val cached = AtomicReference<CachedToken?>()

    fun current(): String {
        cached.get()?.let { if (it.expiresAtEpochMs > System.currentTimeMillis()) return it.token }
        return refresh()
    }

    fun invalidate() { cached.set(null) }

    private fun refresh(): String {
        val clientId = System.getenv(cfg.clientIdEnv)
            ?: error("env var ${cfg.clientIdEnv} is not set — can't refresh Apigee token")
        val clientSecret = System.getenv(cfg.clientSecretEnv)
            ?: error("env var ${cfg.clientSecretEnv} is not set — can't refresh Apigee token")

        val req = Request.Builder()
            .url(cfg.tokenUrl)
            .post(FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("scope", cfg.scope)
                .build())
            .addHeader("Accept", "application/json")
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: error("empty Apigee token response")
            if (!resp.isSuccessful) error("Apigee token fetch failed: ${resp.code} $body")
            val parsed = json.decodeFromString(TokenResponse.serializer(), body)
            val expiresAt = System.currentTimeMillis() + ((parsed.expiresIn - 60).coerceAtLeast(0) * 1000L)
            cached.set(CachedToken(parsed.accessToken, expiresAt))
            return parsed.accessToken
        }
    }

    @Serializable
    private data class TokenResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String,
        @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long,
        @kotlinx.serialization.SerialName("token_type") val tokenType: String = "Bearer"
    )

    private data class CachedToken(val token: String, val expiresAtEpochMs: Long)
}
