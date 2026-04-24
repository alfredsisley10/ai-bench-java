package com.aibench.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class VaultSecretSource(private val cfg: BenchConfig.VaultSection) : SecretSource {

    private val log = LoggerFactory.getLogger(VaultSecretSource::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, String>()
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile
    private var token: String? = null

    override fun resolve(ref: String): String? {
        cache[ref]?.let { return it }
        return runCatching {
            ensureAuthenticated()
            val secrets = readSecret(cfg.secretPath)
            secrets?.forEach { (k, v) -> cache[k] = v }
            secrets?.get(ref)
        }.getOrElse {
            log.error("Vault lookup failed for {}: {}", ref, it.message)
            null
        }
    }

    private fun ensureAuthenticated() {
        if (token != null) return
        token = when (cfg.authMethod) {
            "kubernetes" -> authenticateKubernetes()
            "token" -> System.getenv("VAULT_TOKEN")
            else -> throw IllegalStateException("Unsupported Vault auth method: ${cfg.authMethod}")
        }
    }

    private fun authenticateKubernetes(): String {
        val jwt = Files.readString(
            Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/token")
        ).trim()
        val body = """{"jwt":"$jwt","role":"${cfg.role}"}"""
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${cfg.address}/v1/auth/kubernetes/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("Vault k8s auth failed: ${response.statusCode()}")
        }
        val parsed = json.decodeFromString<VaultAuthResponse>(response.body())
        return parsed.auth.clientToken
    }

    private fun readSecret(path: String): Map<String, String>? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${cfg.address}/v1/$path"))
            .header("X-Vault-Token", token ?: throw IllegalStateException("Not authenticated"))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            log.warn("Vault read failed for {}: status={}", path, response.statusCode())
            if (response.statusCode() == 403) {
                token = null
            }
            return null
        }
        val parsed = json.decodeFromString<VaultReadResponse>(response.body())
        return parsed.data.data
    }

    @Serializable
    internal data class VaultAuthResponse(val auth: AuthBlock) {
        @Serializable
        data class AuthBlock(val clientToken: String = "", val client_token: String = "") {
            val effectiveToken: String get() = clientToken.ifEmpty { client_token }
        }

        // Vault returns client_token in the JSON
        val resolvedToken: String get() = auth.client_token.ifEmpty { auth.clientToken }
    }

    @Serializable
    internal data class VaultReadResponse(val data: DataBlock) {
        @Serializable
        data class DataBlock(val data: Map<String, String> = emptyMap())
    }
}
