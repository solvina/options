package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.features.execution.TradeExecutionPort
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPriority
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.regime.DirectionalBias
import cz.solvina.options.domain.features.regime.TrendRegimeService
import cz.solvina.options.domain.features.regime.alignment
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("TRADES")

@Service
class ScannerService(
    private val universePort: UniversePort,
    private val bullPutSelector: BullPutCandidateSelector,
    private val bearCallSelector: BearCallCandidateSelector,
    private val bearCallConfig: BearCallScannerConfig,
    private val effectiveAccount: EffectiveAccountService,
    private val executionPort: TradeExecutionPort,
    private val spreadQuery: SpreadQueryFacade,
    private val config: ScannerConfig,
    private val clock: Clock,
    // Read-only: supplies per-symbol greek-delivery coverage for the scan-status table.
    private val optionChainPort: OptionChainPort,
    // Observational: per-symbol status of the latest scan pass, surfaced by the UI table.
    private val scanStatusRegistry: ScanStatusRegistry = ScanStatusRegistry(),
    // Observe-only: logs each symbol's trend regime alongside scan decisions; does NOT gate trading.
    private val regimeService: TrendRegimeService? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ScannerPort {
    private val ivRanksSnapshot = ConcurrentHashMap<String, Double>()

    @Volatile private var lastRunAt: Instant? = null

    // SCANNER priority: every market-data request below (chains, greeks snapshots, prices) draws
    // from the leftover line budget and yields message headroom — never from exec/exit/flag reserves.
    override suspend fun scan(): Unit =
        withContext(MarketDataPriority.SCANNER) {
            runScan()
        }

    private suspend fun runScan() {
        lastRunAt = Instant.now(clock)
        logger.info { "Scanner run started" }

        // Count every non-terminal spread toward the cap. PENDING (in-flight orders) and CLOSING
        // both still consume risk budget; counting OPEN alone let concurrent in-flight entries
        // blow past maxOpenSpreads before any of them flipped to OPEN. This is a coarse early-out;
        // the authoritative per-slot reservation happens atomically in TradeExecutionService.execute.
        val activeCount = spreadQuery.activeSpreadCount()
        if (activeCount >= config.maxOpenSpreads) {
            logger.info { "Max active spreads reached ($activeCount/${config.maxOpenSpreads}), skipping scan" }
            return
        }

        // Effective (possibly capped) account size — see EffectiveAccountService. All spread sizing
        // downstream (allowedRiskPerTrade = capital × maxRiskPercent) runs off this.
        val accountDetail =
            effectiveAccount.detail() ?: run {
                logger.info { "Account detail not yet received, skipping scan" }
                return
            }
        val totalCapital =
            accountDetail.totalCapital ?: run {
                logger.info { "Net liquidation not yet available, skipping scan" }
                return
            }
        val symbolsWithOpenSpread = spreadQuery.symbolsWithActiveSpread()

        // Fresh run: the status table only ever shows the symbols evaluated in this pass.
        val runId = scanStatusRegistry.beginRun()

        val watchlist = universePort.getActiveSymbols()
        for (symbol in watchlist) {
            if (symbolsWithOpenSpread.contains(symbol) || executionPort.isInFlight(symbol)) {
                logger.debug { "[$symbol] Already has open or in-flight spread, skipping" }
                recordStatus(symbol, runId, ScanOutcome.ALREADY_OPEN, null, null, null)
                continue
            }
            if (executionPort.isCoolingDown(symbol)) {
                logger.debug { "[$symbol] Entry cooldown active, skipping" }
                recordStatus(symbol, runId, ScanOutcome.COOLDOWN, null, null, null)
                continue
            }
            runCatching { scanSymbol(symbol, totalCapital, runId) }
                .onFailure { e ->
                    logger.error(e) { "[$symbol] Error during scan: ${e.message}" }
                    recordStatus(symbol, runId, ScanOutcome.ERROR, null, null, null)
                }
        }

        logger.info { "Scanner run complete" }
    }

    private suspend fun scanSymbol(
        symbol: Symbol,
        totalCapital: Money,
        runId: Long,
    ) {
        // Directional gate: when regime gating is on, the symbol's trend+RSI bias decides which
        // strategy is eligible, so bull put and bear call each fire on their own signal instead of
        // bull put always winning by position. BULLISH → bull put only; BEARISH → bear call only;
        // NEUTRAL (or gating off / regime unavailable) → both eligible, bull-put-first fallback.
        val gating = regimeService?.gatingEnabled() == true
        val bias = if (gating) regimeService!!.biasFor(symbol) else null
        val bullAllowed = bias == null || bias == DirectionalBias.BULLISH || bias == DirectionalBias.NEUTRAL
        val bearAllowed = bias == null || bias == DirectionalBias.BEARISH || bias == DirectionalBias.NEUTRAL

        // Terminal result of the strategy that actually ran to a decision — used for the status row.
        var lastResult: CandidateResult? = null
        var lastStrategy: StrategyId? = null

        // At most one entry per symbol per scan (the cross-strategy dedup in scan() already prevents a
        // second entry on a symbol that holds any open/in-flight spread).
        if (bullAllowed) {
            val result = bullPutSelector.select(symbol, totalCapital)
            lastResult = result
            lastStrategy = StrategyId.BULL_PUT
            if (result is CandidateResult.Selected) {
                logRegimeAtDecision(symbol, "BULL_PUT", DirectionalBias.BULLISH)
                launchEntry(symbol, result.request)
                recordStatus(symbol, runId, ScanOutcome.ENTERED, StrategyId.BULL_PUT, null, result.detail, bias)
                return
            }
        } else {
            logGateSuppressed(symbol, "BULL_PUT", bias)
        }
        if (bearAllowed && bearCallConfig.enabled) {
            val result = bearCallSelector.select(symbol, totalCapital)
            lastResult = result
            lastStrategy = StrategyId.BEAR_CALL
            if (result is CandidateResult.Selected) {
                logRegimeAtDecision(symbol, "BEAR_CALL", DirectionalBias.BEARISH)
                launchEntry(symbol, result.request)
                recordStatus(symbol, runId, ScanOutcome.ENTERED, StrategyId.BEAR_CALL, null, result.detail, bias)
                return
            }
        } else if (!bearAllowed && bearCallConfig.enabled) {
            logGateSuppressed(symbol, "BEAR_CALL", bias)
        }

        // No entry. Record the terminal rejection, or GATE_SUPPRESSED when the gate left nothing to run.
        when (val result = lastResult) {
            is CandidateResult.Rejected ->
                recordStatus(symbol, runId, ScanOutcome.REJECTED, lastStrategy, result.reason, result.detail, bias)
            else ->
                recordStatus(symbol, runId, ScanOutcome.GATE_SUPPRESSED, null, null, null, bias)
        }
    }

    /**
     * Builds and stores one status row, merging the selector's [detail] with the cached directional
     * regime and the option-chain greek coverage. Coverage is only attached when a chain was actually
     * fetched this evaluation (detail has an expiry), so IV-gated rows don't show stale coverage.
     */
    private fun recordStatus(
        symbol: Symbol,
        runId: Long,
        outcome: ScanOutcome,
        strategyId: StrategyId?,
        rejectReason: RejectReason?,
        detail: ScanDetail?,
        bias: DirectionalBias? = null,
    ) {
        val rs = regimeService?.regimeFor(symbol)
        val coverage = if (detail?.expiry != null) optionChainPort.lastCoverage(symbol) else null
        scanStatusRegistry.record(
            SymbolScanStatus(
                symbol = symbol.value,
                runId = runId,
                evaluatedAt = Instant.now(clock),
                outcome = outcome,
                strategyId = strategyId,
                rejectReason = rejectReason,
                detail = detail,
                regime = rs?.regime,
                rsi = rs?.rsi?.toDouble(),
                bias = bias ?: rs?.bias,
                strikesRequested = coverage?.strikesRequested,
                strikesWithGreeks = coverage?.strikesWithGreeks,
            ),
        )
    }

    fun getScanStatus(): List<SymbolScanStatus> = scanStatusRegistry.snapshot()

    /**
     * Logs the cached market regime alongside a trade decision, flagged aligned vs OPPOSITE to the
     * strategy's directional bias, with the RSI and combined bias. Cache read only — no fetch.
     */
    private fun logRegimeAtDecision(
        symbol: Symbol,
        strategy: String,
        bias: DirectionalBias,
    ) {
        val rs = regimeService?.regimeFor(symbol) ?: return
        tradeLogger.info {
            "REGIME $symbol  $strategy candidate  market=${rs.regime} (${alignment(rs.regime, bias)})  " +
                "rsi=${rs.rsi}  bias=${rs.bias}  close=${rs.lastClose}"
        }
    }

    /** Logs a strategy the directional gate suppressed for this symbol (observability of the gate). */
    private fun logGateSuppressed(
        symbol: Symbol,
        strategy: String,
        bias: DirectionalBias?,
    ) {
        if (bias == null) return // gating off — nothing was suppressed
        tradeLogger.info { "GATE   $symbol  $strategy suppressed by bias=$bias" }
    }

    private fun launchEntry(
        symbol: Symbol,
        request: TradeExecutionRequest,
    ) {
        ivRanksSnapshot[symbol.value] = request.ivRankAtEntry
        scope.launch { executionPort.execute(request) }
    }

    fun getLastRunAt(): Instant? = lastRunAt

    fun getIvRanksSnapshot(): Map<String, Double> = ivRanksSnapshot.toMap()
}
