package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Negative-control probe launcher. Pairs with the dashboard's
 * "Pre-training contamination" warning section. The operator picks
 * a probe model (typically a smaller / older model believed not to
 * have memorized this codebase) and the controller launches a
 * benchmark run for each (modelId, bugId) pair using
 * contextProvider="none" + appmapMode="OFF" + seeds=1.
 *
 * Interpretation of the result:
 *   - Probe PASSES → bug-definition contamination is the cause; the
 *     problem statement / hints leak the answer regardless of the
 *     model in use. Rewrite the bug yaml.
 *   - Probe FAILS → model contamination is the cause; the originally
 *     flagged model memorized the fix. Disqualify that model from
 *     scoring this bug.
 *
 * The probe row IS just another BenchmarkRun -- no separate table,
 * no separate worker. Dashboard correlates the probe back to the
 * contamination row via (issueId, contextProvider="none",
 * appmapMode="OFF", model NOT in original contamination set).
 */
@Controller
class ContaminationProbeController(
    private val benchmarkRuns: BenchmarkRunService,
    private val bugCatalog: BugCatalog,
    private val registeredModels: RegisteredModelsRegistry
) {
    private val log = LoggerFactory.getLogger(ContaminationProbeController::class.java)

    /**
     * Form-style POST so the dashboard can submit straight from a
     * `<form action="/admin/contamination/probe">`. Each `pairs[]`
     * value is `"<provider>|<modelId>|<bugId>"` (the originating
     * contamination row -- supplied so we know which model to
     * exclude from the dashboard's probe-correlation lookup, even
     * though the launched run uses the probe model not the original).
     */
    @PostMapping("/admin/contamination/probe")
    fun probe(
        @RequestParam("pairs", required = false) pairs: List<String>?,
        @RequestParam("probeModelId") probeModelId: String,
        session: HttpSession
    ): String {
        val resolved = registeredModels.availableModels(session)
            .firstOrNull { it.id == probeModelId }
            ?: return "redirect:/?probeErr=unknown-probe-model"

        val items = (pairs ?: emptyList()).mapNotNull { raw ->
            val parts = raw.split("|")
            if (parts.size != 3) null
            else Triple(parts[0], parts[1], parts[2])  // origProvider, origModel, bugId
        }
        if (items.isEmpty()) return "redirect:/?probeErr=no-pairs"

        var launched = 0
        for ((origProvider, origModel, bugId) in items) {
            val bug = bugCatalog.getBug(bugId)
            if (bug == null) {
                log.warn("Probe skipped -- unknown bug {}", bugId)
                continue
            }
            // Skip if the chosen probe model IS the originally flagged
            // model. A self-probe would tautologically reproduce the
            // contamination signal and waste tokens.
            if (resolved.provider.equals(origProvider, ignoreCase = true)
                && resolved.id.equals(origModel, ignoreCase = true)) {
                log.warn("Probe skipped -- probe model equals original contaminated model ({})", origModel)
                continue
            }
            benchmarkRuns.start(
                issueId = bugId,
                issueTitle = bug.title,
                provider = resolved.provider,
                modelId = resolved.id,
                modelIdentifier = resolved.modelIdentifier,
                contextProvider = "none",
                appmapMode = "OFF",
                seeds = 1
            )
            launched++
        }
        log.info("Launched {} negative-control probe(s) using model {}", launched, probeModelId)
        return "redirect:/?probed=$launched"
    }
}
