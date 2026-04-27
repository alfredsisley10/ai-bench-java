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
        val description: String = "",
        // Size + activity metrics surfaced in the ranked table and the
        // CSV / XLSX export. -1 on the count fields means "not yet
        // enriched" — the per-repo follow-up API call hasn't run or
        // failed (rate-limit, 409 on empty repo, etc.). The template
        // renders -1 as an em-dash so a missing enrichment doesn't read
        // as "0 commits".
        val stars: Int = 0,
        val sizeKb: Int = 0,
        val openIssues: Int = 0,
        val pushedAt: String = "",
        val createdAt: String = "",
        val defaultBranch: String = "main",
        val visibility: String = "",
        val isFork: Boolean = false,
        val isArchived: Boolean = false,
        val commitCount: Int = -1,
        val contributorCount: Int = -1,
        val totalCodeBytes: Long = -1L
    ) {
        /**
         * Rough lines-of-code estimate from `/repos/.../languages` byte
         * counts. ~30 bytes/line is the convention for Java-ish source;
         * fine for ranking but don't quote it as ground truth.
         */
        val estimatedLoc: Int
            get() = if (totalCodeBytes < 0) -1
                    else (totalCodeBytes / 30L).toInt().coerceAtLeast(0)
    }

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
        @RequestParam(defaultValue = "10") batchSize: Int,
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
            // Score first (free, in-memory), then enrich with extra
            // per-repo API calls for commit/contributor totals + LOC
            // estimate. Sequential to keep traffic predictable; with
            // batchSize=10 the enrichment adds ~30 extra calls and
            // ~2-3s wall time, still trivially within rate limits.
            val scored = parsed.map(::scoreRepo).map { enrichCandidate(it, token, apiBase) }
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

    /**
     * CSV export of every candidate currently in the session's scan
     * state. Headers match the on-screen table 1:1 plus a few raw
     * fields (description, created_at, visibility) the table truncates
     * or omits but a downstream spreadsheet may want.
     */
    @GetMapping("/github/repos/export.csv", produces = ["text/csv;charset=UTF-8"])
    @org.springframework.web.bind.annotation.ResponseBody
    fun exportCsv(session: HttpSession): org.springframework.http.ResponseEntity<ByteArray> {
        val rows = scanState(session).candidates
        val sb = StringBuilder()
        sb.append(EXPORT_HEADERS.joinToString(",")).append('\n')
        rows.forEach { c ->
            sb.append(toExportRow(c).joinToString(",") { csvEscape(it) }).append('\n')
        }
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"github-repos.csv\"")
            .body(sb.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * XLSX export. Apache POI streaming workbook -- writes directly to
     * the response body without buffering the whole sheet first.
     */
    @GetMapping(
        "/github/repos/export.xlsx",
        produces = ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"]
    )
    @org.springframework.web.bind.annotation.ResponseBody
    fun exportXlsx(session: HttpSession): org.springframework.http.ResponseEntity<ByteArray> {
        val rows = scanState(session).candidates
        val out = java.io.ByteArrayOutputStream()
        org.apache.poi.xssf.streaming.SXSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("repos")
            val header = sheet.createRow(0)
            EXPORT_HEADERS.forEachIndexed { i, h -> header.createCell(i).setCellValue(h) }
            rows.forEachIndexed { rowIdx, c ->
                val r = sheet.createRow(rowIdx + 1)
                toExportRow(c).forEachIndexed { i, v -> r.createCell(i).setCellValue(v) }
            }
            wb.write(out)
        }
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"github-repos.xlsx\"")
            .body(out.toByteArray())
    }

    // Single source of truth for column order — the on-screen table,
    // CSV, and XLSX all use this list so they stay in lockstep.
    private val EXPORT_HEADERS = listOf(
        "Repository", "Description", "Language", "Score",
        "Stars", "Open issues", "Size (KB)", "Est. LOC",
        "Commits", "Contributors", "Total bytes",
        "Default branch", "Visibility", "Fork", "Archived",
        "Created at", "Last push"
    )

    private fun toExportRow(c: RepoCandidate): List<String> = listOf(
        c.fullName, c.description, c.primaryLanguage,
        "%.1f".format(c.score),
        c.stars.toString(),
        c.openIssues.toString(),
        c.sizeKb.toString(),
        if (c.estimatedLoc < 0) "" else c.estimatedLoc.toString(),
        if (c.commitCount < 0) "" else c.commitCount.toString(),
        if (c.contributorCount < 0) "" else c.contributorCount.toString(),
        if (c.totalCodeBytes < 0) "" else c.totalCodeBytes.toString(),
        c.defaultBranch, c.visibility,
        if (c.isFork) "yes" else "no",
        if (c.isArchived) "yes" else "no",
        c.createdAt, c.pushedAt
    )

    private fun csvEscape(s: String): String {
        if (s.isEmpty()) return ""
        if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            return "\"" + s.replace("\"", "\"\"") + "\""
        }
        return s
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
        val open_issues_count: Int = 0,
        val fork: Boolean = false,
        val archived: Boolean = false,
        val pushed_at: String? = null,
        val created_at: String? = null,
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
            description = r.description.orEmpty(),
            stars = r.stargazers_count,
            sizeKb = r.size,
            openIssues = r.open_issues_count,
            pushedAt = r.pushed_at.orEmpty(),
            createdAt = r.created_at.orEmpty(),
            defaultBranch = r.default_branch,
            visibility = r.visibility,
            isFork = r.fork,
            isArchived = r.archived
        )
    }

    /**
     * Enrich one candidate with three follow-up API calls:
     *  - {@code /commits?per_page=1} — Link header's <code>rel="last"</code>
     *    page number is the commit total on the default branch
     *  - {@code /contributors?per_page=1&anon=true} — same Link-header trick
     *    yields the contributor total (incl. anonymous email-only authors)
     *  - {@code /languages} — bytes-per-language map; we sum it for
     *    the LOC estimate column
     *
     * Each call is wrapped in its own try/catch so a single failed
     * enrichment (rate-limit hit on a forked-from-massive-repo, 409 on
     * an empty repo, etc.) doesn't blow up the whole scan. Fields keep
     * their default of -1 when enrichment fails so the UI renders them
     * as em-dashes rather than misleading 0s.
     */
    private fun enrichCandidate(
        c: RepoCandidate,
        token: String,
        apiBase: String
    ): RepoCandidate {
        val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(15))
        val baseRepo = "$apiBase/repos/${c.fullName}"

        fun head(url: String): java.net.http.HttpResponse<String>? = try {
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(java.time.Duration.ofSeconds(15))
                .GET().build()
            client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) { null }

        var commits = -1
        head("$baseRepo/commits?per_page=1&sha=${c.defaultBranch}")?.let { resp ->
            if (resp.statusCode() == 200) {
                val link = resp.headers().firstValue("Link").orElse("")
                commits = lastPageFromLinkHeader(link)
                    // No Link header but body is non-empty -> exactly 1 commit
                    ?: if (resp.body().contains("\"sha\"")) 1 else 0
            } else if (resp.statusCode() == 409) {
                // Empty repo -- GitHub's documented response for this
                // case. Recording 0 here lets the column sort cleanly.
                commits = 0
            }
        }

        var contributors = -1
        head("$baseRepo/contributors?per_page=1&anon=true")?.let { resp ->
            if (resp.statusCode() == 200) {
                val link = resp.headers().firstValue("Link").orElse("")
                contributors = lastPageFromLinkHeader(link)
                    ?: if (resp.body().trim().startsWith("[") && resp.body().contains("\"login\"")) 1 else 0
            } else if (resp.statusCode() == 204) {
                // No content = no contributors recorded. Empty repo.
                contributors = 0
            }
        }

        var totalBytes = -1L
        head("$baseRepo/languages")?.let { resp ->
            if (resp.statusCode() == 200) {
                // Body shape: {"Java":1234,"Kotlin":567}. A regex sum is
                // good enough; full JSON parse would be overkill.
                totalBytes = Regex(""":\s*(\d+)""").findAll(resp.body())
                    .sumOf { it.groupValues[1].toLong() }
            }
        }

        return c.copy(
            commitCount = commits,
            contributorCount = contributors,
            totalCodeBytes = totalBytes
        )
    }

    /**
     * Parse <code>rel="last"</code> URL out of a GitHub Link header and
     * return the {@code page=} query value. That number is the total
     * count when the per_page is 1, which is the trick we use to get a
     * commit/contributor total in O(1) requests instead of paging.
     * Returns null when the header is missing or unparseable, in which
     * case the caller falls back to body-shape inference.
     */
    private fun lastPageFromLinkHeader(linkHeader: String?): Int? {
        if (linkHeader.isNullOrEmpty()) return null
        val rel = Regex("""<([^>]+)>;\s*rel="last"""").find(linkHeader) ?: return null
        val page = Regex("""[?&]page=(\d+)""").find(rel.groupValues[1]) ?: return null
        return page.groupValues[1].toIntOrNull()
    }
}
