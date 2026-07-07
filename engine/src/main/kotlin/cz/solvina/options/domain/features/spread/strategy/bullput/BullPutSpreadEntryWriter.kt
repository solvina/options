package cz.solvina.options.domain.features.spread.strategy.bullput

import cz.solvina.options.domain.features.execution.SpreadEntryWriter
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Money
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant

/**
 * Bull put entry persistence behind the [SpreadEntryWriter] seam — the spread build + status writes
 * the execution loop previously did inline, unchanged, now resolved by [StrategyId.BULL_PUT].
 */
@Component
class BullPutSpreadEntryWriter(
    private val spreadPort: BullPutSpreadPort,
    private val clock: Clock,
) : SpreadEntryWriter {
    override val strategyId = StrategyId.BULL_PUT

    override suspend fun persistPending(
        request: TradeExecutionRequest,
        credit: BigDecimal,
    ): Spread {
        val soldPremium = credit.add(request.boughtMid).setScale(4, RoundingMode.HALF_UP)
        return spreadPort.save(
            BullPutSpread(
                id = null,
                symbol = request.underlyingSymbol,
                soldLeg = SpreadLeg(request.soldContract, LegAction.SELL, Money(soldPremium), orderId = 0),
                boughtLeg = SpreadLeg(request.boughtContract, LegAction.BUY, Money(request.boughtMid), orderId = 0),
                creditPerShare = credit,
                maxRiskPerShare = request.maxRiskPerShare,
                quantity = request.quantity,
                status = SpreadStatus.PENDING,
                ivRankAtEntry = request.ivRankAtEntry,
                underlyingPriceAtEntry = request.underlyingPriceAtEntry,
                openedAt = Instant.now(clock),
            ),
        )
    }

    override suspend fun stampOrderIds(
        spread: Spread,
        orderId: Int,
        credit: BigDecimal,
    ): Spread {
        val s = spread as BullPutSpread
        return spreadPort.update(
            s.copy(
                soldLeg = s.soldLeg.copy(orderId = orderId),
                boughtLeg = s.boughtLeg.copy(orderId = orderId),
                creditPerShare = credit,
            ),
        )
    }

    override suspend fun markFilled(
        spread: Spread,
        orderId: Int,
        netCredit: BigDecimal,
        entryMid: BigDecimal?,
    ): Spread {
        val s = spread as BullPutSpread
        return spreadPort.update(
            s.copy(
                soldLeg = s.soldLeg.copy(orderId = orderId),
                boughtLeg = s.boughtLeg.copy(orderId = orderId),
                creditPerShare = netCredit,
                entryMidPerShare = entryMid,
                status = SpreadStatus.OPEN,
            ),
        )
    }

    override suspend fun markBrokenLongOnly(
        spread: Spread,
        longOrderId: Int,
        reason: String,
    ): Spread {
        val s = spread as BullPutSpread
        return spreadPort.update(
            s.copy(
                boughtLeg = s.boughtLeg.copy(orderId = longOrderId),
                status = SpreadStatus.BROKEN_LONG_ONLY,
                closeReason = reason,
            ),
        )
    }

    override suspend fun markStatus(
        spread: Spread,
        status: SpreadStatus,
        closeReason: String,
    ): Spread {
        val s = spread as BullPutSpread
        return spreadPort.update(s.copy(status = status, closeReason = closeReason))
    }
}
