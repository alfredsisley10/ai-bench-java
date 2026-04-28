package com.aibench.webui

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Process-wide store of manually-registered LLM models, persisted to
 * {@code ~/.ai-bench/registered-models.json}. Replaces the session-
 * scoped {@code session.getAttribute("llmModels")} list so registered
 * models survive bench-webui restarts.
 *
 * <p>Auto-discovered entries (the synthetic copilot-default,
 * corp-openai-default, and Navie rows assembled by
 * RegisteredModelsRegistry) are NOT persisted here -- they're
 * reconstructed on every request from the live bridge / corp-yaml /
 * AppMap CLI probes. Only entries the operator typed in via the
 * Add Model form (or the Copilot wizard's per-model Register button)
 * land in this file.
 *
 * <p>Single-user assumption: bench-webui ships as a developer tool
 * running on the operator's machine, so a process-wide store is
 * functionally equivalent to a per-user store. If we ever need
 * multi-user mode, this becomes per-principal -- but the call sites
 * stay the same; only the load/save path changes.
 */
@Component
class RegisteredModelsStore {

    private val log = LoggerFactory.getLogger(RegisteredModelsStore::class.java)
    // Plain ObjectMapper rather than jacksonObjectMapper() so we don't
    // pull in jackson-module-kotlin as a new dependency. We serialise
    // by hand via Map<String,Any> below, which sidesteps Kotlin data-
    // class reflection entirely and keeps the JSON shape stable when
    // ModelInfo grows new fields.
    private val mapper: ObjectMapper = ObjectMapper()

    private val storeFile: File = File(
        System.getProperty("user.home"),
        ".ai-bench/registered-models.json"
    )

    private val lock = Any()
    private val models: MutableList<LlmConfigController.ModelInfo> = mutableListOf()

    init {
        load()
    }

    /** Snapshot of every persisted model. Returns a defensive copy so
     *  callers can iterate without holding the lock. */
    fun all(): List<LlmConfigController.ModelInfo> = synchronized(lock) {
        models.toList()
    }

    /** True iff a model with this id exists in the store. */
    fun contains(id: String): Boolean = synchronized(lock) {
        models.any { it.id == id }
    }

    fun findById(id: String): LlmConfigController.ModelInfo? = synchronized(lock) {
        models.firstOrNull { it.id == id }
    }

    /**
     * Add a model. Returns false (and writes nothing to disk) if a
     * model with the same id already exists -- callers should call
     * {@link #upsertById} instead when the form path is "register or
     * update."
     */
    fun add(m: LlmConfigController.ModelInfo): Boolean = synchronized(lock) {
        if (models.any { it.id == m.id }) return false
        models.add(m)
        save()
        true
    }

    /**
     * Replace the entry with the given id by applying the supplied
     * mutation lambda. Returns true if a matching entry was found
     * and updated, false otherwise. Persists on success.
     */
    fun upsertById(id: String,
                   mutate: (LlmConfigController.ModelInfo) -> LlmConfigController.ModelInfo): Boolean = synchronized(lock) {
        val idx = models.indexOfFirst { it.id == id }
        if (idx < 0) return false
        models[idx] = mutate(models[idx])
        save()
        true
    }

    fun removeById(id: String): Boolean = synchronized(lock) {
        val removed = models.removeAll { it.id == id }
        if (removed) save()
        removed
    }

    /** Persist the current list. Atomic write: tmp file + rename. */
    private fun save() {
        try {
            val parent = storeFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            val asMaps = models.map { m ->
                mapOf(
                    "id" to m.id,
                    "displayName" to m.displayName,
                    "provider" to m.provider,
                    "modelIdentifier" to m.modelIdentifier,
                    "status" to m.status,
                    "costPer1kPrompt" to m.costPer1kPrompt,
                    "costPer1kCompletion" to m.costPer1kCompletion,
                    "editable" to m.editable
                )
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, asMaps)
            // Atomic rename so a crashed write never leaves a half-
            // written JSON on disk; callers' next load() either sees
            // the previous good state or the new one, never garbage.
            Files.move(
                tmp.toPath(), storeFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            log.warn("Failed to persist registered models to {}: {}",
                storeFile.absolutePath, e.message)
        }
    }

    /** Hydrate from disk. Missing or corrupt file → empty list, logged. */
    private fun load() {
        if (!storeFile.isFile) return
        try {
            @Suppress("UNCHECKED_CAST")
            val raw = mapper.readValue(storeFile, List::class.java) as List<Map<String, Any?>>
            val parsed = raw.mapNotNull { m ->
                val id = m["id"] as? String ?: return@mapNotNull null
                LlmConfigController.ModelInfo(
                    id = id,
                    displayName = m["displayName"] as? String ?: id,
                    provider = m["provider"] as? String ?: "",
                    modelIdentifier = m["modelIdentifier"] as? String ?: "",
                    status = m["status"] as? String ?: "configured",
                    costPer1kPrompt = (m["costPer1kPrompt"] as? Number)?.toDouble() ?: 0.0,
                    costPer1kCompletion = (m["costPer1kCompletion"] as? Number)?.toDouble() ?: 0.0,
                    editable = m["editable"] as? Boolean ?: true
                )
            }
            synchronized(lock) {
                models.clear()
                models.addAll(parsed)
            }
            log.info("Loaded {} registered model(s) from {}",
                parsed.size, storeFile.absolutePath)
        } catch (e: Exception) {
            log.warn("Failed to read {}: {}. Starting with an empty list. " +
                "Move the file aside and re-register if the corruption persists.",
                storeFile.absolutePath, e.message)
        }
    }
}
