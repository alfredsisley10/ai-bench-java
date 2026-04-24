package com.aibench.llm.corp

import com.aibench.llm.LlmClient
import com.aibench.llm.LlmException
import com.aibench.llm.LlmRequest
import com.aibench.llm.LlmResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * OpenAI-spec chat-completions client for corporate gateways that front the
 * model behind Apigee. Per-request:
 *
 *   - Bearer token from [ApigeeTokenProvider] (cached, refreshed as needed)
 *   - Configurable custom headers (X-Correlation-Id auto-generated per call;
 *     others come from YAML config)
 *   - Optional HTTPS proxy + corporate truststore (never mutates global JVM
 *     truststore)
 */
class CorpOpenAiClient(
    private val cfg: CorpOpenAiConfig,
    httpFactory: () -> OkHttpClient = { buildHttpClient(cfg) },
    private val tokenProvider: ApigeeTokenProvider = ApigeeTokenProvider(cfg.apigee, httpFactory())
) : LlmClient {

    private val http = httpFactory()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val body = buildBody(request)
        val resp = send(body, tokenProvider.current())

        LlmResponse(
            content = resp.choices.firstOrNull()?.message?.content.orEmpty(),
            modelIdentifier = resp.model ?: request.model,
            promptTokens = resp.usage?.promptTokens ?: 0,
            completionTokens = resp.usage?.completionTokens ?: 0,
            latencyMillis = System.currentTimeMillis() - start
        )
    }

    private fun send(body: OpenAiChatRequest, bearer: String, retry: Boolean = true): OpenAiChatResponse {
        val req = Request.Builder()
            .url("${cfg.baseUrl.trimEnd('/')}/chat/completions")
            .post(json.encodeToString(OpenAiChatRequest.serializer(), body).toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer $bearer")
            .addHeader("Accept", "application/json")
            .addHeader("X-Correlation-Id", UUID.randomUUID().toString())
            .apply { cfg.headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        http.newCall(req).execute().use { httpResp ->
            if (httpResp.code == 401 && retry) {
                tokenProvider.invalidate()
                return send(body, tokenProvider.current(), retry = false)
            }
            val s = httpResp.body?.string() ?: throw LlmException("empty corp OpenAI response")
            if (!httpResp.isSuccessful) throw LlmException("corp OpenAI ${httpResp.code}: $s")
            return json.decodeFromString(OpenAiChatResponse.serializer(), s)
        }
    }

    private fun buildBody(req: LlmRequest): OpenAiChatRequest {
        val messages = mutableListOf<WireMessage>()
        if (req.system.isNotBlank()) messages += WireMessage("system", req.system)
        req.messages.forEach { m -> messages += WireMessage(m.role.name.lowercase(), m.content) }
        return OpenAiChatRequest(
            model = req.model.ifBlank { cfg.defaultModel },
            messages = messages,
            temperature = req.temperature,
            maxTokens = req.maxTokens,
            seed = req.seed
        )
    }

    @Serializable
    private data class OpenAiChatRequest(
        val model: String,
        val messages: List<WireMessage>,
        val temperature: Double,
        @kotlinx.serialization.SerialName("max_tokens") val maxTokens: Int,
        val seed: Long? = null
    )

    @Serializable
    private data class WireMessage(val role: String, val content: String)

    @Serializable
    private data class OpenAiChatResponse(
        val id: String? = null,
        val model: String? = null,
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null
    )

    @Serializable
    private data class Choice(val message: WireMessage? = null, @kotlinx.serialization.SerialName("finish_reason") val finishReason: String? = null)

    @Serializable
    private data class Usage(
        @kotlinx.serialization.SerialName("prompt_tokens") val promptTokens: Int = 0,
        @kotlinx.serialization.SerialName("completion_tokens") val completionTokens: Int = 0,
        @kotlinx.serialization.SerialName("total_tokens") val totalTokens: Int = 0
    )

    companion object {
        fun buildHttpClient(cfg: CorpOpenAiConfig): OkHttpClient {
            val builder = OkHttpClient.Builder()

            cfg.proxy?.urlEnv?.let { envVar ->
                System.getenv(envVar)?.let { url ->
                    val stripped = url.removePrefix("http://").removePrefix("https://")
                    val (host, port) = stripped.split(":")
                    builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port.toInt())))
                }
            }

            cfg.proxy?.truststore?.let { tsPath ->
                val password = cfg.proxy.truststorePasswordEnv?.let(System::getenv)?.toCharArray()
                    ?: CharArray(0)
                val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
                FileInputStream(tsPath).use { trustStore.load(it, password) }
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(trustStore)
                val trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(trustManager), null)
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            }

            return builder.build()
        }
    }
}
