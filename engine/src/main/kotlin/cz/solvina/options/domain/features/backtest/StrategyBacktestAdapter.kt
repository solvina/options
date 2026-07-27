package cz.solvina.options.domain.features.backtest

import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.strategy.StockStrategy
import cz.solvina.options.domain.features.strategy.StrategyContext
import cz.solvina.options.domain.features.strategy.StrategyTrade
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a [StockStrategy] inside the backtest engine — the replay *host*.
 *
 * Owns everything that is a host concern and must never leak into a strategy: trade-id minting,
 * pending/open bookkeeping, and turning fills and closes into [StrategyTrade] rows. The strategy
 * only ever decides. The live host will do the same job against the broker, driving the identical
 * [StockStrategy] instance, which is what keeps backtest and production honest about each other.
 *
 * Rejects a tick-dependent strategy up front rather than approximating ticks with candles — see
 * [cz.solvina.options.domain.features.strategy.StrategyInputs.requiresTicks].
 */
class StrategyBacktestAdapter(
    private val strategy: StockStrategy,
) : BacktestableStrategy {
    init {
        require(!strategy.inputs.requiresTicks) {
            "Strategy '${strategy.id}' requires tick data, which the bar store cannot provide — " +
                "approximating ticks with candles would produce backtest numbers that mean nothing"
        }
    }

    private val pending = mutableMapOf<String, PendingInfo>() // tradeId → symbol+shares, set at emit
    private val open = mutableMapOf<String, OpenTrade>()
    private val completed = mutableListOf<StrategyTrade>()
    private val counter = AtomicInteger(0)

    private data class PendingInfo(
        val symbol: Symbol,
        val shares: Int,
    )

    private data class OpenTrade(
        val symbol: Symbol,
        val entryAt: Instant,
        val entryPrice: BigDecimal,
        val shares: Int,
    )

    override fun initialize(
        symbols: List<Symbol>,
        warmupBars: Map<Symbol, List<Candle>>,
    ) {
        val primary = strategy.inputs.primary
        symbols.forEach { symbol ->
            strategy.warmup(symbol, mapOf(primary to warmupBars[symbol].orEmpty()))
        }
    }

    override fun onBar(
        symbol: Symbol,
        bar: Candle,
        account: BacktestAccountView,
    ): List<BacktestSignal> {
        val decision =
            strategy.decide(
                StrategyContext(
                    symbol = symbol,
                    candle = bar,
                    byTimeframe = mapOf(strategy.inputs.primary to bar),
                    equity = account.capital,
                    openPositions = account.openPositions,
                    pendingPositions = account.pendingPositions,
                ),
            ) ?: return emptyList()

        val tradeId = "${strategy.id}-${counter.incrementAndGet()}"
        pending[tradeId] = PendingInfo(symbol, decision.shares)
        return listOf(
            BacktestSignal.OpenBracket(
                tradeId = tradeId,
                symbol = symbol,
                shares = decision.shares,
                entryPrice = decision.entryPrice,
                stopLossPrice = decision.stopLossPrice,
                profitTargetPrice = decision.profitTargetPrice,
            ),
        )
    }

    override fun onEntryFilled(
        tradeId: String,
        fillPrice: BigDecimal,
        filledAt: Instant,
    ) {
        val info = pending.remove(tradeId) ?: return
        open[tradeId] = OpenTrade(info.symbol, filledAt, fillPrice, info.shares)
    }

    override fun onEntryExpired(tradeId: String) {
        pending.remove(tradeId)
    }

    override fun onPositionClosed(
        tradeId: String,
        closePrice: BigDecimal,
        closeReason: String,
        closedAt: Instant,
        highestSeen: BigDecimal,
        lowestSeen: BigDecimal,
    ) {
        val o = open.remove(tradeId) ?: return
        completed +=
            StrategyTrade(
                symbol = o.symbol.value,
                entryAt = o.entryAt,
                entryPrice = o.entryPrice,
                exitAt = closedAt,
                exitPrice = closePrice,
                closeReason = closeReason,
                shares = o.shares,
                pnl = closePrice.subtract(o.entryPrice).multiply(BigDecimal(o.shares)),
            )
    }

    override fun trades(): List<StrategyTrade> = completed
}
