package com.aibench.webui

import java.io.File
import java.util.Locale

/**
 * Small cross-platform helpers used across the WebUI. Every function here
 * returns a shell-invocation or filesystem path that behaves on macOS,
 * Linux, and Windows without further guarding at the call site.
 *
 * <p>Rules of thumb:
 * <ul>
 *   <li>Anything that shells out to a platform-specific tool goes here
 *       so the rest of the codebase can stay platform-agnostic.</li>
 *   <li>Defaults assume the repository checkout layout — the banking-app
 *       Gradle wrapper is colocated with bench-webui, the bridge socket
 *       lives in the platform's temp dir, etc.</li>
 * </ul>
 */
object Platform {

    /** True when running on Windows (any edition). */
    val isWindows: Boolean by lazy {
        System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
    }

    /** True when running on macOS. */
    val isMac: Boolean by lazy {
        System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("mac")
    }

    /** True when running on Linux or a *nix that advertises itself as such. */
    val isLinux: Boolean by lazy {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        os.contains("nux") || os.contains("nix")
    }

    /**
     * Returns the Gradle wrapper invocation tokens for a given project
     * directory. Windows uses the <code>gradlew.bat</code> batch script;
     * macOS / Linux use the POSIX <code>./gradlew</code> shell script.
     *
     * <p>Returned as a list so callers can prepend it to their subprocess
     * argument vector:
     * <pre>val cmd = mutableListOf&lt;String&gt;().apply { addAll(Platform.gradleWrapper(dir)); add("bootRun") }</pre>
     */
    fun gradleWrapper(projectDir: File): List<String> =
        if (isWindows) listOf(projectDir.resolve("gradlew.bat").absolutePath)
        else listOf("./gradlew")

    /**
     * Default socket / named-pipe path for the Copilot bridge. Unix
     * platforms use a well-known location under <code>/tmp</code>;
     * Windows Unix-domain sockets (supported since Windows 10 1803)
     * require a regular file path, so we use the user's temp dir.
     */
    fun defaultCopilotSocket(): String {
        val override = System.getenv("AI_BENCH_COPILOT_SOCK")
        if (!override.isNullOrBlank()) return override
        return if (isWindows) {
            val tmp = System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir", "C:\\Temp")
            File(tmp, "ai-bench-copilot.sock").absolutePath
        } else {
            "/tmp/ai-bench-copilot.sock"
        }
    }
}
