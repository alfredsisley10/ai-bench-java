package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Single source of truth for "how much per 1K tokens does this
 * (registry id, vendor identifier) cost?". Used by:
 *   - [RegisteredModelsRegistry.availableModels] when overlaying
 *     prices on auto-discovered Copilot ModelInfos.
 *   - [BenchmarkRunService.simulate]'s estimated-cost computation
 *     so newly-completed runs get the same pricing the LLM page
 *     advertises.
 *   - The recompute backfill triggered by an operator price edit.
 *
 * Priority chain (first hit wins):
 *   1. Operator override -- a stored entry in [RegisteredModelsStore]
 *      with the matching id and non-zero rates. Always wins; the
 *      operator's edit is canonical.
 *   2. Public catalog ([ModelPriceCatalog]) by vendor identifier.
 *      Catalog rows have a `modelIdentifier` field (e.g. `gpt-4o`,
 *      `claude-opus-4-6`) that matches the SAME field on the runtime
 *      ModelInfo. This is the fix for the longstanding "Copilot
 *      models always show $0" bug -- the previous match-by-id was
 *      comparing `copilot-gpt-4-1` (registry id) to `openai-gpt-4o`
 *      (catalog id) and silently never matching.
 *   3. Pattern store ([ModelPricingStore.priceFor]) using regex
 *      patterns against the vendor identifier. This catches
 *      Copilot models like `gpt-5-mini` or `claude-haiku-4-5` that
 *      aren't in the static catalog yet -- the operator can ship
 *      them via the "Seed missing patterns" admin button (PR #10)
 *      and they'll match here without a code change.
 *   4. Fuzzy catalog fallback -- normalize `_` and `.` -> `-`,
 *      lowercase, strip leading provider prefixes (`copilot-`,
 *      `openai-`, etc.) on both sides; if anything matches that way
 *      we use it. Catches drift like `copilot-gpt-4-1` vs catalog's
 *      `gpt-4.1` modelIdentifier.
 *   5. (0.0, 0.0) with a one-shot WARN log so the operator knows
 *      the pricing lookup failed silently.
 */
@Component
class PriceResolver(
    private val priceCatalog: ModelPriceCatalog,
    private val registeredModelsStore: RegisteredModelsStore,
    private val pricingStore: ModelPricingStore
) {
    private val log = LoggerFactory.getLogger(PriceResolver::class.java)

    /** Cache of "we already warned about this id" so the log
     *  doesn't get one WARN per run for known-unmatched models. */
    private val warnedForId = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** (promptPer1k, completionPer1k) for the given (registry id,
     *  vendor identifier). Either id may be empty; the resolver
     *  uses whichever is non-blank. */
    data class Price(val promptPer1k: Double, val completionPer1k: Double, val source: String)

    fun priceFor(registryId: String, vendorId: String): Price {
        // 1. Operator override.
        val stored = registeredModelsStore.findById(registryId)
        if (stored != null && (stored.costPer1kPrompt > 0.0 || stored.costPer1kCompletion > 0.0)) {
            return Price(stored.costPer1kPrompt, stored.costPer1kCompletion, "operator-override")
        }

        // 2. Public catalog by vendor identifier (the fix for
        //    Copilot's "$0 cost" bug).
        val catalogByVendor = priceCatalog.catalog.firstOrNull {
            it.modelIdentifier.equals(vendorId, ignoreCase = true)
        }
        if (catalogByVendor != null && catalogByVendor.costPer1kPrompt > 0.0) {
            return Price(catalogByVendor.costPer1kPrompt,
                catalogByVendor.costPer1kCompletion ?: 0.0, "catalog:vendorId")
        }

        // 2b. Catalog by registry id (works for manual registrations
        //     where the operator chose the catalog id verbatim).
        val catalogById = priceCatalog.catalog.firstOrNull { it.id == registryId }
        if (catalogById != null && catalogById.costPer1kPrompt > 0.0) {
            return Price(catalogById.costPer1kPrompt,
                catalogById.costPer1kCompletion ?: 0.0, "catalog:id")
        }

        // 3. Pattern store via vendor id (the patterns in pricing.json
        //    use the vendor format like `^gpt-5\\.3-codex`).
        val patternMatch = pricingStore.priceFor(vendorId.ifBlank { registryId })
        if (patternMatch != null) {
            return Price(patternMatch.promptPer1k, patternMatch.completionPer1k, "pattern-store")
        }

        // 4. Fuzzy catalog fallback -- normalize both sides and try
        //    match-by-vendor again with relaxed comparison.
        val normalizedVendor = normalize(vendorId)
        val normalizedRegistry = normalize(registryId)
        val fuzzyMatch = priceCatalog.catalog.firstOrNull {
            val n = normalize(it.modelIdentifier)
            n == normalizedVendor || n == normalizedRegistry ||
                normalizedVendor.startsWith(n) || normalizedRegistry.startsWith(n)
        }
        if (fuzzyMatch != null && fuzzyMatch.costPer1kPrompt > 0.0) {
            return Price(fuzzyMatch.costPer1kPrompt,
                fuzzyMatch.costPer1kCompletion ?: 0.0, "catalog:fuzzy")
        }

        // 5. Nothing matched.
        if (warnedForId.add("$registryId|$vendorId")) {
            log.warn("PriceResolver: no price for registryId='{}' vendorId='{}' " +
                "-- runs against this model will show \$0 cost. Either edit " +
                "/llm to set per-model override, or add a pattern at " +
                "/admin/pricing matching its vendor id.",
                registryId, vendorId)
        }
        return Price(0.0, 0.0, "no-match")
    }

    /** Normalization for fuzzy compare: lowercase, drop non-alphanumeric
     *  except letters+digits, strip a leading `copilot-`/`openai-`/etc.
     *  prefix. Goal: `copilot-gpt-4-1` ↔ `gpt-4.1` ↔ `gpt-4_1` all
     *  collapse to the same `gpt41` token. */
    private fun normalize(s: String): String {
        if (s.isBlank()) return ""
        var t = s.lowercase()
        // Strip common provider prefixes once.
        for (prefix in providerPrefixes) {
            if (t.startsWith(prefix)) { t = t.removePrefix(prefix); break }
        }
        return t.replace(Regex("[^a-z0-9]"), "")
    }

    private val providerPrefixes = listOf(
        "copilot-", "openai-", "anthropic-", "google-",
        "meta-", "mistral-", "corp-openai-"
    )
}
