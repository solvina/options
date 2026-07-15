package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerPort
import cz.solvina.options.domain.features.scanner.ScannerService
import cz.solvina.options.domain.features.scanner.SymbolScanStatus
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import `cz.solvina.options.spreads`.api.MonitorApi
import `cz.solvina.options.spreads`.api.ScannerApi
import `cz.solvina.options.spreads`.dto.ScannerStatusDto
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
