package com.aibench.llm.copilot

import com.aibench.llm.LlmClient
import com.aibench.llm.LlmException
import com.aibench.llm.LlmRequest
import com.aibench.llm.LlmResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Speaks JSON-line protocol to the companion VSCode extension (see
 * tools/copilot-bridge-extension/). The extension binds a TCP listener on
 * 127.0.0.1 with an OS-assigned port and writes the port to a sidecar file
 * (`~/.ai-bench-copilot.port`). The client reads that file, opens a TCP
 * connection, and exchanges one JSON request/response per line.
 *
 * The bridge originally used AF_UNIX, but Node's libuv on some Windows
 * machines refuses AF_UNIX bind with EACCES regardless of path/ACLs, while
 * Java has no problem at the same paths. TCP localhost works for both
 * sides on every supported platform.
 */
class CopilotSocketClient(
    private val portFile: String = System.getenv("AI_BENCH_COPILOT_PORT_FILE")
        ?: "${System.getProperty("user.home")}${File.separator}.ai-bench-copilot.port",
    private val defaultModel: String = "copilot",
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 60_000
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val port = readPort() ?: throw LlmException(
            "Copilot bridge port file not found at $portFile — is the VSCode extension running?")

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs)
        } catch (e: Exception) {
            throw LlmException("Copilot bridge port file says $port but TCP connect failed: ${e.message}")
        }
        socket.soTimeout = readTimeoutMs

        socket.use { s ->
            val out = PrintWriter(s.getOutputStream(), true, Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

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
    }

    private fun readPort(): Int? {
        val f = File(portFile)
        if (!f.isFile) return null
        return f.readText(Charsets.UTF_8).trim().toIntOrNull()?.takeIf { it in 1..65535 }
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
