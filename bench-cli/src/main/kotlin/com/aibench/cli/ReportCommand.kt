package com.aibench.cli

import picocli.CommandLine
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(name = "report", description = ["Aggregate run results into pass-rate pivots"])
class ReportCommand : Callable<Int> {

    @CommandLine.Option(names = ["--runs"], defaultValue = "reports/summary.csv")
    lateinit var runs: Path

    @CommandLine.Option(names = ["--pivot"], defaultValue = "category")
    lateinit var pivot: String

    override fun call(): Int {
        println("(TODO: read $runs, pivot by $pivot, emit CSV + Markdown summary)")
        return 0
    }
}
