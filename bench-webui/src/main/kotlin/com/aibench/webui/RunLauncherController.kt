package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class RunLauncherController(
    private val registeredModelsRegistry: RegisteredModelsRegistry,
    private val benchmarkRuns: BenchmarkRunService
) {

    // Built-in OmniBank issue titles. Mirrors DemoController.demoIssues
    // by id but only carries the title -- enough for the run record's
    // display label without dragging the whole DemoIssue model into
    // this launcher. Keep in sync if new BUG-XXXX entries are added.
    private val omnibankIssueTitles = mapOf(
        "BUG-0001" to "ACH same-day cutoff off-by-one at final window",
        "BUG-0002" to "Wire cutoff timezone defaults to UTC instead of ET",
        "BUG-0003" to "Trial balance invariant broken by uncommitted posting with mixed currency",
        "BUG-0004" to "Fee assessor double-charges on retry after timeout",
        "BUG-0005" to "OFAC screener allow-lists names that match by phonetics only",
        "BUG-0006" to "Loan amortization rounds the wrong direction on final payment",
        "BUG-0007" to "Card auth gateway races on stand-in approval window",
        "BUG-0008" to "Saga rollback skips compensation when initial step is async",
        "BUG-0009" to "Audit query service returns rows past the retention horizon",
        "BUG-0010" to "Statement renderer drops trailing transactions when locale is fr-FR",
        "BUG-0011" to "Settlement batcher emits duplicates after a stuck cursor",
        "BUG-0012" to "Risk engine ignores the override flag for VIP counterparties"
    )

    @GetMapping("/run")
    fun form(model: Model, session: HttpSession): String {
        // Pull the live, verified registry instead of hardcoding
        // ["corp-openai", "copilot"]. availableModels() applies the
        // same liveness checks the /demo Quick benchmark uses — Copilot
        // bridge is probed via TCP, Corp-OpenAI presence is gated on
        // its capabilities probe, AppMap Navie is gated on bridge +
        // CLI both being present. Anything that's still "registered
        // but not verified" gets filtered out before it reaches the
        // dropdown so the operator can't accidentally launch a run
        // against a provider whose connectivity hasn't been confirmed.
        val allModels = registeredModelsRegistry.availableModels(session)
        // Navie is a context provider, not an LLM, so it doesn't belong
        // in the LLM-provider dropdown. (The /demo page surfaces it as
        // a separate context-provider field; the launcher doesn't yet,
        // so dropping here keeps things simple.)
        val llmModels = allModels.filter { it.provider != "appmap-navie" }
        val verifiedProviders = llmModels.map { it.provider }.distinct().sorted()

        model.addAttribute("providers", verifiedProviders)
        model.addAttribute("noProvidersVerified", verifiedProviders.isEmpty())
        // Context-provider availability — same gating as /demo's
        // Quick benchmark: AppMap Navie surfaces only when the bridge
        // socket is live AND the AppMap CLI is on the host. Both come
        // through the registry's availableModels() probe.
        model.addAttribute("navieAvailable", allModels.any { it.provider == "appmap-navie" })

        // Target-type value is what's POSTed back; label is the display
        // string. Value kept as the short slug so /results filtering and
        // other call sites don't have to change — label updated per the
        // banking-app branding (Omnibank = the built-in sample).
        model.addAttribute("targetTypes", listOf(
            mapOf("value" to "omnibank", "label" to "Omnibank (built-in sample banking app)"),
            mapOf("value" to "enterprise", "label" to "Enterprise (real repository)")
        ))
        model.addAttribute("availableBugs", (1..12).map { "BUG-%04d".format(it) })
        model.addAttribute("availableRepos", emptyList<String>())

        // Per-model rows for the cascading model dropdown — each carries
        // a data-provider attr so the JS in run-launcher.html filters
        // the model list when the operator picks a provider above.
        val modelRows = llmModels.map {
            mapOf("id" to it.id, "name" to it.displayName, "provider" to it.provider)
        }
        model.addAttribute("models", modelRows)

        return "run-launcher"
    }

    @PostMapping("/run/launch")
    fun launch(
        @RequestParam(required = false) targetType: String?,
        @RequestParam(required = false) bugId: String?,
        @RequestParam(required = false) repoName: String?,
        @RequestParam(required = false) jiraTicket: String?,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) modelId: String?,
        @RequestParam(defaultValue = "none") contextProvider: String,
        @RequestParam(required = false) appmapMode: String?,
        @RequestParam(defaultValue = "3") seeds: Int,
        session: HttpSession
    ): String {
        // Validate required fields ourselves rather than relying on
        // Spring's @RequestParam binding to throw 400. The opaque
        // "Bad Request" JSON Spring produces is what operators kept
        // hitting when, e.g., the Copilot model list was empty post-
        // restart and the form submitted with modelId="" -- they had
        // no idea which field was missing. Now each missing field
        // bounces back to the form with a specific named error.
        val missing = buildList<String> {
            if (targetType.isNullOrBlank())  add("targetType")
            if (provider.isNullOrBlank())    add("provider")
            if (modelId.isNullOrBlank())     add("modelId")
            if (appmapMode.isNullOrBlank())  add("appmapMode")
        }
        if (missing.isNotEmpty()) {
            val encoded = java.net.URLEncoder.encode(missing.joinToString(","), "UTF-8")
            return "redirect:/run?error=missing-fields&fields=$encoded"
        }
        // Re-bind to non-null locals now that we've validated.
        val targetTypeNN = targetType!!
        val providerNN = provider!!
        val modelIdNN = modelId!!
        val appmapModeNN = appmapMode!!
        // Defense-in-depth: even though the dropdown only surfaces
        // verified providers, the form could be POSTed by hand with
        // anything. Re-check the chosen provider/model is still in the
        // verified set before kicking off a run.
        val verified = registeredModelsRegistry.availableModels(session)
            .filter { it.provider != "appmap-navie" }
        val ok = verified.any { it.provider == providerNN && it.id == modelIdNN }
        if (!ok) {
            // Surface the picked combo so the form can show "we
            // couldn't verify modelId 'copilot-gpt-4-1' for provider
            // 'copilot' — register it on /llm or pick another."
            val pickedQ = "&picked=" + java.net.URLEncoder.encode("$providerNN/$modelIdNN", "UTF-8")
            return "redirect:/run?error=unverified$pickedQ"
        }

        // Build the issueId + title from the target type. Without this
        // the launch redirected to / and silently never started a run --
        // the form's inputs never reached BenchmarkRunService.start.
        val (issueId, issueTitle) = when (targetTypeNN.lowercase()) {
            "omnibank" -> {
                val id = bugId?.takeIf { it.isNotBlank() }
                    ?: return "redirect:/run?error=missing-bug"
                id to (omnibankIssueTitles[id] ?: id)
            }
            "enterprise" -> {
                val repo = repoName?.takeIf { it.isNotBlank() }
                    ?: return "redirect:/run?error=missing-repo"
                val ticket = jiraTicket?.takeIf { it.isNotBlank() } ?: "unspecified"
                "$repo:$ticket" to "$repo / $ticket"
            }
            else -> return "redirect:/run?error=unknown-target-type"
        }

        val run = benchmarkRuns.start(
            issueId = issueId,
            issueTitle = issueTitle,
            provider = providerNN,
            modelId = modelIdNN,
            contextProvider = contextProvider,
            appmapMode = appmapModeNN,
            seeds = seeds
        )
        // Stash for /demo's live panel + the dashboard's "active run"
        // pill, then send the operator straight to the per-run drilldown
        // so they can watch progress without manually navigating.
        session.setAttribute("activeRunId", run.id)
        return "redirect:/results/${run.id}"
    }
}
