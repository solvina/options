package cz.solvina.options.adapters.inbound.api

import `cz.solvina.options.bearcall`.api.BearCallSpreadsApi
import `cz.solvina.options.bearcall`.dto.BearCallSpreadDto
import `cz.solvina.options.bearcall`.dto.PagedBearCallSpreadsDto
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.spread.BearCallSpreadPort
import cz.solvina.options.domain.features.spread.SpreadManagementService
import cz.solvina.options.domain.features.spread.model.BearCallSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.universe.UniversePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@RestController
@RequestMapping
class BearCallSpreadsApiImpl(
    private val spreadPort: BearCallSpreadPort,
    private val spreadManagementService: SpreadManagementService,
    private val universePort: UniversePort,
    private val config: BearCallScannerConfig,
    private val clock: Clock,
) : BearCallSpreadsApi {
    override suspend fun listBearCallSpreads(
        status: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<PagedBearCallSpreadsDto> {
        val all =
            status?.let { s -> runCatching { SpreadStatus.valueOf(s) }.getOrNull()?.let { spreadPort.findByStatus(it) } ?: emptyList() }
                ?: spreadPort.findAll()
        val pageItems = if (size <= 0) emptyList() else all.drop(page * size).take(size)
        return ResponseEntity.ok(
            PagedBearCallSpreadsDto(
                content = pageItems.map { it.toDto() },
                totalElements = all.size.toLong(),
                totalPages = if (size <= 0) 0 else (all.size + size - 1) / size,
                page = page,
                propertySize = size,
            ),
        )
    }

    override suspend fun getBearCallSpreadById(id: UUID): ResponseEntity<BearCallSpreadDto> =
        spreadPort.findById(id)?.let { ResponseEntity.ok(it.toDto()) } ?: ResponseEntity.notFound().build()

    override fun listBearCallDividendRisk(): ResponseEntity<Flow<BearCallSpreadDto>> =
        ResponseEntity.ok(
            flow {
                val today = LocalDate.now(clock)
                val windowDays = config.dividendCheckWindowHours / 24
                for (spread in spreadPort.findOpen()) {
                    val exDiv = universePort.get(spread.symbol)?.exDividendDate ?: continue
                    if (ChronoUnit.DAYS.between(today, exDiv) in 0..windowDays) emit(spread.toDto())
                }
            },
        )

    override suspend fun softCloseBearCallSpread(id: UUID): ResponseEntity<BearCallSpreadDto> = close(id, force = false)

    override suspend fun forceCloseBearCallSpread(id: UUID): ResponseEntity<BearCallSpreadDto> = close(id, force = true)

    private suspend fun close(
        id: UUID,
        force: Boolean,
    ): ResponseEntity<BearCallSpreadDto> {
        // Guard: only act on bear-call ids (softClose/forceClose resolve across strategies).
        spreadPort.findById(id) ?: return ResponseEntity.notFound().build()
        val result = if (force) spreadManagementService.forceClose(id) else spreadManagementService.softClose(id)
        return when (result) {
            is SpreadManagementService.ManualCloseResult.Closed -> ResponseEntity.ok((result.spread as BearCallSpread).toDto())
            is SpreadManagementService.ManualCloseResult.NotFound -> ResponseEntity.notFound().build()
            is SpreadManagementService.ManualCloseResult.AlreadyClosed -> ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    private suspend fun BearCallSpread.toDto(): BearCallSpreadDto {
        val (currentSpreadValue, currentPnl) =
            if (status == SpreadStatus.OPEN) {
                val sv = lastSpreadValue
                if (sv != null) sv to creditPerShare.subtract(sv) else null to null
            } else {
                val sv = closePricePerShare
                sv to (if (sv != null) creditPerShare.subtract(sv) else null)
            }
        return BearCallSpreadDto(
            symbol = symbol.value,
            status = status.name,
            soldStrike = soldLeg.contract.strike,
            boughtStrike = boughtLeg.contract.strike,
            expiryDate = soldLeg.contract.expiry,
            creditPerShare = creditPerShare,
            maxRiskPerShare = maxRiskPerShare,
            quantity = quantity,
            openedAt = openedAt.atOffset(ZoneOffset.UTC),
            id = id,
            ivRankAtEntry = ivRankAtEntry?.toBigDecimal(),
            underlyingPriceAtEntry = underlyingPriceAtEntry,
            closedAt = closedAt?.atOffset(ZoneOffset.UTC),
            closeReason = closeReason,
            closePricePerShare = closePricePerShare,
            currentSpreadValue = currentSpreadValue,
            currentPnl = currentPnl,
            underlyingPriceAtExit = underlyingPriceAtExit,
            ivRankAtExit = ivRankAtExit,
            exDividendDate = universePort.get(symbol)?.exDividendDate,
        )
    }
}
