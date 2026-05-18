package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.execution.TradeExecutionPort
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.SpreadStatus
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
    private val candidateSelector: ScanCandidateSelector,
    private val accountPort: AccountPort,
    private val executionPort: TradeExecutionPort,
    private val spreadPort: SpreadPort,
    private val config: ScannerConfig,
    private val clock: Clock,
) : ScannerPort {
    private val ivRanksSnapshot = ConcurrentHashMap<String, Double>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastRunAt: Instant? = null

    override suspend fun scan() {
        lastRunAt = Instant.now(clock)
        logger.info { "Scanner run started" }

        val openCount = spreadPort.countByStatus(SpreadStatus.OPEN)
        if (openCount >= config.maxOpenSpreads) {
            logger.info { "Max open spreads reached ($openCount/${config.maxOpenSpreads}), skipping scan" }
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
        val openSpreads =
            spreadPort.findOpen() +
                spreadPort.findByStatus(SpreadStatus.PENDING) +
                spreadPort.findByStatus(SpreadStatus.CLOSING)
        val symbolsWithOpenSpread = openSpreads.map { it.symbol }.toSet()

        val watchlist = universePort.getActiveSymbols()
        for (symbol in watchlist) {
            if (symbolsWithOpenSpread.contains(symbol) || executionPort.isInFlight(symbol)) {
                logger.debug { "[$symbol] Already has open or in-flight spread, skipping" }
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
        val request = candidateSelector.select(symbol, totalCapital) ?: return
        ivRanksSnapshot[symbol.value] = request.ivRankAtEntry
        scope.launch { executionPort.execute(request) }
    }

    fun getLastRunAt(): Instant? = lastRunAt

    fun getIvRanksSnapshot(): Map<String, Double> = ivRanksSnapshot.toMap()
}
