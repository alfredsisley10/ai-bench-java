package com.aibench.cli

import picocli.CommandLine
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exit = CommandLine(BenchCommand())
        .addSubcommand(CatalogCommand())
        .addSubcommand(SolveCommand())
        .addSubcommand(ScanCommand())
        .addSubcommand(BuildCommand())
        .addSubcommand(ReportCommand())
        .execute(*args)
    exitProcess(exit)
}

@CommandLine.Command(
    name = "bench",
    mixinStandardHelpOptions = true,
    version = ["0.1.0"],
    description = ["ai-bench-java benchmark driver"],
    subcommands = [
        CatalogCommand::class,
        SolveCommand::class,
        ScanCommand::class,
        BuildCommand::class,
        ReportCommand::class
    ]
)
class BenchCommand : Runnable {
    override fun run() {
        CommandLine.usage(this, System.out)
    }
}
