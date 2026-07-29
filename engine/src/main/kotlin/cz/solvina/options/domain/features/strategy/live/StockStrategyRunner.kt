package cz.solvina.options.domain.features.strategy.live

import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.features.bars.AtrCalculator
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.strategy.StrategyContext
import cz.solvina.options.domain.features.strategy.StrategyRegistry
import cz.solvina.options.domain.features.strategy.StrategyWarmup
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignment
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignmentPort
import cz.solvina.options.domain.features.strategy.tuning.StrategyParamsResolver
import cz.solvina.options.domain.features.universe.UniversePort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * The live host: the twin of [cz.solvina.options.domain.features.backtest.StrategyBacktestAdapter].
 *
 * Both build a [StrategyContext] and call the same `decide`. Neither contains strategy logic — if a
 * rule appears in here, it is a bug. What differs is only where the context comes from (bar store +
 * live account instead of a replay loop) and where the [cz.solvina.options.domain.features.strategy.Decision]
 * goes (a broker instead of a simulated fill).
 *
 * Ships dark: [StockStrategyConfig.enabled] defaults false, so deploying this changes nothing until
 * it is deliberately switched on.
 */
@Service
class StockStrategyRunner(
    private val assignments: StrategyAssignmentPort,
    private val strategies: StrategyRegistry,
    private val positions: StockPositionPort,
    private val orders: StockOrderPort,
    private val barStore: BarStorePort,
    private val universe: UniversePort,
    private val effectiveAccount: EffectiveAccountService,
    private val config: StockStrategyConfig,
    private val paramsResolver: StrategyParamsResolver,
    private val mapper: ObjectMapper,
) {
    /**
     * One pass: every enabled assignment whose market is open gets its latest closed bar evaluated.
     *
     * Deliberately sequential. A parallel fan-out over assignments would race on the portfolio cap
     * (each coroutine reading a live count that another is about to change) — the same class of bug
     * the spread scanner's per-slot reservation exists to prevent.
     */
    suspend fun runOnce() {
        if (!config.enabled) {
            logger.debug { "Stock strategies disabled — skipping run" }
            return
        }
        val enabled = assignments.findEnabled()
        if (enabled.isEmpty()) return

        val live = positions.findLive()
        if (live.size >= config.maxOpenPositions) {
            logger.info { "Stock strategies at capacity (${live.size}/${config.maxOpenPositions}) — skipping run" }
            return
        }
        var slots = config.maxOpenPositions - live.size

        logger.info { "Stock strategy run: ${enabled.size} enabled assignment(s), $slots slot(s) free" }
        for (assignment in enabled) {
            if (slots <= 0) {
                logger.info { "Portfolio cap reached mid-run — remaining assignments skipped" }
                break
            }
            runCatching { evaluate(assignment) }
                .onFailure { logger.error(it) { "[${assignment.symbol.value}] ${assignment.strategyId} evaluation failed: ${it.message}" } }
                .getOrNull()
                ?.let { slots-- }
        }
    }

    /** Returns the position when an entry was actually submitted, so the caller can count the slot. */
    private suspend fun evaluate(assignment: StrategyAssignment): StockPosition? {
        val symbol = assignment.symbol
        val template =
            strategies.find(assignment.strategyId) ?: run {
                logger.warn { "[${symbol.value}] assignment names unknown strategy '${assignment.strategyId}' — skipped" }
                return null
            }

        if (!universe.isMarketOpen(symbol)) return null

        // One live position per (strategy, symbol). A second entry on a name we already hold is
        // pyramiding, which none of these strategies were tested for.
        positions.findLiveFor(assignment.strategyId, symbol)?.let {
            logger.debug { "[${symbol.value}] ${assignment.strategyId} already live (${it.status}) — skipped" }
            return null
        }

        // Resolved through the shared tuning layer (descriptor defaults -> strategy_default_params
        // -> strategy_symbol_params) rather than from the assignment row, so a live run and the
        // Strategy Parameters screen can never disagree about what this symbol is trading.
        val resolved = paramsResolver.effectiveParams(assignment.strategyId, symbol.value, assignment.timeframe.label)
        template.validate(resolved)?.let {
            logger.warn { "[${symbol.value}] ${assignment.strategyId} params invalid: $it — skipped" }
            return null
        }
        val strategy = template.withParams(resolved, assignment.timeframe)

        // Warmup + a margin: the strategy declares how many bars it needs, and a signal computed on
        // a short series is not the signal the backtest produced.
        val needed = strategy.inputs.warmupBars + 2
        val to = Instant.now()
        val from = to.minus(Duration.ofDays(StrategyWarmup.calendarDays(needed, assignment.timeframe)))
        val bars = barStore.readBars(symbol, from, to, assignment.timeframe)
        if (bars.size < needed) {
            logger.warn { "[${symbol.value}] only ${bars.size} ${assignment.timeframe.label} bars, need $needed — skipped" }
            return null
        }

        val equity =
            effectiveAccount.detail()?.totalCapital?.amount ?: run {
                logger.info { "Account equity not yet available — run skipped" }
                return null
            }
        val liveCount = positions.findLive().size
        val decision =
            strategy.decide(
                StrategyContext(
                    symbol = symbol,
                    candle = bars.last(),
                    byTimeframe = mapOf(strategy.inputs.primary to bars.last()),
                    equity = equity,
                    openPositions = liveCount,
                    pendingPositions = 0,
                ),
            ) ?: return null

        // The plan's entry rule: a day limit at the open plus an ATR-scaled tolerance. Not a market
        // order — the Xetra opening auction is where the ETF spread is widest, and paying it every
        // trade is a material fraction of any edge. Not a stop entry either; see StockOrderPort.
        val signalPrice = decision.entryPrice
        val atr = AtrCalculator.atr(bars, resolved.intOrNull("atrPeriod") ?: DEFAULT_ATR_PERIOD)
        val tolerance =
            if (atr.isNaN()) {
                signalPrice.multiply(FALLBACK_TOLERANCE_PCT)
            } else {
                BigDecimal.valueOf(atr * config.limitToleranceAtrMultiple)
            }
        val limitPrice = signalPrice.add(tolerance).setScale(4, RoundingMode.HALF_UP)

        val ids = orders.reserveOrderIds()
        val position =
            StockPosition(
                id = UUID.randomUUID(),
                strategyId = assignment.strategyId,
                assignmentId = assignment.id,
                // Snapshot the resolved params: the assignment row may be edited before this closes.
                paramsJson = mapper.writeValueAsString(resolved.asMap()),
                symbol = symbol,
                timeframe = assignment.timeframe,
                status = StockPositionStatus.PENDING,
                entryOrderId = ids.entryOrderId,
                stopOrderId = ids.stopOrderId,
                targetOrderId = decision.profitTargetPrice?.let { ids.targetOrderId },
                signalPrice = signalPrice,
                limitPrice = limitPrice,
                stopPrice = decision.stopLossPrice,
                targetPrice = decision.profitTargetPrice,
                shares = decision.shares,
                riskAmount =
                    signalPrice
                        .subtract(decision.stopLossPrice)
                        .multiply(BigDecimal(decision.shares))
                        .setScale(2, RoundingMode.HALF_UP),
                signalledAt = Instant.now(),
            )

        // Persist BEFORE submitting. A row with no order is a recoverable inconsistency the
        // reconciler can close; an order with no row is an orphan nobody is watching.
        val saved = positions.save(position)
        orders.submitLimitEntryWithProtection(
            ids = ids,
            symbol = symbol,
            shares = decision.shares,
            limitPrice = limitPrice,
            stopPrice = decision.stopLossPrice,
            targetPrice = decision.profitTargetPrice,
        )
        logger.info {
            "[${symbol.value}] ${assignment.strategyId} ENTRY submitted: ${decision.shares} sh " +
                "limit=$limitPrice stop=${decision.stopLossPrice} target=${decision.profitTargetPrice ?: "none"} " +
                "position=${saved.id}"
        }
        return saved
    }

    private companion object {
        const val DEFAULT_ATR_PERIOD = 14
        val FALLBACK_TOLERANCE_PCT = BigDecimal("0.0025")
    }
}
