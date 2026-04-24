package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * Thin facade over the host OS secret store so the WebUI can offer
 * "persist securely" as an alternative to keeping credentials in the
 * HTTP session. Each platform has its own CLI:
 *
 * <ul>
 *   <li><b>macOS</b> — {@code security(1)} writes / reads
 *       {@code generic-password} entries in the login Keychain.</li>
 *   <li><b>Linux</b> — {@code secret-tool} (libsecret) stores against
 *       the GNOME keyring / KWallet / whichever org.freedesktop.secrets
 *       provider is running.</li>
 *   <li><b>Windows</b> — {@code cmdkey /generic:} writes to the Windows
 *       Credential Manager; reads use a PowerShell trampoline that
 *       calls the Win32 {@code CredRead} API via P/Invoke (no
 *       third-party PowerShell modules required, works on stock
 *       Windows 10/11 / Server 2019+).</li>
 * </ul>
 *
 * <p>All calls are best-effort: if the OS doesn't have the tool
 * installed, {@link #available()} returns false and the UI should fall
 * back to in-memory-only.
 *
 * <p>Every entry is namespaced under the fixed service name
 * {@value #SERVICE} so one WebUI install does not stomp on entries
 * created by other tools.
 */
@Component
class SecretStore {

    private val log = LoggerFactory.getLogger(SecretStore::class.java)

    companion object {
        const val SERVICE = "ai-bench-webui"
    }

    enum class Backend { MACOS_KEYCHAIN, LINUX_SECRET_TOOL, WINDOWS_CRED_MANAGER, NONE }

    val backend: Backend = detectBackend()

    fun available(): Boolean = backend != Backend.NONE

    fun humanName(): String = when (backend) {
        Backend.MACOS_KEYCHAIN -> "macOS Keychain"
        Backend.LINUX_SECRET_TOOL -> "Linux libsecret (GNOME Keyring / KWallet)"
        Backend.WINDOWS_CRED_MANAGER -> "Windows Credential Manager"
        Backend.NONE -> "(no OS keystore detected)"
    }

    /**
     * Store a secret under {@code account}. Overwrites silently on all
     * backends. Returns true on success.
     */
    fun put(account: String, secret: String): Boolean {
        if (account.isBlank()) return false
        return when (backend) {
            Backend.MACOS_KEYCHAIN -> runCli(listOf(
                "security", "add-generic-password",
                "-s", SERVICE, "-a", account, "-w", secret, "-U"
            ))
            Backend.LINUX_SECRET_TOOL -> runCli(
                listOf("secret-tool", "store", "--label=$SERVICE:$account",
                    "service", SERVICE, "account", account),
                stdin = secret
            )
            Backend.WINDOWS_CRED_MANAGER -> runCli(listOf(
                "cmdkey", "/generic:$SERVICE:$account",
                "/user:$account", "/pass:$secret"
            ))
            Backend.NONE -> false
        }
    }

    /**
     * Retrieve a secret previously stored under {@code account}, or null
     * if it's not present (or the OS doesn't support read-back via CLI).
     */
    fun get(account: String): String? {
        if (account.isBlank()) return null
        return when (backend) {
            Backend.MACOS_KEYCHAIN -> runCliCapture(listOf(
                "security", "find-generic-password",
                "-s", SERVICE, "-a", account, "-w"
            ))?.trim()?.ifBlank { null }
            Backend.LINUX_SECRET_TOOL -> runCliCapture(listOf(
                "secret-tool", "lookup",
                "service", SERVICE, "account", account
            ))?.trim()?.ifBlank { null }
            Backend.WINDOWS_CRED_MANAGER -> readWindowsCredential("$SERVICE:$account")
            Backend.NONE -> null
        }
    }

    fun delete(account: String): Boolean {
        if (account.isBlank()) return false
        return when (backend) {
            Backend.MACOS_KEYCHAIN -> runCli(listOf(
                "security", "delete-generic-password",
                "-s", SERVICE, "-a", account
            ))
            Backend.LINUX_SECRET_TOOL -> runCli(listOf(
                "secret-tool", "clear",
                "service", SERVICE, "account", account
            ))
            Backend.WINDOWS_CRED_MANAGER -> runCli(listOf(
                "cmdkey", "/delete:$SERVICE:$account"
            ))
            Backend.NONE -> false
        }
    }

    private fun detectBackend(): Backend {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("mac") -> if (isOnPath("security")) Backend.MACOS_KEYCHAIN else Backend.NONE
            os.contains("win") -> if (isOnPath("cmdkey")) Backend.WINDOWS_CRED_MANAGER else Backend.NONE
            os.contains("nux") || os.contains("nix") ->
                if (isOnPath("secret-tool")) Backend.LINUX_SECRET_TOOL else Backend.NONE
            else -> Backend.NONE
        }
    }

    private fun isOnPath(exe: String): Boolean = runCatching {
        val which = if (System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")) "where" else "which"
        val p = ProcessBuilder(which, exe).redirectErrorStream(true).start()
        p.waitFor() == 0
    }.getOrDefault(false)

    private fun runCli(cmd: List<String>, stdin: String? = null): Boolean = runCatching {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val p = pb.start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }
        p.inputStream.bufferedReader().readText().also {
            if (p.waitFor() != 0) log.warn("secret-store command {} failed: {}", cmd.first(), it.take(200))
        }
        p.exitValue() == 0
    }.getOrElse {
        log.warn("secret-store command {} threw: {}", cmd.first(), it.message)
        false
    }

    private fun runCliCapture(cmd: List<String>): String? = runCatching {
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) null else out
    }.getOrNull()

    /**
     * Read a Windows Credential Manager generic-credential value via a
     * one-shot PowerShell script that pinvokes Advapi32!CredRead. The
     * script is passed via {@code -EncodedCommand} (UTF-16-LE base64)
     * so target names containing spaces / special chars don't need
     * extra shell-quoting.
     *
     * <p>Returns null when the credential is missing OR PowerShell is
     * not on PATH (e.g. a stripped-down Windows server install).
     */
    private fun readWindowsCredential(target: String): String? = runCatching {
        if (!isOnPath("powershell")) {
            log.warn("PowerShell not found on PATH — Windows secret read unavailable")
            return null
        }
        // PowerShell script:
        //   - Inline P/Invoke wrapper around Advapi32!CredRead
        //   - Marshal the CREDENTIAL struct out, copy the
        //     CredentialBlob bytes, decode as UTF-16-LE
        //   - Print the secret to stdout, no trailing newline
        // The argument passed in via $args[0] is the credential's
        // TargetName (the same string we used with cmdkey /generic:).
        val script = """
            ${'$'}target = ${'$'}args[0]
            Add-Type -Name CredManRead -Namespace AiBench -MemberDefinition @'
            [System.Runtime.InteropServices.DllImport("Advapi32.dll", CharSet=System.Runtime.InteropServices.CharSet.Unicode, SetLastError=true)]
            public static extern bool CredReadW(string target, uint type, uint flags, out System.IntPtr credentialPtr);
            [System.Runtime.InteropServices.DllImport("Advapi32.dll", SetLastError=true)]
            public static extern void CredFree(System.IntPtr buffer);
            [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential, CharSet=System.Runtime.InteropServices.CharSet.Unicode)]
            public struct CREDENTIAL {
                public uint Flags;
                public uint Type;
                public string TargetName;
                public string Comment;
                public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
                public uint CredentialBlobSize;
                public System.IntPtr CredentialBlob;
                public uint Persist;
                public uint AttributeCount;
                public System.IntPtr Attributes;
                public string TargetAlias;
                public string UserName;
            }
            '@
            ${'$'}ptr = [System.IntPtr]::Zero
            ${'$'}ok = [AiBench.CredManRead]::CredReadW(${'$'}target, 1, 0, [ref] ${'$'}ptr)
            if (-not ${'$'}ok) { exit 2 }
            ${'$'}cred = [System.Runtime.InteropServices.Marshal]::PtrToStructure(${'$'}ptr, [type] ([AiBench.CredManRead+CREDENTIAL]))
            ${'$'}bytes = New-Object byte[] ${'$'}cred.CredentialBlobSize
            [System.Runtime.InteropServices.Marshal]::Copy(${'$'}cred.CredentialBlob, ${'$'}bytes, 0, ${'$'}cred.CredentialBlobSize)
            [AiBench.CredManRead]::CredFree(${'$'}ptr)
            [System.Console]::Out.Write([System.Text.Encoding]::Unicode.GetString(${'$'}bytes))
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(
            script.toByteArray(Charsets.UTF_16LE))
        val pb = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-EncodedCommand", encoded,
            target
        )
        val p = pb.start()
        val stdout = p.inputStream.bufferedReader().readText()
        val stderr = p.errorStream.bufferedReader().readText()
        val exit = p.waitFor()
        when {
            exit == 0 -> stdout.ifBlank { null }
            exit == 2 -> null // credential not present — normal
            else -> {
                log.warn("PowerShell credential read failed (exit {}): {}", exit, stderr.take(200))
                null
            }
        }
    }.getOrElse {
        log.warn("PowerShell credential read threw: {}", it.message)
        null
    }
}
