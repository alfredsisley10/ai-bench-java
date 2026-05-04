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
    private val connectionSettings: ConnectionSettings,
    private val bugCatalog: BugCatalog,
    private val worktreePool: WorktreePool
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
        // Acquire a worktree from the shared pool. The pool blocks if
        // every worktree is busy (acts as the local-execution throttle,
        // mirroring AdaptiveThrottler's role for LLM calls). Pool
        // acquire also re-overlays filesTouched from the live tree to
        // undo any prior run's mutations -- so we start clean, then
        // overlay the break-branch versions below.
        val bug = bugCatalog.getBug(issueId)
        val touched = bug?.filesTouched ?: emptyList()
        if (bug == null) {
            return ScoreResult(Outcome.FAILED_NO_BRANCH, 0, 0,
                System.currentTimeMillis() - started,
                "Bug $issueId not in catalog; can't acquire worktree.",
                extractedPatch = patch)
        }
        logFn("Seed $seedNumber: acquiring worktree from pool (cap=${worktreePool.status().cap})")
        val lease = worktreePool.acquire(bug)
        val seedRoot = lease.dir
        return try {
            logFn("Seed $seedNumber: worktree at ${seedRoot.absolutePath} (pooled)")

            // Overlay the bug's filesTouched from the break branch on
            // top of the live state the pool just reset us to. Same
            // git-show pattern as before; the pool is just the dir,
            // the git store is still the parent banking-app repo.
            if (touched.isEmpty()) {
                logFn("Seed $seedNumber: WARNING -- no filesTouched; patch will apply against working tree, not break.")
            } else {
                for (path in touched) {
                    val show = runProcess(
                        listOf("git", "show", "$branch:$path"),
                        bankingDir, 15
                    )
                    if (show.exitCode != 0) {
                        return ScoreResult(Outcome.FAILED_GRADLE_ERROR, 0, 0,
                            System.currentTimeMillis() - started,
                            "git show $branch:$path failed (exit ${show.exitCode}): " +
                            show.tail(200),
                            extractedPatch = patch)
                    }
                    val target = File(seedRoot, path)
                    target.parentFile?.mkdirs()
                    target.writeText(show.stdout)
                }
                logFn("Seed $seedNumber: overlaid ${touched.size} file(s) from $branch")
            }

            // Persist patch to disk so 'git apply' has a clean stdin.
            val patchFile = File(seedRoot, ".llm-patch.diff")
            patchFile.writeText(patch + "\n")
            // Snapshot file contents before apply so we can detect a no-op
            // patch (identical -/+ lines that git apply silently accepts
            // under --ignore-whitespace). Compared to seedDigestsAfter below.
            val digestsBefore = touched.associateWith {
                runCatching { File(seedRoot, it).readBytes().contentHashCode() }.getOrNull()
            }
            // `--recount` makes git apply recompute the per-hunk line
            // counts from the body rather than trusting the `@@ -a,b
            // +c,d @@` header, AND lets a hunk apply at a different
            // line offset when the body still matches. Both matter:
            // LLMs (especially gpt-4.1) routinely emit hunks where
            // the new-side line count is off by one (e.g. `+38,8`
            // when the body actually adds only 7 new-side lines), and
            // they also anchor the hunk at a wrong line number when
            // the surrounding context has shifted in the file. Without
            // --recount, both produced "corrupt patch at line N" /
            // "patch does not apply" rejections that masked otherwise-
            // correct edits. `--ignore-whitespace` adds tolerance for
            // the LLM rewriting tabs as spaces or trimming trailing
            // whitespace -- harmless on this codebase since gradle
            // checkstyle/spotless reformats anyway, but it kills a
            // class of "context lines have one trailing space" rejects.
            val applyProc = runProcess(
                listOf("git", "apply", "--whitespace=nowarn",
                    "--recount", "--ignore-whitespace",
                    patchFile.absolutePath),
                seedRoot, 30
            )
            if (applyProc.exitCode != 0) {
                logFn("Seed $seedNumber: ✗ git apply failed (exit ${applyProc.exitCode})")
                return ScoreResult(Outcome.FAILED_PATCH_APPLY, 0, 0,
                    System.currentTimeMillis() - started,
                    "git apply rejected the LLM's patch: ${applyProc.tail(300)}",
                    extractedPatch = patch)
            }
            // No-op patch detection: with --ignore-whitespace, byte-identical
            // `-` and `+` lines exit 0 but change nothing. Treat as a failed
            // solution rather than letting it slip through to FAILED_TESTS,
            // which is misleading -- the LLM didn't actually edit anything.
            val seedDigestsAfter = touched.associateWith {
                runCatching { File(seedRoot, it).readBytes().contentHashCode() }.getOrNull()
            }
            if (touched.isNotEmpty() && seedDigestsAfter == digestsBefore) {
                logFn("Seed $seedNumber: ✗ patch applied but produced no file changes (no-op diff)")
                return ScoreResult(Outcome.FAILED_PATCH_APPLY, 0, 0,
                    System.currentTimeMillis() - started,
                    "Patch applied but changed nothing -- the `-` and `+` lines were identical " +
                        "(a no-op diff). Re-prompt the model for a real edit.",
                    extractedPatch = patch)
            }
            logFn("Seed $seedNumber: ✓ patch applied cleanly")

            // Run the bug's specific test in the bug's module rather
            // than :app-bootstrap:test (which is a smoke aggregator
            // that doesn't include payments-hub / accounts-* tests).
            // Filter to the hidden verification test so the run only
            // exercises the JUnit case the patch is supposed to fix
            // -- avoids dragging in unrelated module tests that might
            // have transient failures.
            val bug = bugCatalog.getBug(issueId)
            val testTask = bug?.module?.takeIf { it.isNotBlank() }
                ?.let { ":$it:test" } ?: ":app-bootstrap:test"
            val cmd = mutableListOf<String>().apply {
                addAll(Platform.gradleWrapper(seedRoot))
                add(testTask)
                bug?.hiddenTestClass?.takeIf { it.isNotBlank() }?.let {
                    add("--tests"); add(it)
                }
                add("--no-daemon")
                add("--console=plain")
                addAll(connectionSettings.gradleSystemProps())
            }
            // Mask any -D*Password=<cleartext> portions before joining --
            // the result is what we persist on the SeedAudit and surface
            // in the result-detail page, so cleartext credentials must
            // not slip into the H2 DB or any UI render.
            val cmdJoined = connectionSettings.maskCredentialArgsInLine(cmd.joinToString(" "))
            logFn("Seed $seedNumber: running ${cmd.subList(0, 3).joinToString(" ")} … (cap 5min)")
            val testProc = runProcess(cmd, seedRoot, 300)
            // Test counts come from the JUnit XML reports gradle writes
            // to <module>/build/test-results/test/TEST-*.xml. They are
            // authoritative regardless of whether gradle re-ran the
            // task or pulled the result FROM-CACHE -- the cached
            // outputs include the report files. Parsing stdout's
            // "ClassName > test PASSED" lines or the "N tests, N
            // failed" summary fails when the test task is cached
            // (those lines aren't emitted on cache-hit), which made
            // every cached pass display as 0/0 even though exit_code
            // was 0.
            val (testsTotal, testsFailed, testsPassed) = parseJUnitReports(seedRoot, bug?.module)
                .also { (t, f, _) -> if (t == 0) {
                    logFn("Seed $seedNumber: WARNING -- 0 tests parsed from JUnit XML " +
                        "(was the test task skipped? exit=${testProc.exitCode})")
                }}
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
            // Return the worktree to the pool. The pool's next acquire
            // will re-overlay filesTouched from the live tree to wipe
            // this run's mutations -- much cheaper than the previous
            // pattern's full deleteRecursively + recreate.
            lease.release()
        }
    }

    /**
     * Read every JUnit XML report under
     * <module>/build/test-results/test/ and sum tests / failures /
     * errors / skipped across them. Authoritative because gradle
     * always writes these reports, even when the test task is
     * resolved FROM-CACHE -- unlike the stdout's per-test PASSED
     * lines which are cache-suppressed.
     *
     * Returns Triple(total, failed, passed). When `module` is null we
     * scan every subproject's build/test-results, which is how the
     * fallback :app-bootstrap:test path lands here too.
     */
    private fun parseJUnitReports(seedRoot: File, module: String?): Triple<Int, Int, Int> {
        val candidates = if (!module.isNullOrBlank())
            listOf(File(seedRoot, "$module/build/test-results/test"))
        else
            seedRoot.walkTopDown()
                .filter { it.isDirectory && it.name == "test" && it.parentFile?.name == "test-results" }
                .toList()
        var total = 0; var failed = 0
        // Match the JUnit-XML <testsuite> attributes. We tolerate any
        // attribute order; the only fields we need are tests / failures
        // / errors / skipped. Disabled tests (different attribute) are
        // counted as skipped if present.
        val tag = Regex("<testsuite\\s[^>]*?>")
        val attr = Regex("""(\w+)="(\d+)"""")
        for (dir in candidates) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("TEST-") &&
                f.name.endsWith(".xml") } ?: continue
            for (xml in files) {
                val head = runCatching { xml.bufferedReader().use { it.readText().take(2_000) } }
                    .getOrDefault("")
                val opening = tag.find(head) ?: continue
                val attrs = attr.findAll(opening.value)
                    .associate { it.groupValues[1] to it.groupValues[2].toInt() }
                total += attrs["tests"] ?: 0
                failed += (attrs["failures"] ?: 0) + (attrs["errors"] ?: 0)
            }
        }
        val passed = (total - failed).coerceAtLeast(0)
        return Triple(total, failed, passed)
    }

    /**
     * Overlay the main-repo banking-app/'s gradle wrapper files on top
     * of the seed worktree. We replace gradlew, gradlew.bat, and the
     * full gradle/wrapper/ directory so the worktree uses main's
     * gradle distribution + wrapper-properties even though the rest
     * of the source is from the bug-break commit.
     *
     * <p>Conservative: nothing else is touched -- settings.gradle.kts,
     * build.gradle.kts, and source files all stay at the break-commit
     * shape. If main's settings references subprojects that don't
     * exist on the break commit, the build will still fail; that's a
     * deeper "bug branch is too far behind main" problem than this
     * helper can fix.
     */
    private fun overlayMainWrapper(mainRepo: File, worktree: File) {
        runCatching {
            for (file in listOf("gradlew", "gradlew.bat")) {
                val src = File(mainRepo, file)
                if (src.isFile) {
                    val dst = File(worktree, file)
                    src.copyTo(dst, overwrite = true)
                    dst.setExecutable(true)
                }
            }
            val srcWrapper = File(mainRepo, "gradle/wrapper")
            val dstWrapper = File(worktree, "gradle/wrapper")
            if (srcWrapper.isDirectory) {
                dstWrapper.mkdirs()
                srcWrapper.listFiles()?.forEach { f ->
                    if (f.isFile) f.copyTo(File(dstWrapper, f.name), overwrite = true)
                }
            }
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
