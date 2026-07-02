package cz.solvina.options.adapters.outbound.ibkr.order

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertTrue

/**
 * BigDecimal `==` is scale-sensitive (445 != 445.0). IBKR positions and our own contracts can carry
 * strikes with different scales for the same numeric value, so position matching must use
 * `compareTo`, not `==` — otherwise a real filled leg is reported as "not found".
 */
class PositionReconciliationServiceTest {
    private val positionsPort: PositionsPort = mockk()
    private val service = PositionReconciliationService(positionsPort)

    private val expiry = LocalDate.of(2025, 6, 20)
    private val soldContract = OptionContract(Symbol("SPY"), expiry, BigDecimal("445"), OptionType.PUT)
    private val boughtContract = OptionContract(Symbol("SPY"), expiry, BigDecimal("440"), OptionType.PUT)

    @Test
    fun `matches positions whose strike has a different BigDecimal scale than the contract`() =
        runTest {
            val positions =
                listOf(
                    AccountPosition(
                        account = "DU1",
                        symbol = "SPY",
                        secType = "OPT",
                        currency = "USD",
                        expiry = expiry,
                        strike = BigDecimal("445.0"), // scale=1, contract strike is scale=0 ("445")
                        optionRight = "P",
                        quantity = BigDecimal("-1"),
                        avgCost = BigDecimal.ZERO,
                    ),
                    AccountPosition(
                        account = "DU1",
                        symbol = "SPY",
                        secType = "OPT",
                        currency = "USD",
                        expiry = expiry,
                        strike = BigDecimal("440.00"), // scale=2
                        optionRight = "P",
                        quantity = BigDecimal("1"),
                        avgCost = BigDecimal.ZERO,
                    ),
                )
            coEvery { positionsPort.getPositions() } returns positions

            val result = service.verifyBothLegsFilled(soldContract, boughtContract, qty = 1)

            assertTrue(result.success, "both legs must be found despite the differing BigDecimal scale: ${result.message}")
        }
}
