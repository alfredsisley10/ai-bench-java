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
    private val benchmarkRuns: BenchmarkRunService,
    private val connectionSettings: ConnectionSettings
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

        // Use invariantSeparatorsPath so the contains() checks work
        // identically on Windows ("\") and Unix ("/"). f.path returns
        // the platform-native form, which silently zero'd every count
        // on Windows because paths look like "src\main\..." there.
        dir.walkTopDown().filter {
            val ip = it.invariantSeparatorsPath
            it.isFile && !ip.contains("/build/") && !ip.contains("/.git/")
        }.forEach { f ->
            totalBytes += f.length()
            val ip = f.invariantSeparatorsPath
            when {
                f.extension == "java" && ip.contains("src/main") -> {
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
                f.extension == "java" && ip.contains("src/test") -> { testFiles++; loc += f.readLines().size }
                f.extension == "sql" && ip.contains("migration") -> migrations++
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
        val location = bankingApp.location
        val dir = location.resolved
        val issues = issuesWithCommits()
        model.addAttribute("issues", issues)
        model.addAttribute("bankingAppPath", dir.absolutePath)
        model.addAttribute("bankingAppRepo", "alfredsisley10/omnibank-demo")
        // Diagnostic surfaces — populated whether the location resolved or not.
        model.addAttribute("bankingAppLocation", location)
        model.addAttribute("bankingAppFound", location.hasGradle)
        model.addAttribute("javaVersion", System.getProperty("java.version") ?: "?")
        model.addAttribute("javaVendor",  System.getProperty("java.vendor") ?: "?")
        model.addAttribute("javaHome",    System.getProperty("java.home") ?: "?")
        model.addAttribute("verifyResult", session.getAttribute("bankingAppVerifyResult"))
        session.removeAttribute("bankingAppVerifyResult")
        model.addAttribute("javaVerifyResult", session.getAttribute("bankingAppJavaVerifyResult"))
        session.removeAttribute("bankingAppJavaVerifyResult")
        // JDK discovery + toolchain requirement, surfaced together so
        // the user can spot a mismatch *before* clicking Verify.
        val jdks = JdkDiscovery.discover()
        val toolchainMajor = bankingApp.toolchainMajor()
        val currentJavaHome = System.getenv("JAVA_HOME")
        // Saved default wins. If it's still in the discovery list, use
        // it; if the saved path no longer resolves, readSavedDefaultHome
        // self-cleans the file and returns null so we fall back below.
        val savedDefault = JdkDiscovery.readSavedDefaultHome()
            ?.takeIf { saved -> jdks.any { it.home == saved } }
        val preferredJdkHome = savedDefault
            ?: jdks.firstOrNull { toolchainMajor != null && it.major == toolchainMajor }?.home
            ?: jdks.firstOrNull { it.home == currentJavaHome }?.home
            ?: jdks.firstOrNull()?.home
        model.addAttribute("availableJdks", jdks.map {
            mapOf("home" to it.home, "major" to it.major, "label" to it.label,
                  "versionLine" to it.versionLine, "source" to it.source)
        })
        model.addAttribute("toolchainMajor", toolchainMajor ?: -1)
        model.addAttribute("preferredJdkHome", preferredJdkHome ?: "")
        model.addAttribute("savedDefaultJdkHome", savedDefault ?: "")
        model.addAttribute("toolchainMismatch",
            toolchainMajor != null && jdks.none { it.major == toolchainMajor })
        // Skip slow ops (git rev-parse, full file walk) when the app isn't
        // detected — they'd just produce more noise on top of the warning.
        model.addAttribute("baselineCommit", if (location.hasGradle) resolveCommit(dir, "main") else "")
        model.addAttribute("appStatus", bankingApp.status().name)
        model.addAttribute("appUrl", bankingApp.url)
        model.addAttribute("appPort", bankingApp.port)
        model.addAttribute("stats", if (location.hasGradle) computeStats(dir) else AppStats())

        val runStatus = session.getAttribute("demoRunStatus") as? DemoRunStatus
        model.addAttribute("runStatus", runStatus)
        session.removeAttribute("demoRunStatus")

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

    @PostMapping("/demo/banking-app/verify")
    fun verifyBankingApp(
        @RequestParam(required = false) jdkPath: String?,
        session: HttpSession
    ): String {
        session.setAttribute("bankingAppVerifyResult", bankingApp.verifyGradle(jdkPath))
        return "redirect:/demo#banking-app-verify"
    }

    @PostMapping("/demo/banking-app/verify-java")
    fun verifyJava(
        @RequestParam(required = false) jdkPath: String?,
        session: HttpSession
    ): String {
        session.setAttribute("bankingAppJavaVerifyResult", bankingApp.verifyJava(jdkPath))
        return "redirect:/demo#banking-app-verify"
    }

    /**
     * Persist a user-supplied JDK path so it shows up in the dropdown
     * even though our standard scan didn't find it (portable JDK,
     * non-standard install root, USB drive, etc.). Returns JSON so the
     * page can display a success/failure banner inline.
     */
    @PostMapping("/demo/banking-app/jdk/add")
    @org.springframework.web.bind.annotation.ResponseBody
    fun addCustomJdk(@RequestParam path: String): Map<String, Any> {
        val jdk = JdkDiscovery.addCustomPath(path)
            ?: return mapOf(
                "ok" to false,
                "message" to "That path doesn't contain a runnable `bin/java` (or it's not a JDK). " +
                    "Pass the JAVA_HOME-style root (the dir whose `bin/` holds java/java.exe).",
                "home" to "", "label" to ""
            )
        return mapOf(
            "ok" to true,
            "message" to "Added ${jdk.label}. Refresh the page to pick it in the dropdown.",
            "home" to jdk.home, "label" to jdk.label
        )
    }

    /**
     * Recursively scan the given folder for any JDK home and persist
     * each hit. Used by the "Scan this folder for JDKs" button on the
     * /demo page so the operator can point at a parent directory
     * (e.g. <code>C:\Program Files\Java</code>) and let the WebUI
     * find every install underneath without having to type each
     * <code>jdk-NN.x.y</code> path individually.
     */
    /**
     * Persist [path] as the user's default JDK. Survives webui
     * restarts; validated on every page render so a moved or
     * deleted JDK is auto-cleared instead of silently mis-pointing
     * the build.
     */
    @PostMapping("/demo/banking-app/jdk/save-default")
    @org.springframework.web.bind.annotation.ResponseBody
    fun saveDefaultJdk(@RequestParam path: String): Map<String, Any> {
        val ok = JdkDiscovery.saveDefaultHome(path)
        return mapOf(
            "ok" to ok,
            "message" to if (ok)
                "Saved as default — ~/.ai-bench/default-jdk-home.txt. Survives webui restarts."
            else
                "Path doesn't contain a runnable bin/java; default not changed."
        )
    }

    @PostMapping("/demo/banking-app/jdk/clear-default")
    @org.springframework.web.bind.annotation.ResponseBody
    fun clearDefaultJdk(): Map<String, Any> {
        JdkDiscovery.clearDefaultHome()
        return mapOf("ok" to true,
            "message" to "Default cleared. Dropdown will auto-pick on next render.")
    }

    /**
     * Create placeholder <code>bug/&lt;id&gt;/break</code> and
     * <code>bug/&lt;id&gt;/fix</code> branches from <code>main</code>
     * for every demoIssue. Each branch is empty (no diff) — real bug
     * patches still have to be committed by hand or by external
     * tooling. The point of this button is to unblock the UI flow
     * (Quick benchmark dropdown, prepare/run buttons) on a freshly
     * cloned banking-app, so the operator can exercise the WebUI
     * without first hand-running a seeding script that doesn't ship
     * with the repo.
     *
     * <p>Existing branches are left alone (idempotent). Returns a
     * verbose log block so a failure on any one issue surfaces
     * inline rather than silently leaving the dropdown half-greyed.
     */
    @PostMapping("/demo/banking-app/seed-bug-branches")
    @org.springframework.web.bind.annotation.ResponseBody
    fun seedBugBranches(): Map<String, Any> {
        val dir = bankingAppDir()
        val log = StringBuilder()
        // banking-app may either be its own git repo (typical when
        // cloned from omnibank-demo) or a regular subdirectory of a
        // larger repo (this checkout's case — banking-app/ is a
        // subdir of ai-bench-java/.git). In both cases `git` commands
        // run inside the dir resolve to whichever .git applies, so
        // the rev-parse / branch ops below work regardless. We only
        // bail if no git context can be found at all.
        val isInsideRepo = runCatching {
            val p = ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                .directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor() == 0 && out == "true"
        }.getOrDefault(false)
        if (!isInsideRepo) {
            return mapOf(
                "ok" to false,
                "message" to "${dir.absolutePath} is not inside any git repository.",
                "log" to "[err] git rev-parse --is-inside-work-tree returned non-true at ${dir.absolutePath}\n"
            )
        }

        // 1. Resolve current HEAD commit so we can re-create branches at
        //    the same point. Doesn't require any active branch — works
        //    even on detached-HEAD checkouts.
        val headCommit = runCatching {
            val proc = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(dir).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0 && out.isNotBlank()) out else null
        }.getOrNull()
        if (headCommit == null) {
            return mapOf(
                "ok" to false,
                "message" to "Could not resolve HEAD in ${dir.absolutePath}.",
                "log" to "[err] git rev-parse HEAD failed\n"
            )
        }
        log.appendLine("[info] base commit (HEAD): $headCommit")
        log.appendLine("[info] seeding placeholder bug branches for ${demoIssues.size} demo issues")
        log.appendLine()

        var created = 0
        var skipped = 0
        var failed = 0

        for (issue in demoIssues) {
            for (kind in listOf("break", "fix")) {
                val branch = "bug/${issue.id}/$kind"
                val exists = runCatching {
                    val p = ProcessBuilder("git", "rev-parse", "--verify", "--quiet", "refs/heads/$branch")
                        .directory(dir).redirectErrorStream(true).start()
                    p.waitFor() == 0
                }.getOrDefault(false)
                if (exists) {
                    log.appendLine("[skip] $branch — already exists")
                    skipped++
                    continue
                }
                // `git branch <name> <commit>` creates the ref without
                // touching the working tree. Pure metadata operation —
                // safe even if the operator has uncommitted changes.
                val res = runCatching {
                    val p = ProcessBuilder("git", "branch", branch, headCommit)
                        .directory(dir).redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    p.waitFor() to out
                }.getOrElse { -1 to (it.message ?: "exception") }
                if (res.first == 0) {
                    log.appendLine("[ok]   created $branch @ ${headCommit.take(8)}")
                    created++
                } else {
                    log.appendLine("[err]  $branch failed (exit=${res.first}): ${res.second}")
                    failed++
                }
            }
        }

        log.appendLine()
        log.appendLine("[done] created=$created  skipped=$skipped  failed=$failed")
        log.appendLine("[note] These branches are EMPTY placeholders — no actual bug diff.")
        log.appendLine("[note] Replace each break branch's tip with a real bug-introducing")
        log.appendLine("[note] commit (and fix branch with the corresponding fix) to make")
        log.appendLine("[note] benchmarks meaningful. The webui only needs the branches to")
        log.appendLine("[note] *exist* for the dropdown to enable; the contents are scored")
        log.appendLine("[note] separately.")

        return mapOf(
            "ok" to (failed == 0),
            "summary" to "created=$created, skipped=$skipped, failed=$failed",
            "message" to (
                if (failed == 0)
                    "Seeded $created branch(es), skipped $skipped existing. Refresh /demo to use the dropdown."
                else
                    "Seeding completed with $failed failures — see log."),
            "log" to log.toString()
        )
    }

    @PostMapping("/demo/banking-app/jdk/scan")
    @org.springframework.web.bind.annotation.ResponseBody
    fun scanForJdks(@RequestParam path: String): Map<String, Any> {
        val outcome = JdkDiscovery.scanFolderForJdks(path)
        val message = when {
            outcome.jdks.isEmpty() && outcome.truncated ->
                "No JDK found in ${outcome.visitedDirs}+ scanned dirs (search hit the cap). Pick a more specific subfolder and retry."
            outcome.jdks.isEmpty() ->
                "No JDK found under that folder (scanned ${outcome.visitedDirs} dirs). Pick the JAVA_HOME-style root or a parent that contains one."
            outcome.jdks.size == 1 ->
                "Found 1 JDK (scanned ${outcome.visitedDirs} dirs). Refresh the page to pick it in the dropdown."
            else ->
                "Found ${outcome.jdks.size} JDKs (scanned ${outcome.visitedDirs} dirs). Refresh the page to pick one in the dropdown."
        }
        return mapOf(
            "ok" to outcome.jdks.isNotEmpty(),
            "count" to outcome.jdks.size,
            "scannedDirs" to outcome.visitedDirs,
            "truncated" to outcome.truncated,
            "jdks" to outcome.jdks.map {
                mapOf("home" to it.home, "label" to it.label, "major" to it.major)
            },
            "message" to message
        )
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

    /**
     * Self-healing diagnostic endpoint. Tails the banking-app startup
     * log and runs the regex pattern catalogue (BankingAppDiagnostics)
     * to surface known failure signatures. Each finding may carry a
     * fixActionId the operator can click to apply an auto-recovery.
     * Pure regex; the LLM-assisted analysis lives at /demo/app/diagnose-with-llm
     * so that endpoint can be skipped when no LLM bridge is reachable.
     */
    @GetMapping("/demo/app/diagnostics")
    @org.springframework.web.bind.annotation.ResponseBody
    fun appDiagnostics(@RequestParam(defaultValue = "500") lines: Int): Map<String, Any> {
        val log = bankingApp.logTail(lines)
        val findings = BankingAppDiagnostics.analyze(log)
        return mapOf(
            "logLines" to lines,
            "logBytes" to log.length,
            "findings" to findings.map {
                mapOf(
                    "id" to it.id,
                    "title" to it.title,
                    "severity" to it.severity.name,
                    "message" to it.message,
                    "fixActionId" to it.fixActionId,
                    "fixActionLabel" to it.fixActionLabel
                )
            },
            "llmAvailable" to copilotBridgeReachable()
        )
    }

    /**
     * Optional LLM-assisted root cause. Only fires when the Copilot
     * bridge's OpenAI shim is reachable on 127.0.0.1:11434. Sends the
     * regex findings + the log tail as context, asks the LLM for any
     * additional analysis the regex catalogue missed. Returns the LLM's
     * raw text plus latency/token info.
     */
    @GetMapping("/demo/app/diagnose-with-llm")
    @org.springframework.web.bind.annotation.ResponseBody
    fun appDiagnoseWithLlm(
        @RequestParam(defaultValue = "500") lines: Int
    ): Map<String, Any> {
        if (!copilotBridgeReachable()) {
            return mapOf(
                "ok" to false,
                "reason" to "Copilot bridge not reachable at 127.0.0.1:11434. " +
                    "Configure an LLM via the Copilot Bridge VSCode extension on /llm first."
            )
        }
        val log = bankingApp.logTail(lines)
        val findings = BankingAppDiagnostics.analyze(log)
        val findingsBlock = if (findings.isEmpty()) "(none)"
            else findings.joinToString("\n") { "- ${it.title}: ${it.message}" }

        val systemPrompt = """
            You are a senior Gradle / Spring Boot engineer triaging a
            failed banking-app build on an enterprise developer box.
            Bench-webui already ran a pattern-based diagnostic; your
            job is to spot anything the regex catalogue missed -- a
            specific stack trace, an unusual pattern, or a corp-network
            symptom that needs human judgment.

            Respond in 3-6 short bullet points. Be concrete: cite line
            numbers from the log when possible, name the specific
            command or config knob to fix, do NOT repeat what the
            regex findings already cover. If the log doesn't reveal
            anything beyond the regex findings, say so in one line.
        """.trimIndent()

        val userPrompt = """
            Regex findings (already shown to the operator):
            $findingsBlock

            Last $lines lines of banking-app/tmp/bootRun.log:
            ```
            $log
            ```
        """.trimIndent()

        val start = System.currentTimeMillis()
        return try {
            val body = """{"model":"copilot","temperature":0.1,"messages":[""" +
                """{"role":"system","content":${jsonString(systemPrompt)}},""" +
                """{"role":"user","content":${jsonString(userPrompt)}}""" +
                """]}"""
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:11434/v1/chat/completions"))
                .timeout(java.time.Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build()
            val client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build()
            val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            val ms = System.currentTimeMillis() - start
            if (resp.statusCode() !in 200..299) {
                return mapOf(
                    "ok" to false,
                    "reason" to "LLM bridge returned HTTP ${resp.statusCode()}. " +
                        "Body (first 200 chars): ${resp.body().take(200)}"
                )
            }
            // Extract content with a regex rather than pulling kotlinx.serialization
            // for one field; the OpenAI shim's response shape is stable enough.
            val content = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(resp.body())?.groupValues?.get(1)
                ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                ?: "(empty response)"
            mapOf(
                "ok" to true,
                "analysis" to content,
                "durationMs" to ms,
                "model" to "copilot"
            )
        } catch (e: Exception) {
            mapOf(
                "ok" to false,
                "reason" to "LLM call failed: ${e.javaClass.simpleName}: ${e.message ?: ""}"
            )
        }
    }

    /**
     * Apply an operator-confirmed auto-fix. Each fix is intentionally
     * narrow and idempotent -- safe to click twice, no irreversible
     * destructive ops. The operator clicks Start banking app again
     * after the fix; we don't auto-restart, since some fixes only
     * paper over a failure mode that needs further investigation.
     */
    @org.springframework.web.bind.annotation.PostMapping("/demo/app/auto-fix")
    @org.springframework.web.bind.annotation.ResponseBody
    fun appAutoFix(@RequestParam fixActionId: String): Map<String, Any> {
        return when (fixActionId) {
            "regenerate-init" -> {
                runCatching {
                    // Re-fire the same writers a /proxy save would --
                    // writeCorpInitScript + writeGradleProxyProperties
                    // run inside ConnectionSettings.update(). No-op
                    // settings change; only the side effects matter.
                    val s = connectionSettings.settings
                    connectionSettings.update(s)
                }.fold(
                    onSuccess = { mapOf(
                        "ok" to true,
                        "message" to "Regenerated ~/.gradle/init.d/corp-repos.gradle.kts " +
                            "and ~/.gradle/gradle.properties from current /proxy settings. " +
                            "Click Start banking app to retry."
                    ) },
                    onFailure = { mapOf("ok" to false, "message" to "Failed: ${it.message}") }
                )
            }
            "clear-foojay-locks" -> {
                val home = System.getProperty("user.home") ?: return mapOf(
                    "ok" to false, "message" to "Could not resolve user home.")
                val jdksDir = java.io.File(home, ".gradle/jdks")
                if (!jdksDir.isDirectory) return mapOf(
                    "ok" to true,
                    "message" to "No ~/.gradle/jdks/ directory; nothing to clean.")
                val locks = jdksDir.listFiles { f -> f.name.endsWith(".reserved.lock") }
                    ?: emptyArray()
                val deleted = locks.count { it.delete() }
                mapOf(
                    "ok" to true,
                    "message" to "Deleted $deleted .reserved.lock file(s) from ~/.gradle/jdks/. " +
                        "Click Start banking app to retry; foojay will re-attempt the JDK download cleanly."
                )
            }
            "clear-build-cache" -> {
                val home = System.getProperty("user.home") ?: return mapOf(
                    "ok" to false, "message" to "Could not resolve user home.")
                val cachesDir = java.io.File(home, ".gradle/caches")
                if (!cachesDir.isDirectory) return mapOf(
                    "ok" to true, "message" to "No ~/.gradle/caches/ directory; nothing to clean.")
                // Only the build-cache-* subdirs (not the entire caches/, which
                // would force a full re-download of every dependency).
                val targets = cachesDir.listFiles { f ->
                    f.isDirectory && f.name.startsWith("build-cache-")
                } ?: emptyArray()
                val cleaned = targets.count { it.deleteRecursively() }
                mapOf(
                    "ok" to true,
                    "message" to "Cleared $cleaned local build-cache directory/ies. " +
                        "Click Start banking app to retry."
                )
            }
            else -> mapOf(
                "ok" to false,
                "message" to "Unknown fixActionId '$fixActionId'."
            )
        }
    }

    /**
     * Cheap reachability check for the Copilot bridge HTTP shim --
     * one TCP connect to 127.0.0.1:11434 with a 500ms timeout. Used
     * to gate the /diagnose-with-llm endpoint and the corresponding
     * UI button on the banking-app log viewer.
     */
    private fun copilotBridgeReachable(): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", 11434), 500)
            true
        }
    }.getOrDefault(false)

    /** Encode a string as a JSON string literal. */
    private fun jsonString(s: String): String {
        val sb = StringBuilder().append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
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

    /**
     * Resolve a user-supplied path to the banking-app directory. Accepts
     * either the banking-app dir itself or a parent that contains a
     * banking-app/ subdir (e.g. the repo root). Returns null if neither
     * shape contains a gradlew wrapper.
     */
    private fun resolveBankingAppCandidate(input: String, log: StringBuilder): File? {
        val trimmed = input.trim().trim('"', '\'').trim()
        if (trimmed.isEmpty()) {
            log.appendLine("[err]  Empty path.")
            return null
        }
        val direct = File(trimmed)
        log.appendLine("[info] Checking: ${direct.absolutePath}")
        if (direct.resolve("gradlew").exists() || direct.resolve("gradlew.bat").exists()) {
            log.appendLine("[ok]   gradlew wrapper found at ${direct.absolutePath}")
            return direct
        }
        val nested = direct.resolve("banking-app")
        log.appendLine("[info] Checking: ${nested.absolutePath}")
        if (nested.resolve("gradlew").exists() || nested.resolve("gradlew.bat").exists()) {
            log.appendLine("[ok]   gradlew wrapper found at ${nested.absolutePath}")
            return nested
        }
        log.appendLine("[err]  No gradlew wrapper at either path.")
        return null
    }

    /**
     * "How to fix" buttons A & B from the /demo diagnostics panel.
     * Validates the path the user supplied, persists it as a runtime
     * override (so the resolution survives a webui restart), and applies
     * it in-process so the next /demo render shows the warning cleared.
     */
    @PostMapping("/demo/banking-app/fix/apply-path")
    @org.springframework.web.bind.annotation.ResponseBody
    fun fixApplyPath(
        @RequestParam path: String,
        @RequestParam(defaultValue = "env") mode: String
    ): Map<String, Any> {
        val log = StringBuilder()
        log.appendLine("$ apply-path mode=$mode")
        val resolved = resolveBankingAppCandidate(path, log)
            ?: return mapOf(
                "ok" to false,
                "message" to "Path doesn't contain a banking-app/gradlew wrapper.",
                "output" to log.toString()
            )
        return runCatching {
            bankingApp.setRuntimeOverride(resolved.absolutePath)
            log.appendLine("[ok]   Persisted to ~/.ai-bench/banking-app-path.txt (survives restart).")
            log.appendLine("[ok]   In-memory override applied — banking-app now resolves at:")
            log.appendLine("       ${resolved.absolutePath}")
            mapOf(
                "ok" to true,
                "message" to "Applied. Refresh the page to clear the warning.",
                "output" to log.toString()
            )
        }.getOrElse { e ->
            log.appendLine("[err]  ${e.message}")
            mapOf("ok" to false,
                "message" to "Failed: ${e.message}",
                "output" to log.toString())
        }
    }

    /**
     * "How to fix" button C from the /demo diagnostics panel. Creates
     * an NTFS junction (Windows) or a POSIX symlink (Unix) at
     * ${user.dir}/banking-app pointing at the user's checkout, so the
     * existing parent-walk discovery finds it on the next request.
     */
    @PostMapping("/demo/banking-app/fix/symlink")
    @org.springframework.web.bind.annotation.ResponseBody
    fun fixSymlink(@RequestParam path: String): Map<String, Any> {
        val log = StringBuilder()
        val resolved = resolveBankingAppCandidate(path, log)
            ?: return mapOf(
                "ok" to false,
                "message" to "Path doesn't contain a banking-app/gradlew wrapper.",
                "output" to log.toString()
            )
        val link = File(System.getProperty("user.dir"), "banking-app")
        if (link.exists()) {
            log.appendLine("[err]  ${link.absolutePath} already exists; remove or rename it first.")
            return mapOf(
                "ok" to false,
                "message" to "${link.absolutePath} already exists.",
                "output" to log.toString()
            )
        }
        val cmd = if (Platform.isWindows)
            listOf("cmd", "/c", "mklink", "/J", link.absolutePath, resolved.absolutePath)
        else
            listOf("ln", "-s", resolved.absolutePath, link.absolutePath)
        log.appendLine("$ ${cmd.joinToString(" ")}")
        return runCatching {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (out.isNotBlank()) log.append(out).also { if (!out.endsWith("\n")) log.append("\n") }
            if (!finished) {
                proc.destroyForcibly()
                return mapOf("ok" to false,
                    "message" to "Command timed out.",
                    "output" to log.toString())
            }
            if (proc.exitValue() == 0) {
                log.appendLine("[ok]   Junction/symlink created at ${link.absolutePath}.")
                mapOf("ok" to true,
                    "message" to "Created. Refresh the page to clear the warning.",
                    "output" to log.toString())
            } else {
                log.appendLine("[err]  Exit code ${proc.exitValue()}.")
                mapOf("ok" to false,
                    "message" to "Command failed (exit ${proc.exitValue()}).",
                    "output" to log.toString())
            }
        }.getOrElse { e ->
            log.appendLine("[err]  ${e.message}")
            mapOf("ok" to false,
                "message" to "Failed: ${e.message}",
                "output" to log.toString())
        }
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
