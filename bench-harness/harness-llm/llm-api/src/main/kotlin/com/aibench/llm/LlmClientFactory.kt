package com.aibench.llm

import java.util.ServiceLoader

/**
 * Loads all registered [LlmClientProvider]s via java.util.ServiceLoader and
 * picks the one matching the solver name. Adapters live in separate modules
 * and self-register via META-INF/services.
 */
object LlmClientFactory {

    fun forSolver(name: String): LlmClient {
        val providers = ServiceLoader.load(LlmClientProvider::class.java).toList()
        val match = providers.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw LlmException("No LLM adapter registered for solver: $name. " +
                    "Available: ${providers.joinToString(", ") { it.name }}")
        return match.build()
    }
}

interface LlmClientProvider {
    val name: String
    fun build(): LlmClient
}
