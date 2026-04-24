package com.aibench.cli

import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(name = "scan", description = ["Scan a user's GitHub to rank benchmark candidates"])
class ScanCommand : Callable<Int> {

    @CommandLine.Option(names = ["--api-url"], defaultValue = "https://api.github.com")
    lateinit var apiUrl: String

    @CommandLine.Option(names = ["--max"], defaultValue = "100")
    var max: Int = 100

    override fun call(): Int {
        val token = System.getenv("GITHUB_TOKEN")
            ?: System.getenv("AI_BENCH_GITHUB_TOKEN")
        if (token == null) {
            System.err.println("Set GITHUB_TOKEN env var, or run the webui to authenticate via device flow.")
            return 2
        }
        // com.aibench.github.RepoScanner(token, apiUrl).scan(max).forEach { ... }
        println("(TODO: delegate to harness-github RepoScanner and render ranked table)")
        return 0
    }
}
