package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.shadow.ShadowSpread
import cz.solvina.options.domain.features.shadow.ShadowSpreadPort
import `cz.solvina.options.spreads`.api.ShadowSpreadsApi
import `cz.solvina.options.spreads`.dto.ShadowSpreadDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneOffset

@RestController
@RequestMapping
class ShadowSpreadsApiImpl(
    private val port: ShadowSpreadPort,
) : ShadowSpreadsApi {
    override fun listShadowSpreads(): ResponseEntity<Flow<ShadowSpreadDto>> =
        ResponseEntity.ok(
            flow {
                port.findAll().sortedByDescending { it.openedAt }.forEach { emit(it.toDto()) }
            },
        )

    private fun ShadowSpread.toDto(): ShadowSpreadDto {
        val value = exitValue ?: lastValue

        fun pnl(credit: BigDecimal) = value?.let { credit.subtract(it).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP) }
        return ShadowSpreadDto(
            id = id,
            strategyId = strategyId.name,
            symbol = symbol.value,
            optionType = optionType.name,
            expiry = expiry,
            soldStrike = soldStrike,
            boughtStrike = boughtStrike,
            width = width,
            openedAt = openedAt.atOffset(ZoneOffset.UTC),
            sourceOutcome = sourceOutcome,
            midCredit = midCredit,
            floorCredit = floorCredit,
            naturalCredit = naturalCredit,
            underlyingAtEntry = underlyingAtEntry,
            ivRankAtEntry = ivRankAtEntry?.toBigDecimal(),
            status = status.name,
            closeReason = closeReason,
            closedAt = closedAt?.atOffset(ZoneOffset.UTC),
            exitValue = exitValue,
            lastValue = lastValue,
            lastUnderlying = lastUnderlying,
            lastValuedAt = lastValuedAt?.atOffset(ZoneOffset.UTC),
            pnlAtMid = pnl(midCredit),
            pnlAt95PctMid = pnl(midCredit.multiply(BigDecimal("0.95"))),
            backfilled = backfilled,
        )
    }
}
