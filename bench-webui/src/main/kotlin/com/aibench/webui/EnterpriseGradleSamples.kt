package com.aibench.webui

import org.springframework.stereotype.Component

/**
 * Curated catalog of known-working enterprise gradle.properties
 * shapes. Used as reference material for the LLM-recommend feature
 * (PR-L) so the model has concrete examples of what corp setups
 * actually look like instead of generating from cold.
 *
 * Each sample omits real hostnames + tokens; placeholders make the
 * pattern obvious. The recommender shows the operator the chosen
 * sample alongside the LLM's adapted version so they can see the
 * provenance.
 *
 * Add new samples here when an operator reports a NEW shape that
 * worked for them — over time this becomes the bench-webui's
 * collected enterprise wisdom about gradle config.
 */
@Component
class EnterpriseGradleSamples {

    data class Sample(
        val id: String,
        val title: String,
        val description: String,
        /** When to suggest this sample. Free-form English used in
         *  the LLM system prompt to help it pattern-match. */
        val whenToUse: String,
        val text: String
    )

    val samples: List<Sample> = listOf(
        Sample(
            id = "artifactory-virtual-with-auth",
            title = "Authenticated Artifactory virtual (most common corp setup)",
            description = "Single Artifactory virtual repo serving Maven Central + plugins.gradle.org passthrough. Auth via API key.",
            whenToUse = "Operator has a corporate Artifactory URL and a personal API key.",
            text = """
                # ----------------------------------------------------------------------
                # Corporate Artifactory virtual mirror — most common corp shape
                # ----------------------------------------------------------------------
                # Mirror serves Maven Central + plugins.gradle.org + all sub-virtuals.
                # The bench-webui /mirror form forwards these to every gradle subprocess.
                systemProp.https.proxyHost=corp.proxy.example.com
                systemProp.https.proxyPort=8080
                systemProp.http.proxyHost=corp.proxy.example.com
                systemProp.http.proxyPort=8080
                systemProp.http.nonProxyHosts=*.corp.example.com|localhost|127.*

                # Mirror credentials. The corp-repos.gradle.kts init script
                # picks these up via providers.gradleProperty().
                orgInternalMavenUser=YOUR_USERNAME
                orgInternalMavenPassword=YOUR_API_KEY

                # JVM args for the daemon. -Xmx4g comfortably covers a 30+ module
                # build; HeapDumpOnOutOfMemoryError makes a future OOM debuggable.
                org.gradle.jvmargs=-Xmx4g -XX:+HeapDumpOnOutOfMemoryError

                # Daemon defaults. Configuration cache disabled because the
                # AppMap plugin's instrumentation isn't config-cache-safe.
                org.gradle.daemon=true
                org.gradle.parallel=true
                org.gradle.caching=true
                org.gradle.configuration-cache=false
            """.trimIndent()
        ),
        Sample(
            id = "artifactory-direct-foojay-passthrough",
            title = "Artifactory + foojay direct passthrough",
            description = "Mirror serves Maven Central / plugin marker artifacts but the operator's corp net allows direct egress to api.foojay.io.",
            whenToUse = "Operator's mirror lacks Foojay JDK distributions but corp proxy permits direct HTTPS to api.foojay.io.",
            text = """
                # ----------------------------------------------------------------------
                # Artifactory virtual + direct Foojay (JDK toolchain auto-download)
                # ----------------------------------------------------------------------
                # Same proxy as above; mirror covers Maven; foojay served direct
                # via the corp proxy.
                systemProp.https.proxyHost=corp.proxy.example.com
                systemProp.https.proxyPort=8080

                orgInternalMavenUser=YOUR_USERNAME
                orgInternalMavenPassword=YOUR_API_KEY

                # Tell foojay-resolver-convention to use the public foojay
                # endpoint; gradle's HTTP layer will route it via the proxy.
                # Set this when your Artifactory does NOT carry foojay
                # distributions (most don't -- foojay is plugin-portal-only).
                org.gradle.jvmargs=-Xmx4g
                org.gradle.parallel=true
            """.trimIndent()
        ),
        Sample(
            id = "no-mirror-direct-only",
            title = "No mirror — proxy-only egress",
            description = "Operator's network allows direct HTTPS to plugins.gradle.org + repo.maven.apache.org through the proxy. No Artifactory in the picture.",
            whenToUse = "Operator has saved a proxy on /proxy but no mirror -- usually true on cloud dev VMs without Artifactory.",
            text = """
                # ----------------------------------------------------------------------
                # No mirror — direct egress through corp proxy
                # ----------------------------------------------------------------------
                systemProp.https.proxyHost=corp.proxy.example.com
                systemProp.https.proxyPort=8080
                systemProp.http.proxyHost=corp.proxy.example.com
                systemProp.http.proxyPort=8080

                # Deny-list internal hosts so they don't loop back through
                # the external proxy. Use '|' separator (Java JVM convention).
                systemProp.http.nonProxyHosts=*.corp.example.com|localhost|127.*

                org.gradle.jvmargs=-Xmx4g
                org.gradle.parallel=true
                org.gradle.daemon=true
            """.trimIndent()
        ),
        Sample(
            id = "two-mirror-split",
            title = "Two-mirror split: plugins on Artifactory, deps on Maven Central",
            description = "Some enterprises run Artifactory ONLY for plugins (because of the Foojay gap) and let Maven Central serve dependencies directly through the proxy.",
            whenToUse = "Operator's Artifactory carries plugin artifacts but explicitly does NOT carry Maven Central content (or carrying it is policy-restricted).",
            text = """
                # ----------------------------------------------------------------------
                # Two-mirror split: Artifactory for plugins, MavenCentral for deps
                # ----------------------------------------------------------------------
                systemProp.https.proxyHost=corp.proxy.example.com
                systemProp.https.proxyPort=8080

                # Plugins.gradle.org + Maven Central both routed direct via the
                # corp proxy (not through Artifactory). Set bypassMirror=true
                # in bench-webui's /mirror form so the Artifactory URL stays
                # visible for the verify panel but doesn't get injected.
                # The Artifactory below is used by the corp-repos init script
                # for org-private artifacts only.
                orgInternalMavenUser=YOUR_USERNAME
                orgInternalMavenPassword=YOUR_API_KEY

                org.gradle.jvmargs=-Xmx4g
                org.gradle.parallel=true
            """.trimIndent()
        )
    )

    fun byId(id: String): Sample? = samples.firstOrNull { it.id == id }
}
