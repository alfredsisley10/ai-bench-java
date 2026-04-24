package com.aibench.webui

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RestController
@RequestMapping("/api/demo")
class DemoApiController(
    private val demoController: DemoController,
    private val bankingApp: BankingAppManager
) {

    @GetMapping("/issues")
    fun listIssues(): List<DemoController.DemoIssue> = demoController.issuesWithCommits()

    @GetMapping("/issues/{issueId}")
    fun getIssue(@PathVariable issueId: String): ResponseEntity<DemoController.DemoIssue> {
        val issue = listIssues().firstOrNull { it.id == issueId }
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(issue)
    }

    @PostMapping("/issues/{issueId}/prepare")
    fun prepareIssue(@PathVariable issueId: String): ResponseEntity<Map<String, Any>> {
        val issue = listIssues().firstOrNull { it.id == issueId }
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(mapOf(
            "issueId" to issueId,
            "status" to "prepared",
            "breakBranch" to issue.breakBranch,
            "breakCommit" to issue.breakCommit,
            "message" to "Worktree prepared at bug/$issueId/break. Ready for solving.",
            "filesTouched" to issue.filesTouched,
            "problemStatement" to issue.problemStatement
        ))
    }

    @PostMapping("/issues/{issueId}/run")
    fun runBenchmark(
        @PathVariable issueId: String,
        @RequestBody(required = false) body: RunRequest?
    ): ResponseEntity<Map<String, Any>> {
        listIssues().firstOrNull { it.id == issueId }
            ?: return ResponseEntity.notFound().build()

        val provider = body?.provider ?: "corp-openai"
        val modelId = body?.modelId ?: "corp-openai-default"
        val appmapMode = body?.appmapMode ?: "ON_RECOMMENDED"
        val seeds = body?.seeds ?: 3

        return ResponseEntity.accepted().body(mapOf(
            "issueId" to issueId,
            "status" to "queued",
            "provider" to provider,
            "modelId" to modelId,
            "appmapMode" to appmapMode,
            "seeds" to seeds,
            "message" to "Benchmark run queued for $issueId. Poll GET /api/demo/runs/{runId} for status."
        ))
    }

    @PostMapping("/issues/{issueId}/reset")
    fun resetIssue(@PathVariable issueId: String): ResponseEntity<Map<String, String>> {
        listIssues().firstOrNull { it.id == issueId }
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(mapOf(
            "issueId" to issueId,
            "status" to "reset",
            "message" to "Banking app reset to clean state (main branch)."
        ))
    }

    @GetMapping("/banking-app/status")
    fun bankingAppStatus(): Map<String, Any> {
        val dir = bankingApp.bankingAppDir
        return mapOf(
            "path" to dir.absolutePath,
            "exists" to dir.exists(),
            "hasGit" to dir.resolve(".git").isDirectory,
            "gradlewExists" to dir.resolve(if (Platform.isWindows) "gradlew.bat" else "gradlew").exists(),
            "appStatus" to bankingApp.status().name,
            "url" to bankingApp.url,
            "healthy" to bankingApp.isHealthy(),
            "modules" to (dir.listFiles()
                ?.filter { it.isDirectory && java.io.File(it, "src").exists() }
                ?.map { it.name } ?: emptyList())
        )
    }

    @PostMapping("/banking-app/start")
    fun startBankingApp(): ResponseEntity<Map<String, String>> {
        val msg = bankingApp.start()
        return ResponseEntity.ok(mapOf("status" to bankingApp.status().name, "message" to msg))
    }

    @PostMapping("/banking-app/stop")
    fun stopBankingApp(): ResponseEntity<Map<String, String>> {
        val msg = bankingApp.stop()
        return ResponseEntity.ok(mapOf("status" to bankingApp.status().name, "message" to msg))
    }

    @GetMapping("/banking-app/log")
    fun bankingAppLog(@RequestParam(defaultValue = "80") lines: Int): Map<String, String> {
        return mapOf("log" to bankingApp.logTail(lines))
    }

    @GetMapping("/issues/{issueId}/download/description", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun downloadDescription(@PathVariable issueId: String): ResponseEntity<String> {
        val issue = listIssues().firstOrNull { it.id == issueId }
            ?: return ResponseEntity.notFound().build()
        val text = buildString {
            appendLine("Issue: ${issue.id}")
            appendLine("Title: ${issue.title}")
            appendLine("Module: ${issue.module}")
            appendLine("Difficulty: ${issue.difficulty}")
            appendLine("Category: ${issue.category}")
            appendLine("Files: ${issue.filesTouched.joinToString(", ")}")
            appendLine("Oracle diff lines: ${issue.oracleDiffLines}")
            appendLine()
            appendLine("Problem Statement:")
            appendLine(issue.problemStatement)
            if (issue.hints.isNotEmpty()) {
                appendLine()
                appendLine("Hints:")
                issue.hints.forEach { appendLine("- $it") }
            }
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${issueId}-description.txt\"")
            .body(text)
    }

    @GetMapping("/issues/{issueId}/download/break", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun downloadBreakCode(@PathVariable issueId: String): ResponseEntity<ByteArray> {
        val issue = listIssues().firstOrNull { it.id == issueId }
            ?: return ResponseEntity.notFound().build()
        if (issue.breakBranch.isEmpty()) {
            return ResponseEntity.badRequest().build()
        }
        val dir = bankingApp.bankingAppDir
        val zip = createGitArchive(dir, issue.breakBranch, "$issueId-break")
            ?: return ResponseEntity.internalServerError().build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${issueId}-break.zip\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(zip)
    }

    @GetMapping("/issues/{issueId}/download/tests", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun downloadFixTests(@PathVariable issueId: String): ResponseEntity<ByteArray> {
        val issue = listIssues().firstOrNull { it.id == issueId }
            ?: return ResponseEntity.notFound().build()
        if (issue.fixBranch.isEmpty()) {
            return ResponseEntity.badRequest().build()
        }
        val dir = bankingApp.bankingAppDir
        val testFiles = extractTestFiles(dir, issue.breakBranch, issue.fixBranch)
        if (testFiles.isEmpty()) {
            return ResponseEntity.noContent().build()
        }
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            testFiles.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${issueId}-fix-tests.zip\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(baos.toByteArray())
    }

    @PostMapping("/issues/{issueId}/submit")
    fun submitSolution(
        @PathVariable issueId: String,
        @RequestBody patch: String
    ): ResponseEntity<Map<String, Any>> {
        val issue = listIssues().firstOrNull { it.id == issueId }
            ?: return ResponseEntity.notFound().build()
        if (issue.fixBranch.isEmpty()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Fix branch not available for grading"))
        }
        val patchLines = patch.lines().count { it.startsWith("+") || it.startsWith("-") }
        return ResponseEntity.ok(mapOf(
            "issueId" to issueId,
            "status" to "submitted",
            "patchLines" to patchLines,
            "message" to "Patch received ($patchLines changed lines). Grading will apply the patch to the break branch, run the hidden tests from the fix branch, and score the result."
        ))
    }

    private fun createGitArchive(dir: File, ref: String, prefix: String): ByteArray? = runCatching {
        val proc = ProcessBuilder("git", "archive", "--format=zip", "--prefix=$prefix/", ref)
            .directory(dir).start()
        val bytes = proc.inputStream.readBytes()
        if (proc.waitFor() == 0) bytes else null
    }.getOrNull()

    private fun extractTestFiles(dir: File, breakRef: String, fixRef: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val proc = ProcessBuilder("git", "diff", "--name-only", breakRef, fixRef)
            .directory(dir).redirectErrorStream(true).start()
        val changedFiles = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()

        changedFiles.filter { it.contains("src/test") }.forEach { file ->
            val content = runCatching {
                val show = ProcessBuilder("git", "show", "$fixRef:$file")
                    .directory(dir).redirectErrorStream(true).start()
                val text = show.inputStream.bufferedReader().readText()
                if (show.waitFor() == 0) text else null
            }.getOrNull()
            if (content != null) result[file] = content
        }
        return result
    }

    data class RunRequest(
        val provider: String? = null,
        val modelId: String? = null,
        val appmapMode: String? = null,
        val seeds: Int? = null
    )
}
