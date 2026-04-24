package com.aibench.appmap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extracts a token-budget-friendly subset of an AppMap trace file: the call
 * tree's method names, SQL statements, and HTTP requests — stripping arg
 * payloads above a cap. Keeps the solver informed about runtime behavior
 * without drowning its context.
 */
class TraceCompactor(private val argValueCap: Int = 120) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun compact(tracePath: Path): JsonObject {
        val root = json.parseToJsonElement(Files.readString(tracePath)) as? JsonObject
            ?: return buildJsonObject { put("error", "not a JSON object") }

        val events = root["events"] as? JsonArray ?: JsonArray(emptyList())
        val sqls = mutableListOf<String>()
        val httpReqs = mutableListOf<String>()
        val calls = mutableListOf<String>()

        for (e in events) {
            val obj = e as? JsonObject ?: continue
            val eventType = (obj["event"] as? JsonPrimitive)?.content ?: continue
            if (eventType != "call") continue
            val sql = (obj["sql_query"] as? JsonObject)?.get("sql")?.let { (it as? JsonPrimitive)?.content }
            if (sql != null) {
                sqls += truncate(sql)
                continue
            }
            val http = (obj["http_server_request"] as? JsonObject)
            if (http != null) {
                val method = (http["request_method"] as? JsonPrimitive)?.content ?: "?"
                val url = (http["path_info"] as? JsonPrimitive)?.content ?: "?"
                httpReqs += "$method $url"
                continue
            }
            val defined = (obj["defined_class"] as? JsonPrimitive)?.content ?: "?"
            val method = (obj["method_id"] as? JsonPrimitive)?.content ?: "?"
            calls += "$defined#$method"
        }

        return buildJsonObject {
            put("call_count", calls.size)
            put("sql_count", sqls.size)
            put("http_count", httpReqs.size)
            put("top_calls", JsonArray(calls.distinct().take(200).map { JsonPrimitive(it) }))
            put("sql_statements", JsonArray(sqls.take(50).map { JsonPrimitive(it) }))
            put("http_requests", JsonArray(httpReqs.map { JsonPrimitive(it) }))
        }
    }

    private fun truncate(s: String) = if (s.length <= argValueCap) s else s.substring(0, argValueCap) + "…"
}
