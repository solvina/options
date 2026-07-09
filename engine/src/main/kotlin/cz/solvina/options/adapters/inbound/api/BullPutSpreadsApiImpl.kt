package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.SpreadAnalyticsService
import cz.solvina.options.domain.features.spread.SpreadManagementService
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import `cz.solvina.options.spreads`.api.BullPutSpreadsApi
import `cz.solvina.options.spreads`.dto.AnalyticsSummaryDto
import `cz.solvina.options.spreads`.dto.IvBucketBreakdownDto
import `cz.solvina.options.spreads`.dto.PagedSpreadsDto
import `cz.solvina.options.spreads`.dto.PnlTimelinePointDto
import `cz.solvina.options.spreads`.dto.SpreadAnalyticsDto
import `cz.solvina.options.spreads`.dto.SpreadDto
import `cz.solvina.options.spreads`.dto.StatusBreakdownDto
import `cz.solvina.options.spreads`.dto.SymbolBreakdownDto
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.UUID

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping
class BullPutSpreadsApiImpl(
    private val spreadPort: BullPutSpreadPort,
    private val spreadManagementService: SpreadManagementService,
    private val spreadAnalyticsService: SpreadAnalyticsService,
    private val marketDataPort: MarketDataPort,
) : BullPutSpreadsApi {
    override suspend fun listSpreads(
        status: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<PagedSpreadsDto> {
        val spreadStatus = status?.let { runCatching { SpreadStatus.valueOf(it) }.getOrNull() }
        val spreadPage = spreadPort.findPage(spreadStatus, page, size)
        val dtos = spreadPage.content.map { it.toDto() }
        return ResponseEntity.ok(
            PagedSpreadsDto(
                content = dtos,
                totalElements = spreadPage.totalElements,
                totalPages = spreadPage.totalPages,
                page = spreadPage.page,
                propertySize = spreadPage.size,
            ),
        )
    }

    override suspend fun getSpreadById(id: UUID): ResponseEntity<SpreadDto> {
        val all = spreadPort.findAll()
        val spread = all.firstOrNull { it.id == id } ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(spread.toDto())
    }

    override suspend fun refreshSpreadPnl(id: UUID): ResponseEntity<SpreadDto> {
        val spread = spreadPort.findById(id) ?: return ResponseEntity.notFound().build()
        if (spread.status != SpreadStatus.OPEN) return ResponseEntity.ok(spread.toDto())

        val updated =
            runCatching {
                val soldMid = marketDataPort.getOptionMid(spread.soldLeg.contract).amount
                val boughtMid = marketDataPort.getOptionMid(spread.boughtLeg.contract).amount
                val spreadValue = soldMid.subtract(boughtMid)
                spreadPort.update(spread.copy(lastSpreadValue = spreadValue))
            }.onFailure { e -> logger.warn(e) { "[${spread.symbol}] refreshSpreadPnl failed: ${e.message}" } }
                .getOrDefault(spread)

        return ResponseEntity.ok(updated.toDto())
    }

    override suspend fun softCloseSpread(id: UUID): ResponseEntity<SpreadDto> =
        when (val result = spreadManagementService.softClose(id)) {
            is SpreadManagementService.ManualCloseResult.Closed -> ResponseEntity.ok(result.spread.toDto())
            is SpreadManagementService.ManualCloseResult.NotFound -> ResponseEntity.notFound().build()
            is SpreadManagementService.ManualCloseResult.AlreadyClosed -> ResponseEntity.status(HttpStatus.CONFLICT).build()
        }

    override suspend fun getSpreadAnalytics(): ResponseEntity<SpreadAnalyticsDto> {
        val a = spreadAnalyticsService.compute()
        return ResponseEntity.ok(
            SpreadAnalyticsDto(
                summary =
                    AnalyticsSummaryDto(
                        totalTrades = a.summary.totalTrades,
                        openTrades = a.summary.openTrades,
                        winRate = a.summary.winRate.toBigDecimal(),
                        totalRealizedPnl = a.summary.totalRealizedPnl,
                        avgPnlPerTrade = a.summary.avgPnlPerTrade,
                        avgHoldDays = a.summary.avgHoldDays.toBigDecimal(),
                    ),
                byStatus =
                    a.byStatus.map { s ->
                        StatusBreakdownDto(
                            status = s.status,
                            count = s.count,
                            totalPnl = s.totalPnl,
                            avgPnl = s.avgPnl,
                            avgHoldDays = s.avgHoldDays.toBigDecimal(),
                        )
                    },
                bySymbol =
                    a.bySymbol.map { s ->
                        SymbolBreakdownDto(
                            symbol = s.symbol,
                            count = s.count,
                            wins = s.wins,
                            winRate = s.winRate.toBigDecimal(),
                            totalPnl = s.totalPnl,
                            avgPnl = s.avgPnl,
                            avgCreditRatio = s.avgCreditRatio.toBigDecimal(),
                        )
                    },
                byEntryIvBucket =
                    a.byEntryIvBucket.map { b ->
                        IvBucketBreakdownDto(
                            bucket = b.bucket,
                            count = b.count,
                            winRate = b.winRate.toBigDecimal(),
                            avgPnl = b.avgPnl,
                        )
                    },
                pnlTimeline =
                    a.pnlTimeline.map { p ->
                        PnlTimelinePointDto(
                            date = p.date,
                            dailyPnl = p.dailyPnl,
                            cumulativePnl = p.cumulativePnl,
                        )
                    },
            ),
        )
    }

    override suspend fun forceCloseSpread(id: UUID): ResponseEntity<SpreadDto> =
        when (val result = spreadManagementService.forceClose(id)) {
            is SpreadManagementService.ManualCloseResult.Closed -> ResponseEntity.ok(result.spread.toDto())
            is SpreadManagementService.ManualCloseResult.NotFound -> ResponseEntity.notFound().build()
            is SpreadManagementService.ManualCloseResult.AlreadyClosed -> ResponseEntity.status(HttpStatus.CONFLICT).build()
        }

    private fun Spread.toDto(): SpreadDto {
        val (currentSpreadValue, currentPnl) =
            if (status == SpreadStatus.OPEN) {
                val sv = lastSpreadValue
                if (sv != null) sv to creditPerShare.subtract(sv) else null to null
            } else {
                val sv = closePricePerShare
                val pnl = if (sv != null) creditPerShare.subtract(sv) else null
                sv to pnl
            }

        return SpreadDto(
            id = id,
            symbol = symbol.value,
            status = status.name,
            soldStrike = soldLeg.contract.strike,
            boughtStrike = boughtLeg.contract.strike,
            expiryDate = soldLeg.contract.expiry,
            creditPerShare = creditPerShare,
            maxRiskPerShare = maxRiskPerShare,
            quantity = quantity,
            ivRankAtEntry = ivRankAtEntry?.toBigDecimal(),
            underlyingPriceAtEntry = underlyingPriceAtEntry,
            openedAt = openedAt.atOffset(ZoneOffset.UTC),
            closedAt = closedAt?.atOffset(ZoneOffset.UTC),
            closeReason = closeReason,
            closePricePerShare = closePricePerShare,
            currentSpreadValue = currentSpreadValue,
            currentPnl = currentPnl,
            underlyingPriceNow = lastUnderlyingPrice,
            // Bull put is safe while spot stays ABOVE the short (sold) strike.
            distanceToShortStrikePct = cushionPct(lastUnderlyingPrice, soldLeg.contract.strike, bullish = true),
            underlyingPriceAtExit = underlyingPriceAtExit,
            ivRankAtExit = ivRankAtExit,
        )
    }
}

/**
 * Cushion of the underlying from the short strike, as a signed percent of spot. Positive means the
 * safe side (bull put: spot above the short strike; bear call: spot below it). Null when no spot.
 */
internal fun cushionPct(
    underlying: java.math.BigDecimal?,
    shortStrike: java.math.BigDecimal,
    bullish: Boolean,
): java.math.BigDecimal? {
    if (underlying == null || underlying <= java.math.BigDecimal.ZERO) return null
    val gap = if (bullish) underlying.subtract(shortStrike) else shortStrike.subtract(underlying)
    return gap
        .divide(
            underlying,
            6,
            java.math.RoundingMode.HALF_UP,
        ).multiply(java.math.BigDecimal("100"))
        .setScale(2, java.math.RoundingMode.HALF_UP)
}
