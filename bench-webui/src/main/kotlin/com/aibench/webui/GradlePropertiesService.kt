package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File

/**
 * Compare an operator-supplied gradle.properties (paste / upload)
 * against the live ~/.gradle/gradle.properties, then optionally
 * apply selected keys.
 *
 * Why this exists: enterprise teams have known-working
 * gradle.properties snippets sitting in confluence pages, README
 * files, internal wikis. Pasting one in here lets the operator
 * preview which keys would change before committing — a safer flow
 * than overwriting the whole file blind.
 *
 * Security: password-shaped keys (anything ending in -Password,
 * -Passwd, -Token, -Secret, -ApiKey) get masked in display via
 * [maskValue]. The `apply` operation writes the actual unmasked
 * value to disk; the candidate text the operator pastes IS
 * cleartext (they wouldn't paste a placeholder), so the per-key
 * apply UI must surface the cleartext value once for confirmation
 * before saving.
 */
@Component
class GradlePropertiesService {
    private val log = LoggerFactory.getLogger(GradlePropertiesService::class.java)

    private val gradlePropsFile: File = File(
        System.getProperty("user.home"), ".gradle/gradle.properties"
    )

    enum class DiffStatus { ADDED, CHANGED, REMOVED, UNCHANGED }

    data class DiffEntry(
        val key: String,
        val candidateValue: String?,
        val currentValue: String?,
        val status: DiffStatus,
        /** True when key looks credential-shaped; UI masks display. */
        val isSecret: Boolean,
        /** Display-safe variants. */
        val candidateDisplay: String?,
        val currentDisplay: String?
    )

    /**
     * Parse a candidate gradle.properties text into a key→value map.
     * Uses java.util.Properties under the hood so it handles the
     * same escapes (\n, \=, \:) the gradle daemon does. Comment
     * lines and blank lines are dropped from the diff but not from
     * the raw text -- they're preserved on apply via line-by-line
     * merge below.
     */
    fun parse(text: String): Map<String, String> {
        val props = java.util.Properties()
        runCatching {
            props.load(java.io.StringReader(text))
        }.onFailure {
            log.warn("gradle.properties parse failed: {}", it.message)
        }
        val out = LinkedHashMap<String, String>()
        for ((k, v) in props) {
            if (k is String && v is String) out[k] = v
        }
        return out
    }

    /**
     * Compute a per-key diff between the candidate the operator
     * pasted and the current ~/.gradle/gradle.properties.
     */
    fun diff(candidateText: String): List<DiffEntry> {
        val candidate = parse(candidateText)
        val current = if (gradlePropsFile.isFile)
            parse(gradlePropsFile.readText()) else emptyMap()
        val allKeys = (candidate.keys + current.keys).toSortedSet()
        return allKeys.map { key ->
            val cand = candidate[key]
            val curr = current[key]
            val status = when {
                cand != null && curr == null -> DiffStatus.ADDED
                cand == null && curr != null -> DiffStatus.REMOVED
                cand != null && curr != null && cand != curr -> DiffStatus.CHANGED
                else -> DiffStatus.UNCHANGED
            }
            val secret = isSecretKey(key)
            DiffEntry(
                key = key,
                candidateValue = cand,
                currentValue = curr,
                status = status,
                isSecret = secret,
                candidateDisplay = cand?.let { if (secret) maskValue(it) else it },
                currentDisplay = curr?.let { if (secret) maskValue(it) else it }
            )
        }
    }

    /**
     * Apply the listed keys from the candidate text to the live
     * gradle.properties. Per-key surgical: only the named keys are
     * touched; other lines (including comments + unrelated keys)
     * are preserved verbatim. Returns a summary of what changed.
     *
     * Accepts both "set X to candidate's value" (key in candidate)
     * and "remove X" (key not in candidate but listed) so the same
     * endpoint handles both shapes.
     */
    fun apply(candidateText: String, keysToApply: Set<String>): ApplyResult {
        val candidate = parse(candidateText)
        val target = gradlePropsFile
        target.parentFile?.mkdirs()
        val existingLines = if (target.isFile) target.readLines() else emptyList()

        // Build a set of (line-index, key) for existing key lines so we
        // can rewrite them in place. Lines whose key isn't being applied
        // are preserved as-is.
        val keyLineRe = Regex("^\\s*([^#!=:\\s][^=:\\s]*)\\s*[=:](.*)$")
        val seenKeys = mutableSetOf<String>()
        val out = mutableListOf<String>()
        var added = 0; var changed = 0; var removed = 0; var unchanged = 0
        for (line in existingLines) {
            val m = keyLineRe.matchEntire(line)
            if (m == null) {
                out += line
                continue
            }
            val key = m.groupValues[1].trim()
            if (key in keysToApply) {
                seenKeys += key
                val newVal = candidate[key]
                if (newVal == null) {
                    // Operator chose to apply a key that's not in the
                    // candidate -- this means "remove from current".
                    removed++
                    // skip line (omit from output)
                } else {
                    val escaped = escapePropertyValue(newVal)
                    out += "$key=$escaped"
                    changed++
                }
            } else {
                out += line
                unchanged++
            }
        }
        // Append any to-apply keys that weren't in the existing file.
        for (key in keysToApply - seenKeys) {
            val newVal = candidate[key] ?: continue
            out += "$key=${escapePropertyValue(newVal)}"
            added++
        }
        target.writeText(out.joinToString("\n").let {
            if (it.endsWith("\n")) it else "$it\n"
        })
        log.info("gradle.properties applied: added={}, changed={}, removed={}, " +
            "unchanged={} (file={})", added, changed, removed, unchanged, target.absolutePath)
        return ApplyResult(added, changed, removed, unchanged, target.absolutePath)
    }

    data class ApplyResult(
        val added: Int, val changed: Int, val removed: Int, val unchanged: Int,
        val filePath: String
    )

    /** Read the live file's raw text for the UI's "current contents"
     *  pane. Returns empty string if file doesn't exist. */
    fun currentText(): String =
        if (gradlePropsFile.isFile) gradlePropsFile.readText() else ""

    /** Heuristic: does this key look credential-shaped? Same suffix
     *  list ConnectionSettings.maskCredentialArgs uses, plus a few
     *  enterprise-common tokens. */
    private val secretSuffixes = listOf(
        "password", "passwd", "token", "secret", "apikey", "api-key",
        "credential", "credentials", "auth"
    )
    fun isSecretKey(key: String): Boolean {
        val k = key.lowercase()
        return secretSuffixes.any { k.endsWith(it) || k.contains(".$it") || k.contains("_$it") }
    }

    /** Replace the value with `********` (8 chars regardless of
     *  actual length so we don't leak length information). */
    private fun maskValue(v: String): String =
        if (v.isBlank()) v else "********"

    /** Escape a value for .properties syntax: backslash + colon +
     *  equals get escaped; newlines become \n. */
    private fun escapePropertyValue(v: String): String =
        v.replace("\\", "\\\\")
            .replace("\n", "\\n").replace("\r", "\\r")
            .replace(":", "\\:").replace("=", "\\=")
}
