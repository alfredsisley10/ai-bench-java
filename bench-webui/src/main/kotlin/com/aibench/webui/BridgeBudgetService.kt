package com.aibench.webui

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Polls the Copilot bridge's /v1/budget endpoint and caches the
 * result so the run launcher can show "X tokens used / Y budget"
 * without hammering the bridge on every page render. The bridge
 * computes the snapshot from its own globalState so every webui
 * call is cheap (no upstream Copilot API hit) — the cache here
 * just smooths over the network round-trip latency.
 *
 * When the bridge isn't reachable (VSCode closed, OpenAI shim not
 * started, etc.) snapshot() returns null. Callers MUST handle
 * null gracefully — the launcher renders a "bridge offline" tile
 * instead of refusing to launch, since the harness can run with
 * synthetic-only LLM activity even without the bridge.
 */
@Component
class BridgeBudgetService {
    private val log = LoggerFactory.getLogger(BridgeBudgetService::class.java)
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(800))
        .build()
    private val cached = AtomicReference<Cached?>(null)

    /** Mirror of the bridge's BudgetSnapshot interface from usage.ts.
     *  Field names match the JSON keys returned by /v1/budget. */
    data class BudgetSnapshot(
        val windowStartIso: String,
        val windowEndIso: String,
        val windowKind: String,
        val budgetTokens: Long,
        val usedTokens: Long,
        val remainingTokens: Long,
        val estimatedCostUsd: Double,
        val quotaExceededLastSeenIso: String?,
        val quotaExceededCount: Int
    )

    private data class Cached(val snapshot: BudgetSnapshot, val fetchedAt: Instant)

    /** TTL on the in-memory cache. Short enough that an operator
     *  refreshing /run a few times in a row sees fresh data; long
     *  enough that auto-refresh polling doesn't spam the bridge. */
    private val ttl: Duration = Duration.ofSeconds(15)

    /** Returns the latest cached snapshot when it's fresh enough,
     *  otherwise re-fetches. Returns null if the bridge is unreachable
     *  or the request times out -- caller should treat this as
     *  "budget unknown" and not block on it. */
    fun snapshot(): BudgetSnapshot? {
        val now = Instant.now()
        val c = cached.get()
        if (c != null && Duration.between(c.fetchedAt, now) < ttl) {
            return c.snapshot
        }
        return runCatching { fetchFromBridge() }
            .onFailure { log.debug("budget fetch failed: {}", it.message) }
            .getOrNull()
            ?.also { cached.set(Cached(it, now)) }
    }

    private fun fetchFromBridge(): BudgetSnapshot {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:11434/v1/budget"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("bridge /v1/budget returned ${resp.statusCode()}")
        }
        return mapper.readValue(resp.body(), BudgetSnapshot::class.java)
    }

    /** Estimate the token cost of one benchmark run -- used by the
     *  launcher to check "this batch will need ~X tokens, you have Y
     *  remaining". Conservative: assumes oracle-ish prompt size
     *  (~12K prompt tokens) and a typical patch response (~500
     *  completion tokens). Real per-bug variance is large; treat the
     *  output as an order-of-magnitude floor, not a tight forecast. */
    fun estimateRunCost(): Long = 12_000L + 500L

    /** Conservative budget-projection helper for the launcher.
     *  Returns null when the bridge is offline (caller should not
     *  block) or the budget is unset (no enforcement). */
    fun projectionForBatch(runCount: Int): Projection? {
        val snap = snapshot() ?: return null
        if (snap.budgetTokens <= 0) return Projection(
            snap, runCount, runCount * estimateRunCost(),
            wouldExceed = false, percentAfter = null
        )
        val projected = runCount * estimateRunCost()
        val totalAfter = snap.usedTokens + projected
        return Projection(
            snapshot = snap,
            runCount = runCount,
            projectedTokens = projected,
            wouldExceed = totalAfter > snap.budgetTokens,
            percentAfter = (totalAfter * 100.0 / snap.budgetTokens).coerceAtMost(999.0)
        )
    }

    data class Projection(
        val snapshot: BudgetSnapshot,
        val runCount: Int,
        val projectedTokens: Long,
        val wouldExceed: Boolean,
        val percentAfter: Double?
    )
}
