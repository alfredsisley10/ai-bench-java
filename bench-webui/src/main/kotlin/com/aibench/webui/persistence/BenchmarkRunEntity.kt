package com.aibench.webui.persistence

import com.aibench.webui.BenchmarkRunService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.Instant

/**
 * JPA mirror of [BenchmarkRunService.BenchmarkRun]. Kept as a separate
 * type so the runtime BenchmarkRun can keep using @Volatile +
 * CopyOnWriteArrayList for its concurrent-mutation needs without
 * dragging Hibernate's proxy lifecycle into the worker-thread paths.
 *
 * Schema choice: scalars get real columns (so SQL drill-down /
 * dashboards / external tools can filter by model / status / time
 * without pulling JSON apart). Nested structures (RunStats list,
 * SeedResult list, SeedAudit list, LogEntry list) are stored as JSON
 * text -- they are only ever consumed wholesale by the dashboard
 * detail pages, so per-field columns would be all cost no benefit.
 *
 * SECURITY: This entity has no API-key / token / credential field, by
 * design. Secrets live in the OS keychain via SecretStore; do NOT add
 * one here, even if a future controller has one in scope.
 */
// IMPORTANT: every JPA annotation below uses the @field: use-site
// target. Kotlin's default for `var` constructor params is @param:,
// which Hibernate ignores during reflection-based introspection.
// Without the explicit @field: prefix, @Enumerated(EnumType.STRING)
// silently fell back to ORDINAL (storing 5 instead of "CANCELED")
// and broke startup hydration with "No enum constant Status.5".
// The @Convert annotations on the JSON columns were equally at risk.
@Entity
@Table(name = "benchmark_run")
class BenchmarkRunEntity(

    @field:Id
    @field:Column(length = 64)
    var id: String = "",

    @field:Column(name = "issue_id", length = 64) var issueId: String = "",
    @field:Column(name = "issue_title", length = 512) var issueTitle: String = "",
    @field:Column(length = 64) var provider: String = "",
    @field:Column(name = "model_id", length = 128) var modelId: String = "",
    @field:Column(name = "model_identifier", length = 128) var modelIdentifier: String = "",
    @field:Column(name = "context_provider", length = 64) var contextProvider: String = "",
    @field:Column(name = "appmap_mode", length = 32) var appmapMode: String = "",
    var seeds: Int = 0,

    @field:Column(name = "started_at") var startedAt: Instant = Instant.EPOCH,
    @field:Column(name = "ended_at") var endedAt: Instant? = null,

    @field:Column(length = 64) var phase: String = "",
    @field:Column(name = "current_seed") var currentSeed: Int = 0,

    @field:Enumerated(EnumType.STRING)
    @field:Column(length = 16)
    var status: BenchmarkRunService.Status = BenchmarkRunService.Status.QUEUED,

    @field:Column(name = "used_real_llm") var usedRealLlm: Boolean = false,
    @field:Column(name = "used_real_scoring") var usedRealScoring: Boolean = false,

    // ---- JSON blobs -- whole-object reads only.
    @field:Lob
    @field:Column(name = "stats_json", columnDefinition = "CLOB")
    @field:Convert(converter = JsonConverter::class)
    var stats: BenchmarkRunService.RunStats = BenchmarkRunService.RunStats(),

    @field:Lob
    @field:Column(name = "seed_results_json", columnDefinition = "CLOB")
    @field:Convert(converter = SeedResultListConverter::class)
    var seedResults: List<BenchmarkRunService.SeedResult> = emptyList(),

    @field:Lob
    @field:Column(name = "seed_audits_json", columnDefinition = "CLOB")
    @field:Convert(converter = SeedAuditListConverter::class)
    var seedAudits: List<BenchmarkRunService.SeedAudit> = emptyList(),

    @field:Lob
    @field:Column(name = "log_entries_json", columnDefinition = "CLOB")
    @field:Convert(converter = LogEntryListConverter::class)
    var logEntries: List<BenchmarkRunService.LogEntry> = emptyList()
) {
    /** No-arg ctor required by Hibernate. */
    constructor() : this(id = "")

    companion object {
        /** Snapshot the live in-memory run into a persistable entity.
         *  Reads from @Volatile fields without locking -- a single
         *  publisher (the worker thread) writes them, the snapshot
         *  observes whatever was last committed. */
        fun from(r: BenchmarkRunService.BenchmarkRun): BenchmarkRunEntity =
            BenchmarkRunEntity(
                id = r.id,
                issueId = r.issueId,
                issueTitle = r.issueTitle,
                provider = r.provider,
                modelId = r.modelId,
                modelIdentifier = r.modelIdentifier,
                contextProvider = r.contextProvider,
                appmapMode = r.appmapMode,
                seeds = r.seeds,
                startedAt = r.startedAt,
                endedAt = r.endedAt,
                phase = r.phase,
                currentSeed = r.currentSeed,
                status = r.status,
                usedRealLlm = r.usedRealLlm,
                usedRealScoring = r.usedRealScoring,
                stats = r.stats,
                seedResults = r.seedResults,
                seedAudits = r.seedAudits,
                logEntries = r.logEntries.toList()  // CopyOnWriteArrayList -> immutable copy
            )
    }

    /** Hydrate an in-memory run from a stored entity. logEntries are
     *  copied into a new CopyOnWriteArrayList so further appends from
     *  the worker thread (if the run resumes... which it won't, but
     *  defensively) don't mutate a JPA-managed list. */
    fun toDomain(): BenchmarkRunService.BenchmarkRun {
        val r = BenchmarkRunService.BenchmarkRun(
            id = id,
            issueId = issueId,
            issueTitle = issueTitle,
            provider = provider,
            modelId = modelId,
            modelIdentifier = modelIdentifier,
            contextProvider = contextProvider,
            appmapMode = appmapMode,
            seeds = seeds,
            startedAt = startedAt
        )
        r.endedAt = endedAt
        r.phase = phase
        r.currentSeed = currentSeed
        r.status = status
        r.usedRealLlm = usedRealLlm
        r.usedRealScoring = usedRealScoring
        r.stats = stats
        r.seedResults = seedResults
        r.seedAudits = seedAudits
        r.logEntries.addAll(logEntries)
        return r
    }
}

/**
 * Shared Jackson mapper for every JSON converter on this entity.
 * Module set is the minimum needed for the data classes used in
 * BenchmarkRun: KotlinModule for default values + nullable fields,
 * JavaTimeModule for Instant on LogEntry.
 */
private val mapper: ObjectMapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

@Converter
class JsonConverter : AttributeConverter<BenchmarkRunService.RunStats, String> {
    override fun convertToDatabaseColumn(attribute: BenchmarkRunService.RunStats?): String =
        mapper.writeValueAsString(attribute ?: BenchmarkRunService.RunStats())
    override fun convertToEntityAttribute(dbData: String?): BenchmarkRunService.RunStats {
        if (dbData.isNullOrBlank()) return BenchmarkRunService.RunStats()
        return mapper.readValue(dbData)
    }
}

@Converter
class SeedResultListConverter : AttributeConverter<List<BenchmarkRunService.SeedResult>, String> {
    override fun convertToDatabaseColumn(attribute: List<BenchmarkRunService.SeedResult>?): String =
        mapper.writeValueAsString(attribute ?: emptyList<BenchmarkRunService.SeedResult>())
    override fun convertToEntityAttribute(dbData: String?): List<BenchmarkRunService.SeedResult> {
        if (dbData.isNullOrBlank()) return emptyList()
        return mapper.readValue(dbData)
    }
}

@Converter
class SeedAuditListConverter : AttributeConverter<List<BenchmarkRunService.SeedAudit>, String> {
    override fun convertToDatabaseColumn(attribute: List<BenchmarkRunService.SeedAudit>?): String =
        mapper.writeValueAsString(attribute ?: emptyList<BenchmarkRunService.SeedAudit>())
    override fun convertToEntityAttribute(dbData: String?): List<BenchmarkRunService.SeedAudit> {
        if (dbData.isNullOrBlank()) return emptyList()
        return mapper.readValue(dbData)
    }
}

@Converter
class LogEntryListConverter : AttributeConverter<List<BenchmarkRunService.LogEntry>, String> {
    override fun convertToDatabaseColumn(attribute: List<BenchmarkRunService.LogEntry>?): String =
        mapper.writeValueAsString(attribute ?: emptyList<BenchmarkRunService.LogEntry>())
    override fun convertToEntityAttribute(dbData: String?): List<BenchmarkRunService.LogEntry> {
        if (dbData.isNullOrBlank()) return emptyList()
        return mapper.readValue(dbData)
    }
}
