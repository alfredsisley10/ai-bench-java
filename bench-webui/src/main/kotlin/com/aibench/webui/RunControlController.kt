package com.aibench.webui

import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.time.Duration
import java.time.Instant

/**
 * Operator controls for the in-flight benchmark queue. Three
 * orthogonal actions:
 *   - PAUSE / RESUME (soft) -- gates new dispatches via PauseGate.
 *     In-flight LLM calls + scoring complete; the queue drains to
 *     a stable state; nothing new starts until resume. Cheapest
 *     way to "pause to inspect" a long matrix.
 *   - CANCEL ALL ACTIVE (hard) -- marks every RUNNING + QUEUED
 *     run CANCELED. Worker threads observe the flip and return
 *     on their next checkpoint.
 *   - CANCEL SELECTED (hard, batch) -- same but only for the
 *     run ids the operator checkboxed.
 *
 * Live progress JSON at /runs/control/progress.json so the
 * dashboard can poll without re-rendering the full Thymeleaf
 * template every 4s.
 */
@Controller
class RunControlController(
    private val benchmarkRuns: BenchmarkRunService,
    private val pauseGate: PauseGate,
    private val throttler: AdaptiveThrottler,
    private val worktreePool: WorktreePool,
    private val dashboard: DashboardController
) {

    @PostMapping("/runs/control/pause")
    fun pause(): String {
        pauseGate.pause()
        return "redirect:/?paused=1"
    }

    @PostMapping("/runs/control/resume")
    fun resume(): String {
        pauseGate.resume()
        return "redirect:/?resumed=1"
    }

    @PostMapping("/runs/control/cancel-all")
    fun cancelAll(): String {
        val n = benchmarkRuns.cancelAll()
        return "redirect:/?cancelled=$n"
    }

    @PostMapping("/runs/control/cancel-batch")
    fun cancelBatch(
        @RequestParam(name = "ids", required = false) ids: List<String>?
    ): String {
        val n = benchmarkRuns.cancelMany(ids ?: emptyList())
        return "redirect:/?cancelled=$n"
    }

    /**
     * Live in-progress stats. Cheap enough to poll from the
     * dashboard's existing 4s auto-refresh -- avoids re-rendering
     * the whole Thymeleaf template just to update the counters.
     */
    @GetMapping("/runs/control/progress.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun progress(): Map<String, Any?> {
        val all = benchmarkRuns.recentRuns(2000)
        val lp = dashboard.computeLiveProgress(all)
        return mapOf(
            "isPaused" to lp.isPaused,
            "active" to lp.active,
            "running" to lp.running,
            "queued" to lp.queued,
            "throttlerCap" to throttler.status().currentCap,
            "poolCap" to lp.poolCap,
            "poolAvailable" to lp.poolAvailable,
            "recentCompleted" to lp.recentCompleted,
            "recentPassRate" to lp.recentPassRate,
            "avgRunMs" to lp.avgRunMs,
            "etrSec" to lp.etrSec,
            "sessionCostUsd" to lp.sessionCostUsd,
            "asOf" to Instant.now().toString()
        )
    }
}
