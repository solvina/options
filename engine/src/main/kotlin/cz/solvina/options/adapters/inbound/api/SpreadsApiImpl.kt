package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.spread.SpreadManagementService
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import `cz.solvina.options.spreads`.api.SpreadsApi
import `cz.solvina.options.spreads`.dto.SpreadDto
import kotlinx.coroutines.flow.Flow
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.UUID

@RestController
@RequestMapping
class SpreadsApiImpl(
    private val spreadPort: SpreadPort,
    private val spreadManagementService: SpreadManagementService,
) : SpreadsApi {
    override fun listSpreads(status: String?): ResponseEntity<Flow<SpreadDto>> {
        // Spring WebFlux invokes this method in a coroutine context; returning Flow is fine here.
        // We use a suspend-to-blocking shim by delegating to a blocking repository call via a flow.
        val spreadsFlow =
            kotlinx.coroutines.flow.flow {
                val spreads =
                    if (status != null) {
                        val spreadStatus = runCatching { SpreadStatus.valueOf(status) }.getOrNull()
                        if (spreadStatus != null) spreadPort.findByStatus(spreadStatus) else spreadPort.findAll()
                    } else {
                        spreadPort.findAll()
                    }
                spreads.forEach { emit(it.toDto()) }
            }
        return ResponseEntity.ok(spreadsFlow)
    }

    override suspend fun getSpreadById(id: UUID): ResponseEntity<SpreadDto> {
        val all = spreadPort.findAll()
        val spread =
            all.firstOrNull { it.id == id }
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(spread.toDto())
    }

    override suspend fun softCloseSpread(id: UUID): ResponseEntity<SpreadDto> =
        when (val result = spreadManagementService.softClose(id)) {
            is SpreadManagementService.ManualCloseResult.Closed -> ResponseEntity.ok(result.spread.toDto())
            is SpreadManagementService.ManualCloseResult.NotFound -> ResponseEntity.notFound().build()
            is SpreadManagementService.ManualCloseResult.AlreadyClosed -> ResponseEntity.status(HttpStatus.CONFLICT).build()
        }

    override suspend fun forceCloseSpread(id: UUID): ResponseEntity<SpreadDto> =
        when (val result = spreadManagementService.forceClose(id)) {
            is SpreadManagementService.ManualCloseResult.Closed -> ResponseEntity.ok(result.spread.toDto())
            is SpreadManagementService.ManualCloseResult.NotFound -> ResponseEntity.notFound().build()
            is SpreadManagementService.ManualCloseResult.AlreadyClosed -> ResponseEntity.status(HttpStatus.CONFLICT).build()
        }

    private fun BullPutSpread.toDto(): SpreadDto =
        SpreadDto(
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
        )
}
