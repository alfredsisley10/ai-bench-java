package com.aibench.config

import kotlinx.serialization.SerialName
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

    override val scheme: String = "vault"

    private val log = LoggerFactory.getLogger(VaultSecretSource::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, String>()
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile
    private var token: String? = null
    private val tokenLock = Any()

    override fun resolve(key: String): String? {
        cache[key]?.let { return it }
        return runCatching {
            ensureAuthenticated()
            val secrets = readSecret(cfg.secretPath) ?: return@runCatching null
            cache.putAll(secrets)
            secrets[key]
        }.getOrElse {
            log.error("Vault lookup failed for {}: {}", key, it.message)
            null
        }
    }

    private fun ensureAuthenticated() {
        if (token != null) return
        synchronized(tokenLock) {
            if (token != null) return
            token = when (cfg.authMethod) {
                "kubernetes" -> authenticateKubernetes()
                "token" -> System.getenv("VAULT_TOKEN")
                    ?: throw IllegalStateException("VAULT_TOKEN env var is not set")
                else -> throw IllegalStateException("Unsupported Vault auth method: ${cfg.authMethod}")
            }
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
        return json.decodeFromString<VaultAuthResponse>(response.body()).auth.clientToken
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
            if (response.statusCode() == 403) token = null
            return null
        }
        return json.decodeFromString<VaultReadResponse>(response.body()).data.data
    }

    @Serializable
    internal data class VaultAuthResponse(val auth: AuthBlock) {
        @Serializable
        data class AuthBlock(@SerialName("client_token") val clientToken: String)
    }

    @Serializable
    internal data class VaultReadResponse(val data: DataBlock) {
        @Serializable
        data class DataBlock(val data: Map<String, String> = emptyMap())
    }
}
