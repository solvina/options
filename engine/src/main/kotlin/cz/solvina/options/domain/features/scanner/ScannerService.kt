package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.features.aireview.CandidateReviewPort
import cz.solvina.options.domain.features.execution.TradeExecutionPort
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.notification.OpportunityNotificationPort
import cz.solvina.options.domain.features.regime.DirectionalBias
import cz.solvina.options.domain.features.regime.TrendRegimeService
import cz.solvina.options.domain.features.regime.alignment
import cz.solvina.options.domain.features.shadow.ShadowCandidate
import cz.solvina.options.domain.features.shadow.ShadowSpreadService
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private val regimeService: TrendRegimeService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    // Collect-then-choose (see ScannerPoolConfig). Null or disabled = enter as soon as qualified.
    private val poolConfig: ScannerPoolConfig = ScannerPoolConfig(),
    private val pool: DailyCandidatePool? = null,
    private val shadowSpreads: ShadowSpreadService? = null,
    // AI review of each title the first time it qualifies in a day (fire-and-forget, advisory).
    private val candidateReview: CandidateReviewPort = CandidateReviewPort.NoOp,
    /** Candidate email when the title is entered directly (no pool); pooled titles go through [candidateReview]. */
    private val opportunityNotifier: OpportunityNotificationPort = OpportunityNotificationPort.NoOp,
    /** Snapshot-wait measurements, logged with each run's summary (observe-only). */
    private val scanTelemetry: ScanTelemetryPort = ScanTelemetryPort.NoOp,
) : ScannerPort {
    private val ivRanksSnapshot = ConcurrentHashMap<String, Double>()

    // Completion order of each symbol's last evaluation (higher = more recent). In-memory: after a
    // restart the first run uses universe order, later runs rotate from there.
    private val scanSequence = ConcurrentHashMap<Symbol, Long>()
    private val scanCounter = AtomicLong()

    // A decision must not interleave with a scan: both walk symbols and may launch entries.
    private val passMutex = Mutex()

    private val poolActive: Boolean get() = poolConfig.enabled && pool != null

    @Volatile private var lastRunAt: Instant? = null

    override suspend fun scan() = passMutex.withLock { scanPass() }

    private suspend fun scanPass() {
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

        // Least-recently-scanned first. A US-session run can hit its time limit before the end of
        // the list; a fixed order then starves the same tail forever. Symbols a run never reached
        // (or never scanned since startup) sort first and keep universe order among themselves.
        val watchlist = universePort.getActiveSymbols().sortedBy { scanSequence[it] ?: -1L }
        logger.debug { "Scan order: ${watchlist.joinToString { it.value }}" }
        val started = System.nanoTime()
        var completed = false
        scanTelemetry.drain() // start the run's measurement window clean
        try {
            for (symbol in watchlist) {
                scanWatchlistSymbol(symbol, symbolsWithOpenSpread, totalCapital, runId)
                scanSequence[symbol] = scanCounter.incrementAndGet()
            }
            completed = true
            logger.info { "Scanner run complete" }
        } finally {
            logRunSummary(started, completed, watchlist.size)
        }
    }

    /**
     * One greppable SCAN_SUMMARY line per run, also for runs cancelled at the time limit: duration,
     * coverage, outcome and reject-reason counts, and the snapshot-wait measurements. The baseline
     * for judging any change to how long market-data requests wait.
     */
    private fun logRunSummary(
        startedNanos: Long,
        completed: Boolean,
        total: Int,
    ) {
        runCatching {
            val rows = scanStatusRegistry.snapshot()
            val outcomes = rows.groupingBy { it.outcome.name }.eachCount().toSortedMap()
            val rejects =
                rows
                    .mapNotNull { it.rejectReason?.name }
                    .groupingBy { it }
                    .eachCount()
                    .toSortedMap()
            val secs = (System.nanoTime() - startedNanos) / 1_000_000_000L
            logger.info {
                "SCAN_SUMMARY ${if (completed) "complete" else "CUT"} ${secs}s scanned=${rows.size}/$total " +
                    "outcomes=$outcomes rejects=$rejects wait: ${scanTelemetry.drain()}"
            }
        }.onFailure { logger.warn { "Scan summary failed: ${it.message}" } }
    }

    private suspend fun scanWatchlistSymbol(
        symbol: Symbol,
        symbolsWithOpenSpread: Set<Symbol>,
        totalCapital: Money,
        runId: Long,
    ) {
        if (symbolsWithOpenSpread.contains(symbol) || executionPort.isInFlight(symbol)) {
            logger.debug { "[$symbol] Already has open or in-flight spread, skipping" }
            recordStatus(symbol, runId, ScanOutcome.ALREADY_OPEN, null, null, null)
            return
        }
        if (executionPort.isCoolingDown(symbol)) {
            logger.debug { "[$symbol] Entry cooldown active, skipping" }
            recordStatus(symbol, runId, ScanOutcome.COOLDOWN, null, null, null)
            return
        }
        if (poolActive && pool?.contains(symbol) == true) {
            // Already waiting for the decision, which re-prices it; no need to evaluate it again.
            recordStatus(symbol, runId, ScanOutcome.POOLED, null, null, null)
            return
        }
        runCatching {
            scanSymbol(symbol, totalCapital, runId) { selected ->
                if (poolActive) {
                    pool?.offer(symbol)
                    // First qualification today (pooled titles are skipped above): ask for a review,
                    // which also owns the candidate email (sent with the verdict, or straight away
                    // when no review is possible). Re-offers after a restart or an unfilled entry
                    // are absorbed by the task's unique key. The decision re-prices but never emails.
                    selected.opportunity?.let { candidateReview.requestReview(it) }
                    // The same moment becomes a would-be trade of its own, followed whatever the
                    // verdict or the decision later does: the AI is judged on the trade it vetoed.
                    shadowSpreads?.let { shadows ->
                        val score = selected.qualityScore()
                        scope.launch {
                            runCatching { shadows.recordCandidate(selected.request, score, Instant.now(clock)) }
                                .onFailure { logger.warn { "[$symbol] candidate would-be trade not recorded: ${it.message}" } }
                        }
                    }
                    ivRanksSnapshot[symbol.value] = selected.request.ivRankAtEntry
                    ScanOutcome.POOLED
                } else {
                    selected.opportunity?.let { o -> scope.launch { opportunityNotifier.notify(o) } }
                    launchEntry(symbol, selected.request)
                    ScanOutcome.ENTERED
                }
            }
        }.onFailure { e ->
            // The run's own timeout cancelled us: stop here instead of stamping every remaining
            // symbol as an ERROR (and as scanned) — they lead the next run instead. A timeout that
            // leaked out of a selector (our job still active) stays a per-symbol error.
            currentCoroutineContext().ensureActive()
            logger.error(e) { "[$symbol] Error during scan: ${e.message}" }
            recordStatus(
                symbol,
                runId,
                ScanOutcome.ERROR,
                null,
                null,
                null,
                errorMessage = "${e::class.simpleName}: ${e.message}",
            )
        }
    }

    /**
     * Re-prices every pooled title with fresh quotes (the selectors run again, so one that stopped
     * qualifying drops out), ranks them, records each as a would-be position with its rank and score,
     * and enters the best up to [ScannerPoolConfig.maxEntriesPerDecision].
     */
    suspend fun decidePool(finalOfDay: Boolean) =
        passMutex.withLock {
            val candidatePool = pool?.takeIf { poolConfig.enabled } ?: return@withLock
            val totalCapital =
                effectiveAccount.detail()?.totalCapital ?: run {
                    logger.warn { "Pool decision skipped: account size not available" }
                    return@withLock
                }
            if (candidatePool.symbols().isEmpty()) {
                tradeLogger.info { "POOL   decision: no titles waiting" }
                return@withLock
            }
            val active = spreadQuery.symbolsWithActiveSpread()
            val runId = scanStatusRegistry.beginRun()
            val ranked = mutableListOf<ShadowCandidate>()
            for (symbol in candidatePool.symbols()) {
                if (active.contains(symbol) || executionPort.isInFlight(symbol) || executionPort.isCoolingDown(symbol)) continue
                runCatching {
                    scanSymbol(symbol, totalCapital, runId) { selected ->
                        ranked += selected.toCandidate(symbol, candidatePool.firstSeenAt(symbol))
                        ScanOutcome.POOLED
                    }
                }.onFailure { e -> logger.warn { "[$symbol] pool decision evaluation failed: ${e.message}" } }
            }
            ranked.sortByDescending { it.score }
            tradeLogger.info {
                "POOL   decision of ${candidatePool.symbols().size} title(s), ${ranked.size} still qualify: " +
                    ranked.joinToString {
                        "${it.request.underlyingSymbol} ${"%.3f".format(it.score)}" +
                            if (it.flags.isEmpty()) "" else " [${it.flags.joinToString("/") { f -> f.label }}]"
                    }
            }
            runCatching { shadowSpreads?.recordDecision(ranked) }
                .onFailure { e -> logger.warn { "Pool decision not recorded as would-be trades: ${e.message}" } }
            ranked.filter { it.flags.isEmpty() }.take(poolConfig.maxEntriesPerDecision).forEach {
                launchEntry(it.request.underlyingSymbol, it.request)
                candidatePool.remove(it.request.underlyingSymbol)
            }
            if (finalOfDay) candidatePool.clear()
        }

    private fun CandidateResult.Selected.qualityScore(): Double {
        val bidPct = SpreadSelectionRules.comboBidPctOfMid(detail.bidCredit ?: BigDecimal.ZERO, request.targetCredit) ?: 0.0
        return SpreadSelectionRules.qualityScore(detail.creditPctOfWidth ?: 0.0, bidPct)
    }

    private fun CandidateResult.Selected.toCandidate(
        symbol: Symbol,
        firstSeenAt: Instant?,
    ): ShadowCandidate =
        ShadowCandidate(
            request = request,
            score = qualityScore(),
            firstSeenAt = firstSeenAt ?: Instant.now(clock),
            flags = CandidateFlags.of(request.strategyId, request.ivRankAtEntry, regimeService.regimeFor(symbol), poolConfig),
        ).also { logger.debug { "[$symbol] pool score ${it.score}" } }

    private suspend fun scanSymbol(
        symbol: Symbol,
        totalCapital: Money,
        runId: Long,
        onSelected: (CandidateResult.Selected) -> ScanOutcome,
    ) {
        // Directional gate: when regime gating is on, the symbol's trend+RSI bias decides which
        // strategy is eligible, so bull put and bear call each fire on their own signal instead of
        // bull put always winning by position. BULLISH → bull put only; BEARISH → bear call only;
        // NEUTRAL (or gating off / regime unavailable) → both are eligible, bull-put-first fallback.
        val gating = regimeService.gatingEnabled()
        val bias = if (gating) regimeService.biasFor(symbol) else null
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
                recordStatus(symbol, runId, onSelected(result), StrategyId.BULL_PUT, null, result.detail, bias)
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
                recordStatus(symbol, runId, onSelected(result), StrategyId.BEAR_CALL, null, result.detail, bias)
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
     * fetched this evaluation (detail has expiry), so IV-gated rows don't show stale coverage.
     */
    private fun recordStatus(
        symbol: Symbol,
        runId: Long,
        outcome: ScanOutcome,
        strategyId: StrategyId?,
        rejectReason: RejectReason?,
        detail: ScanDetail?,
        bias: DirectionalBias? = null,
        errorMessage: String? = null,
    ) {
        val rs = regimeService.regimeFor(symbol)
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
                regime = rs.regime,
                rsi = rs.rsi?.toDouble(),
                bias = bias ?: rs.bias,
                strikesRequested = coverage?.strikesRequested,
                strikesWithGreeks = coverage?.strikesWithGreeks,
                errorMessage = errorMessage,
            ),
        )
    }

    fun getScanStatus(): List<SymbolScanStatus> = scanStatusRegistry.snapshot()

    /**
     * Logs the cached market regime alongside a trade decision, flagged aligned errorDuringvs OPPOSITE to the
     * strategy's directional bias, with the RSI and combined bias. Cache read-only — no fetch.
     */
    private fun logRegimeAtDecision(
        symbol: Symbol,
        strategy: String,
        bias: DirectionalBias,
    ) {
        val rs = regimeService.regimeFor(symbol)
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
