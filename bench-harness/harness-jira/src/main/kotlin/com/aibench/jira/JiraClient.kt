package com.aibench.jira

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

/**
 * Minimal JIRA Cloud / Server REST v3 client for ticket fetching. The harness
 * uses this to pull ticket descriptions as problem statements when evaluating
 * against an enterprise repo that tracks bugs in JIRA (rather than GitHub
 * Issues).
 *
 * Auth: basic auth (email + API token) for JIRA Cloud, or PAT for Server.
 */
class JiraClient(
    private val baseUrl: String,
    private val email: String,
    private val apiToken: String,
    private val http: OkHttpClient = OkHttpClient()
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun getIssue(key: String): Issue {
        val req = Request.Builder()
            .url("$baseUrl/rest/api/3/issue/$key".toHttpUrl())
            .addHeader("Authorization", Credentials.basic(email, apiToken))
            .addHeader("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: error("empty issue response for $key")
            if (!resp.isSuccessful) error("JIRA $key fetch failed: ${resp.code} $body")
            return json.decodeFromString(Issue.serializer(), body)
        }
    }

    fun searchJql(jql: String, fields: List<String> = DEFAULT_FIELDS, maxResults: Int = 50): List<Issue> {
        val url = "$baseUrl/rest/api/3/search".toHttpUrl().newBuilder()
            .addQueryParameter("jql", jql)
            .addQueryParameter("fields", fields.joinToString(","))
            .addQueryParameter("maxResults", maxResults.toString())
            .build()
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", Credentials.basic(email, apiToken))
            .addHeader("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: error("empty search response")
            if (!resp.isSuccessful) error("JIRA search failed: ${resp.code} $body")
            val result = json.decodeFromString(SearchResult.serializer(), body)
            return result.issues
        }
    }

    companion object {
        val DEFAULT_FIELDS = listOf("summary", "description", "status", "priority", "labels", "issuetype")
    }

    @Serializable
    data class Issue(val key: String, val fields: Fields)

    @Serializable
    data class Fields(
        val summary: String,
        val description: JsonElementDoc? = null,
        val status: Named? = null,
        val priority: Named? = null,
        val labels: List<String> = emptyList()
    )

    @Serializable
    data class Named(val name: String)

    @Serializable
    data class JsonElementDoc(val content: List<Node>? = null)

    @Serializable
    data class Node(val type: String, val text: String? = null, val content: List<Node>? = null) {
        fun flatten(): String {
            if (text != null) return text
            return (content ?: emptyList()).joinToString(" ") { it.flatten() }
        }
    }

    @Serializable
    data class SearchResult(val issues: List<Issue> = emptyList())
}
