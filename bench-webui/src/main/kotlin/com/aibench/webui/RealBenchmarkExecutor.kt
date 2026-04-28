package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Real per-seed benchmark scoring. Replaces the synthetic
 * `Math.random() > 0.45` outcome inside BenchmarkRunService.simulate
 * with a real pipeline:
 *
 *  1. Extract the LLM's unified-diff patch from its response text.
 *  2. Create a fresh git worktree of banking-app at the bug's break
 *     branch (bug/<issueId>/break). The worktree shares the main
 *     repo's .git so it's cheap; the file tree is per-seed isolated
 *     so two seeds can't trample each other's patch state.
 *  3. `git apply` the patch.
 *  4. Run `gradlew :app-bootstrap:test` with the operator's saved
 *     proxy/mirror sysprops piped in, capped at the seed timeout.
 *  5. Pass = exit 0 on the test task; fail = exit non-zero, patch
 *     application failure, missing branch, or no patch in the
 *     response. Each branch logs the specific reason so the operator
 *     can tell "model produced no patch" from "tests failed".
 *  6. Worktree always removed in finally; the .git refs cleanup ('git
 *     worktree prune' in main repo) runs on the next launch.
 *
 * Falls back to a clear "no real scoring possible" outcome when the
 * bug/<issueId>/break branch doesn't exist (graceful degradation
 * for fresh banking-app checkouts that haven't been seeded yet).
 */
@Component
class RealBenchmarkExecutor(
    private val bankingApp: BankingAppManager,
    private val connectionSettings: ConnectionSettings
) {

    private val log = LoggerFactory.getLogger(RealBenchmarkExecutor::class.java)

    enum class Outcome { PASSED, FAILED_TESTS, FAILED_PATCH_APPLY, FAILED_NO_PATCH, FAILED_NO_BRANCH, FAILED_GRADLE_ERROR }

    data class ScoreResult(
        val outcome: Outcome,
        val testsPassed: Int,
        val testsTotal: Int,
        val durationMs: Long,
        val message: String,
        // ---- Audit-trail fields ----------------------------------------
        // Populated whenever the corresponding step actually ran. Null
        // entries mean the step was skipped (e.g., extractedPatch is
        // null when outcome=FAILED_NO_PATCH). The /results audit page
        // surfaces these so the operator can see what was tried.
        /** The diff block we pulled from the LLM response (input to git apply). */
        val extractedPatch: String? = null,
        /** Full gradle command (joined by space) that ran the tests. */
        val verificationCommand: String? = null,
        /** Tail of the gradle process's combined stdout+stderr (cap ~16KB). */
        val testStdoutTail: String? = null,
        /** gradle :app-bootstrap:test exit code, or null when test step didn't run. */
        val testExitCode: Int? = null,
        /** True when the worktree was created and we attempted git apply. */
        val patchApplied: Boolean = false
    )

    /**
     * Find a unified-diff block in the LLM's response text. Recognises
     * three shapes the model commonly produces:
     *   ```diff\n<patch>\n```
     *   ```patch\n<patch>\n```
     *   raw ---/+++/@@ block with no fence
     * Returns null when no patch-shaped content is present (the LLM
     * answered with prose only -- common when the prompt didn't
     * explicitly demand a diff).
     */
    fun extractPatch(text: String): String? {
        // Fenced block, language tag = diff or patch.
        Regex("```(?:diff|patch)\\s*\\n([\\s\\S]*?)\\n```").find(text)?.let {
            return it.groupValues[1].trim()
        }
        // Untagged fenced block whose content starts with a unified-diff header.
        Regex("```\\s*\\n(--- [\\s\\S]*?)\\n```").find(text)?.let {
            return it.groupValues[1].trim()
        }
        // Raw block — start at first `--- ` line that's followed shortly
        // by a `+++ ` line, run to end of message or to a blank line +
        // non-diff chatter.
        val rawStart = Regex("(?m)^--- ").find(text)?.range?.first
        if (rawStart != null) {
            val raw = text.substring(rawStart)
            // Trim trailing prose by keeping only contiguous diff-shape lines.
            val kept = raw.lineSequence().takeWhile {
                it.isNotEmpty() && (it.startsWith("---") || it.startsWith("+++") ||
                    it.startsWith("@@") || it.startsWith("+") || it.startsWith("-") ||
                    it.startsWith(" ") || it.startsWith("\\") || it.startsWith("diff "))
            }.joinToString("\n").trim()
            if (kept.lines().size >= 3) return kept
        }
        return null
    }

    /**
     * Score one seed against the supplied bug. Returns a ScoreResult
     * regardless of which step failed -- caller logs the message and
     * threads the outcome into the BenchmarkRun's per-seed table.
     *
     * `logFn` is the BenchmarkRunService.entry callback so per-seed
     * progress (worktree create, patch apply, gradle exit code, etc.)
     * is visible in the run drilldown's live tail.
     */
    fun scoreSeed(
        runId: String,
        seedNumber: Int,
        issueId: String,
        llmResponseText: String,
        logFn: (String) -> Unit
    ): ScoreResult {
        val started = System.currentTimeMillis()
        val patch = extractPatch(llmResponseText)
        if (patch == null) {
            return ScoreResult(Outcome.FAILED_NO_PATCH, 0, 0,
                System.currentTimeMillis() - started,
                "LLM response contained no diff/patch block. Run a follow-up prompt asking " +
                    "explicitly for a unified diff in a ```diff fenced block.")
        }
        logFn("Seed $seedNumber: extracted patch (${patch.length} chars) from LLM response")

        val bankingDir = bankingApp.bankingAppDir
        if (!File(bankingDir, ".git").exists()) {
            return ScoreResult(Outcome.FAILED_NO_BRANCH, 0, 0,
                System.currentTimeMillis() - started,
                "banking-app at ${bankingDir.absolutePath} is not a git checkout; cannot create a worktree.")
        }

        val branch = "bug/$issueId/break"
        if (!branchExists(bankingDir, branch)) {
            return ScoreResult(Outcome.FAILED_NO_BRANCH, 0, 0,
                System.currentTimeMillis() - started,
                "Branch '$branch' does not exist in banking-app. Real scoring needs the " +
                    "bug branches seeded first -- click 'Create placeholder bug branches' on /demo, " +
                    "then commit a real broken state to $branch before re-running.",
                extractedPatch = patch)
        }
        logFn("Seed $seedNumber: bug branch '$branch' exists -- creating per-seed worktree")

        val seedRoot = Files.createTempDirectory("ai-bench-${runId}-seed-${seedNumber}-").toFile()
        return try {
            // git worktree add -- shares .git with the main repo, so this
            // is fast and disk-cheap. Detached HEAD on the branch tip so
            // we never accidentally publish a per-seed mutation.
            val worktreeAdd = runProcess(
                listOf("git", "worktree", "add", "--detach", seedRoot.absolutePath, branch),
                bankingDir, 60
            )
            if (worktreeAdd.exitCode != 0) {
                return ScoreResult(Outcome.FAILED_GRADLE_ERROR, 0, 0,
                    System.currentTimeMillis() - started,
                    "git worktree add failed (exit ${worktreeAdd.exitCode}): ${worktreeAdd.tail(200)}",
                    extractedPatch = patch)
            }
            logFn("Seed $seedNumber: worktree at ${seedRoot.absolutePath}")

            // Persist patch to disk so 'git apply' has a clean stdin.
            val patchFile = File(seedRoot, ".llm-patch.diff")
            patchFile.writeText(patch + "\n")
            val applyProc = runProcess(
                listOf("git", "apply", "--whitespace=nowarn", patchFile.absolutePath),
                seedRoot, 30
            )
            if (applyProc.exitCode != 0) {
                logFn("Seed $seedNumber: ✗ git apply failed (exit ${applyProc.exitCode})")
                return ScoreResult(Outcome.FAILED_PATCH_APPLY, 0, 0,
                    System.currentTimeMillis() - started,
                    "git apply rejected the LLM's patch: ${applyProc.tail(300)}",
                    extractedPatch = patch)
            }
            logFn("Seed $seedNumber: ✓ patch applied cleanly")

            // Run tests. Quiet console; capture exit code; bound to 5min.
            // Pass operator's proxy/mirror sysprops through so the test
            // gradle run respects /proxy config.
            val cmd = mutableListOf<String>().apply {
                addAll(Platform.gradleWrapper(seedRoot))
                add(":app-bootstrap:test")
                add("--no-daemon")
                add("--console=plain")
                addAll(connectionSettings.gradleSystemProps())
            }
            val cmdJoined = cmd.joinToString(" ")
            logFn("Seed $seedNumber: running ${cmd.subList(0, 3).joinToString(" ")} … (cap 5min)")
            val testProc = runProcess(cmd, seedRoot, 300)
            val testsTotal = parseInt(testProc.stdout, "(\\d+) tests?", default = 0)
            val testsFailed = parseInt(testProc.stdout, "(\\d+) failed", default = 0)
            val testsPassed = (testsTotal - testsFailed).coerceAtLeast(0)
            val outcome = when {
                testProc.exitCode == 0 -> Outcome.PASSED
                testsFailed > 0 -> Outcome.FAILED_TESTS
                else -> Outcome.FAILED_GRADLE_ERROR
            }
            val msg = when (outcome) {
                Outcome.PASSED -> "Seed $seedNumber: ✓ $testsPassed/$testsTotal tests passed."
                Outcome.FAILED_TESTS -> "Seed $seedNumber: ✗ $testsFailed of $testsTotal tests failed. " +
                    "Patch applied but didn't fix the broken state."
                Outcome.FAILED_GRADLE_ERROR -> "Seed $seedNumber: gradle exited ${testProc.exitCode} " +
                    "without parseable test output: ${testProc.tail(200)}"
                else -> ""
            }
            logFn(msg)
            ScoreResult(outcome, testsPassed, testsTotal,
                System.currentTimeMillis() - started, msg,
                extractedPatch = patch,
                verificationCommand = cmdJoined,
                // Cap stdout so audit pages stay snappy. 16KB is enough
                // for a normal failure trace plus the gradle summary;
                // logs longer than that almost always have the same
                // failure repeated and aren't more useful uncapped.
                testStdoutTail = testProc.stdout.takeLast(16_000),
                testExitCode = testProc.exitCode,
                patchApplied = true)
        } catch (e: Exception) {
            log.warn("scoreSeed threw for run={} seed={}", runId, seedNumber, e)
            ScoreResult(Outcome.FAILED_GRADLE_ERROR, 0, 0,
                System.currentTimeMillis() - started,
                "Real scoring threw ${e.javaClass.simpleName}: ${e.message ?: ""}",
                extractedPatch = patch)
        } finally {
            // Clean up the worktree. 'git worktree remove --force' in the
            // main repo invalidates the per-seed .git pointer; deleting
            // the dir handles the file tree.
            runCatching {
                runProcess(listOf("git", "worktree", "remove", "--force", seedRoot.absolutePath),
                    bankingDir, 30)
            }
            runCatching { seedRoot.deleteRecursively() }
        }
    }

    private fun branchExists(repoDir: File, branch: String): Boolean {
        val r = runProcess(
            listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/$branch"),
            repoDir, 5
        )
        return r.exitCode == 0
    }

    private data class ProcResult(val exitCode: Int, val stdout: String) {
        fun tail(n: Int): String = stdout.takeLast(n).replace("\n", " | ")
    }

    private fun runProcess(cmd: List<String>, cwd: File, timeoutSec: Long): ProcResult {
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)
        val proc = pb.start()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        val out = proc.inputStream.bufferedReader().readText()
        if (!finished) {
            runCatching { proc.destroyForcibly() }
            return ProcResult(-1, out + "\n[timed out after ${timeoutSec}s]")
        }
        return ProcResult(proc.exitValue(), out)
    }

    private fun parseInt(text: String, regex: String, default: Int): Int =
        Regex(regex).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: default
}
