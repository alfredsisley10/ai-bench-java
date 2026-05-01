package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Background supervisor for cost-optimized batch launches.
 *
 * Full-matrix mode submits the entire (bug × model × ctx × mode × seed)
 * cross-product up-front. That's correct for regression / cost-curve
 * runs but wasteful when budget matters: a $0.0001/1K cheap-model
 * run that already PASSED a bug doesn't need the same bug re-tried
 * with the $0.06/1K expensive-model variant.
 *
 * Cost-optimized mode flips the order: for each (bug, seed) the
 * launcher hands us the requested (model, ctx, mode) tuples sorted
 * cheapest-first; we submit the FIRST tuple immediately and queue
 * the rest. A periodic poll watches the run-in-flight per (bug, seed):
 *   - PASSED  -> drop the remaining queue (cheaper solver works,
 *                no need to spend more budget on this bug+seed).
 *   - FAILED / ERRORED / CANCELED -> submit the next tuple from
 *                the queue. If queue empty, that (bug, seed) is done.
 *
 * Multiple cost-optimized batches can run concurrently; each gets a
 * batch id and its own per-(bug, seed) queues. The supervisor never
 * skips work for full-matrix runs (they don't pass through here).
 */
@Component
class CostOptimizedLaunchSupervisor(
    private val benchmarkRuns: BenchmarkRunService,
    private val pricing: ModelPricingStore
) {
    private val log = LoggerFactory.getLogger(CostOptimizedLaunchSupervisor::class.java)

    /** A single (bug × model × context × appmap mode) candidate. The
     *  launcher passes a list of these per (bug, seed) sorted ascending
     *  by [costScore]; cheapest gets fired first. */
    data class Tuple(
        val issueId: String,
        val issueTitle: String,
        val provider: String,
        val modelId: String,
        val modelIdentifier: String,
        val contextProvider: String,
        val appmapMode: String,
        /** Composite cost rank used to order [Tuple]s within a (bug,
         *  seed) group. Operator-facing units are USD-equivalent;
         *  unknown-model entries get Double.MAX_VALUE so they sort
         *  to the back of the queue (try known-cost options first). */
        val costScore: Double
    )

    /** Per (bug, seed) state. The active runId is null between submit
     *  attempts (briefly while we wait for the run to register). */
    private data class GroupState(
        val issueId: String,
        val seed: Int,
        @Volatile var queue: ArrayDeque<Tuple>,
        @Volatile var activeRunId: String? = null
    )

    private data class BatchState(
        val batchId: String,
        val createdAt: Instant,
        val groups: MutableList<GroupState>
    )

    private val activeBatches = ConcurrentHashMap<String, BatchState>()
    private val poller = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cost-opt-poll").apply { isDaemon = true }
    }.also {
        it.scheduleWithFixedDelay({ runCatching { tick() } },
            5, 5, TimeUnit.SECONDS)
    }

    /** Submit a cost-optimized batch: every group's cheapest tuple
     *  fires immediately, the rest queue. Returns the batch id +
     *  the run ids the launcher should redirect into. */
    data class SubmitResult(val batchId: String, val initialRunIds: List<String>)

    fun submit(groups: Map<Pair<String, Int>, List<Tuple>>): SubmitResult {
        val batchId = "batch-" + System.currentTimeMillis().toString(36)
        val state = BatchState(batchId, Instant.now(), mutableListOf())
        val initial = mutableListOf<String>()
        for ((key, tuples) in groups) {
            if (tuples.isEmpty()) continue
            val (issueId, seed) = key
            val sorted = tuples.sortedBy { it.costScore }
            val q = ArrayDeque(sorted)
            val first = q.removeFirst()
            val run = benchmarkRuns.start(
                issueId = first.issueId,
                issueTitle = first.issueTitle,
                provider = first.provider,
                modelId = first.modelId,
                modelIdentifier = first.modelIdentifier,
                contextProvider = first.contextProvider,
                appmapMode = first.appmapMode,
                seeds = seed
            )
            initial.add(run.id)
            state.groups += GroupState(issueId, seed, q, run.id)
        }
        if (state.groups.isNotEmpty()) {
            activeBatches[batchId] = state
            log.info("cost-opt batch {}: {} group(s) launched, {} initial run(s)",
                batchId, state.groups.size, initial.size)
        }
        return SubmitResult(batchId, initial)
    }

    /** Operator-facing summary for the dashboard tile. */
    data class BatchSummary(
        val batchId: String,
        val createdAt: Instant,
        val totalGroups: Int,
        val activeGroups: Int,
        val pendingTuples: Int
    )

    fun activeSummaries(): List<BatchSummary> =
        activeBatches.values.map { b ->
            BatchSummary(
                batchId = b.batchId,
                createdAt = b.createdAt,
                totalGroups = b.groups.size,
                activeGroups = b.groups.count { it.activeRunId != null || it.queue.isNotEmpty() },
                pendingTuples = b.groups.sumOf { it.queue.size }
            )
        }

    /** Per-tick: walk every group, advance state. */
    private fun tick() {
        val toRemove = mutableListOf<String>()
        for ((batchId, batch) in activeBatches) {
            for (g in batch.groups) {
                val rid = g.activeRunId ?: continue
                val run = benchmarkRuns.get(rid) ?: continue
                when (run.status) {
                    BenchmarkRunService.Status.QUEUED,
                    BenchmarkRunService.Status.RUNNING -> { /* still in flight */ }
                    BenchmarkRunService.Status.PASSED -> {
                        // Cheaper solver worked -- drop the remaining
                        // (more-expensive) tuples; budget saved.
                        if (g.queue.isNotEmpty()) {
                            log.info("cost-opt {}: {} seed {} solved by {} (skipping {} more-expensive tuple(s))",
                                batchId, g.issueId, g.seed, run.modelId + '/' + run.contextProvider,
                                g.queue.size)
                        }
                        g.queue.clear()
                        g.activeRunId = null
                    }
                    BenchmarkRunService.Status.FAILED,
                    BenchmarkRunService.Status.ERRORED,
                    BenchmarkRunService.Status.CANCELED -> {
                        // Try next tuple in cost order, if any.
                        val next = g.queue.removeFirstOrNull()
                        if (next == null) {
                            g.activeRunId = null
                        } else {
                            val newRun = benchmarkRuns.start(
                                issueId = next.issueId,
                                issueTitle = next.issueTitle,
                                provider = next.provider,
                                modelId = next.modelId,
                                modelIdentifier = next.modelIdentifier,
                                contextProvider = next.contextProvider,
                                appmapMode = next.appmapMode,
                                seeds = g.seed
                            )
                            g.activeRunId = newRun.id
                            log.info("cost-opt {}: {} seed {} -> next tuple {} ({} remaining)",
                                batchId, g.issueId, g.seed,
                                next.modelId + '/' + next.contextProvider, g.queue.size)
                        }
                    }
                }
            }
            // Batch is done when every group has empty queue + no active run.
            if (batch.groups.all { it.queue.isEmpty() && it.activeRunId == null }) {
                toRemove += batchId
            }
        }
        for (id in toRemove) activeBatches.remove(id)
    }

    /** Cost score helper: fold per-1K prices + a coarse per-context
     *  prompt-size estimate + an AppMap-mode multiplier into one
     *  comparable Double. Lower is cheaper. Unknown model -> MAX_VALUE
     *  (sort to the back). */
    fun costScore(modelId: String, contextProvider: String, appmapMode: String): Double {
        val price = pricing.priceFor(modelId) ?: return Double.MAX_VALUE
        val promptTokensCtx = when (contextProvider) {
            "none" -> 200.0
            "oracle" -> 8_000.0
            "bm25" -> 12_000.0
            "appmap-navie" -> 12_000.0
            else -> 8_000.0
        }
        val appmapMult = when (appmapMode) {
            "OFF" -> 1.0
            "ON_RECOMMENDED" -> 1.10
            "ON_ALL" -> 1.30
            else -> 1.0
        }
        val completionTokens = 1_000.0
        val promptCost = (promptTokensCtx * appmapMult / 1000.0) * price.promptPer1k
        val completionCost = (completionTokens / 1000.0) * price.completionPer1k
        return promptCost + completionCost
    }
}
