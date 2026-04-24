package com.aibench.core

import java.nio.file.Files
import java.nio.file.Path

object PromptAssembler {

    private val SYSTEM = """
        You are a software engineer fixing a bug in a Java Spring Boot codebase.
        Read the problem statement and the listed files. Return ONLY a unified diff
        (`git apply` format) that fixes the bug. Do not include prose explanation.
        Prefer the smallest possible change. Do not modify tests unless the problem
        statement explicitly requires it.
    """.trimIndent()

    fun assemble(bug: Bug, worktree: Path, traces: List<AppMapTrace>): Prompt {
        val fileSections = bug.filesTouched.joinToString("\n\n") { relPath ->
            val p = worktree.resolve(relPath)
            val body = if (Files.exists(p)) Files.readString(p) else "(missing)"
            "### $relPath\n```\n$body\n```"
        }

        val traceAttachments = traces.map {
            Prompt.Attachment(
                name = "${it.testName}.appmap.json",
                content = Files.readString(it.path),
                mimeType = "application/json"
            )
        }

        val user = buildString {
            appendLine("# Problem")
            appendLine(bug.problemStatement.trim())
            appendLine()
            appendLine("# Files")
            appendLine(fileSections)
            if (traces.isNotEmpty()) {
                appendLine()
                appendLine("# Runtime traces")
                appendLine("The attached AppMap JSON files are runtime recordings from the passing")
                appendLine("test suite. Use them to ground your fix in actual execution, not just reading.")
            }
        }

        return Prompt(SYSTEM, user, traceAttachments)
    }
}
