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
    private val benchmarkRuns: BenchmarkRunService,
    private val bridgeBudget: BridgeBudgetService,
    private val costOptSupervisor: CostOptimizedLaunchSupervisor
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

        // Form defaults — pre-select the operator's most-common picks so
        // launching a benchmark needs only a couple of clicks. Each
        // default is *conditional*: it only takes effect when the value
        // actually exists in the rendered dropdown for the current
        // session, so a Copilot-less environment doesn't end up with
        // "copilot" pre-selected and then "no model available" below.
        val preferredProvider = "copilot"
        val preferredModelId = "copilot-gpt-4-1"
        val defaultProvider =
            if (verifiedProviders.contains(preferredProvider)) preferredProvider else ""
        val defaultModelId =
            if (modelRows.any { it["id"] == preferredModelId && it["provider"] == defaultProvider })
                preferredModelId else ""
        val defaultContextProvider =
            if (allModels.any { it.provider == "appmap-navie" }) "appmap-navie" else "none"
        model.addAttribute("defaultTargetType", "omnibank")
        model.addAttribute("defaultBugId", "BUG-0001")
        model.addAttribute("defaultProvider", defaultProvider)
        model.addAttribute("defaultModelId", defaultModelId)
        model.addAttribute("defaultContextProvider", defaultContextProvider)
        // appmapMode=OFF + seeds=1 are the right defaults for the
        // "I just want to validate the launcher works" case. ON_*
        // modes pay AppMap's recording cost which most operators only
        // want once they've confirmed the basic chain works.
        model.addAttribute("defaultAppmapMode", "OFF")
        model.addAttribute("defaultSeeds", 1)

        // Bridge token-budget snapshot for the launcher's "you have X
        // tokens remaining this month" tile. Null when the bridge is
        // offline -- the template renders a "bridge offline" hint
        // instead, since the harness can still launch (it'll fall back
        // to simulated tokens).
        model.addAttribute("budgetSnapshot", bridgeBudget.snapshot())

        return "run-launcher"
    }

    @PostMapping("/run/launch")
    fun launch(
        @RequestParam(required = false) targetType: String?,
        @RequestParam(required = false) bugId: String?,
        @RequestParam(required = false) bugIds: List<String>?,
        @RequestParam(required = false) repoName: String?,
        @RequestParam(required = false) jiraTicket: String?,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) modelId: String?,
        @RequestParam(required = false) modelIds: List<String>?,
        @RequestParam(required = false) contextProvider: String?,
        @RequestParam(required = false) contextProviders: List<String>?,
        @RequestParam(required = false) appmapMode: String?,
        @RequestParam(required = false) appmapModes: List<String>?,
        @RequestParam(defaultValue = "3") seeds: Int,
        @RequestParam(defaultValue = "FULL_MATRIX") launchMode: String,
        session: HttpSession
    ): String {
        // Coalesce single + multi inputs across every dimension. The legacy
        // singular form names (bugId/modelId/contextProvider/appmapMode) stay
        // accepted so curl scripts that POSTed the old shape keep working.
        val pickedModelIds = (modelIds.orEmpty() + listOfNotNull(modelId))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val pickedBugIds = (bugIds.orEmpty() + listOfNotNull(bugId))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val pickedContexts = (contextProviders.orEmpty() + listOfNotNull(contextProvider))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty { listOf("none") }
        // AppMap modes are now orthogonal to context providers — Navie
        // SEARCHES traces but doesn't CREATE them, so the mode is just as
        // relevant for navie context as for oracle/bm25/none. Default to
        // OFF when no mode is explicitly selected.
        val pickedAppmap = (appmapModes.orEmpty() + listOfNotNull(appmapMode))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty { listOf("OFF") }

        val missing = buildList<String> {
            if (targetType.isNullOrBlank())  add("targetType")
            if (provider.isNullOrBlank())    add("provider")
            if (pickedModelIds.isEmpty())    add("modelId")
            if (pickedAppmap.isEmpty())      add("appmapMode")
        }
        if (missing.isNotEmpty()) {
            val encoded = java.net.URLEncoder.encode(missing.joinToString(","), "UTF-8")
            return "redirect:/run?error=missing-fields&fields=$encoded"
        }
        val targetTypeNN = targetType!!
        val providerNN = provider!!

        // Defense-in-depth: re-verify every (provider, model) pick against the
        // live registry. Reject the whole launch if any pick is unverified —
        // partial launches make the dashboard confusing.
        val verified = registeredModelsRegistry.availableModels(session)
            .filter { it.provider != "appmap-navie" }
        val resolvedModels = pickedModelIds.map { mid ->
            val m = verified.firstOrNull { it.provider == providerNN && it.id == mid }
            if (m == null) {
                val pickedQ = "&picked=" + java.net.URLEncoder.encode("$providerNN/$mid", "UTF-8")
                return "redirect:/run?error=unverified$pickedQ"
            }
            m
        }

        // Resolve target → list of (issueId, issueTitle) tuples. Multi-bug only
        // applies to omnibank; enterprise is single-target (repoName + ticket).
        val targets: List<Pair<String, String>> = when (targetTypeNN.lowercase()) {
            "omnibank" -> {
                val ids = pickedBugIds.ifEmpty { return "redirect:/run?error=missing-bug" }
                ids.map { id -> id to (omnibankIssueTitles[id] ?: id) }
            }
            "enterprise" -> {
                val repo = repoName?.takeIf { it.isNotBlank() }
                    ?: return "redirect:/run?error=missing-repo"
                val ticket = jiraTicket?.takeIf { it.isNotBlank() } ?: "unspecified"
                listOf("$repo:$ticket" to "$repo / $ticket")
            }
            else -> return "redirect:/run?error=unknown-target-type"
        }

        // Full cross-product: bug × model × context × appmap. Every
        // combination produces one run; the AppMap mode controls which
        // traces the run sees regardless of context. The TraceManager
        // shares trace artifacts across runs that need the same coverage,
        // so launching e.g. {oracle,navie} × {ON_RECOMMENDED} only
        // generates the trace set once.
        val launched = mutableListOf<String>()
        if (launchMode.equals("COST_OPTIMIZED", ignoreCase = true)) {
            // Cost-optimized: build per-(bug, seed) tuple lists, hand to
            // the supervisor which fires the cheapest first and skips the
            // rest once a passing solver is found for that bug+seed.
            val groups = HashMap<Pair<String, Int>, MutableList<CostOptimizedLaunchSupervisor.Tuple>>()
            for ((issueId, issueTitle) in targets) {
                for (seed in 1..seeds) {
                    val list = groups.getOrPut(issueId to seed) { mutableListOf() }
                    for (m in resolvedModels) {
                        for (ctx in pickedContexts) {
                            for (am in pickedAppmap) {
                                list += CostOptimizedLaunchSupervisor.Tuple(
                                    issueId = issueId,
                                    issueTitle = issueTitle,
                                    provider = providerNN,
                                    modelId = m.id,
                                    modelIdentifier = m.modelIdentifier,
                                    contextProvider = ctx,
                                    appmapMode = am,
                                    costScore = costOptSupervisor.costScore(m.id, ctx, am)
                                )
                            }
                        }
                    }
                }
            }
            val result = costOptSupervisor.submit(groups)
            launched.addAll(result.initialRunIds)
        } else {
            // Full matrix: every combination produces one run up-front.
            // Operator picks this for cost/speed regression curves.
            for ((issueId, issueTitle) in targets) {
                for (m in resolvedModels) {
                    for (ctx in pickedContexts) {
                        for (am in pickedAppmap) {
                            val run = benchmarkRuns.start(
                                issueId = issueId,
                                issueTitle = issueTitle,
                                provider = providerNN,
                                modelId = m.id,
                                modelIdentifier = m.modelIdentifier,
                                contextProvider = ctx,
                                appmapMode = am,
                                seeds = seeds
                            )
                            launched.add(run.id)
                        }
                    }
                }
            }
        }

        // Single launch → drill into its detail page like before. Multi-launch
        // → bounce to the dashboard so the operator sees all of them at once.
        return if (launched.size == 1) {
            session.setAttribute("activeRunId", launched.first())
            "redirect:/results/${launched.first()}"
        } else {
            session.setAttribute("activeRunId", launched.first())
            "redirect:/?launched=${launched.size}"
        }
    }
}
