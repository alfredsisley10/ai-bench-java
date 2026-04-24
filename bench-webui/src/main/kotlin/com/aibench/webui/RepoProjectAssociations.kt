package com.aibench.webui

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File

/**
 * Persisted GitHub-repository ↔ JIRA-project links. Many real
 * codebases use JIRA (not GitHub Issues) as the bug tracker, so the
 * benchmark needs to know "which JIRA project is the source of truth
 * for this repo's issues" before it can pull problem statements.
 *
 * <p>Storage is a small JSON file under {@code ~/.ai-bench/} so the
 * mapping survives a WebUI restart but never leaks into the project
 * source tree.
 */
@Component
class RepoProjectAssociations {

    private val log = LoggerFactory.getLogger(RepoProjectAssociations::class.java)

    data class Link(
        val repo: String,
        val jiraProjectKey: String,
        val jiraBaseUrl: String = "",
        val notes: String = ""
    )

    private val file: File by lazy {
        val dir = File(System.getProperty("user.home"), ".ai-bench")
        dir.mkdirs()
        File(dir, "repo-jira-associations.json")
    }

    /** Repo full name (e.g. "owner/name") → Link. */
    @Volatile private var current: Map<String, Link> = emptyMap()

    val links: List<Link> get() = current.values.sortedBy { it.repo.lowercase() }

    fun get(repo: String): Link? = current[repo]

    @PostConstruct
    fun load() {
        if (!file.exists()) {
            log.info("No repo-JIRA association file at {} — starting empty", file.absolutePath)
            return
        }
        runCatching {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val tree = mapper.readTree(file)
            val out = mutableMapOf<String, Link>()
            tree.path("links").forEach { node ->
                val repo = node.path("repo").asText("").trim()
                val proj = node.path("jiraProjectKey").asText("").trim()
                if (repo.isNotBlank() && proj.isNotBlank()) {
                    out[repo] = Link(
                        repo = repo,
                        jiraProjectKey = proj,
                        jiraBaseUrl = node.path("jiraBaseUrl").asText("").trim(),
                        notes = node.path("notes").asText("")
                    )
                }
            }
            current = out
            log.info("Loaded {} repo-JIRA association(s) from {}", out.size, file.absolutePath)
        }.onFailure { log.warn("Failed to load repo-JIRA associations: {}", it.message) }
    }

    fun upsert(link: Link) {
        val cleanRepo = link.repo.trim()
        val cleanProj = link.jiraProjectKey.trim().uppercase()
        if (cleanRepo.isBlank() || cleanProj.isBlank()) return
        val updated = current.toMutableMap()
        updated[cleanRepo] = link.copy(repo = cleanRepo, jiraProjectKey = cleanProj)
        current = updated
        persist()
    }

    fun delete(repo: String) {
        val updated = current.toMutableMap()
        if (updated.remove(repo.trim()) != null) {
            current = updated
            persist()
        }
    }

    private fun persist() {
        runCatching {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val out = mapper.createObjectNode()
            val arr = out.putArray("links")
            current.values.sortedBy { it.repo.lowercase() }.forEach { link ->
                val n = arr.addObject()
                n.put("repo", link.repo)
                n.put("jiraProjectKey", link.jiraProjectKey)
                n.put("jiraBaseUrl", link.jiraBaseUrl)
                n.put("notes", link.notes)
            }
            file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out))
        }.onFailure { log.warn("Failed to persist repo-JIRA associations: {}", it.message) }
    }
}
