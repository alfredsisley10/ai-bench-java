package com.aibench.llm.corp

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class CorpOpenAiConfig(
    val baseUrl: String,
    val apigee: ApigeeConfig,
    val defaultModel: String,
    val headers: Map<String, String> = emptyMap(),
    val proxy: ProxyConfig? = null
) {

    @Serializable
    data class ApigeeConfig(
        val tokenUrl: String,
        val clientIdEnv: String,
        val clientSecretEnv: String,
        val scope: String,
        val tokenCacheTtlSec: Long = 1800
    )

    @Serializable
    data class ProxyConfig(
        val urlEnv: String? = "HTTPS_PROXY",
        val truststore: String? = null,
        val truststorePasswordEnv: String? = null
    )

    companion object {
        fun loadDefault(): CorpOpenAiConfig = load(
            Paths.get(System.getProperty("user.home"), ".ai-bench", "corp-openai.yaml")
        )

        fun load(path: Path): CorpOpenAiConfig =
            Yaml.default.decodeFromString(serializer(), Files.readString(path))
    }
}
