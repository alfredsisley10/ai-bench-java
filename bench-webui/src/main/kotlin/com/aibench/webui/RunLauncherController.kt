package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class RunLauncherController(
    private val registeredModelsRegistry: RegisteredModelsRegistry
) {

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
        @RequestParam targetType: String,
        @RequestParam(required = false) bugId: String?,
        @RequestParam(required = false) repoName: String?,
        @RequestParam(required = false) jiraTicket: String?,
        @RequestParam provider: String,
        @RequestParam modelId: String,
        @RequestParam(defaultValue = "none") contextProvider: String,
        @RequestParam appmapMode: String,
        @RequestParam(defaultValue = "3") seeds: Int,
        session: HttpSession
    ): String {
        // Defense-in-depth: even though the dropdown only surfaces
        // verified providers, the form could be POSTed by hand with
        // anything. Re-check the chosen provider/model is still in the
        // verified set before kicking off a run.
        val verified = registeredModelsRegistry.availableModels(session)
            .filter { it.provider != "appmap-navie" }
        val ok = verified.any { it.provider == provider && it.id == modelId }
        if (!ok) return "redirect:/run?error=unverified"
        return "redirect:/"
    }
}
