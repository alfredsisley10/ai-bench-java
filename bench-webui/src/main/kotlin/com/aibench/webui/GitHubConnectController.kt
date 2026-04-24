package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

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
        val token = session.getAttribute("githubToken") as? String
            ?: System.getenv("GITHUB_TOKEN")
        val host = resolveHost(session)
        model.addAttribute("connected", token != null)
        model.addAttribute("tokenPreview", token?.take(8)?.plus("...") ?: "")
        model.addAttribute("host", host)
        model.addAttribute("apiBaseUrl", apiBaseUrl(host))
        model.addAttribute("isEnterprise", !host.equals(DEFAULT_HOST, ignoreCase = true))
        model.addAttribute("testResult", session.getAttribute("githubTestResult"))
        session.removeAttribute("githubTestResult")
        return "github-connect"
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

    @GetMapping("/github/repos")
    fun listRepos(model: Model): String {
        model.addAttribute("candidates", emptyList<RepoCandidate>())
        // Push the current repo→JIRA mapping in as a name→Link map so
        // the template can render the linked project per row.
        val map = repoAssociations.links.associateBy { it.repo }
        model.addAttribute("jiraLinks", map)
        return "github-repos"
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
}
