package com.aibench.webui

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * Editable mirror of the bridge's model-pricing table. Persisted at
 * ~/.ai-bench/pricing.json so an operator's manual override survives
 * bench-webui restarts and can be pushed back to the VSIX (which
 * persists its own copy in globalState) via syncToBridge().
 *
 * The webui doesn't bake-in its own defaults: on first run it pulls
 * from /v1/pricing on the bridge. If the bridge is offline, the
 * store stays empty and the cost-aware features that depend on it
 * (cost-optimized launcher, leaderboard cost columns) fall back to
 * "unknown cost" rather than guessing.
 */
@Component
class ModelPricingStore {
    private val log = LoggerFactory.getLogger(ModelPricingStore::class.java)
    private val mapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(800))
        .build()
    private val storeFile: File = File(System.getProperty("user.home"), ".ai-bench/pricing.json")

    /** Cached in-memory view of the on-disk store. Reloaded lazily
     *  by callers that mutate it; reads go through state(). */
    @Volatile private var loaded: PricingStore? = null

    /** Wire-format entry, mirrors the VSIX's ModelPriceWire. Ordered
     *  -- the VSIX's pattern matcher checks entries top-to-bottom,
     *  so more-specific patterns must come before less-specific
     *  ones (`^gpt-4` before `^gpt`). The admin editor exposes
     *  drag-to-reorder for that reason. */
    data class Entry @JsonCreator constructor(
        @JsonProperty("pattern") val pattern: String,
        @JsonProperty("flags") val flags: String,
        @JsonProperty("promptPer1k") val promptPer1k: Double,
        @JsonProperty("completionPer1k") val completionPer1k: Double,
        @JsonProperty("label") val label: String
    )

    data class PricingStore @JsonCreator constructor(
        @JsonProperty("entries") val entries: List<Entry>,
        @JsonProperty("lastUpdatedIso") val lastUpdatedIso: String,
        /** Where the current entries originated -- "bridge-defaults"
         *  on first pull, "operator-edit" after the admin page saves,
         *  "synced-from-bridge" after a manual pull-from-bridge. */
        @JsonProperty("source") val source: String
    )

    /** Mirror of /v1/pricing's response shape, for the bridge fetch. */
    data class BridgeSnapshot @JsonCreator constructor(
        @JsonProperty("entries") val entries: List<Entry>,
        @JsonProperty("lastUpdatedIso") val lastUpdatedIso: String,
        @JsonProperty("isOverride") val isOverride: Boolean,
        @JsonProperty("defaultEntryCount") val defaultEntryCount: Int
    )

    /** Current state. Empty store + null if nothing has ever loaded. */
    fun state(): PricingStore? {
        loaded?.let { return it }
        if (storeFile.isFile) {
            return runCatching { mapper.readValue(storeFile, PricingStore::class.java) }
                .onFailure { log.warn("pricing.json parse failed: {}", it.message) }
                .getOrNull()
                ?.also { loaded = it }
        }
        return null
    }

    /** First-run initialization: pull from bridge and persist locally
     *  so subsequent webui-only operations don't need the bridge. */
    fun pullFromBridge(): PricingStore {
        val snap = fetchFromBridge()
        val store = PricingStore(snap.entries, snap.lastUpdatedIso, "synced-from-bridge")
        write(store)
        return store
    }

    /** Push the local store to the bridge so the VSIX activity panel
     *  shows the same per-token costs the harness uses. */
    fun pushToBridge(): Boolean {
        val s = state() ?: return false
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:11434/v1/pricing"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(mapOf(
                    "entries" to s.entries,
                    "lastUpdatedIso" to s.lastUpdatedIso
                )), StandardCharsets.UTF_8))
            .build()
        return runCatching {
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("bridge POST /v1/pricing returned {}: {}", resp.statusCode(), resp.body().take(200))
                false
            } else true
        }.getOrElse { e ->
            log.warn("pushToBridge failed: {}", e.message)
            false
        }
    }

    /** Replace the local store (operator edit). Bumps lastUpdated. */
    fun replace(entries: List<Entry>, source: String = "operator-edit"): PricingStore {
        val store = PricingStore(entries, Instant.now().toString(), source)
        write(store)
        return store
    }

    /** Wipe the local store -- next state() returns null and the
     *  admin page will re-prompt the operator to pull from bridge. */
    fun clearLocal() {
        loaded = null
        storeFile.delete()
    }

    /**
     * Patterns the bench-webui knows publicly-correct rates for as of
     * the build's [ModelPriceCatalog.lastUpdated] date. Surfaced via
     * the admin page's "Seed missing patterns" action: any pattern in
     * BUILT_IN_PATTERNS whose `pattern` literal does not already exist
     * in the local store gets prepended (so it wins over generic
     * fallback patterns like `^gpt-5(\.|$)` matching against
     * `gpt-5-mini`).
     *
     * Existing operator edits are NEVER overwritten -- this only ADDS
     * missing entries. Use [clearLocal] + bridge pull to reset hard.
     *
     * Patterns ordered most-specific to least-specific because [priceFor]
     * walks them top-down. The user's original pricing.json may have
     * these in a different order; the seed inserts at the front of the
     * existing list to take precedence.
     */
    val BUILT_IN_PATTERNS: List<Entry> = listOf(
        // Anthropic Claude 4 family (claude-opus-4-6 etc.)
        Entry("^claude-opus-4-6", "i", 0.018, 0.090, "Claude Opus 4.6"),
        Entry("^claude-opus-4-5", "i", 0.015, 0.075, "Claude Opus 4.5"),
        Entry("^claude-opus-4(?!-)", "i", 0.015, 0.075, "Claude Opus 4"),
        Entry("^claude-sonnet-4-6", "i", 0.003, 0.015, "Claude Sonnet 4.6"),
        Entry("^claude-sonnet-4-5", "i", 0.003, 0.015, "Claude Sonnet 4.5"),
        Entry("^claude-sonnet-4(?!-)", "i", 0.003, 0.015, "Claude Sonnet 4"),
        Entry("^claude-haiku-4-5", "i", 0.001, 0.005, "Claude Haiku 4.5"),
        // OpenAI GPT-5 family — Codex variants distinct from base
        Entry("^gpt-5\\.3-codex", "i", 0.0090, 0.036, "GPT-5.3 Codex"),
        Entry("^gpt-5\\.2-codex", "i", 0.0075, 0.030, "GPT-5.2 Codex"),
        Entry("^gpt-5-codex", "i", 0.005, 0.020, "GPT-5 Codex"),
        Entry("^gpt-5-mini", "i", 0.00025, 0.002, "GPT-5 Mini"),
        // Google Gemini 2.5 family
        Entry("^gemini-2\\.5-pro", "i", 0.00125, 0.010, "Gemini 2.5 Pro"),
        Entry("^gemini-2\\.5-flash", "i", 0.0003, 0.0025, "Gemini 2.5 Flash")
    )

    /**
     * Add any [BUILT_IN_PATTERNS] entries whose `pattern` literal is
     * not already present in the local store. Returns the count of
     * patterns actually added (0 = already up to date). Inserts at the
     * front so newer specific patterns beat existing generic fallbacks
     * during top-down match.
     */
    fun seedMissingPatterns(): Int {
        val current = state() ?: PricingStore(emptyList(), Instant.now().toString(), "seeded")
        val haveByPattern = current.entries.associateBy { it.pattern }
        val missing = BUILT_IN_PATTERNS.filter { it.pattern !in haveByPattern }
        if (missing.isEmpty()) return 0
        val merged = missing + current.entries  // missing first → matches first
        write(PricingStore(merged, Instant.now().toString(),
            current.source.takeIf { it.startsWith("seeded") } ?: "operator-edit+seeded"))
        log.info("seedMissingPatterns: added {} pattern(s)", missing.size)
        return missing.size
    }

    /** Best-effort price lookup by model id. Walks the entries top-
     *  down (matches VSIX semantics). Returns null when no pattern
     *  matches OR when the store is empty. */
    fun priceFor(modelId: String): Entry? {
        val s = state() ?: return null
        for (e in s.entries) {
            val flags = e.flags.uppercase()
            val opts = mutableSetOf<RegexOption>()
            if (flags.contains("I")) opts.add(RegexOption.IGNORE_CASE)
            if (flags.contains("M")) opts.add(RegexOption.MULTILINE)
            if (runCatching { Regex(e.pattern, opts).containsMatchIn(modelId) }.getOrDefault(false)) {
                return e
            }
        }
        return null
    }

    /** Bridge state at this instant; throws on any failure (caller
     *  is the admin controller, which surfaces the error). */
    fun bridgeSnapshot(): BridgeSnapshot? {
        return runCatching { fetchFromBridge() }
            .onFailure { log.debug("bridge pricing fetch failed: {}", it.message) }
            .getOrNull()
    }

    private fun fetchFromBridge(): BridgeSnapshot {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:11434/v1/pricing"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("bridge /v1/pricing returned ${resp.statusCode()}")
        }
        return mapper.readValue(resp.body(), BridgeSnapshot::class.java)
    }

    private fun write(store: PricingStore) {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(mapper.writeValueAsString(store))
        loaded = store
    }
}
