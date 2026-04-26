package com.aibench.config

import org.slf4j.LoggerFactory

class OsKeystoreSecretSource : SecretSource {

    override val scheme: String = "keystore"

    private val log = LoggerFactory.getLogger(OsKeystoreSecretSource::class.java)
    private val os = System.getProperty("os.name", "").lowercase()
    private val isMac = os.contains("mac")
    private val isWindows = os.contains("win")

    override fun resolve(key: String): String? = when {
        isMac -> macKeychainGet(key)
        isWindows -> windowsCredentialGet(key)
        else -> {
            log.debug("OS keystore not supported on {}, falling back", os)
            null
        }
    }

    fun store(key: String, value: String): Boolean = when {
        isMac -> macKeychainStore(key, value)
        isWindows -> windowsCredentialStore(key, value)
        else -> {
            log.warn("OS keystore not supported on {}", os)
            false
        }
    }

    fun delete(key: String): Boolean = when {
        isMac -> macKeychainDelete(key)
        isWindows -> windowsCredentialDelete(key)
        else -> false
    }

    private fun macKeychainGet(key: String): String? = runCatching {
        val proc = ProcessBuilder(
            "security", "find-generic-password", "-s", SERVICE_NAME, "-a", key, "-w"
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0) output else null
    }.getOrElse {
        log.debug("macOS Keychain lookup failed for {}: {}", key, it.message)
        null
    }

    private fun macKeychainStore(key: String, value: String): Boolean = runCatching {
        macKeychainDelete(key)
        val proc = ProcessBuilder(
            "security", "add-generic-password", "-s", SERVICE_NAME, "-a", key, "-w", value, "-U"
        ).redirectErrorStream(true).start()
        proc.waitFor() == 0
    }.getOrElse {
        log.error("macOS Keychain store failed for {}: {}", key, it.message)
        false
    }

    private fun macKeychainDelete(key: String): Boolean = runCatching {
        val proc = ProcessBuilder(
            "security", "delete-generic-password", "-s", SERVICE_NAME, "-a", key
        ).redirectErrorStream(true).start()
        proc.waitFor() == 0
    }.getOrDefault(false)

    private fun windowsCredentialGet(key: String): String? = runCatching {
        val proc = powershell("(Get-StoredCredential -Target '${windowsTarget(key)}').GetNetworkCredential().Password")
        val output = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0 && output.isNotEmpty()) output else null
    }.getOrElse {
        log.debug("Windows Credential Manager lookup failed for {}: {}", key, it.message)
        null
    }

    private fun windowsCredentialStore(key: String, value: String): Boolean = runCatching {
        powershell("New-StoredCredential -Target '${windowsTarget(key)}' -UserName 'ai-bench' -Password '$value' -Persist LocalMachine | Out-Null")
            .waitFor() == 0
    }.getOrElse {
        log.error("Windows Credential Manager store failed for {}: {}", key, it.message)
        false
    }

    private fun windowsCredentialDelete(key: String): Boolean = runCatching {
        powershell("Remove-StoredCredential -Target '${windowsTarget(key)}' | Out-Null").waitFor() == 0
    }.getOrDefault(false)

    private fun windowsTarget(key: String) = "$SERVICE_NAME:$key"

    private fun powershell(script: String): Process =
        ProcessBuilder("powershell", "-NoProfile", "-Command", script).redirectErrorStream(true).start()

    companion object {
        private const val SERVICE_NAME = "ai-bench"
    }
}
