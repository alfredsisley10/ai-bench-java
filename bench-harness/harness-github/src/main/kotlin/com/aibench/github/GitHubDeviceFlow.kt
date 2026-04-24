package com.aibench.github

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OAuth2 device-flow client for corporate GitHub. Works with github.com and
 * GitHub Enterprise Server — set base URL accordingly.
 *
 * Flow:
 *   1. POST /login/device/code → { device_code, user_code, verification_uri, interval }
 *   2. User opens verification_uri in browser and enters user_code
 *   3. Poll /login/oauth/access_token until access_token arrives
 */
class GitHubDeviceFlow(
    private val clientId: String,
    private val baseUrl: String = "https://github.com",
    private val http: OkHttpClient = OkHttpClient()
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun startDeviceFlow(scopes: List<String> = listOf("repo", "read:org", "read:user")): DeviceCodeGrant {
        val req = Request.Builder()
            .url("$baseUrl/login/device/code")
            .post(FormBody.Builder()
                .add("client_id", clientId)
                .add("scope", scopes.joinToString(" "))
                .build())
            .addHeader("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: error("empty device-code response")
            return json.decodeFromString(DeviceCodeGrant.serializer(), body)
        }
    }

    fun pollForAccessToken(deviceCode: String): String {
        val req = Request.Builder()
            .url("$baseUrl/login/oauth/access_token")
            .post(FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build())
            .addHeader("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: error("empty token response")
            val parsed = json.decodeFromString(TokenResponse.serializer(), body)
            return parsed.accessToken ?: error("no access_token yet: ${parsed.error}")
        }
    }

    @Serializable
    data class DeviceCodeGrant(
        @kotlinx.serialization.SerialName("device_code") val deviceCode: String,
        @kotlinx.serialization.SerialName("user_code") val userCode: String,
        @kotlinx.serialization.SerialName("verification_uri") val verificationUri: String,
        val interval: Int = 5,
        @kotlinx.serialization.SerialName("expires_in") val expiresIn: Int
    )

    @Serializable
    data class TokenResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String? = null,
        val error: String? = null,
        @kotlinx.serialization.SerialName("error_description") val errorDescription: String? = null
    )
}
