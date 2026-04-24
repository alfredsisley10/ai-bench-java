package com.aibench.cli

import picocli.CommandLine
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(name = "solve", description = ["Run a solver against a bug and score it"])
class SolveCommand : Callable<Int> {

    @CommandLine.Option(names = ["--bug"], required = true, description = ["bug id, e.g. BUG-0001"])
    lateinit var bugId: String

    @CommandLine.Option(names = ["--solver"], required = true, description = ["solver name: corp-openai | copilot"])
    lateinit var solver: String

    @CommandLine.Option(names = ["--appmap"], defaultValue = "OFF",
            description = ["AppMap mode: OFF | ON_RECOMMENDED | ON_ALL"])
    lateinit var appmap: String

    @CommandLine.Option(names = ["--seed"], defaultValue = "1")
    var seed: Long = 1

    @CommandLine.Option(names = ["--catalog-dir"], defaultValue = "bugs")
    lateinit var catalogDir: Path

    @CommandLine.Option(names = ["--work"], defaultValue = ".ai-bench/runs")
    lateinit var work: Path

    override fun call(): Int {
        println("Solving $bugId with solver=$solver appmap=$appmap seed=$seed")
        println("(TODO: wire to com.aibench.core.RunOrchestrator — run driver instantiation)")
        // Real implementation instantiates the orchestrator with SPI impls:
        //   val orchestrator = RunOrchestrator(
        //       worktree = JGitWorktree(bankingAppPath),
        //       builder = GradleBuilder(),
        //       appmap = AppMapCollectorImpl(),
        //       llm = LlmRunnerImpl(LlmClientFactory.forSolver(solver)),
        //       patcher = UnifiedDiffPatcher(),
        //       scorer = DefaultScorer()
        //   )
        //   val result = orchestrator.run(request)
        return 0
    }
}
