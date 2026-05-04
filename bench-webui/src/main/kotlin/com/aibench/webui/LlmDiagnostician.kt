package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Shared bridge call for "Diagnose X with LLM" features. Originally
 * inlined inside DemoController.appDiagnoseWithLlm; extracted here so
 * the same logic can power per-feature diagnose buttons (banking-app
 * startup, AppMap trace recording, future ones) without each
 * controller re-implementing the bridge POST + response parsing +
 * model fallback logic.
 *
 * The diagnostician is intentionally model-agnostic: caller passes
 * the registry id ("copilot-default", "copilot-gpt-4-1", etc.) and
 * the diagnostician resolves to the underlying vendor identifier the
 * bridge expects. When the bridge isn't reachable the call returns a
 * structured DiagnoseResult.bridgeUnreachable() so the caller can
 * surface a friendly UI hint without throwing.
 */
@Component
class LlmDiagnostician(
    private val registeredModels: RegisteredModelsRegistry
) {

    /** Compact list of models suitable for "Diagnose with LLM" UI
     *  pickers. Drops appmap-navie (which is a context-search loop,
     *  not a chat completion), and any entry without a vendor id
     *  (which the bridge wouldn't know how to route). */
    fun availableForDiagnose(session: jakarta.servlet.http.HttpSession):
        List<LlmConfigController.ModelInfo> =
        registeredModels.availableModels(session)
            .filter { it.provider != "appmap-navie" }
            .filter { it.modelIdentifier.isNotBlank() }
    private val log = LoggerFactory.getLogger(LlmDiagnostician::class.java)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    data class DiagnoseResult(
        val ok: Boolean,
        val analysis: String?,
        val durationMs: Long,
        val modelUsed: String?,
        val reason: String?
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "ok" to ok,
            "analysis" to analysis,
            "durationMs" to durationMs,
            "model" to modelUsed,
            "reason" to reason
        )

        companion object {
            fun bridgeUnreachable() = DiagnoseResult(
                ok = false, analysis = null, durationMs = 0, modelUsed = null,
                reason = "Copilot bridge not reachable at 127.0.0.1:11434. " +
                    "Start the bridge via the Copilot Bridge VSCode extension on /llm first."
            )
            fun llmFailure(reason: String) = DiagnoseResult(
                ok = false, analysis = null, durationMs = 0, modelUsed = null,
                reason = reason
            )
        }
    }

    /**
     * Resolve a registry id (e.g. "copilot-gpt-4-1") to the
     * vendor-side identifier the bridge expects (e.g. "gpt-4.1").
     * Defaults to "copilot" when the id can't be resolved -- the
     * bridge then falls back to the operator's Copilot default.
     */
    private fun resolveVendorId(registryId: String, session: jakarta.servlet.http.HttpSession): String {
        if (registryId.isBlank() || registryId == "copilot-default" || registryId == "copilot") {
            return "copilot"
        }
        val available = registeredModels.availableModels(session)
        return available.firstOrNull { it.id == registryId }?.modelIdentifier ?: "copilot"
    }

    /** Probe the local bridge port; matches the test elsewhere in
     *  bench-webui for "is the bridge live?". */
    fun bridgeReachable(): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", 11434), 500); true
        }
    }.getOrDefault(false)

    /**
     * Run a diagnose chat completion against the chosen model.
     * Bounded ~45s timeout. Returns analysis text in result.analysis
     * on success.
     */
    fun diagnose(
        registryId: String,
        systemPrompt: String,
        userPrompt: String,
        session: jakarta.servlet.http.HttpSession,
        timeoutSec: Long = 45
    ): DiagnoseResult {
        if (!bridgeReachable()) return DiagnoseResult.bridgeUnreachable()
        val vendorId = resolveVendorId(registryId, session)
        val started = System.currentTimeMillis()
        return try {
            val body = """{"model":${jsonString(vendorId)},"temperature":0.1,"messages":[""" +
                """{"role":"system","content":${jsonString(systemPrompt)}},""" +
                """{"role":"user","content":${jsonString(userPrompt)}}""" +
                """]}"""
            val req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:11434/v1/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            val ms = System.currentTimeMillis() - started
            if (resp.statusCode() !in 200..299) {
                return DiagnoseResult.llmFailure(
                    "LLM bridge returned HTTP ${resp.statusCode()}. " +
                    "Body (first 200 chars): ${resp.body().take(200)}"
                )
            }
            // Stable enough to regex out -- the OpenAI shim's response shape
            // hasn't changed and pulling kotlinx.serialization for one
            // field is overkill.
            val content = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(resp.body())?.groupValues?.get(1)
                ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                ?: "(empty response)"
            DiagnoseResult(ok = true, analysis = content, durationMs = ms,
                modelUsed = vendorId, reason = null)
        } catch (e: Exception) {
            log.warn("LLM diagnose call failed: {}", e.message)
            DiagnoseResult.llmFailure("LLM call failed: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    /** JSON-string-escape a value for inline use. Same routine the
     *  caller-inlined version had; centralised so future diagnose
     *  endpoints stay consistent on edge cases (CR/LF, control chars). */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
