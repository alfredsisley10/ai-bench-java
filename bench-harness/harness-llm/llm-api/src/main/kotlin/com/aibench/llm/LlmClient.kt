package com.aibench.llm

import kotlinx.serialization.Serializable

/**
 * Unified LLM client contract across providers. Every adapter (Copilot,
 * Corporate OpenAI, future Claude direct) implements this.
 */
interface LlmClient {

    suspend fun complete(request: LlmRequest): LlmResponse
}

@Serializable
data class LlmRequest(
    val model: String,
    val system: String,
    val messages: List<Message>,
    val temperature: Double = 0.0,
    val maxTokens: Int = 4096,
    val seed: Long? = null,
    val attachments: List<Attachment> = emptyList(),
    /**
     * Optional caller-supplied correlation id. Adapters that talk to
     * the Copilot bridge propagate this on every request so the bridge
     * can group records under a BenchmarkRun, and so the harness can
     * later query the bridge for authoritative token totals scoped to
     * one run (rather than relying on per-call response counts).
     * Empty / null = uncorrelated; bridge stores the record without a
     * runId field.
     */
    val runId: String? = null
) {
    @Serializable
    data class Message(val role: Role, val content: String)

    @Serializable
    enum class Role { USER, ASSISTANT, SYSTEM }

    @Serializable
    data class Attachment(val name: String, val content: String, val mimeType: String)
}

@Serializable
data class LlmResponse(
    val content: String,
    val modelIdentifier: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMillis: Long
)

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
