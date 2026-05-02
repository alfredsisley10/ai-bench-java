package com.aibench.webui

import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Surfaces BugLintService output. HTML page for the operator;
 * JSON sibling for CI hooks (curl ... | jq '.highCount' and fail
 * non-zero).
 */
@Controller
class BugLintController(
    private val bugCatalog: BugCatalog,
    private val lintService: BugLintService
) {

    data class BugReport(
        val bug: BugCatalog.BugMetadata,
        val findings: List<BugLintService.LintFinding>,
        val highCount: Int,
        val mediumCount: Int,
        val infoCount: Int
    )

    private fun reports(): List<BugReport> {
        return bugCatalog.allBugs().map { bug ->
            val f = lintService.lint(bug)
            BugReport(
                bug = bug,
                findings = f,
                highCount = f.count { it.severity == BugLintService.Severity.HIGH },
                mediumCount = f.count { it.severity == BugLintService.Severity.MEDIUM },
                infoCount = f.count { it.severity == BugLintService.Severity.INFO }
            )
        }.sortedWith(
            compareByDescending<BugReport> { it.highCount }
                .thenByDescending { it.mediumCount }
                .thenBy { it.bug.id }
        )
    }

    @GetMapping("/admin/bug-lint")
    fun page(model: Model): String {
        val all = reports()
        val flagged = all.filter { it.findings.isNotEmpty() }
        model.addAttribute("totalBugs", all.size)
        model.addAttribute("flagged", flagged)
        model.addAttribute("flaggedCount", flagged.size)
        model.addAttribute("highCount", all.sumOf { it.highCount })
        model.addAttribute("mediumCount", all.sumOf { it.mediumCount })
        model.addAttribute("infoCount", all.sumOf { it.infoCount })
        return "admin-bug-lint"
    }

    @GetMapping("/admin/bug-lint.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun json(): Map<String, Any> {
        val all = reports()
        return mapOf(
            "totalBugs" to all.size,
            "flagged" to all.count { it.findings.isNotEmpty() },
            "highCount" to all.sumOf { it.highCount },
            "mediumCount" to all.sumOf { it.mediumCount },
            "infoCount" to all.sumOf { it.infoCount },
            "reports" to all.map { r ->
                mapOf(
                    "bugId" to r.bug.id,
                    "title" to r.bug.title,
                    "highCount" to r.highCount,
                    "mediumCount" to r.mediumCount,
                    "infoCount" to r.infoCount,
                    "findings" to r.findings.map { f ->
                        mapOf(
                            "severity" to f.severity.name,
                            "rule" to f.rule,
                            "source" to f.source.name,
                            "sourceIndex" to f.sourceIndex,
                            "offendingToken" to f.offendingToken,
                            "expected" to f.expected,
                            "contextLine" to f.contextLine,
                            "suggestion" to f.suggestion
                        )
                    }
                )
            }
        )
    }
}
