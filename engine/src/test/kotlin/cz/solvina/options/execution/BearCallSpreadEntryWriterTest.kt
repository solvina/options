package cz.solvina.options.execution

import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.spread.strategy.bearcall.BearCallSpreadEntryWriter
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import cz.solvina.options.testutil.InMemoryBearCallSpreadPort
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * Execution-side persistence for bear calls. Mirrors what the bull-put writer does (covered through
 * TradeExecutionServiceTest), but exercised directly with a real in-memory bear-call port.
 */
class BearCallSpreadEntryWriterTest {
    private val symbol = Symbol("AAPL")
    private val expiry = LocalDate.of(2025, 8, 15)
    private val clock = Clock.fixed(LocalDate.of(2025, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    private fun request() =
        TradeExecutionRequest(
            soldContract = OptionContract(symbol, expiry, BigDecimal("180"), OptionType.CALL),
            boughtContract = OptionContract(symbol, expiry, BigDecimal("185"), OptionType.CALL),
            underlyingSymbol = symbol,
            strategyId = StrategyId.BEAR_CALL,
            targetCredit = BigDecimal("1.00"),
            floorCredit = BigDecimal("0.50"),
            maxRiskPerShare = BigDecimal("4.00"),
            ivRankAtEntry = 50.0,
            soldBid = BigDecimal("1.40"),
            soldAsk = BigDecimal("1.60"),
            boughtBid = BigDecimal("0.40"),
            boughtAsk = BigDecimal("0.60"),
            boughtMid = BigDecimal("0.50"),
            underlyingPriceAtEntry = BigDecimal("178"),
        )

    @Test
    fun `persistPending writes a PENDING bear call with CALL legs and short below long strike`() =
        runTest {
            val writer = BearCallSpreadEntryWriter(InMemoryBearCallSpreadPort(), clock)

            val pending = writer.persistPending(request(), BigDecimal("1.00"))

            assertEquals(StrategyId.BEAR_CALL, pending.strategyId)
            assertEquals(SpreadStatus.PENDING, pending.status)
            assertEquals(OptionType.CALL, pending.soldLeg.contract.type)
            assertEquals(OptionType.CALL, pending.boughtLeg.contract.type)
            assertEquals(BigDecimal("180"), pending.soldLeg.contract.strike)
            assertEquals(BigDecimal("185"), pending.boughtLeg.contract.strike)
        }

    @Test
    fun `markFilled opens the spread at the net credit with the combo order id on both legs`() =
        runTest {
            val writer = BearCallSpreadEntryWriter(InMemoryBearCallSpreadPort(), clock)
            val pending = writer.persistPending(request(), BigDecimal("1.00"))

            val filled = writer.markFilled(pending, orderId = 77, netCredit = BigDecimal("0.95"), entryMid = BigDecimal("1.10"))

            assertEquals(SpreadStatus.OPEN, filled.status)
            assertEquals(0, BigDecimal("0.95").compareTo(filled.creditPerShare))
            assertEquals(0, BigDecimal("1.10").compareTo(filled.entryMidPerShare ?: BigDecimal.ZERO))
            assertEquals(77, filled.soldLeg.orderId)
            assertEquals(77, filled.boughtLeg.orderId)
        }
}
