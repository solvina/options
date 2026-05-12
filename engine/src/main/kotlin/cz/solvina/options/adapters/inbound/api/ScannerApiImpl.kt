package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.scanner.ScannerPort
import cz.solvina.options.domain.features.scanner.ScannerService
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import `cz.solvina.options.spreads`.api.MonitorApi
import `cz.solvina.options.spreads`.api.ScannerApi
import `cz.solvina.options.spreads`.dto.ScannerStatusDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val spreadPort: SpreadPort,
    private val killSwitch: TradingKillSwitch,
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
        val openCount = spreadPort.countByStatus(SpreadStatus.OPEN)
        val dto =
            ScannerStatusDto(
                lastRunAt = scannerService.getLastRunAt()?.atOffset(ZoneOffset.UTC),
                openSpreadCount = openCount,
                ivRanks = scannerService.getIvRanksSnapshot().mapValues { it.value.toBigDecimal() },
                scannerPaused = killSwitch.scannerPaused,
                monitorPaused = killSwitch.monitorPaused,
            )
        return ResponseEntity.ok(dto)
    }

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
