package com.aibench.llm

import kotlinx.serialization.Serializable

@Serializable
data class ModelDefinition(
    val id: String,
    val displayName: String,
    val provider: String,
    val modelIdentifier: String,
    val costPer1kPromptTokens: Double = 0.0,
    val costPer1kCompletionTokens: Double = 0.0
) {
    fun estimateCost(promptTokens: Int, completionTokens: Int): Double =
        (promptTokens / 1000.0) * costPer1kPromptTokens +
        (completionTokens / 1000.0) * costPer1kCompletionTokens
}

object ModelRegistry {

    private val models = mutableMapOf<String, ModelDefinition>()

    fun register(model: ModelDefinition) {
        models[model.id] = model
    }

    fun registerAll(defs: List<ModelDefinition>) {
        defs.forEach { register(it) }
    }

    fun get(id: String): ModelDefinition? = models[id]

    fun all(): List<ModelDefinition> = models.values.toList()

    fun forProvider(provider: String): List<ModelDefinition> =
        models.values.filter { it.provider.equals(provider, ignoreCase = true) }

    fun clientFor(modelId: String): LlmClient {
        val def = models[modelId]
            ?: throw LlmException("Unknown model: $modelId. Available: ${models.keys.joinToString()}")
        return LlmClientFactory.forSolver(def.provider)
    }

    fun clear() = models.clear()
}
