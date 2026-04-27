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
     * Path to the sidecar file the VSCode bridge writes after a successful
     * TCP bind. Single line of UTF-8 text containing the decimal port.
     * Bench-webui and the harness read this to discover the bridge.
     *
     * The bridge originally used AF_UNIX, but Node's libuv on some Windows
     * machines returns EACCES on AF_UNIX bind regardless of path/ACLs.
     * Switching to TCP localhost sidesteps the issue without admin rights.
     */
    fun copilotPortFile(): String =
        File(System.getProperty("user.home"), ".ai-bench-copilot.port").absolutePath

    /** Read the bridge's bound port. Returns null if the bridge isn't
     *  running (file absent) or the file is unparseable. */
    fun readCopilotPort(): Int? {
        val f = File(copilotPortFile())
        if (!f.isFile) return null
        return f.readText(Charsets.UTF_8).trim().toIntOrNull()?.takeIf { it in 1..65535 }
    }

}
