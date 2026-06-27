package cz.solvina.options.persistence

import cz.solvina.options.adapters.outbound.persistence.postgres.BearCallSpreadPersistenceAdapter
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.BearCallSpreadEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.BearCallSpreadRepository
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.spread.model.BearCallSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

class BearCallSpreadPersistenceAdapterTest {
    @Test
    fun `maps bear call to entity and back, preserving CALL legs, strike direction and ex-dividend date`() =
        runTest {
            val repo = mockk<BearCallSpreadRepository>()
            val adapter = BearCallSpreadPersistenceAdapter(repo)

            val symbol = Symbol("AAPL")
            val expiry = LocalDate.of(2026, 8, 7)
            // Bear call: SELL the lower strike (180C), BUY the higher strike (185C).
            val soldContract = OptionContract(symbol, expiry, BigDecimal("180"), OptionType.CALL)
            val boughtContract = OptionContract(symbol, expiry, BigDecimal("185"), OptionType.CALL)
            val spread =
                BearCallSpread(
                    id = null,
                    symbol = symbol,
                    soldLeg = SpreadLeg(soldContract, LegAction.SELL, Money(BigDecimal("1.50")), orderId = 11),
                    boughtLeg = SpreadLeg(boughtContract, LegAction.BUY, Money(BigDecimal("0.50")), orderId = 22),
                    creditPerShare = BigDecimal("1.00"),
                    maxRiskPerShare = BigDecimal("4.00"),
                    status = SpreadStatus.OPEN,
                    ivRankAtEntry = 55.0,
                    underlyingPriceAtEntry = BigDecimal("178"),
                    openedAt = Instant.parse("2026-06-27T10:00:00Z"),
                    exDividendDate = LocalDate.of(2026, 8, 1),
                )

            val saved = slot<BearCallSpreadEntity>()
            // Simulate JPA assigning the generated id on persist.
            every { repo.save(capture(saved)) } answers { saved.captured.apply { id = UUID.randomUUID() } }

            val result = adapter.save(spread)

            // toEntity: short = lower strike, long = higher strike, ex-dividend preserved.
            assertEquals(BigDecimal("180"), saved.captured.soldStrike)
            assertEquals(BigDecimal("185"), saved.captured.boughtStrike)
            assertEquals(LocalDate.of(2026, 8, 1), saved.captured.exDividendDate)

            // toDomain round-trip: both legs are CALLs, strategy is BEAR_CALL, ex-dividend kept.
            assertEquals(OptionType.CALL, result.soldLeg.contract.type)
            assertEquals(OptionType.CALL, result.boughtLeg.contract.type)
            assertEquals(StrategyId.BEAR_CALL, result.strategyId)
            assertEquals(LocalDate.of(2026, 8, 1), result.exDividendDate)
        }
}
