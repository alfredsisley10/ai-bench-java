package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class RunLauncherController {

    @GetMapping("/run")
    fun form(model: Model, session: HttpSession): String {
        model.addAttribute("providers", listOf("corp-openai", "copilot"))
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

        @Suppress("UNCHECKED_CAST")
        val sessionModels = session.getAttribute("llmModels") as? List<LlmConfigController.ModelInfo> ?: emptyList()
        val allModels = mutableListOf<Map<String, String>>()
        allModels.add(mapOf("id" to "copilot-default", "name" to "Copilot (default)", "provider" to "copilot"))
        allModels.add(mapOf("id" to "corp-openai-default", "name" to "Corporate OpenAI (default)", "provider" to "corp-openai"))
        sessionModels.forEach { allModels.add(mapOf("id" to it.id, "name" to it.displayName, "provider" to it.provider)) }
        model.addAttribute("models", allModels)

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
        @RequestParam appmapMode: String,
        @RequestParam(defaultValue = "3") seeds: Int
    ): String {
        return "redirect:/results"
    }
}
