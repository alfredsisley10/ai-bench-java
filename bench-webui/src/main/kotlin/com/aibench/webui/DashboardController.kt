package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.io.File

@Controller
class DashboardController(
    private val benchmarkRuns: BenchmarkRunService,
    private val registeredModels: RegisteredModelsRegistry
) {

    @GetMapping("/")
    fun dashboard(model: Model, session: HttpSession): String {
        val recent = benchmarkRuns.recentRuns(10)
        val total = recent.size
        val passed = recent.count { it.status.name == "PASSED" }

        model.addAttribute("totalRuns", total)
        model.addAttribute("passRate", if (total > 0) passed.toDouble() / total else 0.0)
        model.addAttribute("solvers", registeredModels.availableProviders(session))
        model.addAttribute("recentRuns", recent)
        model.addAttribute("connectedRepos", 0)
        model.addAttribute("availableBugs", countBugs())
        return "dashboard"
    }

    /**
     * Count `BUG-*.yaml` files in the repo's bugs/ directory. Walks up
     * from the working directory the same way BankingAppManager locates
     * banking-app/, so the dashboard works whether the WebUI is
     * launched from the repo root or from a subdirectory.
     */
    private fun countBugs(): Int {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val bugs = dir?.resolve("bugs")
            if (bugs != null && bugs.isDirectory) {
                return bugs.listFiles { f -> f.isFile && f.name.endsWith(".yaml") }?.size ?: 0
            }
            dir = dir?.parentFile
        }
        return 0
    }
}
