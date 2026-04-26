package com.aibench.builder

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Writes a scoped Gradle init script into a given location, then deletes it
 * once the build ends. The script rewrites buildscript/repositories to the
 * corporate Artifactory mirror and injects proxy settings.
 */
class GradleInitScriptWriter(private val cfg: EnterpriseBuildConfig) {

    fun write(dir: Path): Path {
        Files.createDirectories(dir)
        val path = dir.resolve("ai-bench-enterprise-${UUID.randomUUID()}.init.gradle.kts")
        val script = cfg.artifactoryUrl?.let { url ->
            """
            buildscript { repositories { ${corpRepoBlock(url)} } }
            allprojects { repositories { ${corpRepoBlock(url)} } }
            """.trimIndent()
        }.orEmpty()
        Files.writeString(path, script)
        return path
    }

    fun delete(path: Path) {
        Files.deleteIfExists(path)
    }

    private fun corpRepoBlock(url: String) = """
        clear()
        maven {
            url = uri("$url")
            credentials {
                username = System.getenv("ARTIFACTORY_USER")
                password = System.getenv("ARTIFACTORY_TOKEN")
            }
        }
    """.trimIndent()
}
