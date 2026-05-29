package cz.solvina.options.adapters.inbound.api

import `cz.solvina.options.flags`.api.FlagsApi
import `cz.solvina.options.flags`.dto.FlagAnalyticsDto
import `cz.solvina.options.flags`.dto.FlagPositionDto
import `cz.solvina.options.flags`.dto.FlagPnlTimelinePointDto
import `cz.solvina.options.flags`.dto.FlagStatusBreakdownDto
import `cz.solvina.options.flags`.dto.FlagSummaryDto
import `cz.solvina.options.flags`.dto.FlagSymbolBreakdownDto
import `cz.solvina.options.flags`.dto.FlagTradingConfigDto
import `cz.solvina.options.flags`.dto.PagedFlagsDto
import cz.solvina.options.domain.features.flag.FlagAnalyticsService
import cz.solvina.options.domain.features.flag.FlagManagementService
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.market.MarketDataPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private val logger = KotlinLogging.logger {}

@RestController
class FlagsApiImpl(
    private val flagPort: FlagPort,
    private val flagManagementService: FlagManagementService,
    private val flagAnalyticsService: FlagAnalyticsService,
    private val flagTradingConfigPort: FlagTradingConfigPort,
    private val marketDataPort: MarketDataPort,
) : FlagsApi {

    override suspend fun listFlags(
        status: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<PagedFlagsDto> {
        val flagStatus = status?.let { FlagStatus.valueOf(it) }
        val result = flagPort.findPage(flagStatus, page, size)
        val dtos = mutableListOf<FlagPositionDto>()
        for (p in result.content) dtos.add(p.toDto())
        return ResponseEntity.ok(
            PagedFlagsDto(
                content = dtos,
                totalElements = result.totalElements,
                totalPages = result.totalPages,
                page = result.page,
                propertySize = result.size,
            ),
        )
    }

    override suspend fun getFlagById(id: UUID): ResponseEntity<FlagPositionDto> {
        val position = flagPort.findById(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(position.toDto())
    }

    override suspend fun getFlagAnalytics(): ResponseEntity<FlagAnalyticsDto> {
        val a = flagAnalyticsService.compute()
        return ResponseEntity.ok(
            FlagAnalyticsDto(
                summary = FlagSummaryDto(
                    totalTrades = a.summary.totalTrades,
                    openTrades = a.summary.openTrades,
                    winRate = a.summary.winRate.toBigDecimal(),
                    totalRealizedPnl = a.summary.totalRealizedPnl,
                    avgWinner = a.summary.avgWinner,
                    avgLoser = a.summary.avgLoser,
                    eodCutPct = a.summary.eodCutPct.toBigDecimal(),
                    avgHoldMinutes = a.summary.avgHoldMinutes.toBigDecimal(),
                ),
                byStatus = a.byStatus.map { s ->
                    FlagStatusBreakdownDto(status = s.status, count = s.count, totalPnl = s.totalPnl, avgPnl = s.avgPnl)
                },
                bySymbol = a.bySymbol.map { s ->
                    FlagSymbolBreakdownDto(
                        symbol = s.symbol,
                        count = s.count,
                        wins = s.wins,
                        winRate = s.winRate.toBigDecimal(),
                        totalPnl = s.totalPnl,
                        avgPnl = s.avgPnl,
                    )
                },
                pnlTimeline = a.pnlTimeline.map { p ->
                    FlagPnlTimelinePointDto(date = p.date, dailyPnl = p.dailyPnl, cumulativePnl = p.cumulativePnl)
                },
            ),
        )
    }

    override suspend fun getFlagConfig(): ResponseEntity<FlagTradingConfigDto> =
        ResponseEntity.ok(flagTradingConfigPort.get().toDto())

    override suspend fun updateFlagConfig(flagTradingConfigDto: FlagTradingConfigDto): ResponseEntity<FlagTradingConfigDto> {
        val updated = flagTradingConfigPort.update(
            FlagTradingConfig(
                riskPerTrade = flagTradingConfigDto.riskPerTrade,
                maxOpenPositions = flagTradingConfigDto.maxOpenPositions,
                enabled = flagTradingConfigDto.enabled,
                entryBlockMinutesBeforeClose = flagTradingConfigDto.entryBlockMinutesBeforeClose,
                eodLiqMinutesBeforeClose = flagTradingConfigDto.eodLiqMinutesBeforeClose,
            ),
        )
        return ResponseEntity.ok(updated.toDto())
    }

    override suspend fun pauseFlagScanner(): ResponseEntity<Unit> {
        flagManagementService.pauseScanner()
        return ResponseEntity.noContent().build()
    }

    override suspend fun resumeFlagScanner(): ResponseEntity<Unit> {
        flagManagementService.resumeScanner()
        return ResponseEntity.noContent().build()
    }

    override suspend fun closeFlagPosition(id: UUID): ResponseEntity<FlagPositionDto> =
        when (val result = flagManagementService.manualClose(id)) {
            is FlagManagementService.ManualCloseResult.Closed -> ResponseEntity.ok(result.position.toDto())
            is FlagManagementService.ManualCloseResult.NotFound -> ResponseEntity.notFound().build()
            is FlagManagementService.ManualCloseResult.AlreadyClosed -> ResponseEntity.status(HttpStatus.CONFLICT).build()
        }

    private suspend fun FlagPosition.toDto(): FlagPositionDto {
        val livePrice = if (status == FlagStatus.OPEN || status == FlagStatus.PENDING) {
            runCatching { marketDataPort.getUnderlyingPrice(symbol).amount }.getOrNull()
        } else null
        val unrealized = livePrice?.let { price ->
            price.subtract(entryPrice)
                .multiply(java.math.BigDecimal(shares))
                .setScale(2, java.math.RoundingMode.HALF_UP)
        }
        return FlagPositionDto(
            id = requireNotNull(id),
            symbol = symbol.value,
            status = FlagPositionDto.Status.valueOf(status.name),
            entryOrderId = entryOrderId,
            stopLossOrderId = stopLossOrderId,
            profitTargetOrderId = profitTargetOrderId,
            entryPrice = entryPrice,
            stopLossPrice = stopLossPrice,
            profitTargetPrice = profitTargetPrice,
            shares = shares,
            riskAmount = riskAmount,
            flagpoleHeight = flagpoleHeight,
            flagRetracement = flagRetracement,
            resistanceAtEntry = resistanceAtEntry,
            patternStartedAt = patternStartedAt?.let { java.time.OffsetDateTime.ofInstant(it, java.time.ZoneOffset.UTC) },
            openedAt = java.time.OffsetDateTime.ofInstant(openedAt, java.time.ZoneOffset.UTC),
            closedAt = closedAt?.let { java.time.OffsetDateTime.ofInstant(it, java.time.ZoneOffset.UTC) },
            closeReason = closeReason,
            closePriceActual = closePriceActual,
            realizedPnl = realizedPnl,
            unrealizedPnl = unrealized,
            currentPrice = livePrice,
        )
    }

    private fun FlagTradingConfig.toDto(): FlagTradingConfigDto =
        FlagTradingConfigDto(
            riskPerTrade = riskPerTrade,
            maxOpenPositions = maxOpenPositions,
            enabled = enabled,
            entryBlockMinutesBeforeClose = entryBlockMinutesBeforeClose,
            eodLiqMinutesBeforeClose = eodLiqMinutesBeforeClose,
        )
}
