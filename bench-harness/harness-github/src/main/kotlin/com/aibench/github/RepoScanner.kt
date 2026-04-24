package com.aibench.github

import org.kohsuke.github.GHRepository
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder

/**
 * Scans repositories the authenticated user has access to and ranks them as
 * candidate benchmark targets. Higher score = better candidate.
 *
 * <p>Rank heuristic (intentionally simple — can be replaced): prefer Java
 * repos with a functioning CI, a non-trivial test suite, recent activity,
 * and > some LOC threshold.
 */
class RepoScanner(
    private val token: String,
    private val apiUrl: String = "https://api.github.com"
) {

    data class Candidate(
        val fullName: String,
        val defaultBranch: String,
        val primaryLanguage: String?,
        val sizeKb: Int,
        val stargazers: Int,
        val lastPushedDaysAgo: Long,
        val hasCi: Boolean,
        val hasTests: Boolean,
        val score: Double
    )

    fun scan(max: Int = 100): List<Candidate> {
        val gh: GitHub = GitHubBuilder().withEndpoint(apiUrl).withOAuthToken(token).build()
        return gh.myself.listRepositories().asSequence()
            .take(max)
            .map { evaluate(it) }
            .sortedByDescending { it.score }
            .toList()
    }

    private fun evaluate(repo: GHRepository): Candidate {
        val primary = repo.language
        val days = (System.currentTimeMillis() - repo.pushedAt.time) / 86_400_000L
        val hasCi = runCatching { repo.getFileContent(".github/workflows") != null }.getOrDefault(false)
        val hasTests = runCatching {
            repo.getDirectoryContent("src/test").isNotEmpty()
        }.getOrDefault(false)

        var score = 0.0
        if (primary.equals("Java", ignoreCase = true)) score += 30
        if (primary.equals("Kotlin", ignoreCase = true)) score += 20
        if (hasCi) score += 15
        if (hasTests) score += 25
        if (repo.size >= 5_000) score += 10
        if (days < 60) score += 10 else if (days < 365) score += 5
        if (!repo.isArchived) score += 5

        return Candidate(
            fullName = repo.fullName,
            defaultBranch = repo.defaultBranch,
            primaryLanguage = primary,
            sizeKb = repo.size,
            stargazers = repo.stargazersCount,
            lastPushedDaysAgo = days,
            hasCi = hasCi,
            hasTests = hasTests,
            score = score
        )
    }
}
