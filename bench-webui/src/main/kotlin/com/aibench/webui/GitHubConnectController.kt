package com.aibench.webui

import jakarta.servlet.http.HttpSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

@Controller
class GitHubConnectController(
    private val connectionSettings: ConnectionSettings,
    private val repoAssociations: RepoProjectAssociations
) {

    companion object {
        const val DEFAULT_HOST = "github.com"

        /**
         * Resolve the REST API base URL for a given GitHub host. Public
         * github.com uses api.github.com; every GitHub Enterprise Server
         * deployment exposes the same REST API at /api/v3 on its own
         * hostname. GHES releases since 2.x have followed this convention.
         */
        fun apiBaseUrl(host: String): String =
            if (normalizeHost(host).equals(DEFAULT_HOST, ignoreCase = true))
                "https://api.github.com"
            else
                "https://${normalizeHost(host)}/api/v3"

        /** Device-flow landing page. Lives at /login/device on both. */
        fun deviceVerificationUrl(host: String): String =
            "https://${normalizeHost(host)}/login/device"

        /** Strip scheme / trailing slashes / whitespace from user input. */
        fun normalizeHost(raw: String?): String {
            if (raw.isNullOrBlank()) return DEFAULT_HOST
            return raw.trim()
                .removePrefix("https://").removePrefix("http://")
                .removeSuffix("/")
        }
    }

    data class RepoCandidate(
        val fullName: String,
        val primaryLanguage: String,
        val score: Double,
        val hasCi: Boolean,
        val hasTests: Boolean,
        val selected: Boolean = false,
        val description: String = ""
    )

    data class RepoBuildStatus(
        val repoName: String,
        val status: String,
        val lastBuildTime: String = "",
        val testsPassed: Int = 0,
        val testsFailed: Int = 0,
        val log: String = ""
    )

    private fun resolveHost(session: HttpSession): String =
        normalizeHost(
            (session.getAttribute("githubHost") as? String)
                ?: System.getenv("GITHUB_HOST")
        )

    @GetMapping("/github")
    fun connect(model: Model, session: HttpSession): String {
        val sessionToken = session.getAttribute("githubToken") as? String
        val envToken = System.getenv("GITHUB_TOKEN")
        val token = sessionToken ?: envToken
        val host = resolveHost(session)
        model.addAttribute("connected", token != null)
        model.addAttribute("tokenPreview", token?.take(8)?.plus("...") ?: "")
        // Surface whether the active token comes from the env var (which
        // /github/clear-token cannot remove) so the UI can render the
        // appropriate disconnect hint.
        model.addAttribute("tokenFromEnv", sessionToken == null && envToken != null)
        model.addAttribute("host", host)
        model.addAttribute("apiBaseUrl", apiBaseUrl(host))
        model.addAttribute("isEnterprise", !host.equals(DEFAULT_HOST, ignoreCase = true))
        model.addAttribute("testResult", session.getAttribute("githubTestResult"))
        session.removeAttribute("githubTestResult")
        return "github-connect"
    }

    @PostMapping("/github/clear-token")
    fun clearToken(session: HttpSession): String {
        session.removeAttribute("githubToken")
        val envToken = System.getenv("GITHUB_TOKEN")
        val message = if (envToken != null) {
            "Session token cleared, but \$GITHUB_TOKEN env var is still set — " +
                "the WebUI will keep using that env-var token until bench-webui is " +
                "restarted with the variable unset."
        } else {
            "GitHub token cleared."
        }
        session.setAttribute(
            "githubTestResult",
            mapOf("success" to true, "message" to message)
        )
        return "redirect:/github"
    }

    @PostMapping("/github/save-host")
    fun saveHost(@RequestParam(required = false) host: String?, session: HttpSession): String {
        val normalized = normalizeHost(host)
        session.setAttribute("githubHost", normalized)
        session.setAttribute(
            "githubTestResult",
            mapOf(
                "success" to true,
                "message" to "GitHub host set to $normalized (API base: ${apiBaseUrl(normalized)})"
            )
        )
        return "redirect:/github"
    }

    @PostMapping("/github/start-device-flow")
    @ResponseBody
    fun startDeviceFlow(
        @RequestParam clientId: String,
        session: HttpSession
    ): Map<String, String> {
        val host = resolveHost(session)
        return mapOf(
            "userCode" to "WXYZ-1234",
            "verificationUri" to deviceVerificationUrl(host),
            "note" to "Complete authorization in browser, then poll /github/poll-token"
        )
    }

    @PostMapping("/github/save-token")
    fun saveToken(@RequestParam token: String, session: HttpSession): String {
        session.setAttribute("githubToken", token)
        return "redirect:/github"
    }

    @PostMapping("/github/test")
    fun testConnection(session: HttpSession): String {
        val token = session.getAttribute("githubToken") as? String
            ?: System.getenv("GITHUB_TOKEN")
        val host = resolveHost(session)
        val apiBase = apiBaseUrl(host)

        if (token.isNullOrEmpty()) {
            session.setAttribute("githubTestResult", mapOf("success" to false, "message" to "No GitHub token configured. Save a token first."))
            return "redirect:/github"
        }

        val result = try {
            val url = java.net.URI.create("$apiBase/user")
            val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(10))
            val request = java.net.http.HttpRequest.newBuilder().uri(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .timeout(java.time.Duration.ofSeconds(10))
                .GET().build()
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val loginMatch = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(response.body())
                val login = loginMatch?.groupValues?.get(1) ?: "unknown"
                mapOf("success" to true, "message" to "Authenticated as $login on $host")
            } else {
                mapOf("success" to false, "message" to "GitHub API at $apiBase returned HTTP ${response.statusCode()}")
            }
        } catch (e: Exception) {
            mapOf("success" to false, "message" to "Connection to $apiBase failed: ${e.message}")
        }

        session.setAttribute("githubTestResult", result)
        return "redirect:/github"
    }

    /**
     * Per-session scan state. Lives in HttpSession (not globalState,
     * not a singleton) so concurrent operators in different sessions
     * have independent scans, and an explicit Reset clears it without
     * touching anyone else.
     */
    data class ScanState(
        var nextPage: Int = 1,
        var totalSeen: Int = 0,
        var candidates: List<RepoCandidate> = emptyList(),
        var hasMore: Boolean = true,
        var lastError: String = "",
        var lastBatchAdded: Int = 0,
        var lastBatchAt: String = ""
    )

    private fun scanState(session: HttpSession): ScanState {
        val existing = session.getAttribute("githubScanState") as? ScanState
        if (existing != null) return existing
        val fresh = ScanState()
        session.setAttribute("githubScanState", fresh)
        return fresh
    }

    @GetMapping("/github/repos")
    fun listRepos(session: HttpSession, model: Model): String {
        val state = scanState(session)
        model.addAttribute("candidates", state.candidates)
        model.addAttribute("scanNextPage", state.nextPage)
        model.addAttribute("scanTotalSeen", state.totalSeen)
        model.addAttribute("scanHasMore", state.hasMore)
        model.addAttribute("scanError", state.lastError)
        model.addAttribute("scanLastBatchAdded", state.lastBatchAdded)
        model.addAttribute("scanLastBatchAt", state.lastBatchAt)
        // Push the current repo→JIRA mapping in as a name→Link map so
        // the template can render the linked project per row.
        val map = repoAssociations.links.associateBy { it.repo }
        model.addAttribute("jiraLinks", map)
        return "github-repos"
    }

    /**
     * Fetch the next page of /user/repos and merge into the running
     * candidate list. Operator-paced: each click is exactly one GitHub
     * API call, so the scan stays well within rate limits even on
     * Enterprise installs with thousands of accessible repos. Repos
     * are sorted by `pushed` desc on GitHub's side, then re-ranked
     * locally by our scoring heuristic — the top of the list converges
     * quickly and the user can stop whenever the head looks right.
     */
    @PostMapping("/github/repos/scan-next")
    fun scanNext(
        @RequestParam(defaultValue = "30") batchSize: Int,
        session: HttpSession
    ): String {
        val state = scanState(session)
        val token = session.getAttribute("githubToken") as? String
            ?: System.getenv("GITHUB_TOKEN")
        if (token.isNullOrEmpty()) {
            state.lastError = "No GitHub token configured. Visit /github first to connect."
            return "redirect:/github/repos"
        }
        val apiBase = apiBaseUrl(resolveHost(session))
        val cap = batchSize.coerceIn(5, 100)
        val page = state.nextPage

        try {
            val url = java.net.URI.create(
                "$apiBase/user/repos?per_page=$cap&page=$page&sort=pushed&direction=desc"
            )
            val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(20))
            val req = java.net.http.HttpRequest.newBuilder().uri(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(java.time.Duration.ofSeconds(20))
                .GET().build()
            val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                state.lastError = "GitHub returned HTTP ${resp.statusCode()}: " +
                    resp.body().take(200).replace("\n", " ")
                return "redirect:/github/repos"
            }
            val parsed = Json { ignoreUnknownKeys = true }
                .decodeFromString<List<GhRepo>>(resp.body())
            val scored = parsed.map(::scoreRepo)
            // Merge by full_name (in case of overlap from a re-fetch)
            // and re-sort by score desc — the head of the list reflects
            // the best candidate seen across ALL pages so far, not just
            // the latest page.
            val merged = (state.candidates + scored)
                .associateBy { it.fullName }
                .values
                .sortedByDescending { it.score }
                .toList()

            state.nextPage = page + 1
            state.totalSeen = merged.size
            state.candidates = merged
            // GitHub returned fewer than per_page entries -> we hit the
            // tail of the user's accessible repo list. The scan is done
            // unless the user explicitly resets.
            state.hasMore = parsed.size == cap
            state.lastError = ""
            state.lastBatchAdded = parsed.size
            state.lastBatchAt = Instant.now().toString()
        } catch (e: Exception) {
            state.lastError = "Scan failed: ${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
        return "redirect:/github/repos"
    }

    /** Reset the running scan so the next "Fetch next batch" starts at page 1. */
    @PostMapping("/github/repos/reset-scan")
    fun resetScan(session: HttpSession): String {
        session.removeAttribute("githubScanState")
        return "redirect:/github/repos"
    }

    @PostMapping("/github/repos/select")
    fun selectRepos(@RequestParam repos: List<String>): String {
        return "redirect:/github/repos/status"
    }

    @GetMapping("/github/repos/status")
    fun buildStatus(model: Model): String {
        model.addAttribute("builds", emptyList<RepoBuildStatus>())
        return "github-build-status"
    }

    /**
     * Subset of GitHub's /user/repos response we use for ranking. Every
     * field is optional with a default so a missing/null value in the
     * payload (e.g. an enterprise install that omits stargazers_count)
     * doesn't throw at deserialization.
     */
    @Serializable
    private data class GhRepo(
        val full_name: String = "",
        val description: String? = null,
        val language: String? = null,
        val size: Int = 0,
        val stargazers_count: Int = 0,
        val fork: Boolean = false,
        val archived: Boolean = false,
        val pushed_at: String? = null,
        val default_branch: String = "main",
        val visibility: String = ""
    )

    /**
     * Score a repo for benchmark suitability on a 0-100ish scale. The
     * exact weights are heuristic; the goal is to surface Java/Kotlin
     * repos with real test suites that haven't gone dormant. Two
     * principles:
     *   - Prefer real production repos (recent activity, non-fork,
     *     non-archive, non-trivial size) over toy projects.
     *   - Don't punish missing data: if pushed_at is null we assume
     *     the repo just lacks the metadata, not that it's stale.
     */
    private fun scoreRepo(r: GhRepo): RepoCandidate {
        var score = 0.0
        when (r.language?.lowercase()) {
            "java", "kotlin" -> score += 50.0     // primary benchmark targets
            "scala", "groovy" -> score += 30.0    // jvm-adjacent
            "python", "typescript", "javascript", "go", "rust", "c#", "c++" -> score += 20.0
            null -> score += 0.0
            else -> score += 10.0
        }
        when {
            r.size >= 10_000 -> score += 25.0     // ~10 MB+ — substantial
            r.size >= 1_000 -> score += 15.0      // ~1 MB+ — non-trivial
            r.size >= 100 -> score += 5.0         // ~100 KB+ — at least real code
        }
        if (r.stargazers_count >= 100) score += 10.0
        else if (r.stargazers_count >= 10) score += 5.0
        if (r.archived) score -= 50.0             // stale by definition
        if (r.fork) score -= 15.0                 // upstream is usually the better target
        // Recency bonus. Bias toward repos pushed within the last year.
        val recencyBonus = r.pushed_at?.let { iso ->
            try {
                val pushed = Instant.parse(iso)
                val days = ChronoUnit.DAYS.between(pushed, Instant.now())
                when {
                    days < 30 -> 15.0
                    days < 180 -> 10.0
                    days < 365 -> 5.0
                    else -> 0.0
                }
            } catch (_: DateTimeParseException) { 0.0 }
        } ?: 0.0
        score += recencyBonus

        return RepoCandidate(
            fullName = r.full_name,
            primaryLanguage = r.language ?: "(unknown)",
            score = score.coerceAtLeast(0.0),
            // hasCi/hasTests would require an extra API call per repo
            // (fetch contents of .github/workflows/ etc) which is too
            // expensive at scan time. The harness's build phase will
            // determine these at run time.
            hasCi = false,
            hasTests = false,
            description = r.description.orEmpty()
        )
    }
}
