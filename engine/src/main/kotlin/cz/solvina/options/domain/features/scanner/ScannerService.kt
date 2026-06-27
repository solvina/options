package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.execution.TradeExecutionPort
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class ScannerService(
    private val universePort: UniversePort,
    private val bullPutSelector: BullPutCandidateSelector,
    private val bearCallSelector: BearCallCandidateSelector,
    private val bearCallConfig: BearCallScannerConfig,
    private val accountPort: AccountPort,
    private val executionPort: TradeExecutionPort,
    private val spreadQuery: SpreadQueryFacade,
    private val config: ScannerConfig,
    private val clock: Clock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ScannerPort {
    private val ivRanksSnapshot = ConcurrentHashMap<String, Double>()

    @Volatile private var lastRunAt: Instant? = null

    override suspend fun scan() {
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

        val accountDetail =
            accountPort.accountDetail.value ?: run {
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
        // Bull put has priority; at most one entry per symbol per scan (the cross-strategy dedup in
        // scan() already prevents a second entry on a symbol that holds any open/in-flight spread).
        bullPutSelector.select(symbol, totalCapital)?.let {
            launchEntry(symbol, it)
            return
        }
        if (bearCallConfig.enabled) {
            bearCallSelector.select(symbol, totalCapital)?.let { launchEntry(symbol, it) }
        }
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
