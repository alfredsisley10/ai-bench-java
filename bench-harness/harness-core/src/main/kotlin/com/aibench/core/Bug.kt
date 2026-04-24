package com.aibench.core

import kotlinx.serialization.Serializable

/**
 * Bug catalog entry. Mirrors the BUG-NNNN.yaml schema in docs/bug-catalog.md.
 * This is the record a solver is scored against.
 */
@Serializable
data class Bug(
    val id: String,
    val title: String,
    val module: String,
    val difficulty: Difficulty,
    val category: Category,
    val tags: List<String> = emptyList(),

    val breakCommit: String,
    val fixCommit: String,

    val filesTouched: List<String>,
    val oracleDiffLines: Int,

    val hiddenTest: HiddenTest,
    val problemStatement: String,
    val hints: List<String> = emptyList(),
    val appmap: AppMapHint? = null
) {
    @Serializable
    enum class Difficulty { TRIVIAL, EASY, MEDIUM, HARD, CROSS_CUTTING }

    @Serializable
    enum class Category { LOGIC, TIMING, CONCURRENCY, NUMERIC, SECURITY, DATA, STATE_MACHINE, N_PLUS_ONE }

    @Serializable
    data class HiddenTest(
        val `class`: String,
        val method: String,
        val file: String
    )

    @Serializable
    data class AppMapHint(
        val recommendedTests: List<String> = emptyList(),
        val traceFocus: List<String> = emptyList()
    )
}
