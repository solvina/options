package cz.solvina.options.domain.features.spread.strategy.bearcall

import cz.solvina.options.domain.features.spread.BearCallSpreadPort
import cz.solvina.options.domain.features.spread.SpreadCloser
import cz.solvina.options.domain.features.spread.model.BearCallSpread
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.model.StrategyId
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Bear call persistence for the management exit loop — the mirror of [BullPutSpreadCloser]. */
@Component
class BearCallSpreadCloser(
    private val port: BearCallSpreadPort,
    private val clock: Clock,
) : SpreadCloser {
    override val strategyId = StrategyId.BEAR_CALL

    override suspend fun findById(id: UUID): Spread? = port.findById(id)

    override suspend fun openSpreads(): List<Spread> = port.findOpen()

    override suspend fun closingSpreads(): List<Spread> = port.findByStatus(SpreadStatus.CLOSING)

    override suspend fun recordLastValue(
        spread: Spread,
        value: BigDecimal,
    ): Spread = port.update((spread as BearCallSpread).copy(lastSpreadValue = value))

    override suspend fun markClosing(
        spread: Spread,
        intendedStatus: SpreadStatus,
        closePrice: BigDecimal,
    ): Spread =
        port.update(
            (spread as BearCallSpread).copy(
                status = SpreadStatus.CLOSING,
                closeReason = intendedStatus.name,
                closePricePerShare = closePrice,
            ),
        )

    override suspend fun close(
        spread: Spread,
        status: SpreadStatus,
        closeReason: String,
        closePrice: BigDecimal,
        underlyingAtExit: BigDecimal?,
        ivAtExit: BigDecimal?,
    ): Spread =
        port.update(
            (spread as BearCallSpread).copy(
                status = status,
                closedAt = Instant.now(clock),
                closeReason = closeReason,
                closePricePerShare = closePrice,
                underlyingPriceAtExit = underlyingAtExit,
                ivRankAtExit = ivAtExit,
            ),
        )
}
