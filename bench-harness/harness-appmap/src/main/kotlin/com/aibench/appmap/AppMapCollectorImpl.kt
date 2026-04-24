package com.aibench.appmap

import com.aibench.core.AppMapCollector
import com.aibench.core.AppMapTrace
import com.aibench.core.Bug
import com.aibench.core.RunRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.streams.asSequence

/**
 * Walks `tmp/appmap/` under the build tree and indexes available traces.
 * Filters by recommended tests listed in the bug metadata when the mode is
 * ON_RECOMMENDED; returns every trace for ON_ALL.
 */
class AppMapCollectorImpl : AppMapCollector {

    override fun collect(path: Path, bug: Bug, mode: RunRequest.AppMapMode): List<AppMapTrace> {
        val appmapDirs = Files.walk(path).asSequence()
            .filter { it.fileName?.toString() == "appmap" && Files.isDirectory(it) }
            .toList()

        val allTraces = appmapDirs.flatMap { dir ->
            Files.walk(dir).asSequence()
                .filter { it.toString().endsWith(".appmap.json") }
                .map {
                    val testName = it.fileName.toString().removeSuffix(".appmap.json")
                    AppMapTrace(testName = testName, path = it, sizeBytes = it.fileSize())
                }
                .toList()
        }

        return when (mode) {
            RunRequest.AppMapMode.OFF -> emptyList()
            RunRequest.AppMapMode.ON_ALL -> allTraces
            RunRequest.AppMapMode.ON_RECOMMENDED -> {
                val recommended = bug.appmap?.recommendedTests ?: emptyList()
                if (recommended.isEmpty()) allTraces
                else allTraces.filter { trace -> recommended.any { trace.testName.contains(it, ignoreCase = true) } }
            }
        }
    }
}
