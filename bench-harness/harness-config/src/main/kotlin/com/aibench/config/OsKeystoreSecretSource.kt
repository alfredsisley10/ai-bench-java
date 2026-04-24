package com.aibench.config

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader

class OsKeystoreSecretSource : SecretSource {

    private val log = LoggerFactory.getLogger(OsKeystoreSecretSource::class.java)
    private val os = System.getProperty("os.name", "").lowercase()

    override fun resolve(ref: String): String? = when {
        os.contains("mac") -> macKeychainGet(ref)
        os.contains("win") -> windowsCredentialGet(ref)
        else -> {
            log.debug("OS keystore not supported on {}, falling back", os)
            null
        }
    }

    fun store(key: String, value: String): Boolean = when {
        os.contains("mac") -> macKeychainStore(key, value)
        os.contains("win") -> windowsCredentialStore(key, value)
        else -> {
            log.warn("OS keystore not supported on {}", os)
            false
        }
    }

    fun delete(key: String): Boolean = when {
        os.contains("mac") -> macKeychainDelete(key)
        os.contains("win") -> windowsCredentialDelete(key)
        else -> false
    }

    private fun macKeychainGet(key: String): String? = runCatching {
        val serviceName = "ai-bench"
        val proc = ProcessBuilder(
            "security", "find-generic-password",
            "-s", serviceName, "-a", key, "-w"
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0) output else null
    }.getOrElse {
        log.debug("macOS Keychain lookup failed for {}: {}", key, it.message)
        null
    }

    private fun macKeychainStore(key: String, value: String): Boolean = runCatching {
        macKeychainDelete(key)
        val serviceName = "ai-bench"
        val proc = ProcessBuilder(
            "security", "add-generic-password",
            "-s", serviceName, "-a", key, "-w", value, "-U"
        ).redirectErrorStream(true).start()
        proc.waitFor() == 0
    }.getOrElse {
        log.error("macOS Keychain store failed for {}: {}", key, it.message)
        false
    }

    private fun macKeychainDelete(key: String): Boolean = runCatching {
        val serviceName = "ai-bench"
        val proc = ProcessBuilder(
            "security", "delete-generic-password",
            "-s", serviceName, "-a", key
        ).redirectErrorStream(true).start()
        proc.waitFor() == 0
    }.getOrDefault(false)

    private fun windowsCredentialGet(key: String): String? = runCatching {
        val target = "ai-bench:$key"
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-Command",
            "(Get-StoredCredential -Target '$target').GetNetworkCredential().Password"
        ).redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val output = reader.readText().trim()
        if (proc.waitFor() == 0 && output.isNotEmpty()) output else null
    }.getOrElse {
        log.debug("Windows Credential Manager lookup failed for {}: {}", key, it.message)
        null
    }

    private fun windowsCredentialStore(key: String, value: String): Boolean = runCatching {
        val target = "ai-bench:$key"
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-Command",
            "New-StoredCredential -Target '$target' -UserName 'ai-bench' -Password '$value' -Persist LocalMachine | Out-Null"
        ).redirectErrorStream(true).start()
        proc.waitFor() == 0
    }.getOrElse {
        log.error("Windows Credential Manager store failed for {}: {}", key, it.message)
        false
    }

    private fun windowsCredentialDelete(key: String): Boolean = runCatching {
        val target = "ai-bench:$key"
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-Command",
            "Remove-StoredCredential -Target '$target' | Out-Null"
        ).redirectErrorStream(true).start()
        proc.waitFor() == 0
    }.getOrDefault(false)
}
