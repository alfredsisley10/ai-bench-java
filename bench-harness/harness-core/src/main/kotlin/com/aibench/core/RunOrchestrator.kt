package com.aibench.core

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories

/**
 * Drives a single bespoke-bug evaluation run end-to-end:
 *   checkout → baseline build → (optional) AppMap collect → prompt → solve →
 *   apply patch → hidden test → regression suite → score.
 *
 * Each step is a pluggable interface so the harness can be composed with
 * different implementations (real Git, mocked LLM, etc.).
 */
class RunOrchestrator(
    private val worktree: Worktree,
    private val builder: Builder,
    private val appmap: AppMapCollector,
    private val llm: LlmRunner,
    private val patcher: Patcher,
    private val scorer: Scorer
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun run(request: RunRequest): RunResult {
        val runId = UUID.randomUUID().toString()
        val start = Instant.now()
        val runRoot: Path = request.workRoot.resolve("runs/$runId").also { it.createDirectories() }

        log.info("[{}] {} · solver={} · appmap={}", runId, request.bug.id, request.solver, request.appmapMode)

        worktree.checkout(request.bug.breakCommit, runRoot)

        val baseline = builder.build(runRoot)
        if (!baseline.success) {
            return failed(runId, request, start, "baseline build failed: ${baseline.log}")
        }

        val baselineTests = builder.testAll(runRoot, excludeHidden = request.bug.hiddenTest)
        val traces = if (request.appmapMode != RunRequest.AppMapMode.OFF) {
            appmap.collect(runRoot, request.bug, request.appmapMode)
        } else emptyList()

        val prompt = PromptAssembler.assemble(request.bug, runRoot, traces)
        val solution = llm.solve(prompt, request.solver, request.seed)

        val applied = patcher.apply(solution.patch, runRoot)
        if (!applied) {
            return failed(runId, request, start, "patch apply failed")
        }

        val hiddenPassed = builder.runSingleTest(runRoot, request.bug.hiddenTest).success
        val postTests = builder.testAll(runRoot, excludeHidden = null)
        val regressions = (baselineTests.passed - postTests.passed).coerceAtLeast(0)

        val score = scorer.score(
            bug = request.bug,
            patchLines = solution.patchLineCount,
            hiddenPassed = hiddenPassed,
            regressions = regressions,
            prompt = solution,
            start = start
        )

        return RunResult(
            runId = runId,
            bugId = request.bug.id,
            solver = request.solver,
            appmapMode = request.appmapMode.name,
            seed = request.seed,
            passed = hiddenPassed,
            promptTokens = solution.promptTokens,
            completionTokens = solution.completionTokens,
            wallMillis = java.time.Duration.between(start, Instant.now()).toMillis(),
            patchLines = solution.patchLineCount,
            oracleLines = request.bug.oracleDiffLines,
            cyclomaticDelta = score.cyclomaticDelta,
            regressionsIntroduced = regressions
        )
    }

    private fun failed(runId: String, request: RunRequest, start: Instant, note: String): RunResult =
        RunResult(
            runId = runId,
            bugId = request.bug.id,
            solver = request.solver,
            appmapMode = request.appmapMode.name,
            seed = request.seed,
            passed = false,
            promptTokens = 0,
            completionTokens = 0,
            wallMillis = java.time.Duration.between(start, Instant.now()).toMillis(),
            patchLines = 0,
            oracleLines = request.bug.oracleDiffLines,
            cyclomaticDelta = 0,
            regressionsIntroduced = 0,
            notes = note
        )
}
