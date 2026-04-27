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
                maxTokens = request.maxTokens,
                // Propagate caller-supplied correlation id. The bridge
                // attaches this to every recorded usage row so the
                // VSIX activity panel can filter to one BenchmarkRun
                // and so the harness's queryRunUsage() helper can pull
                // authoritative totals scoped to that run.
                runId = request.runId
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

    /**
     * Per-run, per-model token totals authoritatively recorded by the
     * Copilot bridge. Returned by {@link #queryRunUsage}.
     */
    @Serializable
    data class RunUsage(
        val runId: String,
        val requests: Int,
        val promptTokens: Int,
        val completionTokens: Int,
        val estimatedCostUsd: Double,
        val firstSeenIso: String? = null,
        val lastSeenIso: String? = null,
        val perModel: List<ModelTotals> = emptyList()
    ) {
        @Serializable
        data class ModelTotals(
            val modelId: String,
            val label: String? = null,
            val requests: Int,
            val promptTokens: Int,
            val completionTokens: Int,
            val estimatedCostUsd: Double
        )
    }

    /**
     * Query the bridge for the AUTHORITATIVE usage totals scoped to
     * one BenchmarkRun. The harness should call this AFTER its run
     * completes -- not during -- to capture the final tally with no
     * race against in-flight chat calls.
     *
     * Returns null when no records are tagged with the given runId
     * (the run never made any tagged bridge calls, or the bridge
     * already rotated the records out of its 1000-entry ring).
     */
    suspend fun queryRunUsage(runId: String): RunUsage? = withContext(Dispatchers.IO) {
        val port = readPort() ?: throw LlmException(
            "Copilot bridge port file not found at $portFile -- is the VSCode extension running?")
        val socket = Socket()
        try { socket.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs) }
        catch (e: Exception) {
            throw LlmException("Copilot bridge port file says $port but TCP connect failed: ${e.message}")
        }
        socket.soTimeout = readTimeoutMs
        socket.use { s ->
            val out = PrintWriter(s.getOutputStream(), true, Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            out.println(json.encodeToString(QueryRequest.serializer(), QueryRequest(runId = runId)))
            val line = reader.readLine()
                ?: throw LlmException("Copilot bridge closed connection on query-activity")
            val resp = json.decodeFromString(QueryResponse.serializer(), line)
            if (!resp.ok) throw LlmException("Copilot bridge query-activity error: ${resp.error}")
            resp.snapshot
        }
    }

    /**
     * List of distinct runIds the bridge currently has records for --
     * useful for the harness to enumerate which runs it can query.
     */
    suspend fun listKnownRunIds(): List<String> = withContext(Dispatchers.IO) {
        val port = readPort() ?: return@withContext emptyList()
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs)
        socket.soTimeout = readTimeoutMs
        socket.use { s ->
            val out = PrintWriter(s.getOutputStream(), true, Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            out.println(json.encodeToString(QueryRequest.serializer(), QueryRequest()))
            val line = reader.readLine() ?: return@withContext emptyList<String>()
            val resp = json.decodeFromString(QueryResponse.serializer(), line)
            resp.runIds ?: emptyList()
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
        val maxTokens: Int,
        val runId: String? = null
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

    @Serializable
    private data class QueryRequest(
        val op: String = "query-activity",
        val runId: String? = null
    )

    @Serializable
    private data class QueryResponse(
        val ok: Boolean = false,
        val snapshot: RunUsage? = null,
        val runIds: List<String>? = null,
        val error: String? = null
    )
}
