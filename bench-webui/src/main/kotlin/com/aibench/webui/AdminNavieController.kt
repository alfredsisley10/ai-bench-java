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
    private val navieCache: NavieCacheManager
) {
    private val log = LoggerFactory.getLogger(AdminNavieController::class.java)

    /** Single-thread executor by design: Navie monopolizes the bridge
     *  via the LLM mutex, so running 12 precomputes in parallel would
     *  just queue them anyway -- and it's much harder to follow
     *  progress when 12 jobs are interleaved. One at a time, FIFO. */
    private val executor = Executors.newSingleThreadExecutor { r ->
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
        executor.submit {
            runCatching { navieCache.precompute(bug) }
                .onFailure { log.warn("navie precompute thread failed for {}: {}", bugId, it.message) }
        }
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
            executor.submit {
                runCatching { navieCache.precompute(bug) }
                    .onFailure { log.warn("navie precompute thread failed for {}: {}", bug.id, it.message) }
            }
        }
        return "redirect:/admin/navie?queued=all"
    }
}
