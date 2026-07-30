package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.account.BrokerStockPositionService
import cz.solvina.options.domain.features.strategy.live.StockPosition
import cz.solvina.options.domain.features.strategy.live.StockPositionPort
import cz.solvina.options.domain.features.strategy.live.StockPositionStatus
import cz.solvina.options.domain.features.strategy.live.StockStrategyConfig
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Live stock-strategy positions, plus the summary that actually matters during paper validation.
 *
 * Fill rate is the headline, not P&L. The backtest fills every emitted entry at the next bar's
 * open; a live day limit does not. That gap is the one number the simulation cannot tell us, and
 * for a strategy whose expected return is around zero the P&L is noise by comparison.
 */
@RestController
@RequestMapping("/stock-positions")
class StockPositionApiController(
    private val positions: StockPositionPort,
    private val config: StockStrategyConfig,
    private val brokerStockPositions: BrokerStockPositionService,
) {
    data class StockPositionDto(
        val id: String,
        val strategyId: String,
        val assignmentId: String,
        val symbol: String,
        val timeframe: String,
        val status: String,
        val signalPrice: BigDecimal,
        val limitPrice: BigDecimal,
        val stopPrice: BigDecimal,
        val targetPrice: BigDecimal?,
        val shares: Int,
        val actualEntryPrice: BigDecimal?,
        val entrySlippage: BigDecimal?,
        val closePrice: BigDecimal?,
        val realizedPnl: BigDecimal?,
        val closeReason: String?,
        val signalledAt: Instant,
        val openedAt: Instant?,
        val closedAt: Instant?,
        // ---- Broker view: IBKR's updatePortfolio row for this symbol, reported verbatim ----
        val brokerShares: BigDecimal?,
        val brokerMarketPrice: BigDecimal?,
        val brokerMarketValue: BigDecimal?,
        /** IBKR's cost basis per share, inclusive of commissions. */
        val brokerAvgCost: BigDecimal?,
        /** IBKR's own unrealized P&L — the figure TWS displays. Not recomputed here. */
        val brokerUnrealizedPnl: BigDecimal?,
        val brokerRealizedPnl: BigDecimal?,
        val brokerUpdatedAt: Instant?,
        val brokerDataStale: Boolean?,
    )

    data class SummaryDto(
        val enabled: Boolean,
        val maxOpenPositions: Int,
        val livePositions: Int,
        val signalled: Int,
        val filled: Int,
        val unfilled: Int,
        /** filled / (filled + unfilled), the live-vs-backtest divergence measure. Null before any entry resolves. */
        val fillRate: BigDecimal?,
        val avgEntrySlippage: BigDecimal?,
    )

    @GetMapping
    suspend fun list(): List<StockPositionDto> {
        // One broker-feed read for the whole list; rows are matched to positions by symbol.
        val broker = brokerStockPositions.bySymbol()
        return positions.findAll().sortedByDescending { it.signalledAt }.map { it.toDto(broker[it.symbol.value]) }
    }

    @GetMapping("/summary")
    suspend fun summary(): SummaryDto {
        val all = positions.findAll()
        val filled = all.count { it.actualEntryPrice != null }
        val unfilled = all.count { it.status == StockPositionStatus.ENTRY_UNFILLED }
        val resolved = filled + unfilled
        val slippages = all.mapNotNull { it.entrySlippage }
        return SummaryDto(
            enabled = config.enabled,
            maxOpenPositions = config.maxOpenPositions,
            livePositions = all.count { it.status.isLive },
            signalled = all.size,
            filled = filled,
            unfilled = unfilled,
            fillRate =
                if (resolved == 0) {
                    null
                } else {
                    BigDecimal(filled).divide(BigDecimal(resolved), 4, RoundingMode.HALF_UP)
                },
            avgEntrySlippage =
                if (slippages.isEmpty()) {
                    null
                } else {
                    slippages.reduce(BigDecimal::add).divide(BigDecimal(slippages.size), 4, RoundingMode.HALF_UP)
                },
        )
    }

    private fun StockPosition.toDto(broker: BrokerStockPositionService.BrokerStockPosition?) =
        StockPositionDto(
            id = id.toString(),
            strategyId = strategyId,
            assignmentId = assignmentId.toString(),
            symbol = symbol.value,
            timeframe = timeframe.label,
            status = status.name,
            signalPrice = signalPrice,
            limitPrice = limitPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            shares = shares,
            actualEntryPrice = actualEntryPrice,
            entrySlippage = entrySlippage,
            closePrice = closePrice,
            realizedPnl = realizedPnl,
            closeReason = closeReason,
            signalledAt = signalledAt,
            openedAt = openedAt,
            closedAt = closedAt,
            brokerShares = broker?.shares,
            brokerMarketPrice = broker?.marketPrice,
            brokerMarketValue = broker?.marketValue,
            brokerAvgCost = broker?.avgCost,
            brokerUnrealizedPnl = broker?.unrealizedPnl,
            brokerRealizedPnl = broker?.realizedPnl,
            brokerUpdatedAt = broker?.updatedAt,
            brokerDataStale = broker?.stale,
        )
}
