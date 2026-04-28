package com.aibench.webui

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File

/**
 * Builds the source-code context block that gets stuffed into the
 * solver prompt for one seed of one bug. Different providers mirror
 * the retrieval strategies SWE-bench evaluates:
 *
 * <ul>
 *   <li><b>none</b> — no source ships; the LLM gets only the natural-
 *       language problem statement. Worst-case baseline that measures
 *       how much the model "knows" without seeing the codebase.</li>
 *   <li><b>oracle</b> — uses the curated {@code filesTouched} list
 *       from the bug's YAML. Best-case ceiling; what we showed before
 *       this provider model existed.</li>
 *   <li><b>bm25</b> — TF-IDF retrieval over banking-app's *.java
 *       files using terms from the problem statement, top-K hits.
 *       Realistic mid-tier — no ground-truth file leak.</li>
 *   <li><b>agentic</b>, <b>bm25+agentic</b> — preview only; show in
 *       the dropdown so operators see the planned shape, but fall
 *       back to Oracle behaviour and log an audit note explaining
 *       the fallback. Implementing the multi-turn tool-use loop is
 *       the next step.</li>
 *   <li><b>appmap-navie</b> — handled separately (Navie spawns the
 *       AppMap CLI; not via this provider).</li>
 * </ul>
 *
 * <p>Returns a structured result so the audit page can show exactly
 * which files were chosen and (where applicable) why.
 */
@Component
class ContextProvider(
    private val bugCatalog: BugCatalog,
    private val bankingApp: BankingAppManager
) {
    private val log = LoggerFactory.getLogger(ContextProvider::class.java)

    /** Result of resolving a context-provider selection for one bug. */
    data class Resolved(
        /** Friendly name of the provider that actually produced the
         *  files (may differ from the requested one when a preview
         *  provider falls back to Oracle). */
        val effectiveProvider: String,
        /** Original provider id the operator picked. */
        val requestedProvider: String,
        /** Per-file source snippets shown to the LLM. */
        val files: List<ContextFile>,
        /** Optional human note surfaced on the audit page (e.g.
         *  "BM25 ranked top 5 of 312 .java files; query terms: ach,
         *  cutoff, ..."). Empty for trivial providers. */
        val rationale: String,
        /** True when a fallback ran (preview-only providers, missing
         *  bug metadata, etc.). The audit page shows a banner. */
        val fellBack: Boolean = false
    )

    /** A single source file inside a Resolved.files list. */
    data class ContextFile(
        val path: String,
        val ref: String?,
        val content: String?,
        val error: String? = null,
        /** Provider-specific score / metric. Null for trivial. */
        val score: Double? = null
    )

    /** Resolve the operator's context-provider pick into a list of
     *  source files to ship in the LLM prompt. */
    fun resolve(issueId: String, providerId: String): Resolved {
        val bug = bugCatalog.getBug(issueId) ?: return Resolved(
            effectiveProvider = "none",
            requestedProvider = providerId,
            files = emptyList(),
            rationale = "Bug metadata not loaded for $issueId — no source context available.",
            fellBack = providerId != "none"
        )
        return when (providerId.lowercase()) {
            "none" -> Resolved("none", providerId, emptyList(),
                "No source-code context: the LLM sees only the problem statement.",
                fellBack = false)
            "oracle" -> oracle(bug, providerId)
            "bm25" -> bm25(bug, providerId)
            "agentic", "bm25+agentic" -> oracle(bug, providerId).copy(
                effectiveProvider = "oracle",
                requestedProvider = providerId,
                rationale = "Provider '$providerId' is preview-only; fell back to Oracle " +
                    "(curated filesTouched). Multi-turn tool-use loop is on the roadmap.",
                fellBack = true
            )
            // appmap-navie is handled by AppmapNavieController (the CLI
            // hop happens before the bridge call), not here.
            else -> Resolved(providerId, providerId, emptyList(),
                "Unknown context provider '$providerId'; sending no source.",
                fellBack = true)
        }
    }

    // ---------------------- Provider impls ----------------------------

    private fun oracle(bug: BugCatalog.BugMetadata, requestedProvider: String): Resolved {
        val files = bug.filesTouched.map { path ->
            val snap = bugCatalog.readFileAtRef(bug.breakCommit, path)
            ContextFile(path = path, ref = bug.breakCommit, content = snap.content,
                error = snap.error)
        }
        return Resolved(
            effectiveProvider = "oracle",
            requestedProvider = requestedProvider,
            files = files,
            rationale = "Oracle: shipped ${files.size} curated file(s) from the bug's " +
                "filesTouched list, read at ${bug.breakCommit}."
        )
    }

    /**
     * Tokenize the problem statement, walk banking-app/**/*.java,
     * score each file by TF-IDF, return top-K. Plain text scoring --
     * we don't want a Lucene dep just for this. K is capped because
     * each file gets dropped into the prompt and we have a 256KB
     * per-file budget shared across all of them.
     */
    private fun bm25(bug: BugCatalog.BugMetadata, requestedProvider: String): Resolved {
        val k = 5
        val repo = runCatching { bankingApp.bankingAppDir }.getOrNull()
        if (repo == null || !repo.isDirectory) {
            return oracle(bug, requestedProvider).copy(
                effectiveProvider = "oracle",
                requestedProvider = requestedProvider,
                rationale = "BM25 fell back to Oracle: banking-app dir not located.",
                fellBack = true
            )
        }
        val queryTerms = tokenize(bug.problemStatement +
            " " + bug.title +
            " " + bug.tags.joinToString(" "))
            // Drop short-stopword-like tokens; weighted more toward
            // domain-specific terms in the bug description.
            .filter { it.length >= 3 }
            .take(40)
        if (queryTerms.isEmpty()) {
            return oracle(bug, requestedProvider).copy(
                effectiveProvider = "oracle",
                requestedProvider = requestedProvider,
                rationale = "BM25 fell back to Oracle: empty query (problem statement too short).",
                fellBack = true
            )
        }
        // Walk *.java files. We deliberately scan the working tree
        // rather than a specific git ref -- worst case the working
        // tree drifts from break-commit, but for SWE-bench-style
        // retrieval the working-tree shape is what real users have.
        val javaFiles = repo.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".git", "node_modules") }
            .filter { it.isFile && it.name.endsWith(".java") }
            .toList()
        if (javaFiles.isEmpty()) {
            return oracle(bug, requestedProvider).copy(
                effectiveProvider = "oracle",
                requestedProvider = requestedProvider,
                rationale = "BM25 fell back to Oracle: no .java files under ${repo.absolutePath}.",
                fellBack = true
            )
        }
        // Build inverted index for query terms only (no need to index
        // the full vocab). Document tf computed on demand. Safe for
        // ~thousand-file repos; if banking-app ever grows past that,
        // swap to a real BM25 lib.
        val docTerms = HashMap<File, Map<String, Int>>(javaFiles.size)
        val df = HashMap<String, Int>()
        for (f in javaFiles) {
            val toks = runCatching { tokenize(f.readText()) }.getOrDefault(emptyList())
            val counts = toks.groupingBy { it }.eachCount()
            docTerms[f] = counts
            for (t in queryTerms.toSet()) {
                if (counts.containsKey(t)) df.merge(t, 1, Int::plus)
            }
        }
        val n = javaFiles.size.toDouble()
        // Classical BM25 with k1=1.2, b=0.75. avgdl from docTerms.
        val k1 = 1.2; val b = 0.75
        val avgdl = docTerms.values.sumOf { it.values.sum() }.toDouble() / n
        data class Scored(val file: File, val score: Double)
        val scored = docTerms.map { (f, counts) ->
            val dl = counts.values.sum().toDouble()
            var s = 0.0
            for (q in queryTerms) {
                val tf = counts[q] ?: continue
                val nq = df[q] ?: continue
                val idf = kotlin.math.ln((n - nq + 0.5) / (nq + 0.5) + 1.0)
                s += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgdl))
            }
            Scored(f, s)
        }.filter { it.score > 0 }.sortedByDescending { it.score }
        if (scored.isEmpty()) {
            return oracle(bug, requestedProvider).copy(
                effectiveProvider = "oracle",
                requestedProvider = requestedProvider,
                rationale = "BM25 found no matches (query terms not present in any " +
                    "*.java file under ${repo.absolutePath}); fell back to Oracle.",
                fellBack = true
            )
        }
        val top = scored.take(k)
        val files = top.map { (f, s) ->
            // Path relative to the repo so the LLM sees the same
            // a/b/<path> shape that git apply expects.
            val rel = f.absolutePath.removePrefix(repo.absolutePath).trimStart('/')
            // Apply same 256KB cap as BugCatalog.readFileAtRef so prompt
            // budget is bounded.
            val raw = runCatching { f.readText() }.getOrDefault("")
            val capped = if (raw.length > 256_000)
                raw.take(256_000) +
                    "\n\n[... truncated ${raw.length - 256_000} bytes ...]\n"
                else raw
            ContextFile(path = rel, ref = "working-tree", content = capped, score = s)
        }
        return Resolved(
            effectiveProvider = "bm25",
            requestedProvider = requestedProvider,
            files = files,
            rationale = "BM25 ranked top $k of ${javaFiles.size} *.java files. " +
                "Query terms: ${queryTerms.take(8).joinToString(", ")}" +
                if (queryTerms.size > 8) "…" else ""
        )
    }

    /** Lowercased alphanumeric tokens, length >= 2. Splits on
     *  CamelCase + snake_case so e.g. "AchCutoffPolicy" produces
     *  "ach", "cutoff", "policy" alongside the joined form. */
    private fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val pieces = text.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
        val out = ArrayList<String>(pieces.size * 2)
        for (p in pieces) {
            out += p.lowercase()
            // CamelCase split: "AchCutoffPolicy" -> ach, cutoff, policy
            val camel = p.split(Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"))
            if (camel.size > 1) {
                for (c in camel) out += c.lowercase()
            }
        }
        return out.filter { it.length >= 2 }
    }
}
