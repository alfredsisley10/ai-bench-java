package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * HTTP surface for managing the GitHub-repo ↔ JIRA-project mapping.
 * The JIRA config page hosts the primary editor; the github-repos
 * page surfaces the resolved link in its own column. The same
 * underlying service backs both views via {@link RepoProjectAssociations}.
 */
@Controller
class AssociationsController(
    private val associations: RepoProjectAssociations
) {

    @PostMapping("/associations/repo-jira/save")
    fun save(
        @RequestParam repo: String,
        @RequestParam jiraProjectKey: String,
        @RequestParam(required = false, defaultValue = "") jiraBaseUrl: String,
        @RequestParam(required = false, defaultValue = "") notes: String,
        @RequestParam(required = false) returnTo: String?,
        session: HttpSession
    ): String {
        associations.upsert(RepoProjectAssociations.Link(
            repo = repo, jiraProjectKey = jiraProjectKey,
            jiraBaseUrl = jiraBaseUrl, notes = notes
        ))
        session.setAttribute("repoJiraSaveResult",
            "Linked $repo → ${jiraProjectKey.uppercase()}.")
        return "redirect:" + (returnTo ?: "/jira") + "#repo-jira-associations"
    }

    @PostMapping("/associations/repo-jira/delete")
    fun delete(
        @RequestParam repo: String,
        @RequestParam(required = false) returnTo: String?,
        session: HttpSession
    ): String {
        associations.delete(repo)
        session.setAttribute("repoJiraSaveResult",
            "Removed link for $repo.")
        return "redirect:" + (returnTo ?: "/jira") + "#repo-jira-associations"
    }

    /** JSON list of all current associations — convenient for the harness
     *  to read before pulling JIRA tickets vs GitHub Issues for a repo. */
    @GetMapping("/api/associations/repo-jira")
    @ResponseBody
    fun list(): List<RepoProjectAssociations.Link> = associations.links

    /** Single-repo lookup; returns 200 with the link or 404 not found. */
    @GetMapping("/api/associations/repo-jira/lookup")
    @ResponseBody
    fun lookup(@RequestParam repo: String): RepoProjectAssociations.Link? =
        associations.get(repo)
}
