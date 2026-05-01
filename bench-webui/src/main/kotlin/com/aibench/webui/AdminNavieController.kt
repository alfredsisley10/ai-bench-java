package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.concurrent.Executors

/**
 * Admin surface for the Layer-C Navie precompute cache. Operator
 * page lists every bug + cache status; per-bug "Precompute" button
 * spawns a background `appmap navie` invocation that populates the
 * shared cache so the actual benchmark runs (which select
 * contextProvider=appmap-navie) get an instant cache HIT instead of
 * sitting through 15-30 minutes of agentic search before the
 * fix-attempt LLM call even fires.
 */
@Controller
class AdminNavieController(
    private val bugCatalog: BugCatalog,
    private val navieCache: NavieCacheManager,
    private val throttler: AdaptiveThrottler
) {
    private val log = LoggerFactory.getLogger(AdminNavieController::class.java)

    /** Larger pool than the throttler's cap so the queue can absorb a
     *  full precompute-all submission immediately; the throttler
     *  decides how many actually run concurrently against the bridge. */
    private val executor = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "navie-precompute").apply { isDaemon = true }
    }

    data class Row(
        val id: String,
        val title: String,
        val status: String,
        val cachedAt: String?,
        val durationMs: Long?,
        val fileCount: Int?,
        /** Trace-file count Navie referenced during its agentic loop --
         *  parsed out of the cached trajectory by NavieCacheManager. */
        val traceCount: Int?,
        val activePhase: String?,
        val activeError: String?,
        /** Live-progress fields populated when activePhase != null:
         *  trajectory event count + how long ago the last event landed.
         *  Lets the operator distinguish "Navie is making progress,
         *  just slow" from "Navie wedged silently for 5 minutes". */
        val activeEvents: Int?,
        val activeBytes: Long?,
        val secondsSinceLastEvent: Long?
    )

    @GetMapping("/admin/navie")
    fun page(model: Model): String {
        val now = java.time.Instant.now()
        val rows = bugCatalog.allBugs().map { bug ->
            val cached = navieCache.get(bug)
            val active = navieCache.activeJob(bug.id)
            val isLive = active != null && active.endedAt == null
            Row(
                id = bug.id,
                title = bug.title,
                status = navieCache.status(bug),
                cachedAt = cached?.generatedAt,
                durationMs = cached?.durationMs,
                fileCount = cached?.filesIdentified?.size,
                traceCount = cached?.tracesIdentified?.size,
                activePhase = active?.takeIf { it.endedAt == null }?.phase,
                activeError = active?.error,
                activeEvents = if (isLive) active?.trajectoryEventsLive else null,
                activeBytes = if (isLive) active?.trajectoryBytesLive else null,
                secondsSinceLastEvent = if (isLive) active?.lastEventAt?.let {
                    java.time.Duration.between(it, now).seconds
                } else null
            )
        }
        model.addAttribute("rows", rows)
        model.addAttribute("cliPath", navieCache.locateCli()?.absolutePath
            ?: "(not found — see NavieCacheManager.locateCli for lookup order)")
        model.addAttribute("activeCount", rows.count { it.activePhase != null })
        return "admin-navie"
    }

    @PostMapping("/admin/navie/precompute")
    fun precomputeOne(@RequestParam bugId: String): String {
        val bug = bugCatalog.getBug(bugId)
            ?: return "redirect:/admin/navie?err=unknown-bug"
        submitWithThrottle(bug)
        return "redirect:/admin/navie?queued=$bugId"
    }

    /** Per-bug detail page: shows the cached NavieResult in full
     *  (files identified, traces identified, full answer markdown,
     *  metadata) plus -- when the job is currently in flight -- the
     *  recent stdout tail and trajectory progress so the operator
     *  can see what stage Navie is in without tail'ing /tmp. */
    @GetMapping("/admin/navie/{bugId}")
    fun detail(@org.springframework.web.bind.annotation.PathVariable bugId: String,
               model: Model): String {
        val bug = bugCatalog.getBug(bugId)
            ?: return "redirect:/admin/navie?err=unknown-bug"
        val cached = navieCache.get(bug)
        val active = navieCache.activeJob(bugId)
        model.addAttribute("bug", bug)
        model.addAttribute("cached", cached)
        model.addAttribute("active", active)
        model.addAttribute("activeIsLive", active != null && active.endedAt == null)
        return "admin-navie-detail"
    }

    /** Cancel an in-flight navie precompute. Kills the appmap CLI
     *  subprocess immediately so the bridge stops getting hit; the
     *  next queued bug starts (or the queue drains if this was last). */
    @PostMapping("/admin/navie/cancel")
    fun cancelOne(@RequestParam bugId: String): String {
        val ok = navieCache.cancel(bugId)
        return "redirect:/admin/navie?" + (if (ok) "canceled=$bugId" else "err=not-running")
    }

    @PostMapping("/admin/navie/precompute-all")
    fun precomputeAll(): String {
        val bugs = bugCatalog.allBugs()
        for (bug in bugs) {
            // Skip already-cached bugs so a re-click doesn't pay the
            // per-bug cost again; operator can use single-bug precompute
            // to force a refresh.
            if (navieCache.status(bug) == "cached") continue
            submitWithThrottle(bug)
        }
        return "redirect:/admin/navie?queued=all"
    }

    /** Throttler-gated precompute submission. The thread that actually
     *  runs blocks on throttler.acquire() before invoking the appmap
     *  CLI -- so the queue can be 12 bugs deep but only N=currentCap
     *  run concurrently against the bridge. After the subprocess
     *  exits, parses its stdout tail for rate-limit signals (the
     *  same isQuotaExhaustionError heuristic the bridge uses) and
     *  reports to the throttler so it can shrink + cool down. */
    private fun submitWithThrottle(bug: BugCatalog.BugMetadata) {
        executor.submit {
            try {
                throttler.acquire()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@submit
            }
            try {
                val job = runCatching { navieCache.precompute(bug) }.getOrElse { e ->
                    log.warn("navie precompute thread failed for {}: {}", bug.id, e.message)
                    null
                }
                // Detect rate-limit pattern in the captured CLI output.
                // The appmap CLI surfaces upstream Copilot quota errors
                // in its stdout; if we see those, signal the throttler
                // to back off before the next acquire.
                val tail = job?.stdoutTail ?: ""
                val errStr = job?.error ?: ""
                if (looksLikeRateLimit(tail) || looksLikeRateLimit(errStr)) {
                    throttler.reportRateLimit(
                        "navie precompute ${bug.id}: " +
                            (errStr.takeIf { it.isNotBlank() } ?: tail.takeLast(200))
                    )
                }
            } finally {
                throttler.release()
            }
        }
    }

    /** Heuristic for "the appmap CLI's output looked like Copilot
     *  rejected us for rate-limit / quota reasons". Conservative --
     *  false negatives over false positives so we don't shrink the
     *  pool on every transient error. */
    private fun looksLikeRateLimit(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.lowercase()
        return t.contains("rate limit") || t.contains("rate_limit") ||
            t.contains("rate-limited") || t.contains("ratelimited") ||
            t.contains("quota") && t.contains("exceed") ||
            t.contains("monthly limit") || t.contains("reached your monthly") ||
            t.contains("429") || t.contains("too many requests")
    }
}
