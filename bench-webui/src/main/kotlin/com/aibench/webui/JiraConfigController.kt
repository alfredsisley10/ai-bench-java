package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class JiraConfigController(
    private val connectionSettings: ConnectionSettings,
    private val secretStore: SecretStore,
    private val repoAssociations: RepoProjectAssociations
) {

    data class JiraQueue(
        val projectKey: String,
        val projectName: String,
        val issueCount: Int,
        val associatedRepo: String = ""
    )

    companion object {
        /** Session sentinel that marks "secret lives in the OS keystore". */
        const val KEYSTORE_SENTINEL = "__KEYSTORE__"

        /** Session prefix that marks "secret lives in an env var injected
         *  by HashiCorp Vault (or an equivalent secrets-as-env system)." */
        const val VAULT_ENV_PREFIX = "__VAULT_ENV__:"

        /** Keystore account names — stable per-WebUI-install so a JIRA
         *  save survives a session restart when keystore mode is used. */
        const val API_TOKEN_ACCOUNT = "jira:api-token"
        const val PASSWORD_ACCOUNT = "jira:password"
    }

    @GetMapping("/jira")
    fun config(model: Model, session: HttpSession): String {
        val configured = session.getAttribute("jiraBaseUrl") != null
        val storage = (session.getAttribute("jiraSecretStorage") as? String) ?: "memory"

        model.addAttribute("configured", configured)
        model.addAttribute("baseUrl", session.getAttribute("jiraBaseUrl") ?: "")
        model.addAttribute("email", session.getAttribute("jiraEmail") ?: "")
        model.addAttribute("defaultProject", session.getAttribute("jiraDefaultProject") ?: "")
        model.addAttribute("authMethod", session.getAttribute("jiraAuthMethod") ?: "api-token")
        model.addAttribute("username", session.getAttribute("jiraUsername") ?: "")
        model.addAttribute("secretStorage", storage)
        model.addAttribute("keystoreAvailable", secretStore.available())
        model.addAttribute("keystoreBackendName", secretStore.humanName())
        model.addAttribute("apiTokenPresent", resolveSecret(session, "jiraApiToken", API_TOKEN_ACCOUNT) != null)
        model.addAttribute("passwordPresent", resolveSecret(session, "jiraPassword", PASSWORD_ACCOUNT) != null)
        model.addAttribute("queues", emptyList<JiraQueue>())
        model.addAttribute("testResult", session.getAttribute("jiraTestResult"))
        session.removeAttribute("jiraTestResult")
        // Repo ↔ JIRA project associations editor + saved status banner.
        model.addAttribute("repoJiraLinks", repoAssociations.links)
        model.addAttribute("repoJiraSaveResult", session.getAttribute("repoJiraSaveResult"))
        session.removeAttribute("repoJiraSaveResult")
        return "jira-config"
    }

    @PostMapping("/jira/save")
    fun save(
        @RequestParam baseUrl: String,
        @RequestParam email: String,
        @RequestParam(defaultValue = "api-token") authMethod: String,
        @RequestParam(required = false) apiToken: String?,
        @RequestParam(required = false) username: String?,
        @RequestParam(required = false) password: String?,
        @RequestParam defaultProject: String,
        @RequestParam(defaultValue = "memory") secretStorage: String,
        session: HttpSession
    ): String {
        val effectiveStorage = when {
            secretStorage == "keystore" && secretStore.available() -> "keystore"
            secretStorage == "vault" -> "vault"
            else -> "memory"
        }

        session.setAttribute("jiraBaseUrl", baseUrl.trimEnd('/'))
        session.setAttribute("jiraEmail", email)
        session.setAttribute("jiraDefaultProject", defaultProject)
        session.setAttribute("jiraAuthMethod", authMethod)
        session.setAttribute("jiraSecretStorage", effectiveStorage)
        if (!username.isNullOrBlank()) session.setAttribute("jiraUsername", username)

        // Persist each secret into the chosen backend. Blank submissions
        // leave any existing value in place (password-field UX convention).
        if (!apiToken.isNullOrBlank()) {
            persistSecret(session, "jiraApiToken", API_TOKEN_ACCOUNT, apiToken, effectiveStorage)
        }
        if (!password.isNullOrBlank()) {
            persistSecret(session, "jiraPassword", PASSWORD_ACCOUNT, password, effectiveStorage)
        }
        return "redirect:/jira"
    }

    /**
     * Put a secret in either the session or the keystore, writing the
     * matching sentinel to session when keystore was used so the rest
     * of the controller can discover where to look on retrieval.
     */
    private fun persistSecret(
        session: HttpSession,
        sessionKey: String,
        keystoreAccount: String,
        value: String,
        storage: String
    ) {
        when (storage) {
            "keystore" -> {
                if (secretStore.put(keystoreAccount, value)) {
                    session.setAttribute(sessionKey, KEYSTORE_SENTINEL)
                } else {
                    // Keystore write failed; fall back to memory.
                    session.setAttribute(sessionKey, value)
                }
            }
            "vault" -> {
                // In Vault mode the input field is the env-var NAME; the
                // actual secret is injected into the process environment
                // by Vault Agent / the OpenShift Vault injector at launch.
                session.setAttribute(sessionKey, VAULT_ENV_PREFIX + value.trim())
            }
            else -> session.setAttribute(sessionKey, value)
        }
    }

    /**
     * Retrieve a secret previously stored under {@code sessionKey} /
     * {@code keystoreAccount}. Sentinel values are resolved against the
     * OS keystore or the process environment as appropriate. Returns
     * null if no secret was ever saved OR if the Vault-injected env var
     * is not present in this process's environment.
     */
    private fun resolveSecret(
        session: HttpSession,
        sessionKey: String,
        keystoreAccount: String
    ): String? {
        val stored = session.getAttribute(sessionKey) as? String ?: return null
        return when {
            stored == KEYSTORE_SENTINEL -> secretStore.get(keystoreAccount)
            stored.startsWith(VAULT_ENV_PREFIX) -> {
                val envName = stored.removePrefix(VAULT_ENV_PREFIX)
                System.getenv(envName)?.ifBlank { null }
            }
            else -> stored
        }
    }

    @PostMapping("/jira/test")
    fun testConnection(session: HttpSession): String {
        val baseUrl = session.getAttribute("jiraBaseUrl") as? String
        if (baseUrl.isNullOrEmpty()) {
            session.setAttribute("jiraTestResult", mapOf("success" to false, "message" to "No JIRA URL configured. Save your connection first."))
            return "redirect:/jira"
        }

        val authMethod = session.getAttribute("jiraAuthMethod") as? String ?: "api-token"
        val email = session.getAttribute("jiraEmail") as? String ?: ""
        val username = session.getAttribute("jiraUsername") as? String ?: ""
        val apiToken = resolveSecret(session, "jiraApiToken", API_TOKEN_ACCOUNT)
        val password = resolveSecret(session, "jiraPassword", PASSWORD_ACCOUNT)

        val result = try {
            val url = java.net.URI.create("$baseUrl/rest/api/2/serverInfo")
            val client = connectionSettings.httpClient(java.time.Duration.ofSeconds(10))
            val builder = java.net.http.HttpRequest.newBuilder().uri(url).GET()
                .timeout(java.time.Duration.ofSeconds(10))

            // JIRA Cloud + api-token: Basic auth with email:apiToken.
            // JIRA Server + username/password: Basic auth with username:password.
            val basic = when (authMethod) {
                "api-token" -> if (email.isNotBlank() && !apiToken.isNullOrBlank())
                    java.util.Base64.getEncoder().encodeToString("$email:$apiToken".toByteArray())
                    else null
                "basic" -> if (username.isNotBlank() && !password.isNullOrBlank())
                    java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray())
                    else null
                else -> null
            }
            if (basic != null) builder.header("Authorization", "Basic $basic")

            val response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                mapOf("success" to true, "message" to "Connected to JIRA at $baseUrl (HTTP ${response.statusCode()})")
            } else {
                mapOf("success" to false, "message" to "JIRA responded with HTTP ${response.statusCode()}")
            }
        } catch (e: Exception) {
            mapOf("success" to false, "message" to "Connection failed: ${e.message}")
        }

        session.setAttribute("jiraTestResult", result)
        return "redirect:/jira"
    }

    @PostMapping("/jira/associate")
    fun associateQueue(
        @RequestParam projectKey: String,
        @RequestParam repoName: String
    ): String {
        return "redirect:/jira"
    }
}
