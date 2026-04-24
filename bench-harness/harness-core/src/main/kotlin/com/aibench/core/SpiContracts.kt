package com.aibench.core

import java.nio.file.Path

/**
 * Worktree management. Typically backed by jgit; tests use an in-memory impl.
 */
interface Worktree {
    fun checkout(commitRef: String, into: Path)
    fun currentBranch(path: Path): String
}

interface Builder {
    fun build(path: Path): BuildOutcome
    fun testAll(path: Path, excludeHidden: Bug.HiddenTest?): TestOutcome
    fun runSingleTest(path: Path, test: Bug.HiddenTest): TestOutcome
}

data class BuildOutcome(val success: Boolean, val log: String)

data class TestOutcome(val success: Boolean, val passed: Int, val failed: Int, val log: String)

interface AppMapCollector {
    fun collect(path: Path, bug: Bug, mode: RunRequest.AppMapMode): List<AppMapTrace>
}

data class AppMapTrace(val testName: String, val path: Path, val sizeBytes: Long)

interface LlmRunner {
    fun solve(prompt: Prompt, solver: String, seed: Long): Solution
}

data class Prompt(
    val system: String,
    val user: String,
    val attachments: List<Prompt.Attachment> = emptyList()
) {
    data class Attachment(val name: String, val content: String, val mimeType: String)
}

data class Solution(
    val patch: String,
    val patchLineCount: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val modelIdentifier: String
)

interface Patcher {
    fun apply(patch: String, into: Path): Boolean
}

interface Scorer {
    fun score(
        bug: Bug,
        patchLines: Int,
        hiddenPassed: Boolean,
        regressions: Int,
        prompt: Solution,
        start: java.time.Instant
    ): Score
}

data class Score(val cyclomaticDelta: Int, val notes: List<String>)
