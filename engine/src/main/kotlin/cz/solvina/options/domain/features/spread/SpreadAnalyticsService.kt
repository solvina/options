package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Service
class SpreadAnalyticsService(
    private val spreadPort: SpreadPort,
) {
    data class Analytics(
        val summary: Summary,
        val byStatus: List<StatusBreakdown>,
        val bySymbol: List<SymbolBreakdown>,
        val byEntryIvBucket: List<IvBucketBreakdown>,
        val pnlTimeline: List<PnlTimelinePoint>,
    )

    data class Summary(
        val totalTrades: Int,
        val openTrades: Int,
        val winRate: Double,
        val totalRealizedPnl: BigDecimal,
        val avgPnlPerTrade: BigDecimal,
        val avgHoldDays: Double,
    )

    data class StatusBreakdown(
        val status: String,
        val count: Int,
        val totalPnl: BigDecimal,
        val avgPnl: BigDecimal,
        val avgHoldDays: Double,
    )

    data class SymbolBreakdown(
        val symbol: String,
        val count: Int,
        val wins: Int,
        val winRate: Double,
        val totalPnl: BigDecimal,
        val avgPnl: BigDecimal,
        val avgCreditRatio: Double,
    )

    data class IvBucketBreakdown(
        val bucket: String,
        val count: Int,
        val winRate: Double,
        val avgPnl: BigDecimal,
    )

    data class PnlTimelinePoint(
        val date: LocalDate,
        val dailyPnl: BigDecimal,
        val cumulativePnl: BigDecimal,
    )

    private val terminalStatuses =
        setOf(
            SpreadStatus.CLOSED_PROFIT,
            SpreadStatus.CLOSED_STOP,
            SpreadStatus.CLOSED_TIME,
            SpreadStatus.CLOSED_MANUAL,
        )

    suspend fun compute(): Analytics {
        val all = spreadPort.findAll()
        val closed = all.filter { it.status in terminalStatuses && it.closePricePerShare != null && it.closedAt != null }
        val open = all.filter { it.status == SpreadStatus.OPEN || it.status == SpreadStatus.CLOSING }

        val winRate =
            if (closed.isEmpty()) {
                0.0
            } else {
                closed.count { it.status == SpreadStatus.CLOSED_PROFIT }.toDouble() / closed.size
            }

        val totalPnl = closed.fold(BigDecimal.ZERO) { acc, s -> acc + s.realizedPnl() }
        val avgPnl =
            if (closed.isEmpty()) {
                BigDecimal.ZERO
            } else {
                totalPnl.divide(BigDecimal(closed.size), 2, RoundingMode.HALF_UP)
            }
        val avgHoldDays = closed.mapNotNull { it.holdDays() }.average().takeIf { !it.isNaN() } ?: 0.0

        return Analytics(
            summary =
                Summary(
                    totalTrades = closed.size + open.size,
                    openTrades = open.size,
                    winRate = winRate,
                    totalRealizedPnl = totalPnl,
                    avgPnlPerTrade = avgPnl,
                    avgHoldDays = avgHoldDays,
                ),
            byStatus = byStatus(closed),
            bySymbol = bySymbol(closed),
            byEntryIvBucket = byIvBucket(closed),
            pnlTimeline = pnlTimeline(closed),
        )
    }

    private fun byStatus(closed: List<BullPutSpread>): List<StatusBreakdown> =
        closed
            .groupBy { it.status.name }
            .map { (status, trades) ->
                val totalPnl = trades.fold(BigDecimal.ZERO) { acc, s -> acc + s.realizedPnl() }
                StatusBreakdown(
                    status = status,
                    count = trades.size,
                    totalPnl = totalPnl,
                    avgPnl = totalPnl.divide(BigDecimal(trades.size), 2, RoundingMode.HALF_UP),
                    avgHoldDays = trades.mapNotNull { it.holdDays() }.average().takeIf { !it.isNaN() } ?: 0.0,
                )
            }.sortedByDescending { it.count }

    private fun bySymbol(closed: List<BullPutSpread>): List<SymbolBreakdown> =
        closed
            .groupBy { it.symbol.value }
            .map { (symbol, trades) ->
                val wins = trades.count { it.status == SpreadStatus.CLOSED_PROFIT }
                val totalPnl = trades.fold(BigDecimal.ZERO) { acc, s -> acc + s.realizedPnl() }
                val avgCreditRatio =
                    trades
                        .mapNotNull { s ->
                            if (s.maxRiskPerShare > BigDecimal.ZERO) {
                                s.creditPerShare.toDouble() / s.maxRiskPerShare.toDouble()
                            } else {
                                null
                            }
                        }.average()
                        .takeIf { !it.isNaN() } ?: 0.0
                SymbolBreakdown(
                    symbol = symbol,
                    count = trades.size,
                    wins = wins,
                    winRate = if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size,
                    totalPnl = totalPnl,
                    avgPnl = totalPnl.divide(BigDecimal(trades.size), 2, RoundingMode.HALF_UP),
                    avgCreditRatio = avgCreditRatio,
                )
            }.sortedByDescending { it.totalPnl }

    private fun byIvBucket(closed: List<BullPutSpread>): List<IvBucketBreakdown> {
        data class Bucket(
            val label: String,
            val lo: Double?,
            val hi: Double?,
        )
        val buckets =
            listOf(
                Bucket("< 30", null, 30.0),
                Bucket("30–50", 30.0, 50.0),
                Bucket("50–70", 50.0, 70.0),
                Bucket("> 70", 70.0, null),
            )
        return buckets.map { (label, lo, hi) ->
            val trades =
                closed.filter { s ->
                    val iv = s.ivRankAtEntry ?: return@filter false
                    (lo == null || iv >= lo) && (hi == null || iv < hi)
                }
            val wins = trades.count { it.status == SpreadStatus.CLOSED_PROFIT }
            val totalPnl = trades.fold(BigDecimal.ZERO) { acc, s -> acc + s.realizedPnl() }
            IvBucketBreakdown(
                bucket = label,
                count = trades.size,
                winRate = if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size,
                avgPnl =
                    if (trades.isEmpty()) {
                        BigDecimal.ZERO
                    } else {
                        totalPnl.divide(BigDecimal(trades.size), 2, RoundingMode.HALF_UP)
                    },
            )
        }
    }

    private fun pnlTimeline(closed: List<BullPutSpread>): List<PnlTimelinePoint> {
        val byDay =
            closed
                .groupBy { it.closedAt!!.atOffset(ZoneOffset.UTC).toLocalDate() }
                .toSortedMap()
        var cumulative = BigDecimal.ZERO
        return byDay.map { (date, trades) ->
            val daily = trades.fold(BigDecimal.ZERO) { acc, s -> acc + s.realizedPnl() }
            cumulative += daily
            PnlTimelinePoint(date, daily, cumulative)
        }
    }

    private fun BullPutSpread.realizedPnl(): BigDecimal {
        val closePrice = closePricePerShare ?: return BigDecimal.ZERO
        return creditPerShare
            .subtract(closePrice)
            .multiply(BigDecimal(100))
            .multiply(BigDecimal(quantity))
    }

    private fun BullPutSpread.holdDays(): Double? = closedAt?.let { ChronoUnit.DAYS.between(openedAt, it).toDouble() }
}
