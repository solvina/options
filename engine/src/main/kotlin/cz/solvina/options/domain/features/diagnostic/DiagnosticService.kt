package cz.solvina.options.domain.features.diagnostic

import cz.solvina.options.domain.features.market.MarketDataPriority
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class DiagnosticService(
    private val probePort: DiagnosticProbePort,
    private val universePort: UniversePort,
    private val optionChainPort: OptionChainPort,
    private val clock: Clock,
) : DiagnosticPort {
    private val symbolReports = ConcurrentHashMap<Symbol, SymbolHealthReport>()

    @Volatile
    private var accountReport: AccountHealthReport? = null

    override suspend fun probeSymbol(symbol: Symbol): SymbolHealthReport =
        withContext(MarketDataPriority.SCANNER) {
            doProbeSymbol(symbol)
        }

    private suspend fun doProbeSymbol(symbol: Symbol): SymbolHealthReport {
        logger.info { "[$symbol] Starting diagnostic probe" }
        val errors = mutableListOf<String>()

        val contractResult =
            runCatching { probePort.probeContractResolution(symbol) }
                .onFailure { errors += "contractResolution: ${it.message}" }
                .getOrElse { SymbolHealthReport.ContractResolutionResult(null, 0, it.message) }

        val optionParamsResult =
            if (contractResult.stockConId != null) {
                runCatching { probePort.probeOptionParams(symbol, contractResult.stockConId) }
                    .onFailure { errors += "optionParams: ${it.message}" }
                    .getOrElse { SymbolHealthReport.OptionParamsResult(0, emptyList(), it.message) }
            } else {
                SymbolHealthReport.OptionParamsResult(0, emptyList(), "skipped — no conId")
            }

        val historicalResult =
            runCatching { probePort.probeHistoricalData(symbol) }
                .onFailure { errors += "historicalData: ${it.message}" }
                .getOrElse { SymbolHealthReport.HistoricalDataResult(0, false, null, null, it.message) }

        val spotResult =
            runCatching { probePort.probeSpot(symbol) }
                .onFailure { errors += "spot: ${it.message}" }
                .getOrElse { SymbolHealthReport.SpotResult(null, DataSource.UNAVAILABLE, 0, it.message) }

        // Pick nearest expiry ≥ 21 DTE and up to 5 strikes around ATM
        val optionSamples = mutableListOf<SymbolHealthReport.OptionMidSample>()
        var tickStreamResult: SymbolHealthReport.TickStreamResult? = null

        val spot = spotResult.price
        if (spot != null && spot > BigDecimal.ZERO) {
            runCatching {
                val expiry = pickExpiry(symbol, spot)
                if (expiry != null) {
                    val strikes = pickStrikes(symbol, expiry, spot)
                    for (contract in strikes) {
                        runCatching { probePort.probeOptionSnapshot(contract) }
                            .onSuccess { optionSamples += it }
                            .onFailure { errors += "optionSnapshot(${contract.strike}P): ${it.message}" }
                    }
                    // Tick stream: use ATM and next OTM strike
                    if (strikes.size >= 2) {
                        tickStreamResult =
                            runCatching {
                                probePort.probeTickStream(symbol, strikes[0], strikes[1])
                            }.onFailure { errors += "tickStream: ${it.message}" }.getOrNull()
                    }
                }
            }.onFailure { errors += "optionChain: ${it.message}" }
        }

        val report =
            SymbolHealthReport(
                symbol = symbol,
                probedAt = Instant.now(clock),
                contractResolution = contractResult,
                optionParams = optionParamsResult,
                historicalData = historicalResult,
                spot = spotResult,
                optionSamples = optionSamples,
                tickStream = tickStreamResult,
                errors = errors,
            )
        symbolReports[symbol] = report
        logger.info {
            "[$symbol] Diagnostic probe complete — errors=${errors.size} optionSamples=${optionSamples.size} tickStream=${tickStreamResult?.ticksReceived}"
        }
        return report
    }

    override suspend fun probeAccount(): AccountHealthReport =
        withContext(MarketDataPriority.SCANNER) {
            doProbeAccount()
        }

    private suspend fun doProbeAccount(): AccountHealthReport {
        logger.info { "Starting account diagnostic probe" }
        val report =
            runCatching { probePort.probeAccount() }
                .getOrElse {
                    AccountHealthReport(
                        probedAt = Instant.now(clock),
                        netLiquidation = null,
                        availableFunds = null,
                        accountError = it.message,
                        positionCount = 0,
                        positionsError = null,
                        openOrderCount = 0,
                        openOrdersError = null,
                    )
                }
        accountReport = report
        return report
    }

    override fun latestSymbolReports(): List<SymbolHealthReport> =
        symbolReports.values
            .toList()
            .sortedBy { it.symbol.value }

    override fun latestAccountReport(): AccountHealthReport? = accountReport

    override fun watchlistSymbols(): List<Symbol> = universePort.getWatchlist()

    private suspend fun pickExpiry(
        symbol: Symbol,
        spot: BigDecimal,
    ): LocalDate? {
        val expiries =
            runCatching { optionChainPort.getAvailableExpirations(symbol) }.getOrNull()
                ?: return null
        val today = LocalDate.now(clock)
        return expiries.filter { ChronoUnit.DAYS.between(today, it) >= 21 }.minOrNull()
    }

    private suspend fun pickStrikes(
        symbol: Symbol,
        expiry: LocalDate,
        spot: BigDecimal,
    ): List<OptionContract> {
        val chain =
            runCatching {
                optionChainPort.getOptionChain(symbol, expiry, Money(spot), StrategyId.BULL_PUT)
            }.getOrNull() ?: return emptyList()

        val atm = chain.minByOrNull { (it.contract.strike - spot).abs() } ?: return emptyList()
        val atmStrike = atm.contract.strike

        return chain
            .filter { it.contract.strike <= atmStrike }
            .sortedByDescending { it.contract.strike }
            .take(5)
            .map { it.contract }
    }
}
