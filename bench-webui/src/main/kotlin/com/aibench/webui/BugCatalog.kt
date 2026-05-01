package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads bug metadata YAML files from `bugs/BUG-XXXX.yaml` and
 * provides per-bug source-snapshot lookup against the banking-app
 * git repo. Used by the audit trail on /results/{runId}/seed/{n} so
 * the operator can see exactly what was shown to the solver.
 *
 * <p>Resolution rules (no eager scan; one-shot lookup per call):
 *   1. The bugs dir is a sibling of banking-app/. We use
 *      BankingAppManager's resolved location to locate it.
 *   2. If banking-app couldn't be located, we degrade gracefully:
 *      every getBug() returns null and audit pages show "bug metadata
 *      not loaded" rather than throwing.
 *
 * <p>YAML parsing uses SnakeYAML, which Spring Boot pulls in
 * transitively. We keep the parser usage tiny -- a Map<String,Any?>
 * load -- so we don't have to maintain a dataclass schema in lock
 * step with the bug authors. Unknown fields are simply ignored.
 */
@Component
class BugCatalog(
    private val bankingApp: BankingAppManager
) {

    private val log = LoggerFactory.getLogger(BugCatalog::class.java)

    /**
     * Resolved bugs directory. Cached because BankingAppManager.location
     * doesn't change during a JVM lifetime once initialized.
     */
    private val bugsDir: File? by lazy {
        runCatching {
            val parent = bankingApp.bankingAppDir.parentFile
            val candidate = File(parent, "bugs")
            if (candidate.isDirectory) candidate else null
        }.getOrNull()
    }

    /** Count of available BUG-*.yaml files. Used by the dashboard's
     *  Bugs tile so the count tracks the same resolution rules as
     *  getBug() -- the previous user.dir-walk implementation broke
     *  whenever bench-webui ran from a directory that didn't have
     *  bugs/ as a child. */
    fun count(): Int =
        bugsDir?.listFiles { f -> f.isFile && f.name.endsWith(".yaml") }?.size ?: 0

    /** Enumerate every parseable BUG-*.yaml in the bugs/ dir, in
     *  filename-sort order. Used by the Navie precompute admin page
     *  to render one row per bug; callers should be ready for an
     *  empty list if the bugs/ dir isn't reachable. */
    fun allBugs(): List<BugMetadata> {
        val dir = bugsDir ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".yaml") }
            ?: return emptyList()
        return files.sortedBy { it.name }
            .mapNotNull { f -> getBug(f.nameWithoutExtension) }
    }

    /** Bug metadata as the operator sees it on the run-detail audit. */
    data class BugMetadata(
        val id: String,
        val title: String,
        val module: String,
        val difficulty: String,
        val category: String,
        val tags: List<String>,
        val breakCommit: String,
        val fixCommit: String,
        val filesTouched: List<String>,
        val problemStatement: String,
        val hints: List<String>,
        val hiddenTestClass: String?,
        val hiddenTestMethod: String?,
        val hiddenTestFile: String?
    )

    /**
     * Returns the parsed BUG-XXXX.yaml metadata, or null when the file
     * doesn't exist / can't be parsed. Callers should treat null as a
     * graceful "no audit data available for this bug" signal.
     */
    fun getBug(issueId: String): BugMetadata? {
        val dir = bugsDir ?: return null
        val file = File(dir, "$issueId.yaml")
        if (!file.isFile) return null
        return try {
            val yaml = org.yaml.snakeyaml.Yaml()
            @Suppress("UNCHECKED_CAST")
            val map = yaml.load<Map<String, Any?>>(file.readText()) ?: return null
            val hidden = map["hiddenTest"] as? Map<*, *>
            BugMetadata(
                id = (map["id"] as? String) ?: issueId,
                title = (map["title"] as? String) ?: "",
                module = (map["module"] as? String) ?: "",
                difficulty = (map["difficulty"] as? String) ?: "",
                category = (map["category"] as? String) ?: "",
                tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                breakCommit = (map["breakCommit"] as? String) ?: "bug/$issueId/break",
                fixCommit = (map["fixCommit"] as? String) ?: "bug/$issueId/fix",
                filesTouched = (map["filesTouched"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                problemStatement = (map["problemStatement"] as? String)?.trim() ?: "",
                hints = (map["hints"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                hiddenTestClass = hidden?.get("class") as? String,
                hiddenTestMethod = hidden?.get("method") as? String,
                hiddenTestFile = hidden?.get("file") as? String
            )
        } catch (e: Exception) {
            log.warn("Failed to parse bug metadata for {}: {}", issueId, e.message)
            null
        }
    }

    /** A single source file as it existed on a specific git ref. */
    data class FileSnapshot(
        val path: String,
        /** "break" or "fix" -- which side of the bug we pulled. */
        val ref: String,
        /** Tail of the file when the read succeeded; null on error. */
        val content: String?,
        /** Empty when content is non-null. */
        val error: String?
    )

    /**
     * Read the contents of a file at a given git ref. Empty content +
     * error message when the file isn't on that ref or git is missing.
     * Capped at 256KB to keep audit pages reasonable; the operator can
     * always fall back to a local checkout for the full file.
     */
    fun readFileAtRef(ref: String, path: String): FileSnapshot {
        val repo = runCatching { bankingApp.bankingAppDir }.getOrNull()
        if (repo == null || !File(repo, ".git").exists()) {
            return FileSnapshot(path, ref, null,
                "banking-app git repo not located -- can't read $path at $ref")
        }
        val proc = ProcessBuilder("git", "show", "$ref:$path")
            .directory(repo).redirectErrorStream(true).start()
        val finished = proc.waitFor(15, TimeUnit.SECONDS)
        val raw = proc.inputStream.bufferedReader().readText()
        if (!finished) {
            runCatching { proc.destroyForcibly() }
            return FileSnapshot(path, ref, null,
                "git show $ref:$path timed out after 15s")
        }
        if (proc.exitValue() != 0) {
            return FileSnapshot(path, ref, null,
                "git show $ref:$path exited ${proc.exitValue()}: ${raw.take(300)}")
        }
        // Cap large files. Java sources rarely exceed 256KB; if they do,
        // the bug-audit page would scroll for ages -- truncating with
        // a banner is friendlier than streaming half a megabyte of HTML.
        val capped = if (raw.length > 256_000)
            raw.take(256_000) + "\n\n[... truncated ${raw.length - 256_000} bytes -- read the full file from a local checkout ...]\n"
            else raw
        return FileSnapshot(path, ref, capped, null)
    }
}
