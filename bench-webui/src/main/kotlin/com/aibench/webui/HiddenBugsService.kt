package com.aibench.webui

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File

/**
 * Persisted set of bug IDs the operator has chosen to hide from
 * dashboard reporting. Used to suppress contamination-impacted
 * (or otherwise non-representative) bugs from the leaderboard,
 * per-bug-leaders, contamination warning, runs table, etc.
 *
 * Storage: ~/.aibench/hidden-bugs.json -- a JSON object of shape
 * `{ "hiddenBugs": ["BUG-0007", "BUG-0009"] }`. Survives WebUI
 * restarts. Persisted writes are best-effort -- a failed write is
 * logged but doesn't crash the request; the in-memory cache
 * still reflects the operator's most recent toggle so the current
 * dashboard render is consistent.
 *
 * Read on every dashboard render (cheap, the file is tiny).
 */
@Component
class HiddenBugsService {

    private val log = LoggerFactory.getLogger(HiddenBugsService::class.java)
    private val mapper = ObjectMapper()
    private val storeFile: File = File(
        System.getProperty("user.home"), ".aibench/hidden-bugs.json")

    @Volatile private var cached: Set<String>? = null

    /** Wire-format. Future-extensible: an operator note + timestamp
     *  would slot in alongside `hiddenBugs` here without a migration. */
    private data class Store(val hiddenBugs: List<String> = emptyList())

    /** Currently-hidden bug IDs. Read-through cache; the file is
     *  reloaded on first call after a write. */
    fun hiddenBugs(): Set<String> {
        cached?.let { return it }
        val loaded = runCatching {
            if (!storeFile.isFile) emptySet<String>()
            else mapper.readValue(storeFile, Store::class.java).hiddenBugs.toSet()
        }.onFailure {
            log.warn("Could not read $storeFile, treating as empty: ${it.message}")
        }.getOrElse { emptySet() }
        cached = loaded
        return loaded
    }

    /** Hide a single bug. Idempotent. */
    fun hide(bugId: String) {
        val updated = hiddenBugs() + bugId
        write(updated)
    }

    /** Unhide a single bug. Idempotent. */
    fun unhide(bugId: String) {
        val updated = hiddenBugs() - bugId
        write(updated)
    }

    /** Clear the hidden set entirely. */
    fun clear() {
        write(emptySet())
    }

    private fun write(set: Set<String>) {
        cached = set
        runCatching {
            storeFile.parentFile?.mkdirs()
            mapper.writeValue(storeFile, Store(set.sorted()))
        }.onFailure {
            log.warn("Could not write $storeFile: ${it.message}")
        }
    }
}
