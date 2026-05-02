package com.aibench.webui

import org.springframework.stereotype.Component

/**
 * Static lint over BugCatalog.BugMetadata that flags
 * "bug-definition contamination": cases where the problem statement
 * or hints accidentally name the file, class, package, or test
 * method that the human fix touched. Such mentions let the model
 * solve the bug from the description alone, with no code, which
 * makes the benchmark grade unfair.
 *
 * Pure: no Spring deps beyond @Component. Trivially unit-testable.
 *
 * Pairs with the dashboard's "Pre-training contamination" section --
 * the contamination warning catches *runtime* leaks (model passed
 * with no context); this lint catches *static* leaks (bug definition
 * gives the answer away regardless of context).
 */
@Component
class BugLintService {

    enum class Severity { HIGH, MEDIUM, INFO }
    enum class Source { PROBLEM_STATEMENT, HINT }

    data class LintFinding(
        val severity: Severity,
        val rule: String,
        val source: Source,
        /** Index into hints[]; 0 for problem statement. */
        val sourceIndex: Int,
        val offendingToken: String,
        val expected: String,
        val contextLine: String,
        val suggestion: String
    )

    private val tokenSplit = Regex("[\\s\\p{Punct}]+")
    private val bugIdRe = Regex("BUG-\\d{4}", RegexOption.IGNORE_CASE)

    fun lint(bug: BugCatalog.BugMetadata): List<LintFinding> {
        val findings = mutableListOf<LintFinding>()
        val needles = collectNeedles(bug)
        val texts = buildList {
            add(Triple(bug.problemStatement, Source.PROBLEM_STATEMENT, 0))
            bug.hints.forEachIndexed { i, h -> add(Triple(h, Source.HINT, i)) }
        }
        for ((text, source, idx) in texts) {
            if (text.isBlank()) continue
            val tokens = tokenSplit.split(text).filter { it.isNotBlank() }
            val tokensLc = tokens.map { it.lowercase() }
            val textLc = text.lowercase()

            // Filename / class needles -- token-equality match.
            for (needle in needles.filenameTokens) {
                val nLc = needle.lowercase()
                tokensLc.forEachIndexed { tIdx, t ->
                    if (t == nLc && !bugIdRe.containsMatchIn(tokens[tIdx])) {
                        findings += LintFinding(
                            severity = Severity.HIGH,
                            rule = "FILENAME_LEAK",
                            source = source, sourceIndex = idx,
                            offendingToken = tokens[tIdx],
                            expected = needle,
                            contextLine = sentenceAround(text, tokens[tIdx]),
                            suggestion = "Rewrite to describe the SYMPTOM, not the LOCATION. " +
                                "Naming the file or class points the model at the fix without code."
                        )
                    }
                }
            }
            for (needle in needles.classTokens) {
                val nLc = needle.lowercase()
                tokensLc.forEachIndexed { tIdx, t ->
                    if (t == nLc && !bugIdRe.containsMatchIn(tokens[tIdx])) {
                        findings += LintFinding(
                            severity = Severity.HIGH,
                            rule = "CLASS_LEAK",
                            source = source, sourceIndex = idx,
                            offendingToken = tokens[tIdx],
                            expected = needle,
                            contextLine = sentenceAround(text, tokens[tIdx]),
                            suggestion = "Rewrite to describe the BEHAVIOUR, not the class. " +
                                "Naming the production class derived from the hidden test gives away the fix target."
                        )
                    }
                }
            }
            // Test method name -- substring match (snake_case is never a normal English word).
            needles.testMethod?.let { method ->
                val mLc = method.lowercase()
                if (textLc.contains(mLc)) {
                    findings += LintFinding(
                        severity = Severity.HIGH,
                        rule = "TEST_METHOD_LEAK",
                        source = source, sourceIndex = idx,
                        offendingToken = method,
                        expected = method,
                        contextLine = sentenceAround(text, method),
                        suggestion = "Replace the test method name with a behavioural description of the failure. " +
                            "The method name IS the bug summary; reproducing it in the prompt is the answer."
                    )
                }
            }
            // Package path fragments -- substring match.
            for (frag in needles.packageFragments) {
                val fLc = frag.lowercase()
                if (textLc.contains(fLc)) {
                    findings += LintFinding(
                        severity = Severity.MEDIUM,
                        rule = "PACKAGE_PATH_LEAK",
                        source = source, sourceIndex = idx,
                        offendingToken = frag,
                        expected = frag,
                        contextLine = sentenceAround(text, frag),
                        suggestion = "Avoid naming source paths in the description. " +
                            "Even partial paths narrow the search space significantly."
                    )
                }
            }
            // INFO: shared wording between problem statement and the test method name.
            if (source == Source.PROBLEM_STATEMENT && needles.testMethod != null) {
                val methodWords = needles.testMethod.split('_')
                    .filter { it.length > 4 && it.lowercase() !in stopwords }
                    .map { it.lowercase() }.toSet()
                val tokenSet = tokensLc.toSet()
                val shared = methodWords.intersect(tokenSet)
                for (w in shared) {
                    findings += LintFinding(
                        severity = Severity.INFO,
                        rule = "TEST_WORDING_ECHO",
                        source = source, sourceIndex = idx,
                        offendingToken = w,
                        expected = needles.testMethod,
                        contextLine = sentenceAround(text, w),
                        suggestion = "Word '$w' appears in BOTH the problem statement and the hidden test method name. " +
                            "Common nouns may be coincidence; review for paraphrase opportunities."
                    )
                }
            }
        }
        // Dedupe identical (rule, offendingToken, source, sourceIndex) tuples --
        // a token can appear multiple times in one sentence; one finding suffices.
        return findings.distinctBy { listOf(it.rule, it.offendingToken.lowercase(),
            it.source, it.sourceIndex) }
    }

    private data class Needles(
        val filenameTokens: List<String>,   // basenames with and without extension
        val classTokens: List<String>,      // production class derived from hiddenTestClass
        val testMethod: String?,
        val packageFragments: List<String>  // rightmost-2-path-segments for each filesTouched
    )

    private fun collectNeedles(bug: BugCatalog.BugMetadata): Needles {
        val files = bug.filesTouched
        val basenames = files.flatMap { path ->
            val leaf = path.substringAfterLast('/').substringAfterLast('\\')
            val noExt = leaf.substringBeforeLast('.', leaf)
            listOf(leaf, noExt).filter { it.isNotBlank() }
        }.distinct()

        val classFromTest = bug.hiddenTestClass?.let { full ->
            // "com.foo.AchCutoffPolicyTest" -> "AchCutoffPolicy"
            val simple = full.substringAfterLast('.')
            simple.removeSuffix("Test").takeIf { it.isNotBlank() }
        }
        val classes = listOfNotNull(classFromTest)

        val pkgFrags = files.flatMap { path ->
            val parts = path.split('/', '\\').filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val n = parts.size
                listOf(
                    parts[n - 2] + "/" + parts[n - 1],
                    if (n >= 3) parts[n - 3] + "/" + parts[n - 2] else null
                ).filterNotNull()
            } else emptyList()
        }.distinct()

        return Needles(basenames, classes, bug.hiddenTestMethod, pkgFrags)
    }

    private fun sentenceAround(text: String, needle: String): String {
        val idx = text.indexOf(needle, ignoreCase = true)
        if (idx < 0) return text.take(160)
        val start = maxOf(0, text.lastIndexOfAny(charArrayOf('.', '\n'), idx) + 1)
        val endRaw = text.indexOfAny(charArrayOf('.', '\n'), idx)
        val end = if (endRaw < 0) text.length else minOf(text.length, endRaw + 1)
        return text.substring(start, end).trim().take(160)
    }

    private val stopwords = setOf(
        "the", "and", "for", "with", "from", "into", "that", "this", "when",
        "should", "would", "could", "while", "until", "after", "before",
        "value", "values", "field", "fields", "test", "tests", "given", "where",
        "case", "cases"
    )
}
