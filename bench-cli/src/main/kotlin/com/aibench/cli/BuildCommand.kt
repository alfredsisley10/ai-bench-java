package com.aibench.cli

import picocli.CommandLine
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(name = "build", description = ["Build a Gradle/Maven project with enterprise proxy + Artifactory config"])
class BuildCommand : Callable<Int> {

    @CommandLine.Parameters(index = "0", description = ["project directory"])
    lateinit var projectDir: Path

    override fun call(): Int {
        // com.aibench.builder.GradleBuilder(EnterpriseBuildConfig()).build(projectDir)
        println("(TODO: delegate to harness-builder GradleBuilder)")
        return 0
    }
}
