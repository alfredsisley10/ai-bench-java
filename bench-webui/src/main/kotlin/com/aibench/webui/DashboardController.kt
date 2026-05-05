package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Combined dashboard + results page. Was two separate routes (`/`
 * for summary cards + recent-runs table, `/results` for the full
 * run history); merged so the operator sees the at-a-glance summary
 * and the complete run history in a single scroll. The
 * <code>/results/{runId}</code> transcript route still lives in
 * {@link ResultsController}, which also redirects bare
 * <code>/results</code> here for any cached bookmarks.
 */
@Controller
class DashboardController(
    private val benchmarkRuns: BenchmarkRunService,
    private val registeredModels: RegisteredModelsRegistry,
    private val bugCatalog: BugCatalog,
    private val navieCache: NavieCacheManager,
    private val tracesAdmin: AdminTracesController,
    private val throttler: AdaptiveThrottler,
    private val bugLint: BugLintService,
    private val pauseGate: PauseGate,
    private val worktreePool: WorktreePool,
    private val hiddenBugs: HiddenBugsService
) {

    /** Live in-progress snapshot. Same shape as
     *  [RunControlController.progress] returns over JSON; this
     *  one is used to render the initial dashboard payload before
     *  the JS poll kicks in. */
    data class LiveProgress(
        val isPaused: Boolean,
        val active: Int,
        val running: Int,
        val queued: Int,
        val poolCap: Int,
        val poolAvailable: Int,
        val recentCompleted: Int,
        val recentPassRate: Double,
        val avgRunMs: Long,
        val etrSec: Long?,
        val sessionCostUsd: Double
    )

    /** Compact row for the dashboard's "Background tasks" tile. Mirrors
     *  the per-task subset the operator needs to know about without
     *  navigating to /admin/navie or /admin/appmap-traces. */
    data class BackgroundTaskRow(
        val kind: String,
        val target: String,
        val phase: String,
        val elapsedSec: Long,
        val link: String
    )

    /** Inline "vs leader" deltas surfaced on every non-leader row.
     *  Negative percents mean "better than leader" (faster / cheaper);
     *  positive means worse. The leader's own row gets zeros. */
    data class LeaderboardDelta(
        val rowKey: String,
        val solvedDelta: Int,
        val avgMsPctVsLeader: Int,
        val avgCostPctVsLeader: Int
    )

    /** Per-bug winner: the configuration that solved this bug
     *  fastest. Drives the "Per-bug leaders" view so operators can
     *  see "for BUG-0001 the best solver is X (Yms, $Z)" at a glance.
     *  When `solvers > 1` multiple configurations passed; the leader
     *  is the fastest. When `uniquelySolved=true` only ONE configuration
     *  ever passed -- a strong signal about that solver's edge. */
    /** One solver's best result for a bug. Drives the per-row
     *  sparkline charts (time + cost) so the operator can SEE which
     *  configurations were close to the winner vs lapped. Winner =
     *  fastest. */
    data class SolverPoint(
        val label: String,        // "model · ctx · appmap" — tooltip text
        val durationMs: Long,
        val costUsd: Double,
        val isWinner: Boolean
    )

    data class PerBugLeader(
        val bugId: String,
        val title: String,
        val difficulty: String,
        val category: String,
        val attempts: Int,
        val solvers: Int,
        val uniquelySolved: Boolean,
        // Time-leader: the (model, ctx, appmap) configuration that
        // posted the FASTEST passing seed for this bug. Distinct
        // from the cost-leader because the fastest solver isn't
        // always the cheapest -- a cheap small model may take
        // longer than an expensive large one. The split exists so
        // operators can rank on either metric without forcing a
        // weighting between $$$ and seconds.
        val timeWinnerModel: String?,
        val timeWinnerCtx: String?,
        val timeWinnerAppmap: String?,
        val timeWinnerDurationMs: Long?,
        val timeWinnerCostUsd: Double?,
        // Cost-leader: cheapest passing seed. Mirror of the
        // time-leader fields above; same model may or may not win
        // both metrics.
        val costWinnerModel: String?,
        val costWinnerCtx: String?,
        val costWinnerAppmap: String?,
        val costWinnerDurationMs: Long?,
        val costWinnerCostUsd: Double?,
        // Pre-rendered inline SVG sparkline strings — one per
        // ranking metric. Each bar represents one (model, ctx,
        // appmap) configuration; height encodes the value relative
        // to the max in the row's set, the winner-on-this-metric
        // bar is highlighted in green, others in slate. The chart
        // overlays the row's min value at the left corner and max
        // at the right corner so the operator reads the spread
        // without hovering. Empty string when no passing solvers.
        val timeSparklineSvg: String,
        val costSparklineSvg: String,
        // Range columns: pre-formatted "min — max · Nx" string and
        // the raw ratio (max / min) for the sortable column. Null
        // when only one solver passed (no spread to report).
        val timeRangeLabel: String?,
        val timeRangeRatio: Double?,
        val costRangeLabel: String?,
        val costRangeRatio: Double?
    )

    /** One cell in the bug-solving heat-map. Rendered in
     *  template as a colored square; status drives the color.
     *  durationMs + costUsd are surfaced as overlay text so the
     *  matrix doubles as a cost/time heat chart -- operators can
     *  compare not just "did it solve?" but "how fast / cheap". */
    data class HeatCell(
        val bugId: String,
        val configKey: String,    // "model|ctx|appmap"
        val status: String,        // PASSED / FAILED / ERRORED / CANCELED / "—" (no attempt)
        val durationMs: Long,
        val costUsd: Double
    )

    /** Configuration column header for the heat-map. */
    data class HeatColumn(
        val configKey: String,
        val modelShort: String,    // model id with provider prefix stripped for compact display
        val ctxShort: String,      // 1-letter ctx ("O", "B", "N", "n")
        val appmapShort: String,   // "+" for ON, "" for OFF
        val tooltip: String,
        val totalSolves: Int
    )

    /** Top-row grouping of heat-map columns by context-provider.
     *  Used to render a multi-row header where the operator sees
     *  "ctx=appmap-navie" / "ctx=bm25" / "ctx=none" as a banner
     *  spanning every column that uses that ctx. `colspan` is the
     *  sum of every appmap subgroup's colspan inside this group. */
    data class HeatCtxGroup(
        val ctxLabel: String,
        val colspan: Int,
        val appmapGroups: List<HeatAppmapGroup>,
        // Aggregate pass rate across every (model column, bug row)
        // cell in this ctx group. Numerator = PASSED cells;
        // denominator = ATTEMPTED cells (PASSED + FAILED + ERRORED
        // + CANCELED). Cells with no run for the (config, bug) pair
        // are excluded so the rate answers "when this group ran,
        // how often did it pass?" rather than "what fraction of the
        // grid is green?". `passLabel` is the pre-formatted string
        // for the header (e.g. "67% (12/18)").
        val passRate: Double,
        val passLabel: String
    )

    /** Sub-banner of [HeatCtxGroup]: every model column sharing the
     *  same (ctx, appmap) combination. `colspan` = number of model
     *  columns in this subgroup. `passRate` / `passLabel` mirror
     *  the ctx group's aggregate but scoped to this (ctx, appmap)
     *  pair only. */
    data class HeatAppmapGroup(
        val appmapLabel: String,
        val colspan: Int,
        val passRate: Double,
        val passLabel: String
    )

    /** Bug row metadata for the heat-map. */
    data class HeatBugRow(
        val bugId: String,
        val title: String,
        val difficulty: String,
        val category: String,
        val solveCount: Int,
        val uniquelySolved: Boolean
    )

    /** Per-bug rollup for the leaderboard drilldown. Aggregates every
     *  run a (provider, model, ctx, mode) configuration made against
     *  this specific bug -- so the operator can see whether the
     *  configuration solved the bug consistently across seeds, or
     *  passed once-and-failed-twice, etc. */
    data class BugDrilldown(
        val bugId: String,
        val difficulty: String,
        val category: String,
        val totalRuns: Int,
        val passedRuns: Int,
        val avgDurationMs: Long,
        val avgCostUsd: Double,
        /** Latest run id (any status) so the operator can deep-link
         *  into /results/{runId} for the audit trail. */
        val latestRunId: String,
        val latestStatus: String
    )

    /** A single PASSED run, denormalised against its bug metadata so the
     *  leaderboard can pivot on difficulty / category without a second
     *  catalog hit per row. */
    data class PassRecord(
        val bugId: String,
        val durationMs: Long,
        val costUsd: Double,
        val difficulty: String,
        val category: String
    )

    /** Per-model leaderboard summary. Lists the operator's fastest /
     *  cheapest successful solve and how the model's solve set breaks
     *  down by bug difficulty + category — the answer to "which models
     *  are best for which kinds of problems?". */
    data class LeaderboardEntry(
        val modelId: String,
        val provider: String,
        /** Context provider used (none / oracle / bm25 / appmap-navie). */
        val contextProvider: String,
        /** AppMap recording mode (OFF / ON_RECOMMENDED / ON_ALL). */
        val appmapMode: String,
        val totalRuns: Int,
        val passedRuns: Int,
        // Distinct bugs for which AT LEAST ONE seed/run passed.
        // Operator-facing "Solved" column on the leaderboard --
        // previously showed passedRuns, which counted re-passes
        // of the same bug across seeds and could exceed the total
        // bug catalog count (operator reported "15 solved but only
        // 12 bugs", because 3 bugs had multiple passing seeds).
        val solvedBugs: Int,
        val passRate: Double,
        val avgPassMs: Long,
        val avgPassCostUsd: Double,
        /** Per-bug breakdown of every run that hit this exact
         *  (provider, model, ctx, mode) configuration. Lets the
         *  leaderboard drill in: click the expand chevron and see
         *  "BUG-0001: 1/1 PASSED in 24s, $0.018 / BUG-0002: 0/1
         *  FAILED_TESTS in 31s, $0.022 / ...". Empty when this
         *  configuration has no runs at all. */
        val bugBreakdown: List<BugDrilldown>,
        /** Inline SVG strip with one bar per catalog bug (sorted by
         *  bugId). Green = at least one run passed, red = ran but
         *  never passed, grey = no run for this configuration.
         *  Replaces the old Difficulty + Category text columns on
         *  the leaderboard -- the operator can scan the strip
         *  left-to-right and instantly see which bugs this
         *  configuration has + hasn't solved. */
        val bugStatusSparkSvg: String
    )

    @GetMapping("/")
    fun dashboard(model: Model, session: HttpSession): String {
        // Pull a wide window so the per-page slicing JS has every
        // available run to choose from. 500 is plenty for a single
        // operator's lifetime; older runs roll off naturally as the
        // in-memory map fills.
        val allRuns = benchmarkRuns.recentRuns(500)
        // Operator-hidden bugs (e.g. flagged as contamination-impacted)
        // are excluded from every downstream computation: leaderboard,
        // per-bug-leaders, contamination warning, summary stats. The
        // raw runs are still in the BenchmarkRunService -- this is a
        // dashboard-presentation filter only. Surfaced separately as
        // `hiddenBugIds` model attr so the template can render a
        // "currently hidden" panel with unhide affordance.
        val hidden = hiddenBugs.hiddenBugs()
        val runs = allRuns.filter { it.issueId !in hidden }
        model.addAttribute("hiddenBugIds", hidden.sorted())
        val total = runs.size
        val passed = runs.count { it.status.name == "PASSED" }

        model.addAttribute("totalRuns", total)
        // Suite coverage stats: scope the pass-rate tile to "of all
        // bugs in the catalog" rather than "of all RUNS executed",
        // and break out per-bug status into PASSED / FAILED / NOT
        // RUN so operators can see harness coverage at a glance.
        // A bug is PASSED if any run against it passed; FAILED if
        // it has runs but none passed; NOT RUN if it has zero
        // benchmark runs to date.
        val catalogBugs = bugCatalog.allBugs().map { it.id }.toSet()
        val bugStatusByBug: Map<String, String> = catalogBugs.associateWith { bugId ->
            val bugRuns = runs.filter { it.issueId == bugId }
            when {
                bugRuns.isEmpty() -> "NOT_RUN"
                bugRuns.any { it.status.name == "PASSED" } -> "PASSED"
                else -> "FAILED"
            }
        }
        val suitePassed = bugStatusByBug.count { it.value == "PASSED" }
        val suiteFailed = bugStatusByBug.count { it.value == "FAILED" }
        val suiteNotRun = bugStatusByBug.count { it.value == "NOT_RUN" }
        val suiteTotal = catalogBugs.size
        model.addAttribute("passRate",
            if (suiteTotal > 0) suitePassed.toDouble() / suiteTotal else 0.0)
        model.addAttribute("suitePassed", suitePassed)
        model.addAttribute("suiteFailed", suiteFailed)
        model.addAttribute("suiteNotRun", suiteNotRun)
        model.addAttribute("suiteTotal", suiteTotal)
        model.addAttribute("solvers", registeredModels.availableProviders(session))
        model.addAttribute("runs", runs)
        model.addAttribute("connectedRepos", 0)
        // "Bugs" tile counts unique bug IDs that have at least one
        // benchmark run -- consistent with Runs / Pass rate / Solvers
        // which are all derived from the live runs list. The catalog's
        // total (bugCatalog.count()) is the right number for the
        // launcher's dropdown but on the dashboard the operator wants
        // to know "how many distinct bugs have I actually benchmarked"
        // -- a 12/12 catalog with no runs should read 0 here.
        model.addAttribute("availableBugs", runs.map { it.issueId }.distinct().size)
        model.addAttribute("liveProgress", computeLiveProgress(runs))
        // Static bug-definition leakiness check. Counts every HIGH
        // severity finding across every bug yaml; the dashboard tile
        // appears only when this is non-zero so a clean catalog stays
        // visually quiet.
        model.addAttribute("bugLintHighCount",
            bugCatalog.allBugs().sumOf { bug ->
                bugLint.lint(bug).count { it.severity == BugLintService.Severity.HIGH }
            })

        // Leaderboard — group PASSED runs by the FULL execution context
        // (LLM provider, model id, context provider, AppMap mode), so
        // each row is one specific configuration the operator launched.
        // Earlier the leaderboard collapsed all runs of a model into a
        // single row regardless of context/mode; that hid the very
        // signal the matrix is supposed to surface ("does adding traces
        // help?"). Difficulty + category come from the YAML catalog
        // (BugCatalog.getBug); enterprise issueIds (repo:ticket form) or
        // catalog-misses land in "(unknown)" so the totals still tally.
        data class CtxKey(val provider: String, val modelId: String,
                          val contextProvider: String, val appmapMode: String)
        fun keyOf(r: BenchmarkRunService.BenchmarkRun) =
            CtxKey(r.provider, r.modelId, r.contextProvider, r.appmapMode)
        val totalsByCtx = runs.groupingBy(::keyOf).eachCount()
        val passedRuns = runs.filter { it.status.name == "PASSED" }
        val passRecordsByCtx: Map<CtxKey, List<PassRecord>> = passedRuns
            .groupBy(::keyOf)
            .mapValues { (_, ctxRuns) ->
                ctxRuns.map { run ->
                    val bug = bugCatalog.getBug(run.issueId)
                    PassRecord(
                        bugId = run.issueId,
                        durationMs = run.durationMs,
                        costUsd = run.stats.estimatedCostUsd,
                        difficulty = bug?.difficulty?.takeIf { it.isNotBlank() } ?: "(unknown)",
                        category = bug?.category?.takeIf { it.isNotBlank() } ?: "(unknown)"
                    )
                }
            }
        // Per-bug runs grouped by configuration -- includes ALL runs
        // (passed AND failed) so the drilldown can show what
        // happened, not just the wins.
        val allRunsByCtx: Map<CtxKey, List<BenchmarkRunService.BenchmarkRun>> =
            runs.groupBy(::keyOf)
        val leaderboard = passRecordsByCtx.map { (ctx, records) ->
            val totalForCtx = totalsByCtx[ctx] ?: records.size
            // Per-bug rollup for the drilldown: every bug this ctx
            // touched, with pass count + averages + a deep-link to
            // the latest run's audit page.
            val byBug = (allRunsByCtx[ctx] ?: emptyList()).groupBy { it.issueId }
            val drilldown = byBug.map { (bugId, ctxBugRuns) ->
                val passed = ctxBugRuns.count { it.status.name == "PASSED" }
                val bug = bugCatalog.getBug(bugId)
                val newest = ctxBugRuns.maxBy { it.startedAt }
                BugDrilldown(
                    bugId = bugId,
                    difficulty = bug?.difficulty?.takeIf { it.isNotBlank() } ?: "(unknown)",
                    category = bug?.category?.takeIf { it.isNotBlank() } ?: "(unknown)",
                    totalRuns = ctxBugRuns.size,
                    passedRuns = passed,
                    avgDurationMs = ctxBugRuns.map { it.durationMs }.average().toLong(),
                    avgCostUsd = ctxBugRuns.map { it.stats.estimatedCostUsd }.average(),
                    latestRunId = newest.id,
                    latestStatus = newest.status.name
                )
            }.sortedWith(
                compareByDescending<BugDrilldown> { it.passedRuns }
                    .thenBy { it.bugId }
            )
            LeaderboardEntry(
                modelId = ctx.modelId,
                provider = ctx.provider,
                contextProvider = ctx.contextProvider,
                appmapMode = ctx.appmapMode,
                totalRuns = totalForCtx,
                passedRuns = records.size,
                solvedBugs = records.map { it.bugId }.distinct().size,
                passRate = records.size.toDouble() / totalForCtx,
                avgPassMs = records.map { it.durationMs }.average().toLong(),
                avgPassCostUsd = records.map { it.costUsd }.average(),
                bugBreakdown = drilldown,
                bugStatusSparkSvg = bugStatusSparkSvg(catalogBugs.sorted(), drilldown)
            )
        }
        // Disqualify Oracle context entries from the leaderboard. Oracle
        // ships the bug's hand-curated filesTouched list -- essential for
        // benchmarking and ceiling-measurement, but not a real-world
        // "leader" since an operator wouldn't have a curated file list
        // per bug in production. Oracle runs still appear in the runs
        // table; this only filters them out of the per-config ranking.
        .filter { it.contextProvider.lowercase() != "oracle" }
        .sortedWith(
            compareByDescending<LeaderboardEntry> { it.passedRuns }
                .thenBy { it.avgPassMs }
        )
        model.addAttribute("leaderboard", leaderboard)
        // Vs-leader deltas. Used by the template to render inline
        // "+15% slower" / "+8% more expensive" / "-2 fewer solves"
        // on every non-leader row so the operator can dimension how
        // much the lower-ranked configurations cost vs the leader.
        // Leader = first row; deltas measured relative to it.
        val leader = leaderboard.firstOrNull()
        val deltas: List<LeaderboardDelta> = if (leader == null) emptyList()
        else leaderboard.map { lb ->
            LeaderboardDelta(
                rowKey = "${lb.modelId}|${lb.contextProvider}|${lb.appmapMode}",
                // Compare distinct bugs solved (matches the "Solved"
                // column semantic post-fix), not raw passing-run counts.
                solvedDelta = lb.solvedBugs - leader.solvedBugs,
                avgMsPctVsLeader = if (leader.avgPassMs > 0)
                    ((lb.avgPassMs - leader.avgPassMs) * 100.0 / leader.avgPassMs).toInt() else 0,
                avgCostPctVsLeader = if (leader.avgPassCostUsd > 0)
                    ((lb.avgPassCostUsd - leader.avgPassCostUsd) * 100.0 / leader.avgPassCostUsd).toInt() else 0
            )
        }
        model.addAttribute("leaderboardDeltas", deltas)

        // ----- Per-bug leaders + heat-map -------------------------------
        // Builds two derived views over the same passed-runs set:
        //   1. PerBugLeader: which configuration won each bug (fastest
        //      passing run), plus a "uniquelySolved" flag for bugs only
        //      one configuration ever cracked.
        //   2. HeatMap (rows=bugs grouped by difficulty, cols=configs):
        //      cell color = run status, intensity = wall time. Surfaces
        //      "this solver dominates EASY but fails HARD" patterns at
        //      a glance.
        // Oracle is excluded here too (matching leaderboard policy)
        // so the views don't show oracle as the trivial leader for
        // every bug it touched.
        val nonOracleRuns = runs.filter { it.contextProvider.lowercase() != "oracle" }

        // Per-bug aggregation
        val byBugAllRuns = nonOracleRuns.groupBy { it.issueId }
        val perBugLeaders: List<PerBugLeader> = bugCatalog.allBugs().map { bug ->
            val attempts = byBugAllRuns[bug.id] ?: emptyList()
            val passes = attempts.filter { it.status == BenchmarkRunService.Status.PASSED }
            val distinctSolverConfigs = passes
                .map { Triple(it.modelId, it.contextProvider, it.appmapMode) }
                .distinct()
            val timeWinner = passes.minByOrNull { it.durationMs }
            val costWinner = passes.minByOrNull { it.stats.estimatedCostUsd }
            // Per-config best seed -- one bar per distinct (model,
            // ctx, appmap) configuration. Each metric has its own
            // SolverPoints set because "fastest seed" and "cheapest
            // seed" can be different runs from the same config when
            // multiple seeds were attempted; sorting and the winner
            // flag also differ. Time set sorts ascending by ms; cost
            // set sorts ascending by USD.
            val byCfg = passes.groupBy { Triple(it.modelId, it.contextProvider, it.appmapMode) }
            val timeSolverPoints: List<SolverPoint> = byCfg
                .map { (_, runs) ->
                    val fastest = runs.minBy { it.durationMs }
                    SolverPoint(
                        label = "${fastest.provider}/${fastest.modelId}" +
                                " · ${fastest.contextProvider}" +
                                " · ${fastest.appmapMode}",
                        durationMs = fastest.durationMs,
                        costUsd = fastest.stats.estimatedCostUsd,
                        isWinner = timeWinner != null && fastest.id == timeWinner.id
                    )
                }
                .sortedBy { it.durationMs }
            val costSolverPoints: List<SolverPoint> = byCfg
                .map { (_, runs) ->
                    val cheapest = runs.minBy { it.stats.estimatedCostUsd }
                    SolverPoint(
                        label = "${cheapest.provider}/${cheapest.modelId}" +
                                " · ${cheapest.contextProvider}" +
                                " · ${cheapest.appmapMode}",
                        durationMs = cheapest.durationMs,
                        costUsd = cheapest.stats.estimatedCostUsd,
                        isWinner = costWinner != null && cheapest.id == costWinner.id
                    )
                }
                .sortedBy { it.costUsd }
            PerBugLeader(
                bugId = bug.id,
                title = bug.title,
                difficulty = bug.difficulty.ifBlank { "(unknown)" },
                category = bug.category.ifBlank { "(unknown)" },
                attempts = attempts.size,
                solvers = distinctSolverConfigs.size,
                uniquelySolved = distinctSolverConfigs.size == 1 && passes.isNotEmpty(),
                timeWinnerModel = timeWinner?.let { "${it.provider}/${it.modelId}" },
                timeWinnerCtx = timeWinner?.contextProvider,
                timeWinnerAppmap = timeWinner?.appmapMode,
                timeWinnerDurationMs = timeWinner?.durationMs,
                timeWinnerCostUsd = timeWinner?.stats?.estimatedCostUsd,
                costWinnerModel = costWinner?.let { "${it.provider}/${it.modelId}" },
                costWinnerCtx = costWinner?.contextProvider,
                costWinnerAppmap = costWinner?.appmapMode,
                costWinnerDurationMs = costWinner?.durationMs,
                costWinnerCostUsd = costWinner?.stats?.estimatedCostUsd,
                timeSparklineSvg = sparklineSvg(timeSolverPoints, ChartMode.TIME),
                costSparklineSvg = sparklineSvg(costSolverPoints, ChartMode.COST),
                timeRangeLabel = rangeLabelTime(timeSolverPoints),
                timeRangeRatio = rangeRatioTime(timeSolverPoints),
                costRangeLabel = rangeLabelCost(costSolverPoints),
                costRangeRatio = rangeRatioCost(costSolverPoints)
            )
        }.sortedBy { it.bugId }
        model.addAttribute("perBugLeaders", perBugLeaders)
        model.addAttribute("uniquelySolvedCount", perBugLeaders.count { it.uniquelySolved })

        // Heat-map: configs × bugs grid.
        // Cap configs to those with at least 1 attempt (drops empty
        // columns) and to non-oracle. Bugs ordered by difficulty
        // bucket (HARD/MEDIUM/EASY/TRIVIAL) then bugId for visual
        // grouping; difficulty also exposed per row so the template
        // can color the row label.
        // Sort columns by (ctx, appmap, model) so the multi-row
        // header banner (ctx → appmap → model) groups visually.
        // OFF first within each ctx so the operator reads the
        // matrix as "no-context first, traces added later".
        val configsWithAttempts: List<Triple<String, String, String>> = nonOracleRuns
            .map { Triple(it.modelId, it.contextProvider, it.appmapMode) }
            .distinct()
            .sortedWith(compareBy(
                { it.second.lowercase() },                         // ctx
                { if (it.third.equals("OFF", true) || it.third.isBlank()) 0 else 1 }, // OFF first
                { it.third },                                      // then ON_* alphabetically
                { it.first }                                       // then model
            ))
        val heatColumns: List<HeatColumn> = configsWithAttempts.map { (model, ctx, mode) ->
            val k = "$model|$ctx|$mode"
            val solves = nonOracleRuns.count {
                it.modelId == model && it.contextProvider == ctx && it.appmapMode == mode &&
                it.status == BenchmarkRunService.Status.PASSED
            }
            HeatColumn(
                configKey = k,
                // strip "copilot-" prefix for compactness; rest stays
                modelShort = model.removePrefix("copilot-"),
                ctxShort = when (ctx.lowercase()) {
                    "none" -> "n"
                    "bm25" -> "B"
                    "appmap-navie" -> "N"
                    "oracle" -> "O"
                    else -> ctx.take(1).uppercase()
                },
                appmapShort = if (mode.uppercase() == "ON" ||
                    mode.uppercase().startsWith("ON_")) "+" else "",
                tooltip = "$model · ctx=$ctx · appmap=$mode — $solves passed",
                totalSolves = solves
            )
        }
        // Only carry bugs that had at least 1 attempt (matches the
        // configs-with-attempts logic). Default sort is by bug id
        // ascending (BUG-0001 first) -- matches the bug-numbered
        // canonical order operators reason about, and matches every
        // other bug-rowed table on the dashboard. The Diff column
        // header is still click-sortable for difficulty grouping.
        val heatBugRows: List<HeatBugRow> = perBugLeaders
            .filter { it.attempts > 0 }
            .sortedBy { it.bugId }
            .map {
                HeatBugRow(
                    bugId = it.bugId,
                    title = it.title,
                    difficulty = it.difficulty,
                    category = it.category,
                    solveCount = it.solvers,
                    uniquelySolved = it.uniquelySolved
                )
            }
        // Cells indexed by (bug, config) -> latest matching run's
        // status. With seeds=1 there's typically 1 run per (bug,config);
        // when more, we pick the run with the strongest result
        // (PASSED > FAILED > ERRORED > nothing) so the matrix
        // emphasizes "did ANY attempt succeed?".
        val heatCells: Map<String, HeatCell> = run {
            val out = mutableMapOf<String, HeatCell>()
            for (run in nonOracleRuns) {
                val key = "${run.issueId}|${run.modelId}|${run.contextProvider}|${run.appmapMode}"
                val existing = out[key]
                val rank = mapOf(
                    "PASSED" to 4, "FAILED" to 3, "ERRORED" to 2,
                    "CANCELED" to 1, "RUNNING" to 0, "QUEUED" to 0
                )
                val newRank = rank[run.status.name] ?: 0
                val existingRank = existing?.let { rank[it.status] ?: 0 } ?: -1
                if (newRank > existingRank) {
                    out[key] = HeatCell(
                        bugId = run.issueId,
                        configKey = "${run.modelId}|${run.contextProvider}|${run.appmapMode}",
                        status = run.status.name,
                        durationMs = run.durationMs,
                        costUsd = run.stats.estimatedCostUsd
                    )
                }
            }
            out
        }

        // Group columns by ctx → appmap (banner header). Now that
        // heatCells exists we can also tag each group with its
        // aggregate pass rate -- "of the bug × config cells in this
        // group that were attempted, what fraction passed?". This
        // surfaces "ctx=appmap-navie holds 67% solve rate vs ctx=none
        // at 33%" right in the header, no eyeball-counting needed.
        // configsWithAttempts is already in column order; groupBy
        // preserves insertion order so headers match column layout.
        fun groupPassStats(configs: List<Triple<String, String, String>>): Pair<Int, Int> {
            var passed = 0
            var attempted = 0
            for ((m, c, mode) in configs) {
                for (bug in heatBugRows) {
                    val cell = heatCells["${bug.bugId}|$m|$c|$mode"]
                    if (cell != null) {
                        attempted++
                        if (cell.status == "PASSED") passed++
                    }
                }
            }
            return passed to attempted
        }
        fun pctLabel(passed: Int, attempted: Int): String =
            if (attempted == 0) "—"
            else "${(passed * 100 + attempted / 2) / attempted}% ($passed/$attempted)"

        val heatCtxGroups: List<HeatCtxGroup> = configsWithAttempts
            .groupBy { it.second }
            .map { (ctx, ctxConfigs) ->
                val (ctxPassed, ctxAttempted) = groupPassStats(ctxConfigs)
                val appmapGroups = ctxConfigs.groupBy { it.third }
                    .map { (mode, modeConfigs) ->
                        val (mPassed, mAttempted) = groupPassStats(modeConfigs)
                        HeatAppmapGroup(
                            appmapLabel = if (mode.equals("OFF", true) || mode.isBlank()) "OFF"
                                          else mode,
                            colspan = modeConfigs.size,
                            passRate = if (mAttempted > 0) mPassed.toDouble() / mAttempted else 0.0,
                            passLabel = pctLabel(mPassed, mAttempted)
                        )
                    }
                HeatCtxGroup(
                    ctxLabel = ctx,
                    colspan = ctxConfigs.size,
                    appmapGroups = appmapGroups,
                    passRate = if (ctxAttempted > 0) ctxPassed.toDouble() / ctxAttempted else 0.0,
                    passLabel = pctLabel(ctxPassed, ctxAttempted)
                )
            }
        val heatAppmapGroups: List<HeatAppmapGroup> =
            heatCtxGroups.flatMap { it.appmapGroups }

        model.addAttribute("heatColumns", heatColumns)
        model.addAttribute("heatCtxGroups", heatCtxGroups)
        model.addAttribute("heatAppmapGroups", heatAppmapGroups)
        model.addAttribute("heatBugRows", heatBugRows)
        model.addAttribute("heatCells", heatCells)
        // When the leaderboard is empty BUT we DO have passing oracle
        // runs in the runs list, surface "Oracle is excluded from the
        // ranking — try a non-Oracle context" instead of just hiding
        // the section silently. Otherwise the operator sees passing
        // runs in the table but no leaderboard with no explanation.
        val hasOraclePass = runs.any {
            it.status.name == "PASSED" && it.contextProvider.equals("oracle", ignoreCase = true)
        }
        model.addAttribute("leaderboardEmptyOracleOnly",
            leaderboard.isEmpty() && hasOraclePass)

        // Pre-training contamination signal: a model that PASSES with
        // contextProvider="none" AND AppMap mode OFF is producing
        // the fix from memory -- there's no source code, no traces,
        // nothing in the prompt to reason about. Either the bug is
        // from the model's training corpus (memorization) or the
        // problem statement is leaky enough to give the answer away.
        // Either way the benchmark can't fairly grade that model, so
        // we surface every such (model, bug) pair on the dashboard
        // to flag for the operator -- WITH the problem statement +
        // hints inline so the operator can audit whether the prompt
        // itself is leaky (which would be a fixable bug-definition
        // issue) vs the model truly having memorized the fix (which
        // is a fixable model-eligibility issue).
        //
        // AppMap mode ON_RECOMMENDED / ON_ALL is NOT contamination
        // even with contextProvider="none" -- the solver IS getting
        // real context (recorded call traces of the failing test),
        // just delivered by a separate pipeline than the source-
        // file context provider. Filtering only "ctx=none AND
        // appmap=OFF" matches the operator's mental model of "no
        // context".
        data class ContaminationRow(
            val provider: String,
            val modelId: String,
            val issueId: String,
            val issueTitle: String,
            val passes: Int,
            val total: Int,
            val latestRunId: String,
            val problemStatement: String,
            val hints: List<String>,
            val filesTouched: List<String>,
            /** "not-run" | "running" | "passed" | "failed" -- derived
             *  from the most recent (issueId, ctx=none, appmap=OFF)
             *  run by a model NOT in the original contamination set. */
            val probeStatus: String,
            val probeModelLabel: String?,
            val probeRunId: String?
        )
        // Pass 1: build the un-probed contamination list. A run is
        // "no context" only when contextProvider="none" AND AppMap
        // mode is OFF -- when AppMap mode is ON_RECOMMENDED or
        // ON_ALL the solver IS receiving real context (recorded
        // call traces of the failing test or every test in the
        // module), which is plenty for solving the bug without
        // memorization. Operators reported the prior logic
        // false-flagged AppMap-on PASSED runs as contamination,
        // since contextProvider stays "none" while AppMap traces
        // are bolted on by a separate pipeline. Filter both axes
        // so only the truly-no-context PASSED runs surface.
        val draftContamination = runs
            .filter {
                it.contextProvider.equals("none", ignoreCase = true) &&
                (it.appmapMode.equals("OFF", ignoreCase = true) ||
                 it.appmapMode.isBlank())
            }
            .groupBy { Triple(it.provider, it.modelId, it.issueId) }
            .mapNotNull { (k, group) ->
                val passes = group.count { it.status.name == "PASSED" }
                if (passes == 0) return@mapNotNull null
                val sample = group.first()
                val bug = bugCatalog.getBug(k.third)
                Triple(k, group, Pair(passes, bug)) to sample
            }
        // Pass 2: collect the (provider, modelId) set of originally-
        // contaminated models so probe correlation can exclude them.
        val originalSet: Set<Pair<String, String>> = draftContamination
            .map { (t, _) -> t.first.first to t.first.second }.toSet()
        // Pass 3: attach probe correlation per row.
        val contamination = draftContamination.map { (t, sample) ->
            val (k, _, p) = t
            val (passes, bug) = p
            val probeRun = runs
                .filter { r ->
                    r.issueId == k.third &&
                    r.contextProvider.equals("none", ignoreCase = true) &&
                    r.appmapMode.equals("OFF", ignoreCase = true) &&
                    (r.provider to r.modelId) !in originalSet
                }
                .maxByOrNull { it.startedAt }
            val probeStatus = when (probeRun?.status?.name) {
                "RUNNING", "QUEUED" -> "running"
                "PASSED" -> "passed"
                "FAILED", "ERRORED", "CANCELED" -> "failed"
                null -> "not-run"
                else -> "not-run"
            }
            ContaminationRow(
                provider = k.first, modelId = k.second, issueId = k.third,
                issueTitle = sample.issueTitle,
                passes = passes, total = runs.count {
                    it.provider == k.first && it.modelId == k.second &&
                    it.issueId == k.third && it.contextProvider.equals("none", true)
                },
                latestRunId = runs
                    .filter { it.provider == k.first && it.modelId == k.second &&
                              it.issueId == k.third && it.contextProvider.equals("none", true) }
                    .maxBy { it.startedAt }.id,
                problemStatement = bug?.problemStatement ?: "(bug definition not found)",
                hints = bug?.hints ?: emptyList(),
                filesTouched = bug?.filesTouched ?: emptyList(),
                probeStatus = probeStatus,
                probeModelLabel = probeRun?.let { "${it.provider}/${it.modelId}" },
                probeRunId = probeRun?.id
            )
        }.sortedWith(compareByDescending<ContaminationRow> { it.passes }
            .thenBy { it.modelId }.thenBy { it.issueId })
        model.addAttribute("contamination", contamination)
        model.addAttribute("contaminationModels",
            contamination.map { "${it.provider}/${it.modelId}" }.distinct().sorted())
        model.addAttribute("contaminationBugs",
            contamination.map { it.issueId }.distinct().sorted())
        // Models the operator can select as the probe. We don't filter
        // out the original contamination models here -- the probe
        // controller skips self-probes server-side; surface every
        // available model so the operator can experiment.
        model.addAttribute("availableProbeModels", registeredModels.availableModels(session))

        // Background-task tile: surface in-flight Navie precomputes +
        // AppMap trace generations on the dashboard so the operator
        // sees the bridge / gradle being monopolized without having
        // to navigate to /admin/navie or /admin/appmap-traces. The
        // page auto-refreshes every 4s while runs are queued/running,
        // which keeps these elapsed times fresh too.
        val now = java.time.Instant.now()
        val backgroundTasks = mutableListOf<BackgroundTaskRow>()
        for (job in navieCache.runningJobs()) {
            backgroundTasks += BackgroundTaskRow(
                kind = "Navie precompute",
                target = job.bugId,
                phase = job.phase,
                elapsedSec = java.time.Duration.between(job.startedAt, now).seconds,
                link = "/admin/navie"
            )
        }
        for (job in tracesAdmin.runningJobs()) {
            backgroundTasks += BackgroundTaskRow(
                kind = "AppMap trace gen",
                target = job.module,
                phase = "running gradle test",
                elapsedSec = java.time.Duration.between(job.startedAt, now).seconds,
                link = "/admin/appmap-traces"
            )
        }
        model.addAttribute("backgroundTasks", backgroundTasks)
        model.addAttribute("throttler", throttler.status())
        // Surface delete-result toast — set by deleteRuns() before
        // redirecting back here so the table re-renders with a one-
        // shot summary message.
        model.addAttribute("runsDeleteResult", session.getAttribute("runsDeleteResult"))
        session.removeAttribute("runsDeleteResult")
        return "dashboard"
    }

    /**
     * Compute live progress over the most recent 2000 runs. Shared
     * with [RunControlController.progress] (which serves the same
     * shape over JSON for poll-based updates).
     */
    fun computeLiveProgress(runs: List<BenchmarkRunService.BenchmarkRun>): LiveProgress {
        val active = runs.filter {
            it.status == BenchmarkRunService.Status.RUNNING ||
            it.status == BenchmarkRunService.Status.QUEUED
        }
        val running = active.count { it.status == BenchmarkRunService.Status.RUNNING }
        val queued = active.count { it.status == BenchmarkRunService.Status.QUEUED }
        val recentCompleted = runs.asSequence()
            .filter {
                it.status == BenchmarkRunService.Status.PASSED ||
                it.status == BenchmarkRunService.Status.FAILED ||
                it.status == BenchmarkRunService.Status.ERRORED ||
                it.status == BenchmarkRunService.Status.CANCELED
            }
            .sortedByDescending { it.endedAt ?: java.time.Instant.EPOCH }
            .take(100).toList()
        val recentN = recentCompleted.size
        val avgRunMs = if (recentN > 0) recentCompleted.map { it.durationMs }.average().toLong() else 0L
        val passed = recentCompleted.count { it.status == BenchmarkRunService.Status.PASSED }
        val passRate = if (recentN > 0) passed.toDouble() / recentN else 0.0
        val sessionHorizon = java.time.Instant.now().minus(java.time.Duration.ofHours(1))
        val sessionRuns = runs.filter { it.startedAt.isAfter(sessionHorizon) }
        val sessionCost = sessionRuns.sumOf { it.stats.estimatedCostUsd }
        val poolStatus = worktreePool.status()
        val etrSec = if (avgRunMs > 0)
            (active.size.toLong() * avgRunMs / poolStatus.cap.coerceAtLeast(1) / 1000) else null
        return LiveProgress(
            isPaused = pauseGate.isPaused(),
            active = active.size,
            running = running,
            queued = queued,
            poolCap = poolStatus.cap,
            poolAvailable = poolStatus.available,
            recentCompleted = recentN,
            recentPassRate = passRate,
            avgRunMs = avgRunMs,
            etrSec = etrSec,
            sessionCostUsd = sessionCost
        )
    }

    /**
     * Bulk-delete runs by id. The dashboard table renders a checkbox
     * per row and a hidden "select all" toggle that POSTs the chosen
     * ids back here as repeated `runIds` form params. Active runs
     * (QUEUED / RUNNING) are skipped — the operator must cancel them
     * first; the response toast spells out exactly what happened.
     */
    /**
     * Cancel one in-flight run from the dashboard. Flips the run's
     * status to CANCELED so the worker thread will bail out at the
     * next checkpoint -- the LLM call already in flight still
     * completes (we can't interrupt vscode.LanguageModelChat mid-
     * stream), but no further bridge calls are made for this run
     * and the queue-aware watchdog stops counting it as active.
     * Useful when the bridge is rate-limited and the operator
     * wants to stop wasting cycles on calls that won't succeed.
     */
    /**
     * Hide a single bug from dashboard reporting. The bug stays in
     * BenchmarkRunService (raw runs are unaffected) but every
     * downstream computation (leaderboard, per-bug-leaders,
     * contamination warning, summary stats) excludes it. Used to
     * suppress contamination-impacted bugs the operator has
     * judged unrepresentative without losing the underlying data.
     */
    @PostMapping("/admin/dashboard/hide-bug")
    fun hideBug(@RequestParam bugId: String, session: HttpSession): String {
        hiddenBugs.hide(bugId.trim())
        session.setAttribute("hideBugResult",
            "Hidden $bugId from dashboard reporting. Unhide via the Hidden Bugs panel.")
        return "redirect:/"
    }

    /** Unhide a single bug — reverses hideBug. */
    @PostMapping("/admin/dashboard/unhide-bug")
    fun unhideBug(@RequestParam bugId: String, session: HttpSession): String {
        hiddenBugs.unhide(bugId.trim())
        session.setAttribute("hideBugResult",
            "Restored $bugId to dashboard reporting.")
        return "redirect:/"
    }

    @PostMapping("/runs/{runId}/cancel")
    fun cancelRun(
        @org.springframework.web.bind.annotation.PathVariable runId: String,
        session: HttpSession
    ): String {
        val ok = benchmarkRuns.cancel(runId)
        session.setAttribute("runsDeleteResult", if (ok)
            "Cancel signal sent to $runId. The current LLM call may complete; no new ones will fire."
        else
            "$runId is not active (already PASSED/FAILED/CANCELED) -- nothing to cancel.")
        return "redirect:/"
    }

    @PostMapping("/runs/delete")
    fun deleteRuns(
        @RequestParam(required = false) runIds: List<String>?,
        session: HttpSession
    ): String {
        val ids = runIds ?: emptyList()
        if (ids.isEmpty()) {
            session.setAttribute("runsDeleteResult",
                "No runs selected — pick at least one before clicking Delete.")
            return "redirect:/"
        }
        val s = benchmarkRuns.deleteRuns(ids)
        session.setAttribute("runsDeleteResult", buildString {
            append("Deleted ").append(s.deleted).append(" run").append(if (s.deleted == 1) "" else "s")
            if (s.skippedActive > 0) {
                append("; skipped ").append(s.skippedActive)
                  .append(" active (cancel first)")
            }
            if (s.missing > 0) {
                append("; ").append(s.missing).append(" id(s) not found")
            }
            append('.')
        })
        return "redirect:/"
    }

    /** Which axis the sparkline encodes — time (ms, lower=better) or
     *  cost (USD, lower=better). Both visually use "shorter bar =
     *  better" so the operator's reading is consistent across charts. */
    private enum class ChartMode { TIME, COST }

    /**
     * Render a tiny inline SVG bar chart for the per-bug-leader row.
     * One bar per solver point, height proportional to the value
     * relative to the row's max, winner highlighted in green and
     * runners-up in slate. Each bar gets a <title> child so hover
     * surfaces "model · ctx · appmap: 24s, $0.018".
     *
     * Returns "" (empty) when no solver passed -- caller renders
     * nothing in that cell.
     */
    private fun sparklineSvg(points: List<SolverPoint>, mode: ChartMode): String {
        if (points.isEmpty()) return ""
        val w = 140  // total chart width (px)
        // Reserve a 10px label band at the top for the min/max value
        // text and 22px below for the bars themselves.
        val labelBand = 10
        val barArea = 22
        val h = labelBand + barArea
        val gap = 1
        val barW = ((w - gap * (points.size + 1)) / points.size).coerceAtLeast(2)
        val maxVal: Double = when (mode) {
            ChartMode.TIME -> points.maxOf { it.durationMs }.coerceAtLeast(1L).toDouble()
            ChartMode.COST -> points.maxOf { it.costUsd }.coerceAtLeast(0.000001)
        }
        // Precompute min/max formatted labels for the corner overlay.
        // Sorted points means first = min on the active metric, last
        // = max -- holds for both TIME (sortedBy durationMs) and COST
        // (sortedBy costUsd) sets passed in by the caller.
        val minLabel = formatPoint(points.first(), mode)
        val maxLabel = formatPoint(points.last(), mode)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" """)
            .append("""viewBox="0 0 $w $h" style="vertical-align:middle">""")
        // Min value at top-left, max at top-right. Small font so the
        // overall row height stays compact.
        sb.append("""<text x="0" y="8" font-size="9" fill="#475569" """)
            .append("""font-family="ui-monospace,Menlo,monospace">""")
            .append(escapeXml(minLabel)).append("</text>")
        sb.append("""<text x="$w" y="8" font-size="9" fill="#475569" text-anchor="end" """)
            .append("""font-family="ui-monospace,Menlo,monospace">""")
            .append(escapeXml(maxLabel)).append("</text>")
        // Baseline track at the bottom of the bar area.
        sb.append("""<rect x="0" y="${h - 1}" width="$w" height="1" fill="#e5e7eb"/>""")
        var x = gap
        for (p in points) {
            val v = when (mode) {
                ChartMode.TIME -> p.durationMs.toDouble()
                ChartMode.COST -> p.costUsd
            }
            val barH = ((v / maxVal) * (barArea - 2)).toInt().coerceAtLeast(1)
            val y = h - 1 - barH
            val fill = if (p.isWinner) "#16a34a" else "#94a3b8"
            val durationLabel = "${p.durationMs / 1000}s"
            val costLabel = "$" + String.format("%.4f", p.costUsd)
            val tip = escapeXml("${p.label}: $durationLabel, $costLabel" +
                if (p.isWinner) " (winner)" else "")
            sb.append("""<rect x="$x" y="$y" width="$barW" height="$barH" """)
                .append("""fill="$fill" rx="1"><title>""")
                .append(tip)
                .append("""</title></rect>""")
            x += barW + gap
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** Format a single SolverPoint's value on the active metric for
     *  the sparkline corner labels: "5s" for time, "$0.0123" for cost. */
    private fun formatPoint(p: SolverPoint, mode: ChartMode): String = when (mode) {
        ChartMode.TIME -> {
            val s = p.durationMs / 1000
            if (s < 1) "<1s" else "${s}s"
        }
        ChartMode.COST -> "$" + String.format("%.4f", p.costUsd)
    }

    /** Range-column helpers. Return null when the bug had ≤ 1 passing
     *  solver -- there's no spread to report and the column should
     *  render "—". `rangeRatio*` returns max / min for the sortable
     *  raw value; `rangeLabel*` returns the pre-formatted "min — max
     *  · Nx" string for display. */
    private fun rangeRatioTime(points: List<SolverPoint>): Double? {
        if (points.size < 2) return null
        val mn = points.first().durationMs.toDouble().coerceAtLeast(1.0)
        val mx = points.last().durationMs.toDouble()
        return mx / mn
    }
    private fun rangeLabelTime(points: List<SolverPoint>): String? {
        val ratio = rangeRatioTime(points) ?: return null
        return "${formatPoint(points.first(), ChartMode.TIME)} — " +
               "${formatPoint(points.last(), ChartMode.TIME)} · " +
               "${String.format("%.1f", ratio)}×"
    }
    private fun rangeRatioCost(points: List<SolverPoint>): Double? {
        if (points.size < 2) return null
        val mn = points.first().costUsd.coerceAtLeast(0.000001)
        val mx = points.last().costUsd
        return mx / mn
    }
    private fun rangeLabelCost(points: List<SolverPoint>): String? {
        val ratio = rangeRatioCost(points) ?: return null
        return "${formatPoint(points.first(), ChartMode.COST)} — " +
               "${formatPoint(points.last(), ChartMode.COST)} · " +
               "${String.format("%.1f", ratio)}×"
    }

    /** One-bar-per-bug status strip for the leaderboard's "Bugs"
     *  column. Bars are full-height (height encodes nothing -- this
     *  is a categorical chart, not a time/cost one); color encodes
     *  status. Bugs are rendered in catalog-id order so an operator
     *  can scan left-to-right and read it as "BUG-0001 ... BUG-N".
     *  Each bar's <title> is the bug id + status -- browser-native
     *  hover tooltip, no JS needed. */
    private fun bugStatusSparkSvg(
        sortedCatalogBugIds: List<String>,
        breakdown: List<BugDrilldown>
    ): String {
        if (sortedCatalogBugIds.isEmpty()) return ""
        val byBug = breakdown.associateBy { it.bugId }
        val h = 18
        val barW = 8
        val gap = 1
        val w = sortedCatalogBugIds.size * (barW + gap) + gap
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" """)
            .append("""viewBox="0 0 $w $h" style="vertical-align:middle">""")
        var x = gap
        for (bugId in sortedCatalogBugIds) {
            val drill = byBug[bugId]
            val (fill, status) = when {
                drill == null -> "#cbd5e1" to "not run"
                drill.passedRuns > 0 -> "#16a34a" to "PASSED (${drill.passedRuns}/${drill.totalRuns})"
                else -> "#dc2626" to "FAILED (0/${drill.totalRuns})"
            }
            val tip = escapeXml("$bugId — $status")
            sb.append("""<rect x="$x" y="0" width="$barW" height="$h" fill="$fill" rx="1"><title>""")
                .append(tip)
                .append("</title></rect>")
            x += barW + gap
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** Minimal XML attribute / text escape — covers every char that
     *  could break the inline-SVG-as-string injection. */
    private fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&'  -> sb.append("&amp;")
                '<'  -> sb.append("&lt;")
                '>'  -> sb.append("&gt;")
                '"'  -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

}
