package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.regime.RegimeConfig
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerPort
import cz.solvina.options.domain.features.scanner.ScannerService
import cz.solvina.options.domain.features.scanner.StrategyParams
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.scanner.SymbolScanStatus
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.UniversePort
import `cz.solvina.options.spreads`.api.MonitorApi
import `cz.solvina.options.spreads`.api.ScannerApi
import `cz.solvina.options.spreads`.dto.PortfolioRulesDto
import `cz.solvina.options.spreads`.dto.ScannerStatusDto
import `cz.solvina.options.spreads`.dto.SpreadRulesDto
import `cz.solvina.options.spreads`.dto.StrategyRulesDto
import `cz.solvina.options.spreads`.dto.SymbolRuleOverrideDto
import `cz.solvina.options.spreads`.dto.SymbolScanStatusDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping
class ScannerApiImpl(
    private val scannerPort: ScannerPort,
    private val scannerService: ScannerService,
    private val spreadQuery: SpreadQueryFacade,
    private val killSwitch: TradingKillSwitch,
    private val scannerConfig: ScannerConfig,
    private val strategyParams: StrategyParamsRegistry,
    private val bearCallConfig: BearCallScannerConfig,
    private val regimeConfig: RegimeConfig,
    private val universePort: UniversePort,
) : ScannerApi,
    MonitorApi {
    private val backgroundScope = CoroutineScope(Dispatchers.Default)

    override suspend fun triggerScan(): ResponseEntity<Unit> {
        logger.info { "Manual scanner run requested" }
        backgroundScope.launch {
            runCatching { scannerPort.scan() }
                .onFailure { e -> logger.error(e) { "Manual scanner run failed: ${e.message}" } }
        }
        return ResponseEntity.accepted().build()
    }

    override suspend fun getScannerStatus(): ResponseEntity<ScannerStatusDto> {
        // Match the scanner's own cap gate (ScannerService uses activeSpreadCount = PENDING+OPEN+CLOSING,
        // across bull-put + bear-call) so the "X / maxOpenSpreads" readout reflects what actually throttles entries.
        val openCount = spreadQuery.activeSpreadCount()
        val dto =
            ScannerStatusDto(
                lastRunAt = scannerService.getLastRunAt()?.atOffset(ZoneOffset.UTC),
                openSpreadCount = openCount,
                maxOpenSpreads = scannerConfig.maxOpenSpreads,
                ivRanks = scannerService.getIvRanksSnapshot().mapValues { it.value.toBigDecimal() },
                scannerPaused = killSwitch.scannerPaused,
                monitorPaused = killSwitch.monitorPaused,
            )
        return ResponseEntity.ok(dto)
    }

    // Read-only view of the rules the engine is actually running with: every value comes from the
    // bound config beans and the universe table, so the Trading Rules page cannot drift from them.
    override suspend fun getSpreadRules(): ResponseEntity<SpreadRulesDto> {
        val strategies =
            listOf(
                strategyParams.forStrategy(StrategyId.BULL_PUT).toRulesDto(enabled = true),
                strategyParams.forStrategy(StrategyId.BEAR_CALL).toRulesDto(enabled = bearCallConfig.enabled),
            )
        val portfolio =
            PortfolioRulesDto(
                tradingEnabled = scannerConfig.tradingEnabled,
                maxOpenSpreads = scannerConfig.maxOpenSpreads,
                maxOpenSpreadsPerSector = scannerConfig.maxOpenSpreadsPerSector,
                maxNewEntriesPerDay = scannerConfig.maxNewEntriesPerDay,
                entryMinFillPctOfMid = scannerConfig.entryMinFillPctOfMid.toBigDecimal(),
                stopLossConfirmCycles = scannerConfig.stopLossConfirmCycles,
                stopLossGraceMinutesAfterEntry = scannerConfig.stopLossGraceMinutesAfterEntry,
                stopLossSkipFirstRthMinutes = scannerConfig.stopLossSkipFirstRthMinutes,
                stopLossCooldownHours = scannerConfig.stopLossCooldownHours,
                entryCooldownMinutes = scannerConfig.entryCooldownMinutes,
                directionalGateEnabled = regimeConfig.gatingEnabled,
                scanCron = scannerConfig.cron,
            )
        val overrides =
            universePort
                .getAll()
                .mapNotNull { inst -> inst.ruleOverrides().takeIf { it.isNotEmpty() }?.let { inst.symbol.value to it } }
                .sortedBy { it.first }
                .map { (symbol, values) -> SymbolRuleOverrideDto(symbol = symbol, overrides = values) }
        return ResponseEntity.ok(SpreadRulesDto(strategies = strategies, portfolio = portfolio, symbolOverrides = overrides))
    }

    private fun StrategyParams.toRulesDto(enabled: Boolean): StrategyRulesDto =
        StrategyRulesDto(
            strategyId = strategyId.name,
            enabled = enabled,
            ivRankThreshold = ivRankThreshold.toBigDecimal(),
            minDte = minDte,
            maxDte = maxDte,
            preferredDte = preferredDte,
            targetDelta = targetDelta.toBigDecimal(),
            deltaMin = deltaMin.toBigDecimal(),
            deltaMax = deltaMax.toBigDecimal(),
            spreadWidthUsd = spreadWidthUsd,
            minCreditPerShare = minCreditPerShare,
            maxCreditPctOfWidth = maxCreditPctOfWidth.toBigDecimal(),
            maxRiskPercent = maxRiskPercent.toBigDecimal(),
            takeProfitPercent = takeProfitPercent.toBigDecimal(),
            stopLossPercent = stopLossPercent.toBigDecimal(),
            underlyingStopEnabled = underlyingStopEnabled,
            underlyingStopBufferPct = underlyingStopBufferPct.toBigDecimal(),
            timeProfitDte = timeProfitDte,
            driftProtectionPct = driftProtectionPct.toBigDecimal(),
        )

    private fun InstrumentConfig.ruleOverrides(): Map<String, String> =
        listOfNotNull(
            ivRankThreshold?.let { "ivRankThreshold" to it.toString() },
            minDte?.let { "minDte" to it.toString() },
            maxDte?.let { "maxDte" to it.toString() },
            preferredDte?.let { "preferredDte" to it.toString() },
            targetDelta?.let { "targetDelta" to it.toString() },
            deltaMin?.let { "deltaMin" to it.toString() },
            deltaMax?.let { "deltaMax" to it.toString() },
            spreadWidthUsd?.let { "spreadWidthUsd" to it.toPlainString() },
            minCreditPerShare?.let { "minCreditPerShare" to it.toPlainString() },
            maxRiskPercent?.let { "maxRiskPercent" to it.toString() },
            takeProfitPercent?.let { "takeProfitPercent" to it.toString() },
            stopLossPercent?.let { "stopLossPercent" to it.toString() },
            timeProfitDte?.let { "timeProfitDte" to it.toString() },
        ).toMap()

    override fun getTickerStatus(): ResponseEntity<Flow<SymbolScanStatusDto>> =
        ResponseEntity.ok(scannerService.getScanStatus().map { it.toDto() }.asFlow())

    private fun SymbolScanStatus.toDto(): SymbolScanStatusDto =
        SymbolScanStatusDto(
            symbol = symbol,
            runId = runId,
            evaluatedAt = evaluatedAt.atOffset(ZoneOffset.UTC),
            outcome = outcome.name,
            strategyId = strategyId?.name,
            rejectReason = rejectReason?.name,
            regime = regime?.name,
            bias = bias?.name,
            rsi = rsi?.toBigDecimal(),
            ivRank = detail?.ivRank?.toBigDecimal(),
            ivRankThreshold = detail?.ivRankThreshold?.toBigDecimal(),
            underlyingPrice = detail?.underlyingPrice,
            expiry = detail?.expiry,
            dte = detail?.dte,
            shortStrike = detail?.shortStrike,
            shortDelta = detail?.shortDelta?.toBigDecimal(),
            longStrike = detail?.longStrike,
            width = detail?.width,
            midCredit = detail?.midCredit,
            bidCredit = detail?.bidCredit,
            maxRiskPerShare = detail?.maxRiskPerShare,
            creditPctOfWidth = detail?.creditPctOfWidth?.toBigDecimal(),
            strikesRequested = strikesRequested,
            strikesWithGreeks = strikesWithGreeks,
        )

    override suspend fun pauseScanner(): ResponseEntity<Unit> {
        killSwitch.scannerPaused = true
        logger.info { "Scanner paused via API" }
        return ResponseEntity.noContent().build()
    }

    override suspend fun resumeScanner(): ResponseEntity<Unit> {
        killSwitch.scannerPaused = false
        logger.info { "Scanner resumed via API" }
        return ResponseEntity.noContent().build()
    }

    override suspend fun pauseMonitor(): ResponseEntity<Unit> {
        killSwitch.monitorPaused = true
        logger.info { "Spread monitor paused via API" }
        return ResponseEntity.noContent().build()
    }

    override suspend fun resumeMonitor(): ResponseEntity<Unit> {
        killSwitch.monitorPaused = false
        logger.info { "Spread monitor resumed via API" }
        return ResponseEntity.noContent().build()
    }
}
