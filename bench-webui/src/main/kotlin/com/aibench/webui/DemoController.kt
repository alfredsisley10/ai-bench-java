package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.io.File

@Controller
class DemoController(
    private val bankingApp: BankingAppManager,
    private val registeredModelsRegistry: RegisteredModelsRegistry,
    private val benchmarkRuns: BenchmarkRunService
) {

    data class VerificationStep(
        val step: Int,
        val title: String,
        val description: String,
        val command: String?,
        val apiCall: String?,
        val expectedOutcome: String,
        val bugVisible: Boolean
    )

    data class DemoIssue(
        val id: String,
        val title: String,
        val module: String,
        val difficulty: String,
        val category: String,
        val problemStatement: String,
        val filesTouched: List<String>,
        val oracleDiffLines: Int,
        val hints: List<String>,
        val verificationSteps: List<VerificationStep> = emptyList(),
        val breakCommit: String = "",
        val fixCommit: String = "",
        val breakBranch: String = "",
        val fixBranch: String = ""
    )

    data class DemoRunStatus(
        val issueId: String,
        val phase: String,
        val detail: String,
        val complete: Boolean = false,
        val success: Boolean? = null
    )

    private val demoIssues = listOf(
        DemoIssue("BUG-0001", "ACH same-day cutoff off-by-one at final window", "payments-hub", "MEDIUM", "TIMING",
            "Customers report that ACH submissions made exactly at the published 4:45 PM ET same-day final cutoff are sometimes accepted into the same-day window and sometimes rejected.",
            listOf("payments-hub/src/main/java/com/omnibank/payments/ach/AchCutoffPolicy.java"), 2,
            listOf("Look at how the comparison operator interacts with the cutoff constant."),
            verificationSteps = listOf(
                VerificationStep(1, "Checkout the buggy code", "Switch to the break branch where the off-by-one bug exists.",
                    "cd banking-app && git checkout bug/BUG-0001/break", null,
                    "You are now on the branch with the bug. The cutoff comparison uses !isAfter() instead of isBefore().", bugVisible = true),
                VerificationStep(2, "View the buggy code", "Inspect the comparison in AchCutoffPolicy that causes the boundary issue.",
                    "cat banking-app/payments-hub/src/main/java/com/omnibank/payments/ach/AchCutoffPolicy.java | grep -A2 -B2 'isAfter\\|isBefore'", null,
                    "Notice: !isAfter(CUTOFF) means 'at or before cutoff' — but the spec says 'strictly before'. Submissions at exactly 16:45:00 are incorrectly accepted.", bugVisible = true),
                VerificationStep(3, "Run the tests (expect failure)", "The hidden test rejects_submission_exactly_at_final_cutoff should FAIL because the bug allows exact-cutoff submissions.",
                    "cd banking-app && ./gradlew :payments-hub:test 2>&1 | tail -20", null,
                    "The test rejects_submission_exactly_at_final_cutoff should FAIL — this proves the bug exists.", bugVisible = true),
                VerificationStep(4, "Checkout the fix", "Switch to the fix branch where the bug is resolved.",
                    "cd banking-app && git checkout bug/BUG-0001/fix", null,
                    "Now on the fix branch. The comparison is restored to isBefore() (strictly less than).", bugVisible = false),
                VerificationStep(5, "View the fixed code", "Confirm the fix: isBefore() excludes the cutoff boundary.",
                    "cat banking-app/payments-hub/src/main/java/com/omnibank/payments/ach/AchCutoffPolicy.java | grep -A2 -B2 'isBefore'", null,
                    "The comparison now uses .isBefore(CUTOFF) — submissions at exactly 16:45:00 are correctly rejected.", bugVisible = false),
                VerificationStep(6, "Run the tests (expect pass)", "All tests should pass now, including the hidden boundary test.",
                    "cd banking-app && ./gradlew :payments-hub:test 2>&1 | tail -20", null,
                    "All tests pass — the bug is fixed. The hidden test verifies the exact-cutoff boundary.", bugVisible = false),
                VerificationStep(7, "Return to baseline", "Reset to the main branch.",
                    "cd banking-app && git checkout main", null,
                    "Back on main. Ready for the next demo issue.", bugVisible = false)
            )),
        DemoIssue("BUG-0002", "Wire cutoff timezone defaults to UTC instead of ET", "payments-hub", "EASY", "TIMING",
            "Wire transfers submitted after 4 PM ET but before 4 PM UTC are incorrectly accepted into the same-day wire window.",
            listOf("payments-hub/src/main/java/com/omnibank/payments/wire/WireCutoffPolicy.java"), 1, emptyList(),
            verificationSteps = listOf(
                VerificationStep(1, "Checkout the buggy code", "Switch to the break branch with the timezone bug.",
                    "cd banking-app && git checkout bug/BUG-0002/break", null,
                    "The wire cutoff comparison now uses UTC instead of Eastern time.", bugVisible = true),
                VerificationStep(2, "View the buggy code", "The timezone is hardcoded to UTC instead of using the bank's ET zone.",
                    "cat banking-app/payments-hub/src/main/java/com/omnibank/payments/wire/WireCutoffPolicy.java | grep -A2 'atZone\\|inBankZone'", null,
                    "Notice: now.atZone(ZoneOffset.UTC) — should be Timestamp.inBankZone(now) for Eastern time.", bugVisible = true),
                VerificationStep(3, "Checkout the fix", "Switch to the fix branch.",
                    "cd banking-app && git checkout bug/BUG-0002/fix", null,
                    "Restored to Timestamp.inBankZone() for correct ET comparison.", bugVisible = false),
                VerificationStep(4, "Return to baseline", "Reset to main.",
                    "cd banking-app && git checkout main", null,
                    "Back on main.", bugVisible = false)
            )),
        DemoIssue("BUG-0003", "Trial balance invariant broken by uncommitted posting with mixed currency", "ledger-core", "HARD", "STATE_MACHINE",
            "An edge case in the validate() routine in PostingServiceImpl allows a mixed-currency journal entry to pass the single-currency check.",
            listOf("ledger-core/src/main/java/com/omnibank/ledger/internal/PostingServiceImpl.java"), 6, emptyList()),
        DemoIssue("BUG-0004", "Money.add allows negative-zero result in JPY", "shared-domain", "EASY", "NUMERIC",
            "Money.add can produce -0 as a BigDecimal value for JPY (zero-decimal currency), which causes display issues and hash mismatches.",
            listOf("shared-domain/src/main/java/com/omnibank/shared/domain/Money.java"), 2, emptyList()),
        DemoIssue("BUG-0005", "Consumer account open race condition loses balance hold", "accounts-consumer", "HARD", "CONCURRENCY",
            "When two threads simultaneously place a hold on a newly opened consumer account, one hold is silently dropped.",
            listOf("accounts-consumer/src/main/java/com/omnibank/accounts/consumer/internal/HoldServiceImpl.java"), 4, emptyList()),
        DemoIssue("BUG-0006", "Commercial loan covenant check skips final covenant in list", "lending-corporate", "MEDIUM", "LOGIC",
            "The covenant compliance check iterates covenants with an off-by-one error, silently skipping the last covenant.",
            listOf("lending-corporate/src/main/java/com/omnibank/lending/corporate/internal/CommercialLoanServiceImpl.java"), 1, emptyList()),
        DemoIssue("BUG-0007", "Ledger account lookup uses wrong cache key", "ledger-core", "MEDIUM", "DATA",
            "The GL account cache uses account code instead of the composite key (code + currency), causing cross-currency lookups to return stale data.",
            listOf("ledger-core/src/main/java/com/omnibank/ledger/internal/GlAccountRepository.java"), 3, emptyList()),
        DemoIssue("BUG-0008", "Payment routing falls through to null rail for book transfers", "payments-hub", "EASY", "LOGIC",
            "Book transfers (internal account-to-account) do not match any rail pattern and fall through to a null rail, causing an NPE at settlement.",
            listOf("payments-hub/src/main/java/com/omnibank/payments/internal/PaymentServiceImpl.java"), 3, emptyList()),
        DemoIssue("BUG-0009", "Amortization schedule double-counts final payment", "lending-corporate", "MEDIUM", "NUMERIC",
            "The amortization schedule calculator includes the balloon payment AND a regular payment in the final period.",
            listOf("lending-corporate/src/main/java/com/omnibank/lending/corporate/api/AmortizationCalculator.java"), 2, emptyList()),
        DemoIssue("BUG-0010", "Soft-delete filter not applied on batch account query", "shared-persistence", "MEDIUM", "DATA",
            "Batch account queries return soft-deleted records because the @Where clause is missing from the batch query path.",
            listOf("shared-persistence/src/main/java/com/omnibank/shared/persistence/AuditableEntity.java"), 1, emptyList()),
        DemoIssue("BUG-0011", "Admin console ledger inquiry leaks GL account data across tenants", "admin-console-api", "HARD", "SECURITY",
            "The admin ledger inquiry endpoint does not filter by the requesting user's tenant, exposing GL data across organizational boundaries.",
            listOf("admin-console-api/src/main/java/com/omnibank/adminconsole/LedgerInquiryController.java"), 3, emptyList()),
        DemoIssue("BUG-0012", "RTP payment idempotency check uses mutable timestamp", "payments-hub", "CROSS_CUTTING", "CONCURRENCY",
            "The RTP idempotency dedup window uses System.currentTimeMillis() at check time instead of the payment's creation timestamp, causing replay acceptance under clock skew.",
            listOf("payments-hub/src/main/java/com/omnibank/payments/internal/PaymentServiceImpl.java"), 5, emptyList())
    )

    data class AppStats(
        val modules: Int = 0,
        val javaFiles: Int = 0,
        val linesOfCode: Int = 0,
        val testFiles: Int = 0,
        val migrationFiles: Int = 0,
        val totalSizeMb: Double = 0.0,
        val jmsTopics: Int = 0,
        val jmsQueues: Int = 0,
        val eventStores: Int = 0,
        val cacheRegions: Int = 0,
        val circuitBreakers: Int = 0,
        val sagaOrchestrators: Int = 0,
        val springServices: Int = 0,
        val jpaEntities: Int = 0,
        val restEndpoints: Int = 0
    )

    private fun computeStats(dir: File): AppStats = runCatching {
        val modules = dir.listFiles()?.count { it.isDirectory && it.resolve("src").isDirectory } ?: 0
        var javaFiles = 0
        var loc = 0
        var testFiles = 0
        var migrations = 0
        var totalBytes = 0L
        var jmsTopics = 0
        var jmsQueues = 0
        var eventStores = 0
        var cacheRegions = 0
        var circuitBreakers = 0
        var sagaOrchestrators = 0
        var springServices = 0
        var jpaEntities = 0
        var restEndpoints = 0

        dir.walkTopDown().filter { it.isFile && !it.path.contains("/build/") && !it.path.contains("/.git/") }.forEach { f ->
            totalBytes += f.length()
            when {
                f.extension == "java" && f.path.contains("src/main") -> {
                    javaFiles++
                    val content = f.readText()
                    loc += content.lines().size
                    if (content.contains("@Service")) springServices++
                    if (content.contains("@Entity") || content.contains("@MappedSuperclass")) jpaEntities++
                    if (content.contains("@GetMapping") || content.contains("@PostMapping") || content.contains("@RestController")) {
                        restEndpoints += Regex("@(Get|Post|Put|Delete|Patch)Mapping").findAll(content).count()
                    }
                    if (content.contains("Topic") && content.contains("events")) jmsTopics += Regex("\"[a-z]+\\.events\"").findAll(content).count()
                    if (content.contains("Queue") && (content.contains("dead-letter") || content.contains("dlq") || content.contains(".queue"))) jmsQueues++
                    if (content.contains("EventStore") && content.contains("class ")) eventStores++
                    if (content.contains("Cache") && content.contains("Region") || content.contains("CacheConfig")) cacheRegions += Regex("\"[a-z-]+\"").findAll(content).count().coerceAtMost(10)
                    if (content.contains("CircuitBreaker") && content.contains("class ")) circuitBreakers++
                    if (content.contains("SagaOrchestrator") && content.contains("class ")) sagaOrchestrators++
                }
                f.extension == "java" && f.path.contains("src/test") -> { testFiles++; loc += f.readLines().size }
                f.extension == "sql" && f.path.contains("migration") -> migrations++
            }
        }
        val sizeMb = totalBytes / (1024.0 * 1024.0)
        AppStats(modules, javaFiles, loc, testFiles, migrations, Math.round(sizeMb * 10.0) / 10.0,
            jmsTopics, jmsQueues, eventStores, cacheRegions, circuitBreakers, sagaOrchestrators,
            springServices, jpaEntities, restEndpoints)
    }.getOrDefault(AppStats())

    private fun bankingAppDir(): File = bankingApp.bankingAppDir

    private fun resolveCommit(dir: File, ref: String): String = runCatching {
        val proc = ProcessBuilder("git", "rev-parse", "--short", ref)
            .directory(dir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0) out else ""
    }.getOrDefault("")

    private fun branchExists(dir: File, branch: String): Boolean = runCatching {
        val proc = ProcessBuilder("git", "rev-parse", "--verify", branch)
            .directory(dir).redirectErrorStream(true).start()
        proc.waitFor() == 0
    }.getOrDefault(false)

    fun issuesWithCommits(): List<DemoIssue> {
        val dir = bankingAppDir()
        return demoIssues.map { issue ->
            val breakBranch = "bug/${issue.id}/break"
            val fixBranch = "bug/${issue.id}/fix"
            issue.copy(
                breakCommit = resolveCommit(dir, breakBranch),
                fixCommit = resolveCommit(dir, fixBranch),
                breakBranch = if (branchExists(dir, breakBranch)) breakBranch else "",
                fixBranch = if (branchExists(dir, fixBranch)) fixBranch else ""
            )
        }
    }

    @GetMapping("/demo")
    fun demo(model: Model, session: HttpSession): String {
        val dir = bankingAppDir()
        val issues = issuesWithCommits()
        model.addAttribute("issues", issues)
        model.addAttribute("bankingAppPath", dir.absolutePath)
        model.addAttribute("bankingAppRepo", "alfredsisley10/omnibank-demo")
        model.addAttribute("baselineCommit", resolveCommit(dir, "main"))
        model.addAttribute("appStatus", bankingApp.status().name)
        model.addAttribute("appUrl", bankingApp.url)
        model.addAttribute("appPort", bankingApp.port)
        model.addAttribute("stats", computeStats(dir))

        val runStatus = session.getAttribute("demoRunStatus") as? DemoRunStatus
        model.addAttribute("runStatus", runStatus)
        session.removeAttribute("demoRunStatus")

        // Quick benchmark dropdown sources — pulled from the shared
        // registry so the auto-derived `copilot-default` and
        // `corp-openai-default` entries (only present when their
        // underlying provider is actually reachable) show up here too,
        // not just on the LLMs page.
        //
        // AppMap Navie is filtered out of the LLM provider/model lists
        // because it is a context provider, not an LLM. It surfaces
        // under a separate "Context provider" dropdown so the user
        // still picks an underlying LLM when running a Navie-mediated
        // benchmark.
        val allModels = registeredModelsRegistry.availableModels(session)
        val llmModels = allModels.filter { it.provider != "appmap-navie" }
        val llmProviders = llmModels.map { it.provider }.distinct().sorted()
        val navieAvailable = allModels.any { it.provider == "appmap-navie" }
        model.addAttribute("registeredModels", llmModels)
        model.addAttribute("registeredProviders", llmProviders)
        model.addAttribute("navieAvailable", navieAvailable)

        // Active benchmark run (if any) — htmx panel polls this id
        // every second until the run reaches a terminal state.
        val activeRunId = session.getAttribute("activeRunId") as? String
        val activeRun = activeRunId?.let { benchmarkRuns.get(it) }
        if (activeRun == null && activeRunId != null) {
            session.removeAttribute("activeRunId")
        }
        model.addAttribute("activeRunId", activeRunId)
        model.addAttribute("recentRuns", benchmarkRuns.recentRuns(5))
        return "demo"
    }

    @PostMapping("/demo/app/start")
    fun startApp(session: HttpSession): String {
        val msg = bankingApp.start()
        session.setAttribute("demoRunStatus", DemoRunStatus("app", "start", msg))
        return "redirect:/demo"
    }

    @PostMapping("/demo/app/stop")
    fun stopApp(session: HttpSession): String {
        val msg = bankingApp.stop()
        session.setAttribute("demoRunStatus", DemoRunStatus("app", "stop", msg))
        return "redirect:/demo"
    }

    @GetMapping("/demo/app/status-fragment")
    fun appStatusFragment(model: Model): String {
        model.addAttribute("appStatus", bankingApp.status().name)
        model.addAttribute("appUrl", bankingApp.url)
        model.addAttribute("appPort", bankingApp.port)
        return "fragments/app-status :: appStatus"
    }

    /**
     * Open the banking app pre-authenticated. Mints a fresh
     * single-use URL containing the in-memory autologin token, then
     * 302s the browser to it. The banking-app's
     * OneTimeAutologinFilter consumes the token, sets a session
     * cookie, and redirects to the requested path. The token is
     * generated when the banking-app started and is held only in
     * memory (no disk write); it's invalidated on first successful
     * exchange so the URL can't be replayed.
     */
    @GetMapping("/demo/app/open")
    fun openApp(@RequestParam(defaultValue = "/") redirect: String): String {
        if (bankingApp.status() != BankingAppManager.Status.RUNNING) {
            return "redirect:/demo"
        }
        val url = bankingApp.autologinUrl(redirect)
            ?: return "redirect:" + bankingApp.url
        return "redirect:$url"
    }

    @GetMapping("/demo/app/log")
    @org.springframework.web.bind.annotation.ResponseBody
    fun appLog(@RequestParam(defaultValue = "80") lines: Int): String {
        return bankingApp.logTail(lines)
    }

    @PostMapping("/demo/prepare/{issueId}")
    fun prepareIssue(@PathVariable issueId: String, session: HttpSession): String {
        val issue = demoIssues.firstOrNull { it.id == issueId }
        if (issue == null) {
            session.setAttribute("demoRunStatus", DemoRunStatus(issueId, "error", "Unknown issue: $issueId", complete = true, success = false))
            return "redirect:/demo"
        }
        session.setAttribute("demoRunStatus",
            DemoRunStatus(issueId, "prepared", "Prepared worktree at bug/${issueId}/break. Banking app ready for solving.", complete = false))
        return "redirect:/demo"
    }

    @PostMapping("/demo/run/{issueId}")
    fun runBenchmark(
        @PathVariable issueId: String,
        @RequestParam provider: String,
        @RequestParam modelId: String,
        // "none" means run the LLM directly. "appmap-navie" inserts
        // Navie's AppMap-driven retrieval in front of the LLM but does
        // not change which LLM provider/model is called.
        @RequestParam(defaultValue = "none") contextProvider: String,
        @RequestParam(defaultValue = "ON_RECOMMENDED") appmapMode: String,
        @RequestParam(defaultValue = "3") seeds: Int,
        session: HttpSession
    ): String {
        val issue = demoIssues.firstOrNull { it.id == issueId }
        if (issue == null) {
            session.setAttribute("demoRunStatus", DemoRunStatus(issueId, "error", "Unknown issue: $issueId", complete = true, success = false))
            return "redirect:/demo"
        }
        val run = benchmarkRuns.start(issue.id, issue.title, provider, modelId,
            contextProvider, appmapMode, seeds)
        // Stash the active run id so /demo's live panel knows which
        // run to poll.
        session.setAttribute("activeRunId", run.id)
        return "redirect:/demo#live-run"
    }

    /**
     * htmx-polled live-status fragment. Returns a small HTML chunk
     * showing the run's current phase, progress bar, stats, and the
     * last ~40 log entries. The fragment self-refreshes every 1s
     * while the run is still in flight; once terminal it stops
     * polling so the client doesn't hammer the server forever.
     */
    @GetMapping("/demo/run/{runId}/panel")
    fun runPanel(@PathVariable runId: String, model: Model): String {
        val run = benchmarkRuns.get(runId)
        model.addAttribute("run", run)
        model.addAttribute("runId", runId)
        return "fragments/run-panel :: panel"
    }

    /** JSON status — for scripts / programmatic polling. */
    @GetMapping("/demo/run/{runId}/status")
    @org.springframework.web.bind.annotation.ResponseBody
    fun runStatus(@PathVariable runId: String): Map<String, Any?> {
        val run = benchmarkRuns.get(runId) ?: return mapOf("error" to "unknown run")
        return mapOf(
            "id" to run.id, "issueId" to run.issueId, "issueTitle" to run.issueTitle,
            "provider" to run.provider, "modelId" to run.modelId,
            "contextProvider" to run.contextProvider,
            "appmapMode" to run.appmapMode, "seeds" to run.seeds,
            "status" to run.status.name, "phase" to run.phase,
            "currentSeed" to run.currentSeed, "percent" to run.percent,
            "startedAt" to run.startedAt.toString(),
            "endedAt" to run.endedAt?.toString(),
            "durationMs" to run.durationMs,
            "stats" to mapOf(
                "tracesRecorded" to run.stats.tracesRecorded,
                "tracesSubmitted" to run.stats.tracesSubmitted,
                "sourceFilesSubmitted" to run.stats.sourceFilesSubmitted,
                "totalPromptTokens" to run.stats.totalPromptTokens,
                "totalCompletionTokens" to run.stats.totalCompletionTokens,
                "estimatedCostUsd" to run.stats.estimatedCostUsd
            ),
            "seedResults" to run.seedResults,
            "log" to run.logEntries.takeLast(80).map {
                mapOf("ts" to it.ts.toString(), "category" to it.category.name, "message" to it.message)
            }
        )
    }

    @PostMapping("/demo/run/{runId}/cancel")
    fun runCancel(@PathVariable runId: String): String {
        benchmarkRuns.cancel(runId)
        return "redirect:/demo#live-run"
    }

    @GetMapping("/demo/{issueId}")
    fun issueDetail(@PathVariable issueId: String, model: Model): String {
        val issues = issuesWithCommits()
        val issue = issues.firstOrNull { it.id == issueId }
        model.addAttribute("issue", issue)
        return "demo-detail"
    }

    @PostMapping("/demo/exec")
    @org.springframework.web.bind.annotation.ResponseBody
    fun executeCommand(@RequestParam command: String): Map<String, Any> {
        val allowed = command.startsWith("cd banking-app") ||
            command.startsWith("cat banking-app") ||
            command.startsWith("grep ") ||
            command.startsWith("git -C banking-app") ||
            command.contains("./gradlew")

        if (!allowed) {
            return mapOf("exitCode" to -1, "output" to "Command not allowed. Only banking-app commands are permitted.")
        }

        val projectRoot = bankingAppDir().parentFile ?: File(System.getProperty("user.dir"))
        return runCatching {
            val proc = ProcessBuilder("bash", "-c", command)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exited = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                return mapOf("exitCode" to -1, "output" to output + "\n(command timed out after 60s)")
            }
            mapOf("exitCode" to proc.exitValue(), "output" to output)
        }.getOrElse {
            mapOf("exitCode" to -1, "output" to "Error: ${it.message}")
        }
    }
}
