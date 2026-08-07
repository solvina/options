package cz.solvina.options.domain.features.report

import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.flag.model.hasBrokerPosition
import cz.solvina.options.domain.features.spread.BearCallSpreadPort
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.strategy.live.StockPosition
import cz.solvina.options.domain.features.strategy.live.StockPositionPort
import cz.solvina.options.domain.features.strategy.live.StockPositionStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Dates in reports are the user's wall clock, not UTC — a 22:05 CET close belongs to that CET day. */
private val REPORT_ZONE = ZoneId.of("Europe/Prague")

/** Spread statuses meaning "a real position existed and was cleanly closed" (P&L attributable). */
private val SPREAD_CLOSED_STATUSES =
    setOf(
        SpreadStatus.CLOSED_PROFIT,
        SpreadStatus.CLOSED_STOP,
        SpreadStatus.CLOSED_TIME,
        SpreadStatus.CLOSED_MANUAL,
        SpreadStatus.CLOSED_DIVIDEND_RISK,
    )

private val FLAG_CLOSED_STATUSES =
    setOf(
        FlagStatus.CLOSED_PROFIT,
        FlagStatus.CLOSED_STOP,
        FlagStatus.CLOSED_EOD,
        FlagStatus.CLOSED_MANUAL,
        FlagStatus.CLOSED_EXTERNAL,
    )

/**
 * ENTRY_UNFILLED is absent on purpose: no position ever existed, so it is neither a win nor a loss.
 * It is counted where it means something — as the live-vs-backtest fill rate on the stock positions
 * endpoint — and folding it in here as a zero-P&L "close" would dilute every win rate on the page.
 */
private val STOCK_CLOSED_STATUSES =
    setOf(
        StockPositionStatus.CLOSED_TARGET,
        StockPositionStatus.CLOSED_STOP,
        StockPositionStatus.CLOSED_MANUAL,
        StockPositionStatus.CLOSED_EXTERNAL,
    )

@Service
class ReportService(
    private val bullPutSpreadPort: BullPutSpreadPort,
    private val bearCallSpreadPort: BearCallSpreadPort,
    private val flagPort: FlagPort,
    private val stockPositionPort: StockPositionPort,
) {
    data class StrategyReport(
        val strategy: String,
        val opened: Int,
        val stillOpen: Int,
        val closed: Int,
        val wins: Int,
        val losses: Int,
        val closedNoPnl: Int,
        val winRate: Double?,
        val realizedPnl: BigDecimal,
        val avgPnl: BigDecimal?,
        val bestPnl: BigDecimal?,
        val worstPnl: BigDecimal?,
        val avgHoldDays: Double?,
    )

    data class Summary(
        val from: LocalDate,
        val to: LocalDate,
        val strategies: List<StrategyReport>,
        val total: StrategyReport,
    )

    /** A position's contribution to the report: when it opened/closed and what it realized. */
    private data class TradeFacts(
        val openedAt: Instant,
        val closedAt: Instant?,
        val filled: Boolean,
        val openNow: Boolean,
        val cleanlyClosed: Boolean,
        val realizedPnl: BigDecimal?,
    )

    suspend fun summary(
        from: LocalDate,
        to: LocalDate,
    ): Summary {
        val start = from.atStartOfDay(REPORT_ZONE).toInstant()
        val end = to.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant()

        val bullPuts = bullPutSpreadPort.findAll().map { it.toFacts() }
        val bearCalls = bearCallSpreadPort.findAll().map { it.toFacts() }
        val flags = flagPort.findAll().map { it.toFacts() }
        // One row per stock strategy rather than a lumped "stocks": they are independent strategies
        // that happen to share a host, and the reason this report exists is to judge each one.
        val stocksByStrategy =
            stockPositionPort
                .findAll()
                .groupBy { it.strategyId }
                .mapValues { (_, rows) -> rows.map { it.toFacts() } }

        val strategies =
            listOf(
                aggregate("bull_put", bullPuts, start, end),
                aggregate("bear_call", bearCalls, start, end),
                aggregate("bull_flag", flags, start, end),
            ) + stocksByStrategy.entries.sortedBy { it.key }.map { (id, facts) -> aggregate(id, facts, start, end) }
        val all = bullPuts + bearCalls + flags + stocksByStrategy.values.flatten()
        return Summary(from, to, strategies, aggregate("total", all, start, end))
    }

    private fun Spread.toFacts() =
        TradeFacts(
            openedAt = openedAt,
            closedAt = closedAt,
            filled = status !in SpreadStatus.NOT_FILLED,
            openNow = status == SpreadStatus.OPEN || status == SpreadStatus.CLOSING,
            cleanlyClosed = status in SPREAD_CLOSED_STATUSES,
            // Same formula as SpreadAnalyticsService: credit received minus close price, per contract.
            realizedPnl =
                closePricePerShare?.let { close ->
                    creditPerShare.subtract(close).multiply(BigDecimal(100)).multiply(BigDecimal(quantity))
                },
        )

    private fun cz.solvina.options.domain.features.flag.model.FlagPosition.toFacts() =
        TradeFacts(
            openedAt = openedAt,
            closedAt = closedAt,
            filled = status != FlagStatus.PENDING && status != FlagStatus.ENTRY_TIMEOUT,
            openNow = status.hasBrokerPosition,
            cleanlyClosed = status in FLAG_CLOSED_STATUSES,
            realizedPnl = realizedPnl,
        )

    /**
     * [StockPosition.openedAt] is null until the entry fills, so an unfilled row falls back to when
     * it was signalled — it is excluded from the opened count by [TradeFacts.filled] anyway, and a
     * nullable open time would only push the same branch into the aggregator.
     */
    private fun StockPosition.toFacts() =
        TradeFacts(
            openedAt = openedAt ?: signalledAt,
            closedAt = closedAt,
            filled = status != StockPositionStatus.PENDING && status != StockPositionStatus.ENTRY_UNFILLED,
            openNow = status == StockPositionStatus.OPEN,
            cleanlyClosed = status in STOCK_CLOSED_STATUSES,
            realizedPnl = realizedPnl,
        )

    private fun aggregate(
        strategy: String,
        trades: List<TradeFacts>,
        start: Instant,
        end: Instant,
    ): StrategyReport {
        val openedInPeriod = trades.filter { it.filled && it.openedAt >= start && it.openedAt < end }
        val closedInPeriod =
            trades.filter { t -> t.cleanlyClosed && t.closedAt?.let { it >= start && it < end } == true }

        val withPnl = closedInPeriod.mapNotNull { it.realizedPnl }
        val wins = withPnl.count { it.signum() >= 0 }
        val losses = withPnl.count { it.signum() < 0 }
        val totalPnl = withPnl.fold(BigDecimal.ZERO, BigDecimal::add)
        val holdDays =
            closedInPeriod.mapNotNull { t ->
                t.closedAt?.let { ChronoUnit.HOURS.between(t.openedAt, it).toDouble() / 24.0 }
            }

        return StrategyReport(
            strategy = strategy,
            opened = openedInPeriod.size,
            stillOpen = openedInPeriod.count { it.openNow },
            closed = closedInPeriod.size,
            wins = wins,
            losses = losses,
            closedNoPnl = closedInPeriod.size - withPnl.size,
            winRate = if (withPnl.isNotEmpty()) wins.toDouble() / withPnl.size else null,
            realizedPnl = totalPnl.setScale(2, RoundingMode.HALF_UP),
            avgPnl =
                if (withPnl.isNotEmpty()) {
                    totalPnl.divide(BigDecimal(withPnl.size), 2, RoundingMode.HALF_UP)
                } else {
                    null
                },
            bestPnl = withPnl.maxOrNull()?.setScale(2, RoundingMode.HALF_UP),
            worstPnl = withPnl.minOrNull()?.setScale(2, RoundingMode.HALF_UP),
            avgHoldDays = if (holdDays.isNotEmpty()) holdDays.average() else null,
        )
    }
}
