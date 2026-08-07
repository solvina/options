package cz.solvina.options.domain.features.strategy.live

import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.features.bars.AtrCalculator
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.strategy.StrategyContext
import cz.solvina.options.domain.features.strategy.StrategyParams
import cz.solvina.options.domain.features.strategy.StrategyRegistry
import cz.solvina.options.domain.features.strategy.StrategyWarmup
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignment
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignmentPort
import cz.solvina.options.domain.features.strategy.tuning.StrategyParamsResolver
import cz.solvina.options.domain.features.universe.DaySession
import cz.solvina.options.domain.features.universe.MarketCalendarPort
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
    private val lifecycle: StockPositionLifecycleService,
    private val calendar: MarketCalendarPort,
    private val clock: Clock,
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
        if (isStale(symbol, bars.last().time, assignment.timeframe)) return null

        val equity =
            effectiveAccount.detail()?.totalCapital?.amount ?: run {
                logger.info { "Account equity not yet available — run skipped" }
                return null
            }
        // Warm the indicators on everything except the bar being decided, then decide on that bar —
        // the same two steps StrategyBacktestAdapter performs (initialize, then onBar per bar), so
        // the two hosts feed an identical series into identical state.
        //
        // Without this the strategy sees exactly one bar. Every strategy in the library carries
        // rolling state (MFI, RSI, ATR), all of which report "not ready" until they have their full
        // period, so `decide` returned null on every symbol, every pass — the live host could not
        // have produced a single entry.
        strategy.warmup(symbol, mapOf(strategy.inputs.primary to bars.dropLast(1)))

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

        // A day limit, not a market order — the opening auction is where the spread is widest, and
        // paying it every trade is a material fraction of any edge. Not a stop entry either; see
        // StockOrderPort.
        //
        // The tolerance ABOVE the strategy's own entry price defaults to zero, and a strategy that
        // wants one owns it as a parameter (MfiOversoldStrategy.limitToleranceAtr). This host must
        // not widen a limit on its own: a Decision carries absolute stop and target prices fixed off
        // entryPrice which do not follow the fill, so a wider limit leaves the stop further from the
        // fill than the strategy intended and the target nearer — measured at 25% -> 15% win rate on
        // MFI over 25 symbols (commit cc3a7b2). Any non-zero value here re-introduces exactly the
        // divergence between this host and the backtest that the twin design exists to prevent.
        val signalPrice = decision.entryPrice
        val limitPrice = signalPrice.add(entryTolerance(bars, resolved, signalPrice)).setScale(4, RoundingMode.HALF_UP)

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
        // Register BEFORE submitting too: an entry that fills the instant it lands would otherwise
        // report its fill into a lifecycle service that is not yet listening for these order ids.
        lifecycle.register(saved)
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

    /**
     * Zero unless [StockStrategyConfig.limitToleranceAtrMultiple] is deliberately raised — including
     * when ATR is unavailable, where a percentage fallback would otherwise smuggle in a tolerance
     * the operator did not ask for.
     */
    private fun entryTolerance(
        bars: List<Candle>,
        resolved: StrategyParams,
        signalPrice: BigDecimal,
    ): BigDecimal {
        if (config.limitToleranceAtrMultiple <= 0.0) return BigDecimal.ZERO
        val atr = AtrCalculator.atr(bars, resolved.intOrNull("atrPeriod") ?: DEFAULT_ATR_PERIOD)
        return if (atr.isNaN()) {
            signalPrice.multiply(FALLBACK_TOLERANCE_PCT)
        } else {
            BigDecimal.valueOf(atr * config.limitToleranceAtrMultiple)
        }
    }

    /**
     * True when the newest stored bar predates the session the decision should be based on.
     *
     * The bar *count* check above says nothing about recency: a store holding a full warmup window
     * that stopped updating three weeks ago passes it, and the runner would then compute a signal
     * off a stale candle and rest a real limit order against today's market. That is not a
     * hypothetical — on 2026-08-07 the paper host's newest daily bars were from 2026-07-21, because
     * nothing refreshed them until [cz.solvina.options.domain.features.bars.DailyBarRefreshService]
     * existed.
     *
     * The bar's session date is its instant read in the exchange's own timezone (a US daily bar is
     * stamped at the close, 22:00 CEST = 16:00 ET the same day). It must be no older than the last
     * completed session; the broker calendar supplies holidays and half-days, and when it is cold a
     * weekday walk is the fallback — coarser, but it still catches a store that is days behind.
     */
    private fun isStale(
        symbol: Symbol,
        lastBarTime: Instant,
        timeframe: Timeframe,
    ): Boolean {
        if (timeframe != Timeframe.DAILY) return false // intraday freshness is the bar feed's own concern
        val zone = universe.getMarketSchedule(symbol).zone
        val today = Instant.now(clock).atZone(zone).toLocalDate()
        val barDate = lastBarTime.atZone(zone).toLocalDate()
        val lastSession = lastCompletedSession(symbol, today)
        if (!barDate.isBefore(lastSession)) return false
        logger.warn {
            "[${symbol.value}] newest ${timeframe.label} bar is $barDate but the last completed session was " +
                "$lastSession — refusing to trade a stale signal"
        }
        return true
    }

    /**
     * The most recent session that has already closed, walking back from yesterday. Today never
     * counts: this host runs during the session, so today's daily bar does not exist yet.
     */
    private fun lastCompletedSession(
        symbol: Symbol,
        today: LocalDate,
    ): LocalDate {
        var day = today.minusDays(1)
        repeat(MAX_SESSION_LOOKBACK_DAYS) {
            when (calendar.sessionFor(symbol, day)) {
                is DaySession.Open -> return day
                DaySession.Closed -> day = day.minusDays(1)
                // Calendar cold: fall back to the coarse weekday rule for this day.
                null -> if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY) return day else day = day.minusDays(1)
            }
        }
        return today.minusDays(MAX_SESSION_LOOKBACK_DAYS.toLong())
    }

    private companion object {
        const val DEFAULT_ATR_PERIOD = 14
        val FALLBACK_TOLERANCE_PCT = BigDecimal("0.0025")

        /** Longest run of closed days worth walking back — comfortably past any holiday cluster. */
        const val MAX_SESSION_LOOKBACK_DAYS = 10
    }
}
