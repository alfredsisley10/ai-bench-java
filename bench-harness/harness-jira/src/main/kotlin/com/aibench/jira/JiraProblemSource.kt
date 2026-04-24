package com.aibench.jira

/**
 * Turns a JIRA issue into a problem-statement string the solver sees.
 * Intentionally stays a single-concern utility — no harness-core import.
 */
class JiraProblemSource(private val client: JiraClient) {

    fun problemStatement(issueKey: String): String {
        val issue = client.getIssue(issueKey)
        val description = issue.fields.description?.content
            ?.joinToString("\n\n") { n -> n.flatten() }
            .orEmpty()
        return buildString {
            appendLine("[${issue.key}] ${issue.fields.summary}")
            appendLine()
            appendLine(description.ifBlank { "(no description)" })
        }
    }

    fun candidatesForBenchmarking(projectKey: String): List<JiraClient.Issue> =
        client.searchJql("project = $projectKey AND issuetype = Bug AND status = Resolved AND resolution = Fixed ORDER BY resolved DESC")
}
