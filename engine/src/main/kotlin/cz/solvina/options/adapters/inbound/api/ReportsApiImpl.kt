package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.report.ReportService
import `cz.solvina.options.reports`.api.ReportsApi
import `cz.solvina.options.reports`.dto.ReportSummaryDto
import `cz.solvina.options.reports`.dto.StrategyReportDto
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
class ReportsApiImpl(
    private val reportService: ReportService,
) : ReportsApi {
    override suspend fun getReportSummary(
        from: LocalDate,
        to: LocalDate,
    ): ResponseEntity<ReportSummaryDto> {
        if (from.isAfter(to)) return ResponseEntity.badRequest().build()
        val summary = reportService.summary(from, to)
        return ResponseEntity.ok(
            ReportSummaryDto(
                from = summary.from,
                to = summary.to,
                strategies = summary.strategies.map { it.toDto() },
                total = summary.total.toDto(),
            ),
        )
    }

    private fun ReportService.StrategyReport.toDto() =
        StrategyReportDto(
            strategy = strategy,
            opened = opened,
            stillOpen = stillOpen,
            closed = closed,
            wins = wins,
            losses = losses,
            closedNoPnl = closedNoPnl,
            winRate = winRate?.toBigDecimal(),
            realizedPnl = realizedPnl,
            avgPnl = avgPnl,
            bestPnl = bestPnl,
            worstPnl = worstPnl,
            avgHoldDays = avgHoldDays?.toBigDecimal(),
        )
}
