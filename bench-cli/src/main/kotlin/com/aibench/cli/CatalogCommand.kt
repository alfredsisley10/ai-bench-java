package com.aibench.cli

import com.aibench.core.BugCatalog
import picocli.CommandLine
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(name = "catalog", description = ["List or show bugs in the catalog"])
class CatalogCommand : Callable<Int> {

    @CommandLine.Option(
        names = ["--catalog-dir"],
        description = ["Path to bugs/ directory (default: ./bugs)"]
    )
    var catalogDir: Path = Path.of("bugs")

    @CommandLine.Parameters(index = "0", description = ["list | show"], defaultValue = "list")
    lateinit var action: String

    @CommandLine.Parameters(index = "1", description = ["bug id (for show)"], defaultValue = "")
    lateinit var bugId: String

    override fun call(): Int {
        val catalog = BugCatalog(catalogDir)
        return when (action) {
            "list" -> {
                catalog.load().sortedBy { it.id }.forEach { b ->
                    println("${b.id}  ${b.difficulty.name.padEnd(14)} ${b.category.name.padEnd(14)} ${b.module.padEnd(22)} ${b.title}")
                }
                0
            }
            "show" -> {
                val b = catalog.byId(bugId)
                println("ID:         ${b.id}")
                println("Title:      ${b.title}")
                println("Module:     ${b.module}")
                println("Difficulty: ${b.difficulty}")
                println("Category:   ${b.category}")
                println("Files:      ${b.filesTouched}")
                println()
                println("Problem:")
                println(b.problemStatement.lineSequence().joinToString("\n") { "  $it" })
                0
            }
            else -> {
                System.err.println("Unknown action: $action")
                2
            }
        }
    }
}
