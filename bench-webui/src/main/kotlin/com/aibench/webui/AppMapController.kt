package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class AppMapController(
    private val appmaps: AppMapService,
    private val llmDiagnostician: LlmDiagnostician,
    private val connectionSettings: ConnectionSettings,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(AppMapController::class.java)

    @GetMapping("/demo/appmap")
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "10") pageSize: Int,
        model: Model,
        session: HttpSession
    ): String {
        val allTraces = appmaps.listTraces()
        val activeRecording = appmaps.latestRecording()

        // Server-side pagination. Aggregate stats (totalEvents/Sql/Http)
        // stay computed across ALL traces because they're summary
        // numbers; the table itself shows just the current page slice.
        // pageSize is constrained to the four exposed options so a
        // hand-edited URL can't blow out memory by requesting a giant
        // single page.
        val allowedPageSizes = listOf(10, 25, 50, 100)
        val safePageSize = if (pageSize in allowedPageSizes) pageSize else 10
        val total = allTraces.size
        val totalPages = if (total == 0) 1 else (total + safePageSize - 1) / safePageSize
        val safePage = page.coerceIn(1, totalPages)
        val from = (safePage - 1) * safePageSize
        val to = (from + safePageSize).coerceAtMost(total)
        val pageTraces = if (total == 0) emptyList() else allTraces.subList(from, to)

        model.addAttribute("traces", pageTraces)
        model.addAttribute("totalCount", total)
        model.addAttribute("currentPage", safePage)
        model.addAttribute("pageSize", safePageSize)
        model.addAttribute("totalPages", totalPages)
        model.addAttribute("pageFrom", if (total == 0) 0 else from + 1)
        model.addAttribute("pageTo", to)
        model.addAttribute("allowedPageSizes", allowedPageSizes)
        model.addAttribute("totalEvents", allTraces.sumOf { it.eventCount })
        model.addAttribute("totalSql", allTraces.sumOf { it.sqlCount })
        model.addAttribute("totalHttp", allTraces.sumOf { it.httpCount })
        model.addAttribute("activeRecording", activeRecording)
        model.addAttribute("modules", appmaps.modulesWithTraces())
        model.addAttribute("deleteResult", session.getAttribute("appmapDeleteResult"))
        session.removeAttribute("appmapDeleteResult")
        model.addAttribute("interactiveStatus", session.getAttribute("appmapInteractiveStatus"))
        session.removeAttribute("appmapInteractiveStatus")

        // Banking-app + agent state. Probing the agent endpoint (not
        // just trusting our session-stored startedWithAgent flag)
        // catches the case where the operator launched the app via
        // /demo without the -javaagent flag.
        val status = appmaps.bankingApp.status()
        val appRunning = status == BankingAppManager.Status.RUNNING
        val appStarting = status == BankingAppManager.Status.STARTING
        val agentAttached = appRunning && appmaps.isAgentAttached()
        model.addAttribute("appStatus", status.name)
        model.addAttribute("appRunning", appRunning)
        model.addAttribute("appStarting", appStarting)
        model.addAttribute("agentAttached", agentAttached)
        model.addAttribute("startedWithAgent", appmaps.bankingApp.startedWithAgent)
        model.addAttribute("bankingAppUrl", appmaps.bankingApp.url)
        // Surface last agent-discovery diagnostic so the user can debug a
        // Phase-1 failure without leaving the page. Empty string when
        // discovery never failed.
        model.addAttribute("agentDiagnostic", appmaps.lastAgentDiscoveryDiagnostic())
        // All Gradle subprojects — feeds the multi-select on "Record from
        // tests" so the operator picks from a known list rather than
        // typing a module name and hoping it matches.
        model.addAttribute("availableSubprojects", appmaps.availableSubprojects())
        return "demo-appmap"
    }

    /**
     * Stop the banking app from the AppMap traces page. Mirrors the
     * action available on the main /demo page so the operator can
     * complete the interactive recording lifecycle (start with agent
     * → record → stop recording → stop app) entirely within the
     * AppMap workflow.
     */
    @PostMapping("/demo/appmap/stop-app")
    fun stopBankingApp(session: HttpSession): String {
        val msg = appmaps.bankingApp.stop()
        session.setAttribute("appmapInteractiveStatus", msg)
        return "redirect:/demo/appmap"
    }

    @PostMapping("/demo/appmap/delete-one")
    fun deleteOne(@RequestParam id: String, session: HttpSession): String {
        val ok = appmaps.deleteTrace(id)
        session.setAttribute("appmapDeleteResult",
            if (ok) "Deleted 1 trace." else "Trace already gone or invalid id.")
        return "redirect:/demo/appmap"
    }

    @PostMapping("/demo/appmap/delete-module")
    fun deleteModule(@RequestParam module: String, session: HttpSession): String {
        val s = appmaps.deleteTracesForModule(module)
        session.setAttribute("appmapDeleteResult",
            "Deleted ${s.filesDeleted} traces from $module (${s.bytesFreed / 1024} KB freed)" +
                if (s.errors.isNotEmpty()) "; ${s.errors.size} errors" else ".")
        return "redirect:/demo/appmap"
    }

    @PostMapping("/demo/appmap/delete-all")
    fun deleteAll(@RequestParam(required = false) confirm: String?, session: HttpSession): String {
        if (confirm != "yes") {
            session.setAttribute("appmapDeleteResult",
                "Delete-all blocked: confirmation checkbox not set.")
            return "redirect:/demo/appmap"
        }
        val s = appmaps.deleteAllTraces()
        session.setAttribute("appmapDeleteResult",
            "Deleted ${s.filesDeleted} traces (${s.bytesFreed / 1024} KB freed)" +
                if (s.errors.isNotEmpty()) "; ${s.errors.size} errors" else ".")
        return "redirect:/demo/appmap"
    }

    @GetMapping("/demo/appmap/view")
    fun view(@RequestParam id: String, model: Model): String {
        val detail = appmaps.load(id)
        if (detail == null) {
            model.addAttribute("missingId", id)
            return "demo-appmap-missing"
        }
        model.addAttribute("trace", detail.summary)
        model.addAttribute("roots", detail.roots)
        model.addAttribute("flat", detail.flat)
        // Feature flag: the official @appland/components Vue viewer
        // gets vendored into static/appmap/components.iife.js by the
        // scripts/vendor-appmap-viewer.sh script. When present, the
        // template loads it and skips the server-side flat list.
        val bundle = AppMapController::class.java.classLoader
            .getResource("static/appmap/components.iife.js")
        model.addAttribute("officialViewerAvailable", bundle != null)
        return "demo-appmap-viewer"
    }

    /**
     * Serves the raw .appmap.json so users can open it in the official
     * AppMap VS Code extension or upload it to app.land for the full
     * interactive viewer (sequence diagrams, filtering, drill-down).
     */
    @GetMapping("/demo/appmap/raw")
    fun raw(@RequestParam id: String): ResponseEntity<FileSystemResource> {
        val detail = appmaps.load(id) ?: return ResponseEntity.notFound().build()
        val path = appmaps.absolutePathFor(detail.summary) ?: return ResponseEntity.notFound().build()
        val fileName = path.fileName.toString()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(FileSystemResource(path))
    }

    @PostMapping("/demo/appmap/record-tests")
    fun recordTests(
        // Accept both `modules` (multi-select, repeated form values) and
        // legacy `module` (single text field). Empty list → record from
        // every module.
        @RequestParam(required = false) modules: List<String>?,
        @RequestParam(required = false) module: String?,
        @RequestParam(required = false) testFilter: String?
    ): String {
        val combined = buildList<String> {
            modules?.let { addAll(it) }
            module?.let { add(it) }
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        runCatching {
            val recording = appmaps.startRecordingFromTests(combined.takeIf { it.isNotEmpty() }, testFilter)
            // Redirect straight to the live status panel so the user sees
            // progress immediately rather than a flash message.
            return "redirect:/demo/appmap?activeId=${recording.id}"
        }.onFailure { /* fall through to plain list */ }
        return "redirect:/demo/appmap"
    }

    /**
     * htmx-polled fragment that re-renders the active-recording panel
     * (status, elapsed, log tail, trace counter). Polled every 2s by
     * the demo-appmap page while a recording is in flight.
     */
    @GetMapping("/demo/appmap/recording/{id}/panel")
    fun recordingPanel(@PathVariable id: String, model: Model): String {
        val recording = appmaps.recording(id) ?: return "fragments/appmap-recording :: empty"
        model.addAttribute("rec", recording)
        model.addAttribute("logTail", appmaps.tailLog(recording, 40))
        model.addAttribute("currentTraceCount", appmaps.listTraces().size)
        return "fragments/appmap-recording :: panel"
    }

    @PostMapping("/demo/appmap/recording/{id}/stop")
    fun stop(@PathVariable id: String): String {
        appmaps.stopRecording(id)
        return "redirect:/demo/appmap?activeId=$id"
    }

    /**
     * Run a Diagnose-with-LLM pass over the given recording. Pulls a
     * larger log tail (200 lines vs the 40-line live view) so the LLM
     * has enough context to spot stack traces or repeated errors,
     * combines it with the recording's status + masked command +
     * operator's connection settings, and asks the bridge for a
     * two-section action plan: local resolutions + source-code
     * project fixes.
     *
     * Same diagnostician + masking + two-section prompt shape used by
     * /mirror/diagnose so the operator's experience is consistent
     * across the gradle/banking-app and AppMap surfaces.
     */
    @PostMapping("/demo/appmap/recording/{id}/diagnose")
    @org.springframework.web.bind.annotation.ResponseBody
    fun diagnoseRecording(
        @PathVariable id: String,
        @RequestParam(defaultValue = "copilot-default") modelId: String,
        session: HttpSession
    ): Map<String, Any?> {
        // Wrap in try/catch to surface a structured error instead of
        // letting Spring render its default 500 HTML page. catch on
        // Throwable (not Exception) so OutOfMemoryError from a huge
        // log tail and other Errors are also surfaced -- previously
        // an OOM in tailLog() escaped the Exception filter and the JS
        // verdict turned into "✗ HTTP 500 from /diagnose Internal
        // Server Error" with no actionable detail.
        return try {
            val recording = appmaps.recording(id)
                ?: return mapOf(
                    "ok" to false,
                    "reason" to "Unknown recording id: $id (was the WebUI restarted? " +
                        "Server-side recording state is in-memory only and clears on restart.)"
                )
            diagnoseRecordingInternal(recording, modelId, session)
        } catch (t: Throwable) {
            log.error("AppMap diagnose endpoint threw for recording id={}", id, t)
            mapOf(
                "ok" to false,
                "reason" to "Diagnose endpoint failed: ${t.javaClass.simpleName}: " +
                    (t.message ?: "(no detail)") +
                    ". Check bench-webui logs for the stack trace."
            )
        }
    }

    private fun diagnoseRecordingInternal(
        recording: AppMapService.Recording,
        modelId: String,
        session: HttpSession
    ): Map<String, Any?> {
        val s = connectionSettings.settings
        // Recording.command is already credential-masked at storage
        // time (PR #27), so this joinToString is safe to embed in the
        // prompt + log.
        val cmd = recording.command.joinToString(" ")
        val logTail = appmaps.tailLog(recording, 200)

        val systemPrompt = """
            You are a senior Gradle / Java test-engineering / AppMap
            specialist triaging an AppMap recording. The operator
            launched a `gradle :MODULE:test -Pappmap_enabled=true` run
            via bench-webui to capture .appmap.json traces; your job
            is to spot WHY the recording produced no useful output
            (or failed outright) and recommend actionable fixes.

            Respond in TWO clearly-delimited sections:

            ## Local resolutions
            Concrete steps the operator can take on THIS machine right
            now. Examples: re-run with a different module / test
            filter, fix a /proxy or /mirror config that's blocking
            dep resolution, kill a stale gradle daemon, install a
            JDK toolchain that matches banking-app's pin, ensure the
            AppMap Java agent jar is on disk. Cite specific button
            names or file paths.

            ## Source-code project fixes
            Changes to raise as PRs against the ai-bench-java repo so
            this failure mode stops happening. Examples: bump or pin
            an AppMap plugin version in banking-app/build.gradle.kts,
            add a defensive check in AppMapService, document a JDK
            requirement in README. Name the file path + the change
            in 1-2 sentences each.

            Be concrete and cite the specific failing line / stack
            frame / coordinate when present. 2-5 bullets per section.
            If no fix is needed in one section, say so in one line
            instead of inventing recommendations.
        """.trimIndent()

        val userPrompt = """
            AppMap recording id: ${recording.id}
            Status: ${recording.status}
            Exit code: ${recording.exitCode ?: "(still running)"}
            Module: ${recording.module ?: "(none — recording all)"}
            Test filter: ${recording.testFilter ?: "(none)"}
            Elapsed: ${recording.elapsedSeconds}s
            Traces before / after: ${recording.tracesBefore} / ${recording.tracesAfter ?: "(unknown)"}
            New trace count: ${recording.newTraceCount ?: "(unknown)"}

            Operator's bench-webui connection settings:
            - HTTPS proxy: ${s.httpsProxy.ifBlank { "(none)" }}
            - HTTP proxy: ${s.httpProxy.ifBlank { "(none)" }}
            - Mirror URL: ${s.mirrorUrl.ifBlank { "(none)" }}
            - Mirror auth: ${if (s.hasMirrorAuth) "configured (user '${s.mirrorAuthUser}')" else "(none)"}
            - Bypass mirror: ${s.bypassMirror}
            - Insecure SSL: ${s.insecureSsl}

            Gradle command bench-webui spawned (credentials masked):
            ```
            $cmd
            ```

            Recent log tail (last 200 lines):
            ```
            ${logTail.take(8000)}
            ```
        """.trimIndent()

        return llmDiagnostician
            .diagnose(modelId, systemPrompt, userPrompt, session, timeoutSec = 60)
            .toMap()
    }

    @PostMapping("/demo/appmap/start-with-agent")
    fun startWithAgent(session: HttpSession): String {
        session.setAttribute("appmapInteractiveStatus", appmaps.startBankingAppWithAgent())
        return "redirect:/demo/appmap"
    }

    @PostMapping("/demo/appmap/interactive/start")
    fun interactiveStart(session: HttpSession): String {
        val outcome = appmaps.startRemoteRecording()
        session.setAttribute("appmapInteractiveStatus", outcome.message)
        return "redirect:/demo/appmap"
    }

    @PostMapping("/demo/appmap/interactive/stop")
    fun interactiveStop(session: HttpSession): String {
        val outcome = appmaps.stopRemoteRecording()
        session.setAttribute("appmapInteractiveStatus",
            outcome.message + (outcome.savedTraceRelative?.let { " ($it)" } ?: ""))
        return "redirect:/demo/appmap"
    }
}
