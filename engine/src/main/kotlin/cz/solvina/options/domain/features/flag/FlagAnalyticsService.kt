package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Service
class FlagAnalyticsService(
    private val flagPort: FlagPort,
) {
    data class Analytics(
        val summary: Summary,
        val byStatus: List<StatusBreakdown>,
        val bySymbol: List<SymbolBreakdown>,
        val pnlTimeline: List<PnlTimelinePoint>,
    )

    data class Summary(
        val totalTrades: Int,
        val openTrades: Int,
        val winRate: Double,
        val totalRealizedPnl: BigDecimal,
        val avgWinner: BigDecimal,
        val avgLoser: BigDecimal,
        val eodCutPct: Double,
        val avgHoldMinutes: Double,
    )

    data class StatusBreakdown(
        val status: String,
        val count: Int,
        val totalPnl: BigDecimal,
        val avgPnl: BigDecimal,
    )

    data class SymbolBreakdown(
        val symbol: String,
        val count: Int,
        val wins: Int,
        val winRate: Double,
        val totalPnl: BigDecimal,
        val avgPnl: BigDecimal,
    )

    data class PnlTimelinePoint(
        val date: LocalDate,
        val dailyPnl: BigDecimal,
        val cumulativePnl: BigDecimal,
    )

    private val closedStatuses = setOf(
        FlagStatus.CLOSED_PROFIT,
        FlagStatus.CLOSED_STOP,
        FlagStatus.CLOSED_EOD,
        FlagStatus.CLOSED_MANUAL,
    )

    suspend fun compute(): Analytics {
        val all = flagPort.findAll()
        val closed = all.filter { it.status in closedStatuses && it.realizedPnl != null && it.closedAt != null }
        val open = all.filter { it.status == FlagStatus.OPEN || it.status == FlagStatus.PENDING }

        val wins = closed.filter { it.realizedPnl != null && it.realizedPnl > BigDecimal.ZERO }
        val losses = closed.filter { it.realizedPnl != null && it.realizedPnl <= BigDecimal.ZERO }
        val eodCuts = closed.filter { it.status == FlagStatus.CLOSED_EOD }

        val totalPnl = closed.fold(BigDecimal.ZERO) { acc, p -> acc + (p.realizedPnl ?: BigDecimal.ZERO) }
        val avgWinner = if (wins.isEmpty()) BigDecimal.ZERO else wins.fold(BigDecimal.ZERO) { acc, p -> acc + (p.realizedPnl!!) }
            .divide(BigDecimal(wins.size), 2, RoundingMode.HALF_UP)
        val avgLoser = if (losses.isEmpty()) BigDecimal.ZERO else losses.fold(BigDecimal.ZERO) { acc, p -> acc + (p.realizedPnl!!) }
            .divide(BigDecimal(losses.size), 2, RoundingMode.HALF_UP)
        val avgHoldMin = closed.mapNotNull { it.holdMinutes() }.average().takeIf { !it.isNaN() } ?: 0.0

        return Analytics(
            summary = Summary(
                totalTrades = all.size,
                openTrades = open.size,
                winRate = if (closed.isEmpty()) 0.0 else wins.size.toDouble() / closed.size,
                totalRealizedPnl = totalPnl,
                avgWinner = avgWinner,
                avgLoser = avgLoser,
                eodCutPct = if (closed.isEmpty()) 0.0 else eodCuts.size.toDouble() / closed.size,
                avgHoldMinutes = avgHoldMin,
            ),
            byStatus = byStatus(closed),
            bySymbol = bySymbol(closed),
            pnlTimeline = pnlTimeline(closed),
        )
    }

    private fun byStatus(closed: List<FlagPosition>): List<StatusBreakdown> =
        closed
            .groupBy { it.status.name }
            .map { (status, trades) ->
                val totalPnl = trades.fold(BigDecimal.ZERO) { acc, p -> acc + (p.realizedPnl ?: BigDecimal.ZERO) }
                StatusBreakdown(
                    status = status,
                    count = trades.size,
                    totalPnl = totalPnl,
                    avgPnl = totalPnl.divide(BigDecimal(trades.size), 2, RoundingMode.HALF_UP),
                )
            }.sortedByDescending { it.count }

    private fun bySymbol(closed: List<FlagPosition>): List<SymbolBreakdown> =
        closed
            .groupBy { it.symbol.value }
            .map { (symbol, trades) ->
                val wins = trades.count { (it.realizedPnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }
                val totalPnl = trades.fold(BigDecimal.ZERO) { acc, p -> acc + (p.realizedPnl ?: BigDecimal.ZERO) }
                SymbolBreakdown(
                    symbol = symbol,
                    count = trades.size,
                    wins = wins,
                    winRate = if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size,
                    totalPnl = totalPnl,
                    avgPnl = totalPnl.divide(BigDecimal(trades.size), 2, RoundingMode.HALF_UP),
                )
            }.sortedByDescending { it.totalPnl }

    private fun pnlTimeline(closed: List<FlagPosition>): List<PnlTimelinePoint> {
        val byDay = closed
            .groupBy { it.closedAt!!.atOffset(ZoneOffset.UTC).toLocalDate() }
            .toSortedMap()
        var cumulative = BigDecimal.ZERO
        return byDay.map { (date, trades) ->
            val daily = trades.fold(BigDecimal.ZERO) { acc, p -> acc + (p.realizedPnl ?: BigDecimal.ZERO) }
            cumulative += daily
            PnlTimelinePoint(date, daily, cumulative)
        }
    }

    private fun FlagPosition.holdMinutes(): Double? =
        closedAt?.let { ChronoUnit.MINUTES.between(openedAt, it).toDouble() }
}
