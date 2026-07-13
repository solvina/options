package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.features.execution.TradeExecutionPort
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPriority
import cz.solvina.options.domain.features.regime.DirectionalBias
import cz.solvina.options.domain.features.regime.TrendRegimeService
import cz.solvina.options.domain.features.regime.alignment
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
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

        val watchlist = universePort.getActiveSymbols()
        for (symbol in watchlist) {
            if (symbolsWithOpenSpread.contains(symbol) || executionPort.isInFlight(symbol)) {
                logger.debug { "[$symbol] Already has open or in-flight spread, skipping" }
                continue
            }
            if (executionPort.isCoolingDown(symbol)) {
                logger.debug { "[$symbol] Entry cooldown active, skipping" }
                continue
            }
            runCatching { scanSymbol(symbol, totalCapital) }
                .onFailure { e -> logger.error(e) { "[$symbol] Error during scan: ${e.message}" } }
        }

        logger.info { "Scanner run complete" }
    }

    private suspend fun scanSymbol(
        symbol: Symbol,
        totalCapital: Money,
    ) {
        // Directional gate: when regime gating is on, the symbol's trend+RSI bias decides which
        // strategy is eligible, so bull put and bear call each fire on their own signal instead of
        // bull put always winning by position. BULLISH → bull put only; BEARISH → bear call only;
        // NEUTRAL (or gating off / regime unavailable) → both eligible, bull-put-first fallback.
        val gating = regimeService?.gatingEnabled() == true
        val bias = if (gating) regimeService!!.biasFor(symbol) else null
        val bullAllowed = bias == null || bias == DirectionalBias.BULLISH || bias == DirectionalBias.NEUTRAL
        val bearAllowed = bias == null || bias == DirectionalBias.BEARISH || bias == DirectionalBias.NEUTRAL

        // At most one entry per symbol per scan (the cross-strategy dedup in scan() already prevents a
        // second entry on a symbol that holds any open/in-flight spread).
        if (bullAllowed) {
            bullPutSelector.select(symbol, totalCapital)?.let {
                logRegimeAtDecision(symbol, "BULL_PUT", DirectionalBias.BULLISH)
                launchEntry(symbol, it)
                return
            }
        } else {
            logGateSuppressed(symbol, "BULL_PUT", bias)
        }
        if (bearAllowed && bearCallConfig.enabled) {
            bearCallSelector.select(symbol, totalCapital)?.let {
                logRegimeAtDecision(symbol, "BEAR_CALL", DirectionalBias.BEARISH)
                launchEntry(symbol, it)
                return
            }
        } else if (!bearAllowed && bearCallConfig.enabled) {
            logGateSuppressed(symbol, "BEAR_CALL", bias)
        }
    }

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
