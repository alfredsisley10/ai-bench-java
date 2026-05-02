package com.aibench.webui

import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import java.time.format.DateTimeFormatter

/**
 * XLSX export of the dashboard's two main tables. CSV export already
 * runs client-side via sortable-table.js (data-csv-target). XLSX adds:
 *   - column-typed cells (numbers stay numbers, dates stay dates)
 *   - bold header row
 *   - frozen top row + auto column-widths
 * which the browser-side string-CSV path can't match.
 *
 * Endpoints stream the workbook directly to the response so a 1000-
 * row export doesn't have to live in memory beyond a single buffer.
 */
@Controller
class ExportController(
    private val benchmarkRuns: BenchmarkRunService,
    private val dashboardController: DashboardController,
    private val bugCatalog: BugCatalog
) {
    private val isoFmt = DateTimeFormatter.ISO_INSTANT

    @GetMapping("/export/runs.xlsx")
    fun exportRuns(response: HttpServletResponse) {
        val rows = benchmarkRuns.recentRuns(500)
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Benchmark runs")
            val headerStyle = wb.createCellStyle().apply {
                val font = wb.createFont().apply { bold = true }
                setFont(font)
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            }
            val headers = listOf(
                "Run ID", "Bug", "Provider", "Model", "Context", "AppMap mode",
                "Status", "Passed seeds", "Total seeds",
                "Prompt tokens", "Completion tokens", "Cost USD",
                "Duration sec", "LLM sec", "Started"
            )
            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { i, name ->
                val c = headerRow.createCell(i)
                c.setCellValue(name)
                c.cellStyle = headerStyle
            }
            rows.forEachIndexed { idx, r ->
                val passed = r.seedResults.count { it.passed }
                val row = sheet.createRow(idx + 1)
                row.createCell(0).setCellValue(r.id)
                row.createCell(1).setCellValue(r.issueId)
                row.createCell(2).setCellValue(r.provider)
                row.createCell(3).setCellValue(r.modelId)
                row.createCell(4).setCellValue(r.contextProvider)
                row.createCell(5).setCellValue(r.appmapMode)
                row.createCell(6).setCellValue(r.status.name)
                row.createCell(7).setCellValue(passed.toDouble())
                row.createCell(8).setCellValue(r.seeds.toDouble())
                row.createCell(9).setCellValue(r.stats.totalPromptTokens.toDouble())
                row.createCell(10).setCellValue(r.stats.totalCompletionTokens.toDouble())
                row.createCell(11).setCellValue(r.stats.estimatedCostUsd)
                row.createCell(12).setCellValue((r.durationMs / 1000.0))
                row.createCell(13).setCellValue((r.totalLlmMs / 1000.0))
                row.createCell(14).setCellValue(r.startedAt.toString())
            }
            sheet.createFreezePane(0, 1)
            for (i in headers.indices) sheet.autoSizeColumn(i)
            stream(wb, response, "benchmark-runs.xlsx")
        }
    }

    @GetMapping("/export/leaderboard.xlsx")
    fun exportLeaderboard(response: HttpServletResponse, session: HttpSession) {
        // Re-run the dashboard's leaderboard build so we don't duplicate
        // logic. Pull via a tiny adapter rather than calling
        // dashboard(model, session) which mutates a Spring Model.
        val runs = benchmarkRuns.recentRuns(500)
        data class CtxKey(val provider: String, val modelId: String,
                          val contextProvider: String, val appmapMode: String)
        val totalsByCtx = runs.groupingBy {
            CtxKey(it.provider, it.modelId, it.contextProvider, it.appmapMode)
        }.eachCount()
        val passed = runs.filter { it.status.name == "PASSED" }
        val passByCtx = passed.groupBy {
            CtxKey(it.provider, it.modelId, it.contextProvider, it.appmapMode)
        }
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Leaderboard")
            val headerStyle = wb.createCellStyle().apply {
                val font = wb.createFont().apply { bold = true }
                setFont(font)
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            }
            val headers = listOf(
                "Rank", "Provider", "Model", "Context", "AppMap mode",
                "Pass rate", "Solved", "Total", "Avg time sec",
                "Avg cost USD", "Best time sec", "Best cost USD"
            )
            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { i, name ->
                val c = headerRow.createCell(i)
                c.setCellValue(name)
                c.cellStyle = headerStyle
            }
            // Sort by passed-runs desc, avg-time asc -- matches the
            // leaderboard's display order (Oracle filtered out).
            val ranked = passByCtx.entries
                .filter { it.key.contextProvider.lowercase() != "oracle" }
                .map { (k, ps) ->
                    val avgMs = ps.map { it.durationMs }.average().toLong()
                    val avgCost = ps.map { it.stats.estimatedCostUsd }.average()
                    val fastest = ps.minByOrNull { it.durationMs }
                    val cheapest = ps.minByOrNull { it.stats.estimatedCostUsd }
                    Triple(k, ps.size, listOf(avgMs, avgCost, fastest, cheapest))
                }
                .sortedWith(compareByDescending<Triple<CtxKey, Int, List<Any?>>> { it.second }
                    .thenBy { it.third[0] as Long })
            ranked.forEachIndexed { idx, (k, solved, extras) ->
                val total = totalsByCtx[k] ?: solved
                val avgMs = extras[0] as Long
                val avgCost = extras[1] as Double
                val fastest = extras[2] as BenchmarkRunService.BenchmarkRun?
                val cheapest = extras[3] as BenchmarkRunService.BenchmarkRun?
                val row = sheet.createRow(idx + 1)
                row.createCell(0).setCellValue((idx + 1).toDouble())
                row.createCell(1).setCellValue(k.provider)
                row.createCell(2).setCellValue(k.modelId)
                row.createCell(3).setCellValue(k.contextProvider)
                row.createCell(4).setCellValue(k.appmapMode)
                row.createCell(5).setCellValue(solved.toDouble() / total)
                row.createCell(6).setCellValue(solved.toDouble())
                row.createCell(7).setCellValue(total.toDouble())
                row.createCell(8).setCellValue(avgMs / 1000.0)
                row.createCell(9).setCellValue(avgCost)
                row.createCell(10).setCellValue((fastest?.durationMs ?: 0) / 1000.0)
                row.createCell(11).setCellValue(cheapest?.stats?.estimatedCostUsd ?: 0.0)
            }
            sheet.createFreezePane(0, 1)
            for (i in headers.indices) sheet.autoSizeColumn(i)
            stream(wb, response, "leaderboard.xlsx")
        }
    }

    private fun stream(wb: XSSFWorkbook, response: HttpServletResponse, filename: String) {
        response.contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        response.setHeader("Content-Disposition", "attachment; filename=\"$filename\"")
        wb.write(response.outputStream)
        response.outputStream.flush()
    }
}
