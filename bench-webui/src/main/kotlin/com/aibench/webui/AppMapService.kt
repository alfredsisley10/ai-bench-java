package com.aibench.webui

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * Walks `banking-app/**/tmp/appmap/` for AppMap JSON traces and renders them
 * for the demo viewer. Pure read-only — recording is handled separately.
 */
@Service
class AppMapService(
    val bankingApp: BankingAppManager,
    private val connectionSettings: ConnectionSettings
) {

    private val log = LoggerFactory.getLogger(AppMapService::class.java)
    private val mapper = ObjectMapper()

    /**
     * Lightweight summary used on the list page. Includes a few
     * complexity counts so the operator can compare AppMap traces at
     * a glance:
     *  - dependencyCount: distinct packages in the classMap (the
     *    width of the Dependency Map)
     *  - sequenceRootCount: top-level call events (root nodes in
     *    the Sequence Diagram)
     *  - traceCallCount: total call events (rows in the Trace View)
     *  - flameFrameCount: distinct (defined_class, method_id) pairs
     *    that appeared at least once (boxes in the Flame Graph)
     */
    data class TraceSummary(
        val id: String,           // base64-url-encoded relative path, used in URLs
        val module: String,       // gradle module the trace lives under
        val displayName: String,  // friendly name (test name if available)
        val relativePath: String, // path under banking-app/, for display
        val sizeBytes: Long,
        val eventCount: Int,
        val sqlCount: Int,
        val httpCount: Int,
        val recorderName: String?,
        val dependencyCount: Int = 0,
        val sequenceRootCount: Int = 0,
        val traceCallCount: Int = 0,
        val flameFrameCount: Int = 0
    )

    /** Full call-tree event used by the viewer. */
    data class CallNode(
        val id: Int,
        val label: String,         // ClassName#methodName or HTTP / SQL line
        val kind: Kind,
        val elapsedMs: Double?,    // null if not yet returned
        val sqlText: String?,      // populated when kind == SQL
        val httpSummary: String?,  // populated when kind == HTTP
        val children: MutableList<CallNode> = mutableListOf()
    ) {
        enum class Kind { CALL, SQL, HTTP }
    }

    data class TraceDetail(
        val summary: TraceSummary,
        val roots: List<CallNode>,
        val flat: List<FlatNode>
    )

    /** Pre-flattened call-tree row used by the viewer template. */
    data class FlatNode(
        val depth: Int,
        val label: String,
        val kind: CallNode.Kind,
        val elapsedMs: Double?,
        val sqlText: String?,
        val httpSummary: String?,
        val hasChildren: Boolean
    )

    fun listTraces(): List<TraceSummary> {
        val appmapRoot = bankingApp.bankingAppDir
        if (!appmapRoot.isDirectory) return emptyList()

        val out = mutableListOf<TraceSummary>()
        Files.walk(appmapRoot.toPath()).use { stream ->
            stream
                .filter {
                    // Use forward-slash form so the `tmp/appmap` substring
                    // check works on Windows too (Path.toString() returns
                    // platform-native separators — "\" on Windows — which
                    // previously made every saved trace invisible because
                    // none of them contained the literal "tmp/appmap").
                    it.extension == "json" &&
                        it.toFile().invariantSeparatorsPath.contains("tmp/appmap")
                }
                .forEach { path ->
                    runCatching { summarize(path, appmapRoot.toPath()) }
                        .onSuccess { out.add(it) }
                        .onFailure { e ->
                            log.warn("Failed to summarize appmap trace {}: {}",
                                path, e.message)
                        }
                }
        }
        return out.sortedBy { it.relativePath }
    }

    fun load(traceId: String): TraceDetail? {
        val relativePath = decodeId(traceId) ?: return null
        val absolute = bankingApp.bankingAppDir.toPath().resolve(relativePath)
        if (!Files.isRegularFile(absolute)) return null
        // Forward-slash form — Windows paths use "\" so the literal
        // "tmp/appmap" substring would never match without normalization.
        if (!absolute.toFile().invariantSeparatorsPath.contains("tmp/appmap")) return null // path-traversal guard
        val summary = runCatching { summarize(absolute, bankingApp.bankingAppDir.toPath()) }
            .getOrNull() ?: return null
        val root = mapper.readTree(absolute.toFile())
        val roots = buildCallTree(root.path("events"), root.path("classMap"))
        val flat = mutableListOf<FlatNode>()
        for (r in roots) flatten(r, 0, flat)
        return TraceDetail(summary, roots, flat)
    }

    private fun flatten(node: CallNode, depth: Int, out: MutableList<FlatNode>) {
        out.add(FlatNode(
            depth = depth,
            label = node.label,
            kind = node.kind,
            elapsedMs = node.elapsedMs,
            sqlText = node.sqlText,
            httpSummary = node.httpSummary,
            hasChildren = node.children.isNotEmpty()
        ))
        // Bound the depth at which we render; very deep trees would
        // overwhelm the page.
        if (depth >= 80) return
        for (child in node.children) flatten(child, depth + 1, out)
    }

    private fun summarize(path: Path, root: Path): TraceSummary {
        val rel = root.relativize(path).toString().replace('\\', '/')
        val module = rel.substringBefore("/")
        val node = mapper.readTree(path.toFile())
        val events = node.path("events")
        var eventCount = 0
        var sqlCount = 0
        var httpCount = 0
        var traceCallCount = 0
        var sequenceRootCount = 0
        val flameFrames = HashSet<String>()
        // Build a quick id→parent_id map so we can identify root call
        // events (calls whose parent isn't itself a call we know about).
        val callIds = HashSet<Int>()
        if (events.isArray) {
            for (e in events) {
                if (e.path("event").asText() == "call") {
                    callIds.add(e.path("id").asInt())
                }
            }
            for (e in events) {
                eventCount++
                if (!e.path("sql_query").isMissingNode) sqlCount++
                if (!e.path("http_server_request").isMissingNode
                    || !e.path("http_client_request").isMissingNode) httpCount++
                if (e.path("event").asText() == "call") {
                    traceCallCount++
                    val parentId = e.path("parent_id").asInt(-1)
                    if (parentId == -1 || !callIds.contains(parentId)) {
                        sequenceRootCount++
                    }
                    val cls = e.path("defined_class").asText("")
                    val method = e.path("method_id").asText("")
                    if (cls.isNotEmpty() || method.isNotEmpty()) {
                        flameFrames.add("$cls#$method")
                    }
                }
            }
        }
        // Dependency-map width = distinct top-level packages in the
        // classMap. Each top-level entry is typically a package node.
        val classMap = node.path("classMap")
        val dependencyCount = countTopLevelPackages(classMap)
        val name = node.path("metadata").path("name").asText("").ifBlank {
            path.name.removeSuffix(".appmap.json").removeSuffix(".json")
        }
        val recorder = node.path("metadata").path("recorder").path("name").asText(null)
        return TraceSummary(
            id = encodeId(rel),
            module = module,
            displayName = name,
            relativePath = rel,
            sizeBytes = path.fileSize(),
            eventCount = eventCount,
            sqlCount = sqlCount,
            httpCount = httpCount,
            recorderName = recorder,
            dependencyCount = dependencyCount,
            sequenceRootCount = sequenceRootCount,
            traceCallCount = traceCallCount,
            flameFrameCount = flameFrames.size
        )
    }

    /**
     * Walk the classMap and count distinct package nodes (any level).
     * The dependency-map renders one node per package, so this matches
     * the user's intuition of "how many things will I see in the
     * Dependency Map".
     */
    private fun countTopLevelPackages(classMap: JsonNode): Int {
        if (!classMap.isArray) return 0
        var n = 0
        fun walk(node: JsonNode) {
            if (node.path("type").asText() == "package") n++
            val kids = node.path("children")
            if (kids.isArray) for (k in kids) walk(k)
        }
        for (top in classMap) walk(top)
        return n
    }

    /**
     * Reconstructs a tree of CallNodes from the linear AppMap event list.
     * Each "call" event opens a node; the matching "return" (linked via
     * `parent_id` to the call) closes it and supplies elapsed time.
     */
    private fun buildCallTree(events: JsonNode, classMap: JsonNode): List<CallNode> {
        if (!events.isArray || events.isEmpty) return emptyList()
        val byId = HashMap<Int, CallNode>(events.size())
        val roots = mutableListOf<CallNode>()
        val stack = ArrayDeque<CallNode>()

        for (e in events) {
            val ev = e.path("event").asText()
            val id = e.path("id").asInt()
            when (ev) {
                "call" -> {
                    val node = nodeForCall(id, e)
                    val parent = stack.lastOrNull()
                    if (parent != null) parent.children.add(node) else roots.add(node)
                    byId[id] = node
                    stack.addLast(node)
                }
                "return" -> {
                    val parentId = e.path("parent_id").asInt(-1)
                    val target = byId[parentId]
                    if (target != null) {
                        val elapsed = e.path("elapsed").let { if (it.isNumber) it.asDouble() else null }
                        // Replace the node with one that has elapsed set, preserving children.
                        val updated = target.copy(elapsedMs = elapsed?.times(1000))
                        updated.children.addAll(target.children)
                        // Find target in parent / roots and replace.
                        replaceNode(roots, target, updated)
                        byId[parentId] = updated
                    }
                    if (stack.isNotEmpty()) stack.removeLast()
                }
            }
        }
        return roots
    }

    private fun replaceNode(roots: MutableList<CallNode>, old: CallNode, new: CallNode) {
        // Walk the tree and swap references. CallNode is identified by id.
        for (i in roots.indices) {
            if (roots[i].id == old.id) {
                roots[i] = new
                return
            }
            if (replaceInChildren(roots[i], old, new)) return
        }
    }

    private fun replaceInChildren(parent: CallNode, old: CallNode, new: CallNode): Boolean {
        for (i in parent.children.indices) {
            if (parent.children[i].id == old.id) {
                parent.children[i] = new
                return true
            }
            if (replaceInChildren(parent.children[i], old, new)) return true
        }
        return false
    }

    private fun nodeForCall(id: Int, e: JsonNode): CallNode {
        val sql = e.path("sql_query")
        if (!sql.isMissingNode) {
            val text = sql.path("sql").asText("")
            return CallNode(
                id = id,
                label = "SQL: " + text.take(80),
                kind = CallNode.Kind.SQL,
                elapsedMs = null,
                sqlText = text,
                httpSummary = null
            )
        }
        val httpServer = e.path("http_server_request")
        if (!httpServer.isMissingNode) {
            val method = httpServer.path("request_method").asText("?")
            val pathInfo = httpServer.path("path_info").asText("?")
            return CallNode(
                id = id,
                label = "HTTP $method $pathInfo",
                kind = CallNode.Kind.HTTP,
                elapsedMs = null,
                sqlText = null,
                httpSummary = "$method $pathInfo"
            )
        }
        val cls = e.path("defined_class").asText("")
        val method = e.path("method_id").asText("")
        val isStatic = e.path("static").asBoolean(false)
        val sep = if (isStatic) "::" else "#"
        val label = if (cls.isNotEmpty() && method.isNotEmpty()) "$cls$sep$method" else "(anonymous)"
        return CallNode(
            id = id,
            label = label,
            kind = CallNode.Kind.CALL,
            elapsedMs = null,
            sqlText = null,
            httpSummary = null
        )
    }

    private fun encodeId(relativePath: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(relativePath.toByteArray(Charsets.UTF_8))

    private fun decodeId(id: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(id), Charsets.UTF_8)
    }.getOrNull()

    // ── Recording state tracking ──────────────────────────────────────

    enum class RecordingStatus {
        RUNNING,
        /** Gradle exited 0 AND new .appmap.json files appeared on disk. */
        SUCCEEDED,
        /** Gradle exited non-zero. */
        FAILED,
        /** Gradle exited 0 but the trace count didn't increase. The
         *  build is "green" but produced nothing -- exactly the
         *  failure mode operators kept reporting as "click succeeds,
         *  no traces appear." Distinct from SUCCEEDED so the panel
         *  can surface the specific likely causes inline (no test
         *  matched the filter, agent didn't attach, plugin marker
         *  resolution fell through, etc.) instead of a misleading
         *  green badge. */
        SUCCEEDED_NO_TRACES
    }

    /**
     * Snapshot of a single recording session. The mutable parts (status,
     * exit code, finishedAt, tracesAfter) are filled in when the gradle
     * subprocess exits.
     */
    data class Recording(
        val id: String,
        val command: List<String>,
        val module: String?,
        val testFilter: String?,
        val logPath: Path,
        val pid: Long,
        val startedAt: Instant,
        val tracesBefore: Int,
        @Volatile var status: RecordingStatus = RecordingStatus.RUNNING,
        @Volatile var finishedAt: Instant? = null,
        @Volatile var exitCode: Int? = null,
        @Volatile var tracesAfter: Int? = null
    ) {
        val elapsedSeconds: Long get() = Duration.between(
            startedAt, finishedAt ?: Instant.now()).seconds.coerceAtLeast(0)
        val newTraceCount: Int? get() = tracesAfter?.let { it - tracesBefore }
    }

    private val recordingsById = ConcurrentHashMap<String, Recording>()

    /** Most-recent (or in-flight) recording, for the status panel. */
    fun latestRecording(): Recording? =
        recordingsById.values.maxByOrNull { it.startedAt }

    fun recording(id: String): Recording? = recordingsById[id]

    fun tailLog(recording: Recording, lines: Int = 80): String {
        val f = recording.logPath.toFile()
        if (!f.exists()) return "(log file not yet created)"
        val all = f.readLines()
        return all.takeLast(lines).joinToString("\n")
    }

    /**
     * Kicks off `./gradlew test` for the given module (or all modules if
     * blank) with `ORG_GRADLE_PROJECT_appmap_enabled=true`. Runs detached;
     * caller polls the returned [Recording.id] for live status.
     */
    /**
     * All Gradle subprojects declared in banking-app/settings.gradle.kts.
     * Cheap regex parse over the file — accurate enough for populating
     * the /demo/appmap "Record from tests" multi-select. Used by the UI
     * so the operator can pick from a known list instead of typing a
     * module name and hoping it matches. Sorted alphabetically.
     */
    fun availableSubprojects(): List<String> {
        val settings = bankingApp.bankingAppDir.resolve("settings.gradle.kts")
        if (!settings.isFile) return emptyList()
        return runCatching {
            val text = settings.readText()
            // Match `include("foo")`, `include("foo:bar")`, etc.
            // Skip commented-out lines.
            Regex("""(?m)^\s*include\s*\(\s*"([^"]+)"\s*\)""")
                .findAll(text)
                .map { it.groupValues[1] }
                .distinct()
                .sorted()
                .toList()
        }.getOrDefault(emptyList())
    }

    /** Backwards-compatible single-module entry — see the [List] overload. */
    fun startRecordingFromTests(module: String?, testFilter: String?): Recording =
        startRecordingFromTests(module?.takeIf { it.isNotBlank() }?.let { listOf(it) }, testFilter)

    fun startRecordingFromTests(modules: List<String>?, testFilter: String?): Recording {
        val dir = bankingApp.bankingAppDir
        val cmd = mutableListOf<String>().apply { addAll(Platform.gradleWrapper(dir)) }
        // The AppMap gradle plugin's `appmap` task injects the AppMap agent
        // into the matching `test` task; ordering matters — both must run,
        // and `appmap` must come first so the agent settings apply when
        // `test` executes.
        // Multi-module support: emit `:mod:appmap :mod:test` per picked
        // module so the operator can scope the recording to a handful
        // of subprojects without invoking the entire :test graph (which
        // takes 30-60s on the full generated module set).
        val cleanModules = (modules ?: emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanModules.isEmpty()) {
            cmd.add("appmap"); cmd.add("test")
        } else {
            cleanModules.forEach { m ->
                // Module IDs may already be colon-separated (`generated:foo`)
                // — that's fine; Gradle handles `:generated:foo:appmap` directly.
                val gradlePath = if (m.startsWith(":")) m else ":$m"
                cmd.add("$gradlePath:appmap"); cmd.add("$gradlePath:test")
            }
        }
        if (!testFilter.isNullOrBlank()) {
            cmd.add("--tests"); cmd.add(testFilter)
        }
        // The plugin only registers itself when this property is true
        // (see banking-app/build.gradle.kts:8). Force a clean run so the
        // gradle config cache picks up the agent wiring.
        cmd.add("-Pappmap_enabled=true")
        cmd.add("--rerun-tasks")
        cmd.add("--no-configuration-cache")
        cmd.add("--console=plain")
        // Forward WebUI proxy + TLS preferences onto the Gradle daemon
        // so dependency downloads and any test-time outbound HTTP
        // inherit the same egress configuration.
        cmd.addAll(connectionSettings.gradleSystemProps())

        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        pb.environment()["ORG_GRADLE_PROJECT_appmap_enabled"] = "true"
        pb.environment()["APPMAP_ENABLED"] = "true"
        // Cross-platform JDK pick — JdkDiscovery walks env var, PATH,
        // and platform-specific install roots. Replaces the old
        // macOS-only Homebrew hardcode that silently broke on Windows.
        val javaHome = JdkDiscovery.bestAvailableHome(matchMajor = bankingApp.toolchainMajor())
        pb.environment()["JAVA_HOME"] = javaHome
        pb.environment()["PATH"] = "$javaHome/bin:" + System.getenv("PATH")

        val recordingId = UUID.randomUUID().toString().substring(0, 8)
        val logFile = File(dir, "tmp/appmap-record-$recordingId.log")
        logFile.parentFile.mkdirs()
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile))

        val tracesBefore = listTraces().size
        val proc = pb.start()
        val recording = Recording(
            id = recordingId,
            command = cmd.toList(),
            // Concatenate the picked modules for the recording metadata
            // (used by the live status panel + log line). null when the
            // operator left the multi-select empty (record everything).
            module = cleanModules.joinToString(",").takeIf { it.isNotEmpty() },
            testFilter = testFilter,
            logPath = logFile.toPath(),
            pid = proc.pid(),
            startedAt = Instant.now(),
            tracesBefore = tracesBefore
        )
        recordingsById[recording.id] = recording

        // Watcher thread fills in finish state without blocking the request.
        Thread({
            val exit = runCatching { proc.waitFor() }.getOrDefault(-1)
            recording.exitCode = exit
            recording.tracesAfter = listTraces().size
            recording.finishedAt = Instant.now()
            recording.status = when {
                exit != 0 -> RecordingStatus.FAILED
                (recording.newTraceCount ?: 0) == 0 -> RecordingStatus.SUCCEEDED_NO_TRACES
                else -> RecordingStatus.SUCCEEDED
            }
            log.info("AppMap recording {} finished: exit={} traces+={}",
                recording.id, exit, recording.newTraceCount)
        }, "appmap-record-watch-${recording.id}").apply {
            isDaemon = true
        }.start()

        log.info("AppMap recording {} started (PID {}, modules={}, filter={}). Log: {}",
            recording.id, proc.pid(), cleanModules, testFilter, logFile.absolutePath)
        return recording
    }

    fun stopRecording(id: String): Boolean {
        val rec = recordingsById[id] ?: return false
        if (rec.status != RecordingStatus.RUNNING) return false
        // Best-effort: locate the gradle process and SIGTERM it.
        ProcessHandle.of(rec.pid).ifPresent { it.destroy() }
        return true
    }

    /** Result of a bulk-delete: how many files were removed and total bytes freed. */
    data class DeleteSummary(val filesDeleted: Int, val bytesFreed: Long, val errors: List<String>)

    /**
     * Delete one trace by its base64-url id. Returns true if the file
     * existed and was removed; false if the id is unknown or the file
     * was already gone.
     */
    fun deleteTrace(traceId: String): Boolean {
        val rel = decodeId(traceId) ?: return false
        val abs = bankingApp.bankingAppDir.toPath().resolve(rel)
        if (!Files.isRegularFile(abs)) return false
        // Forward-slash form — see listTraces() for why.
        if (!abs.toFile().invariantSeparatorsPath.contains("tmp/appmap")) return false // guard
        return Files.deleteIfExists(abs)
    }

    /** Delete every trace under any banking-app `tmp/appmap/` directory — full reset. */
    fun deleteAllTraces(): DeleteSummary {
        val errors = mutableListOf<String>()
        var files = 0
        var bytes = 0L
        for (trace in listTraces()) {
            val abs = bankingApp.bankingAppDir.toPath().resolve(trace.relativePath)
            try {
                val size = Files.size(abs)
                if (Files.deleteIfExists(abs)) {
                    files++
                    bytes += size
                }
            } catch (e: Exception) {
                errors.add("${trace.relativePath}: ${e.message}")
            }
        }
        log.info("Deleted {} appmap traces ({} bytes, {} errors)", files, bytes, errors.size)
        return DeleteSummary(files, bytes, errors)
    }

    /** Delete every trace under a single gradle module's tmp/appmap/. */
    fun deleteTracesForModule(module: String): DeleteSummary {
        val errors = mutableListOf<String>()
        var files = 0
        var bytes = 0L
        for (trace in listTraces().filter { it.module == module }) {
            val abs = bankingApp.bankingAppDir.toPath().resolve(trace.relativePath)
            try {
                val size = Files.size(abs)
                if (Files.deleteIfExists(abs)) {
                    files++
                    bytes += size
                }
            } catch (e: Exception) {
                errors.add("${trace.relativePath}: ${e.message}")
            }
        }
        log.info("Deleted {} appmap traces for module {} ({} bytes, {} errors)",
            files, module, bytes, errors.size)
        return DeleteSummary(files, bytes, errors)
    }

    /** Modules that currently hold one or more traces. */
    fun modulesWithTraces(): List<String> =
        listTraces().map { it.module }.distinct().sorted()

    /** Resolve a trace summary back to the absolute file path on disk. */
    fun absolutePathFor(summary: TraceSummary): Path? {
        val abs = bankingApp.bankingAppDir.toPath().resolve(summary.relativePath)
        return if (Files.isRegularFile(abs)) abs else null
    }

    @Volatile private var cachedAgentJar: String? = null
    @Volatile private var lastAgentDiscoveryDiagnostic: String = ""

    /** Last-attempt diagnostic from agentJarPath() — surfaced in the
     *  /demo/appmap status banner when discovery fails so the operator
     *  doesn't have to dig through bench-webui logs to know why. */
    fun lastAgentDiscoveryDiagnostic(): String = lastAgentDiscoveryDiagnostic

    /**
     * Returns the absolute path to the AppMap Java agent jar, discovering
     * it via the `appmap-print-jar-path` gradle task on first call and
     * caching the result. Returns null if discovery fails.
     *
     * <p>Has a 120s timeout so a hung Gradle daemon can't block the
     * WebUI request indefinitely. On failure, captures the full Gradle
     * output into {@link #lastAgentDiscoveryDiagnostic} so the page can
     * surface what went wrong without sending the operator into bench-webui logs.
     */
    fun agentJarPath(): String? {
        cachedAgentJar?.let { return it }
        val dir = bankingApp.bankingAppDir
        val cmd = mutableListOf<String>().apply {
            addAll(Platform.gradleWrapper(dir))
            add(":app-bootstrap:appmap-print-jar-path")
            add("-Pappmap_enabled=true")
            add("--no-configuration-cache")
            add("--quiet")
            add("--console=plain")
        }
        // Route Gradle's own HTTP (dep resolution for the AppMap plugin
        // jar) through the operator-configured proxy and honor the
        // insecure-SSL toggle if set.
        cmd.addAll(connectionSettings.gradleSystemProps())
        val pb = ProcessBuilder(cmd)
            .directory(dir).redirectErrorStream(true)
        // Cross-platform JDK pick — JdkDiscovery walks env var, PATH,
        // and platform-specific install roots. Replaces the old
        // macOS-only Homebrew hardcode that silently broke on Windows.
        val javaHome = JdkDiscovery.bestAvailableHome(matchMajor = bankingApp.toolchainMajor())
        if (javaHome.isNotBlank()) {
            pb.environment()["JAVA_HOME"] = javaHome
            pb.environment()["PATH"] =
                "$javaHome${java.io.File.separator}bin${java.io.File.pathSeparator}" +
                    (System.getenv("PATH") ?: "")
        }
        val diag = StringBuilder()
        diag.appendLine("$ ${cmd.joinToString(" ")}")
        diag.appendLine("[env] JAVA_HOME=$javaHome")
        return runCatching {
            val p = pb.start()
            val text = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            diag.append(text)
            if (!finished) {
                p.destroyForcibly()
                diag.appendLine("[err] gradle task hung past 120s; killed.")
                lastAgentDiscoveryDiagnostic = diag.toString()
                return null
            }
            // Output line looks like: com.appland:appmap-agent.jar.path=/path/to/agent.jar
            val match = Regex("appmap-agent\\.jar\\.path=(.+)").find(text)
            val jar = match?.groupValues?.get(1)?.trim()
            if (jar.isNullOrBlank()) {
                diag.appendLine("[err] gradle exited ${p.exitValue()} but did not print " +
                    "an `appmap-agent.jar.path=…` line. Common causes: AppMap Gradle " +
                    "plugin not applied (check banking-app/app-bootstrap/build.gradle.kts " +
                    "for the `com.appland.appmap` plugin), a build-script error before " +
                    "the task runs, or a toolchain mismatch.")
                lastAgentDiscoveryDiagnostic = diag.toString()
                return null
            }
            diag.appendLine("[ok] resolved agent: $jar")
            lastAgentDiscoveryDiagnostic = diag.toString()
            cachedAgentJar = jar
            jar
        }.onFailure {
            diag.appendLine("[err] ${it.javaClass.simpleName}: ${it.message}")
            lastAgentDiscoveryDiagnostic = diag.toString()
            log.warn("Failed to discover AppMap agent jar: {}", it.message)
        }.getOrNull()
    }

    fun appMapConfigFile(): String =
        bankingApp.bankingAppDir.resolve("appmap.yml").absolutePath

    /** Start banking-app with the AppMap agent attached. */
    fun startBankingAppWithAgent(): String {
        val agent = agentJarPath()
            ?: return "Could not discover AppMap agent jar. " +
                "Last `appmap-print-jar-path` attempt — see the " +
                "/demo/appmap diagnostics panel for the captured Gradle " +
                "output. Quick check: open a terminal in banking-app/ and " +
                "run `./gradlew :app-bootstrap:appmap-print-jar-path -Pappmap_enabled=true`."
        return bankingApp.startWithAppMapAgent(agent, appMapConfigFile())
    }

    /**
     * Outcomes of a remote-recording control request, mapped onto the
     * AppMap agent's HTTP endpoint.
     */
    enum class RemoteRecordResult { STARTED, STOPPED_AND_SAVED, NO_ACTIVE_RECORDING, AGENT_NOT_RUNNING, AGENT_ERROR }

    data class RemoteRecordOutcome(
        val result: RemoteRecordResult,
        val message: String,
        val savedTraceRelative: String? = null
    )

    // Built on demand so /proxy toggles apply without a WebUI restart.
    // Localhost is always bypassed by ConnectionSettings' ProxySelector,
    // so the agent call still hits 127.0.0.1 directly even when a
    // corporate proxy is configured.
    private fun httpClient() =
        connectionSettings.httpClient(java.time.Duration.ofSeconds(3))

    /**
     * Non-destructive check for whether the AppMap Java agent is
     * actually attached to the running banking-app process. Probes
     * the agent's {@code /_appmap/record} endpoint with HTTP {@code
     * GET} (which agents respond to with the current recording state
     * — never starts or stops anything). Returns:
     *
     * <ul>
     *   <li><b>true</b> — got a 2xx / 405 / 409 (endpoint exists)</li>
     *   <li><b>false</b> — got a 404 (no such endpoint → agent absent)
     *       OR connect refused / timeout (banking app not actually up
     *       on the expected port)</li>
     * </ul>
     *
     * <p>Crucial because the operator may have launched the
     * banking-app via the main /demo page (which doesn't pass
     * {@code -javaagent}) and would otherwise see "RUNNING" here
     * without realizing interactive recording will fail.
     */
    fun isAgentAttached(): Boolean {
        if (bankingApp.status() != BankingAppManager.Status.RUNNING) return false
        return runCatching {
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(bankingApp.url + "/_appmap/record"))
                .timeout(java.time.Duration.ofSeconds(2))
                .GET()
                .build()
            val resp = httpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            // Anything other than 404 means the route is registered
            // somewhere — that route is unique to the AppMap agent.
            resp.statusCode() != 404
        }.getOrDefault(false)
    }

    /** POST /_appmap/record on the running banking-app. */
    fun startRemoteRecording(): RemoteRecordOutcome {
        if (bankingApp.status() != BankingAppManager.Status.RUNNING) {
            return RemoteRecordOutcome(RemoteRecordResult.AGENT_NOT_RUNNING,
                "Banking app is not running. Start it with the agent first.")
        }
        return try {
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(bankingApp.url + "/_appmap/record"))
                .timeout(java.time.Duration.ofSeconds(5))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build()
            val resp = httpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            when (resp.statusCode()) {
                200, 201 -> RemoteRecordOutcome(RemoteRecordResult.STARTED,
                    "Recording started. Drive the banking-app from your browser, then click Stop.")
                409 -> RemoteRecordOutcome(RemoteRecordResult.STARTED,
                    "A recording is already in progress (HTTP 409). Stop it to save the trace.")
                404 -> RemoteRecordOutcome(RemoteRecordResult.AGENT_NOT_RUNNING,
                    "Banking app is running but the AppMap agent isn't attached " +
                    "(/_appmap/record returned 404). Restart the app with the agent.")
                else -> RemoteRecordOutcome(RemoteRecordResult.AGENT_ERROR,
                    "Agent responded HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
            }
        } catch (e: Exception) {
            RemoteRecordOutcome(RemoteRecordResult.AGENT_ERROR,
                "Failed to reach agent: ${e.message}")
        }
    }

    /** DELETE /_appmap/record, capture the response JSON, save it under tmp/appmap/interactive/. */
    fun stopRemoteRecording(): RemoteRecordOutcome {
        if (bankingApp.status() != BankingAppManager.Status.RUNNING) {
            return RemoteRecordOutcome(RemoteRecordResult.AGENT_NOT_RUNNING,
                "Banking app is not running.")
        }
        return try {
            val req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(bankingApp.url + "/_appmap/record"))
                .timeout(java.time.Duration.ofSeconds(15))
                .DELETE()
                .build()
            val resp = httpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            when (resp.statusCode()) {
                200 -> {
                    val body = resp.body()
                    if (body.isBlank() || body == "{}") {
                        return RemoteRecordOutcome(RemoteRecordResult.NO_ACTIVE_RECORDING,
                            "No active recording was in progress.")
                    }
                    // Save under banking-app/app-bootstrap/tmp/appmap/interactive/
                    // so it is picked up by the trace lister.
                    val ts = Instant.now().toString()
                        .replace(":", "").replace(".", "").replace("-", "")
                    val outDir = bankingApp.bankingAppDir.toPath()
                        .resolve("app-bootstrap/tmp/appmap/interactive")
                    Files.createDirectories(outDir)
                    val outFile = outDir.resolve("interactive-$ts.appmap.json")
                    Files.writeString(outFile, body)
                    val rel = bankingApp.bankingAppDir.toPath().relativize(outFile)
                        .toString().replace('\\', '/')
                    log.info("Saved interactive AppMap to {}", outFile)
                    RemoteRecordOutcome(RemoteRecordResult.STOPPED_AND_SAVED,
                        "Recording stopped and saved (${body.length} bytes).", rel)
                }
                404 -> RemoteRecordOutcome(RemoteRecordResult.NO_ACTIVE_RECORDING,
                    "No recording in progress (HTTP 404).")
                else -> RemoteRecordOutcome(RemoteRecordResult.AGENT_ERROR,
                    "Agent responded HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
            }
        } catch (e: Exception) {
            RemoteRecordOutcome(RemoteRecordResult.AGENT_ERROR,
                "Failed to reach agent: ${e.message}")
        }
    }
}
