package com.aibench.builder

import java.nio.file.Path

/**
 * Everything the builder needs to know about the corporate network and
 * artifact infrastructure. All values are optional — an empty config yields
 * the vanilla build-straight-from-the-internet behavior.
 */
data class EnterpriseBuildConfig(
    val httpsProxy: String? = System.getenv("HTTPS_PROXY"),
    val noProxy: String? = System.getenv("NO_PROXY"),
    val artifactoryUrl: String? = System.getenv("ARTIFACTORY_URL"),
    val artifactoryUser: String? = System.getenv("ARTIFACTORY_USER"),
    val artifactoryToken: String? = System.getenv("ARTIFACTORY_TOKEN"),
    val corpTruststore: Path? = System.getenv("CORP_TRUSTSTORE_PATH")?.let(Path::of),
    val corpTruststorePassword: String? = System.getenv("CORP_TRUSTSTORE_PASSWORD"),
    val extraGradleArgs: List<String> = emptyList()
)
