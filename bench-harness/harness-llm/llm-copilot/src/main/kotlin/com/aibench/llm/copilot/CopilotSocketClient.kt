package com.aibench.llm.copilot

import com.aibench.llm.LlmClient
import com.aibench.llm.LlmException
import com.aibench.llm.LlmRequest
import com.aibench.llm.LlmResponse
import jnr.unixsocket.UnixSocketAddress
import jnr.unixsocket.UnixSocketChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.channels.Channels
import java.util.UUID

/**
 * Speaks JSON-line protocol to the companion VSCode extension (see
 * tooling/vscode-copilot-bridge/). Each request gets a unique id; the
 * extension forwards through vscode.lm and responds with the LLM output.
 */
class CopilotSocketClient(
    private val socketPath: String = System.getenv("AI_BENCH_COPILOT_SOCK")
        ?: "${System.getProperty("java.io.tmpdir")}/ai-bench-copilot.sock",
    private val defaultModel: String = "copilot"
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val file = File(socketPath)
        if (!file.exists()) {
            throw LlmException("Copilot bridge socket not found at $socketPath — is the VSCode extension running?")
        }
        val channel = UnixSocketChannel.open(UnixSocketAddress(file))
        val out = PrintWriter(Channels.newOutputStream(channel), true)
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel)))

        val requestId = UUID.randomUUID().toString()
        val wire = WireRequest(
            id = requestId,
            model = request.model.ifBlank { defaultModel },
            system = request.system,
            messages = request.messages.map { WireMessage(it.role.name.lowercase(), it.content) },
            temperature = request.temperature,
            maxTokens = request.maxTokens
        )
        val start = System.currentTimeMillis()
        out.println(json.encodeToString(WireRequest.serializer(), wire))
        val line = reader.readLine() ?: throw LlmException("Copilot bridge closed connection")
        val resp = json.decodeFromString(WireResponse.serializer(), line)
        if (resp.error != null) throw LlmException("Copilot bridge error: ${resp.error}")

        LlmResponse(
            content = resp.content.orEmpty(),
            modelIdentifier = resp.modelIdentifier ?: request.model,
            promptTokens = resp.promptTokens ?: 0,
            completionTokens = resp.completionTokens ?: 0,
            latencyMillis = System.currentTimeMillis() - start
        )
    }

    @Serializable
    private data class WireRequest(
        val id: String,
        val model: String,
        val system: String,
        val messages: List<WireMessage>,
        val temperature: Double,
        val maxTokens: Int
    )

    @Serializable
    private data class WireMessage(val role: String, val content: String)

    @Serializable
    private data class WireResponse(
        val id: String,
        val content: String? = null,
        val modelIdentifier: String? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val error: String? = null
    )
}
