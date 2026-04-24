package com.aibench.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class BenchConfig(
    val environment: String = "local",
    val dataDir: String = "~/.ai-bench",
    val database: DatabaseConfig = DatabaseConfig(),
    val llm: LlmSection = LlmSection(),
    val github: GitHubSection = GitHubSection(),
    val jira: JiraSection = JiraSection(),
    val proxy: ProxySection = ProxySection(),
    val vault: VaultSection = VaultSection(),
    val webui: WebuiSection = WebuiSection()
) {

    @Serializable
    data class DatabaseConfig(
        val url: String = "jdbc:h2:file:~/.ai-bench/db/bench;MODE=PostgreSQL",
        val driver: String = "org.h2.Driver",
        val username: String = "bench",
        val password: String = "bench"
    )

    @Serializable
    data class LlmSection(
        val defaultSolver: String = "corp-openai",
        val availableModels: List<ModelEntry> = emptyList(),
        val corpOpenAiConfigPath: String = "~/.ai-bench/corp-openai.yaml",
        val copilotSocketPath: String = "/tmp/ai-bench-copilot.sock"
    )

    @Serializable
    data class ModelEntry(
        val id: String,
        val displayName: String,
        val provider: String,
        val modelIdentifier: String,
        val costPer1kPromptTokens: Double = 0.0,
        val costPer1kCompletionTokens: Double = 0.0
    )

    @Serializable
    data class GitHubSection(
        val tokenSource: String = "env:GITHUB_TOKEN",
        val apiUrl: String = "https://api.github.com"
    )

    @Serializable
    data class JiraSection(
        val baseUrl: String = "",
        val email: String = "",
        val apiTokenSource: String = "keystore:jira-api-token",
        val defaultProject: String = ""
    )

    @Serializable
    data class ProxySection(
        val httpsProxy: String = "",
        val noProxy: String = "",
        val truststorePath: String = "",
        val truststorePasswordSource: String = ""
    )

    @Serializable
    data class VaultSection(
        val enabled: Boolean = false,
        val address: String = "",
        val role: String = "ai-bench",
        val authMethod: String = "kubernetes",
        val secretPath: String = "secret/data/ai-bench"
    )

    @Serializable
    data class WebuiSection(
        val port: Int = 7777,
        val adminUsers: List<String> = emptyList(),
        val enableReadOnlyAccess: Boolean = true
    )

    fun resolvedDataDir(): Path =
        Paths.get(dataDir.replace("~", System.getProperty("user.home")))

    companion object {
        private val yaml = Yaml.default

        fun load(env: Environment, projectDir: Path? = null): BenchConfig {
            val dotEnv = projectDir?.let { DotEnvLoader.load(it) } ?: emptyMap()
            dotEnv.forEach { (k, v) ->
                if (System.getenv(k) == null) {
                    System.setProperty("dotenv.$k", v)
                }
            }

            val configDir = resolveConfigDir()
            val base = loadFile(configDir.resolve("bench-config.yaml"))
            val overlay = loadFile(configDir.resolve("bench-config-${env.key}.yaml"))

            return merge(base, overlay, dotEnv)
        }

        private fun resolveConfigDir(): Path {
            val explicit = System.getenv("AI_BENCH_CONFIG_DIR")
            if (explicit != null) return Paths.get(explicit)
            return Paths.get(System.getProperty("user.home"), ".ai-bench")
        }

        private fun loadFile(path: Path): BenchConfig? {
            if (!Files.isRegularFile(path)) return null
            return yaml.decodeFromString(serializer(), Files.readString(path))
        }

        private fun merge(
            base: BenchConfig?,
            overlay: BenchConfig?,
            dotEnv: Map<String, String>
        ): BenchConfig {
            val config = when {
                base != null && overlay != null -> overlay.overlayOn(base)
                base != null -> base
                overlay != null -> overlay
                else -> BenchConfig()
            }
            return config.applyDotEnv(dotEnv)
        }

        private fun BenchConfig.overlayOn(base: BenchConfig): BenchConfig = BenchConfig(
            environment = this.environment.ifEmpty { base.environment },
            dataDir = this.dataDir.takeUnless { it == BenchConfig().dataDir } ?: base.dataDir,
            database = DatabaseConfig(
                url = this.database.url.takeUnless { it == DatabaseConfig().url } ?: base.database.url,
                driver = this.database.driver.takeUnless { it == DatabaseConfig().driver } ?: base.database.driver,
                username = this.database.username.takeUnless { it == DatabaseConfig().username } ?: base.database.username,
                password = this.database.password.takeUnless { it == DatabaseConfig().password } ?: base.database.password
            ),
            llm = LlmSection(
                defaultSolver = this.llm.defaultSolver.takeUnless { it == LlmSection().defaultSolver } ?: base.llm.defaultSolver,
                availableModels = this.llm.availableModels.ifEmpty { base.llm.availableModels },
                corpOpenAiConfigPath = this.llm.corpOpenAiConfigPath.takeUnless { it == LlmSection().corpOpenAiConfigPath } ?: base.llm.corpOpenAiConfigPath,
                copilotSocketPath = this.llm.copilotSocketPath.takeUnless { it == LlmSection().copilotSocketPath } ?: base.llm.copilotSocketPath
            ),
            github = GitHubSection(
                tokenSource = this.github.tokenSource.takeUnless { it == GitHubSection().tokenSource } ?: base.github.tokenSource,
                apiUrl = this.github.apiUrl.takeUnless { it == GitHubSection().apiUrl } ?: base.github.apiUrl
            ),
            jira = this.jira.takeUnless { it == JiraSection() } ?: base.jira,
            proxy = this.proxy.takeUnless { it == ProxySection() } ?: base.proxy,
            vault = VaultSection(
                enabled = this.vault.enabled || base.vault.enabled,
                address = this.vault.address.ifEmpty { base.vault.address },
                role = this.vault.role.takeUnless { it == VaultSection().role } ?: base.vault.role,
                authMethod = this.vault.authMethod.takeUnless { it == VaultSection().authMethod } ?: base.vault.authMethod,
                secretPath = this.vault.secretPath.takeUnless { it == VaultSection().secretPath } ?: base.vault.secretPath
            ),
            webui = WebuiSection(
                port = this.webui.port.takeUnless { it == WebuiSection().port } ?: base.webui.port,
                adminUsers = this.webui.adminUsers.ifEmpty { base.webui.adminUsers },
                enableReadOnlyAccess = this.webui.enableReadOnlyAccess
            )
        )

        private fun BenchConfig.applyDotEnv(dotEnv: Map<String, String>): BenchConfig {
            fun resolve(value: String): String {
                if (!value.startsWith("env:")) return value
                val envKey = value.removePrefix("env:")
                return dotEnv[envKey] ?: System.getenv(envKey) ?: value
            }
            return copy(
                database = database.copy(
                    url = resolve(database.url),
                    username = resolve(database.username),
                    password = resolve(database.password)
                ),
                proxy = proxy.copy(
                    httpsProxy = resolve(proxy.httpsProxy),
                    noProxy = resolve(proxy.noProxy)
                )
            )
        }
    }
}
