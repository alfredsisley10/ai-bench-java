package com.aibench.webui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class DashboardController {

    @GetMapping("/")
    fun dashboard(model: Model): String {
        model.addAttribute("totalRuns", 0)
        model.addAttribute("passRate", 0.0)
        model.addAttribute("solvers", listOf("corp-openai", "copilot"))
        model.addAttribute("recentRuns", emptyList<Any>())
        model.addAttribute("connectedRepos", 0)
        model.addAttribute("availableBugs", 12)
        return "dashboard"
    }
}
