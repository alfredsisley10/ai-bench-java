package com.aibench.webui

/**
 * Pattern catalogue + analysis for the banking-app startup log. Pure
 * Kotlin, no Spring deps, easy to unit-test in isolation. Each pattern
 * has a regex matcher, a severity, an operator-facing message, and
 * (optionally) a fix-action id that DemoController knows how to execute.
 *
 * Add a pattern by appending to {@link #PATTERNS}; analysis is just a
 * filter-and-map over that list. Order matters only for the UI -- more
 * specific patterns first so we don't show a generic "build failed"
 * card when a more actionable one matches.
 */
object BankingAppDiagnostics {

    enum class Severity { INFO, WARN, ERROR }

    /**
     * One row of the diagnostic result card. {@code fixActionId} is
     * null when no safe auto-fix exists -- the card is informational
     * and the operator has to act manually.
     */
    data class Finding(
        val id: String,
        val title: String,
        val severity: Severity,
        val message: String,
        val fixActionId: String? = null,
        val fixActionLabel: String? = null
    )

    private data class Pattern(
        val id: String,
        val title: String,
        val regex: Regex,
        val severity: Severity,
        val message: String,
        val fixActionId: String? = null,
        val fixActionLabel: String? = null
    ) {
        fun match(log: String): Finding? =
            if (regex.containsMatchIn(log))
                Finding(id, title, severity, message, fixActionId, fixActionLabel)
            else null
    }

    private val PATTERNS = listOf(
        Pattern(
            id = "spring-boot-plugin-marker",
            title = "Spring Boot plugin marker can't be resolved",
            regex = Regex(
                "could not resolve plugin artifact 'org\\.springframework\\.boot:" +
                "org\\.springframework\\.boot\\.gradle\\.plugin"
            ),
            severity = Severity.ERROR,
            message = "Gradle is looking up the Spring Boot plugin marker (a tiny pointer " +
                "artifact at plugins.gradle.org) but the configured repos don't carry it. " +
                "The corp init script's useModule() substitution should rewrite this lookup " +
                "to org.springframework.boot:spring-boot-gradle-plugin -- which IS on Maven " +
                "Central -- but the substitution didn't fire. Most common cause: the init " +
                "script at ~/.gradle/init.d/corp-repos.gradle.kts is missing or stale.",
            fixActionId = "regenerate-init",
            fixActionLabel = "Regenerate init script from current /proxy settings"
        ),
        Pattern(
            id = "transitive-mirror-bypass",
            title = "Plugin transitive deps fetched from upstream, not the mirror",
            regex = Regex(
                "Could not get resource 'https://plugins\\.gradle\\.org/m2/.*spring-boot-buildpack-platform"
            ),
            severity = Severity.ERROR,
            message = "Transitive Spring Boot plugin dependencies (spring-boot-buildpack-platform, " +
                "spring-boot-loader-tools, etc.) tried to resolve direct from upstream URLs " +
                "instead of going through the corp mirror. Means the init script's URL " +
                "rewriter isn't running -- regenerate it from the current /proxy mirror " +
                "config and retry.",
            fixActionId = "regenerate-init",
            fixActionLabel = "Regenerate init script from current /proxy settings"
        ),
        Pattern(
            id = "class-file-major-mismatch",
            title = "Daemon JVM is older than the toolchain pin",
            regex = Regex("Unsupported class file major version (\\d+)"),
            severity = Severity.ERROR,
            message = "Spring Boot's resolveMainClassName task (and other daemon-internal " +
                "tasks that load compiled bytecode) failed because the Gradle daemon JVM " +
                "is older than what the toolchain compiles to. Major version 65=21, 66=22, " +
                "67=23, 68=24, 69=25. Either install a JDK matching the toolchain pin and " +
                "register it via /demo's 'Add JDK path' button, or revert the toolchain to " +
                "a major your daemon JVM can read.",
            fixActionId = null,
            fixActionLabel = null
        ),
        Pattern(
            id = "cmd-pipe-injection",
            title = "Windows cmd.exe split on a `|` in a sysprop value",
            regex = Regex("' is not recognized as an internal or external command"),
            severity = Severity.ERROR,
            message = "A JVM system property containing the JVM-standard '|' separator " +
                "(typically -Dhttp.nonProxyHosts=) was passed on the command line on " +
                "Windows. cmd.exe interpreted the '|' as a pipe and tried to exec the " +
                "value-fragment as a command. Re-save the proxy form in /proxy -- recent " +
                "builds drop this arg from the command line and write it to gradle.properties " +
                "instead, sidestepping cmd.exe entirely.",
            fixActionId = "regenerate-init",
            fixActionLabel = "Re-write gradle.properties from current /proxy settings"
        ),
        Pattern(
            id = "java-home-invalid",
            title = "JAVA_HOME points at a missing or non-JDK directory",
            regex = Regex(
                "(JAVA_HOME is set to an invalid directory|" +
                "ERROR: JAVA_HOME is not set|" +
                "Cannot find a Java installation)"
            ),
            severity = Severity.ERROR,
            message = "The Gradle wrapper couldn't locate a JDK at \$JAVA_HOME. Pick a " +
                "JDK from the dropdown on /demo (or click 'Add JDK path' if your install " +
                "is in a non-standard location) and retry -- bench-webui sets JAVA_HOME " +
                "for the gradle subprocess based on that selection."
        ),
        Pattern(
            id = "foojay-stale-lock",
            title = "Foojay JDK auto-provision left a stale lock file",
            regex = Regex(
                "(Unable to download toolchain matching|" +
                "Could not download .* from .*foojay)"
            ),
            severity = Severity.WARN,
            message = "Foojay's JDK auto-provision left a half-finished .reserved.lock " +
                "in ~/.gradle/jdks/ -- subsequent builds see the lock and refuse to retry " +
                "the download. Safe to delete; the next build will re-download cleanly.",
            fixActionId = "clear-foojay-locks",
            fixActionLabel = "Delete stale ~/.gradle/jdks/*.reserved.lock files"
        ),
        Pattern(
            id = "proxy-unreachable",
            title = "Outbound HTTPS request couldn't reach the configured proxy",
            regex = Regex(
                "(Connection refused|Connect timed out|UnknownHostException).*" +
                "(http\\.proxyHost|https\\.proxyHost|plugins\\.gradle\\.org|repo\\.maven)",
                RegexOption.DOT_MATCHES_ALL
            ),
            severity = Severity.ERROR,
            message = "The proxy host bench-webui passed to Gradle isn't accepting " +
                "connections. Open /proxy and verify the HTTPS proxy host:port is correct, " +
                "then click 'Verify connectivity' to confirm the proxy answers."
        ),
        Pattern(
            id = "mirror-auth-401",
            title = "Corp mirror rejected the saved credentials",
            regex = Regex(
                "(401 Unauthorized|Received status code 401).*(maven-external-virtual|artifactory)",
                RegexOption.DOT_MATCHES_ALL
            ),
            severity = Severity.ERROR,
            message = "The corp mirror returned 401 Unauthorized -- the username + token " +
                "combination saved in /proxy is rejected. Mirror tokens are typically " +
                "scoped per Artifactory instance and per repository; check the token " +
                "still belongs to the same instance you're targeting and re-save it on /proxy."
        ),
        Pattern(
            id = "build-cache-corruption",
            title = "Gradle build cache appears corrupt",
            regex = Regex(
                "(Could not read entry .* from the local build cache|" +
                "GRADLE-CACHE.*corrupt|" +
                "ZipException.*build cache)"
            ),
            severity = Severity.WARN,
            message = "A previous failed build left a corrupt entry in the local Gradle " +
                "cache. Clearing ~/.gradle/caches/build-cache-* (or running with " +
                "--rerun-tasks) typically fixes it.",
            fixActionId = "clear-build-cache",
            fixActionLabel = "Clear local Gradle build cache"
        )
    )

    /**
     * Run every pattern over the supplied log tail. Returns findings in
     * the order PATTERNS declares them so the UI shows the most
     * specific/actionable matches first.
     */
    fun analyze(log: String): List<Finding> =
        if (log.isBlank()) emptyList()
        else PATTERNS.mapNotNull { it.match(log) }
}
