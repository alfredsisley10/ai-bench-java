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
class AppMapController(private val appmaps: AppMapService) {

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
