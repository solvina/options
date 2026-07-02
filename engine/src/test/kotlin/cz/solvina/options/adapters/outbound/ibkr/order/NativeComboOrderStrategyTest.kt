package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NativeComboOrderStrategy.validateOrder] must accept both spread directions: bull put sells the
 * HIGHER strike, bear call sells the LOWER strike. Mixing option types must always be rejected.
 */
class NativeComboOrderStrategyTest {
    private val strategy =
        NativeComboOrderStrategy(
            exchangeId = "SMART",
            registry = mockk<IbkrOrderRegistry>(relaxed = true),
            client = mockk<EClientSocket>(relaxed = true),
            contractCache = mockk<IbkrContractCache>(relaxed = true),
            connectionConfig = IbkrConnectionConfig(account = ""),
        )

    private val expiry = LocalDate.of(2024, 6, 21)

    private fun contract(
        strike: String,
        type: OptionType,
    ) = OptionContract(symbol = Symbol("SPY"), strike = BigDecimal(strike), expiry = expiry, type = type)

    @Test
    fun `accepts a valid bull-put spread - sold strike above bought strike`() {
        val result =
            strategy.validateOrder(
                soldContract = contract("450", OptionType.PUT),
                boughtContract = contract("440", OptionType.PUT),
                netCredit = Money(BigDecimal("1.50")),
            )
        assertTrue(result.isValid)
    }

    @Test
    fun `rejects a bull-put spread with sold strike below bought strike`() {
        val result =
            strategy.validateOrder(
                soldContract = contract("440", OptionType.PUT),
                boughtContract = contract("450", OptionType.PUT),
                netCredit = Money(BigDecimal("1.50")),
            )
        assertFalse(result.isValid)
        assertTrue(result.reason.contains("Short strike must be > long strike"))
    }

    @Test
    fun `accepts a valid bear-call spread - sold strike below bought strike`() {
        val result =
            strategy.validateOrder(
                soldContract = contract("450", OptionType.CALL),
                boughtContract = contract("460", OptionType.CALL),
                netCredit = Money(BigDecimal("1.50")),
            )
        assertTrue(result.isValid)
    }

    @Test
    fun `rejects a bear-call spread with sold strike above bought strike`() {
        val result =
            strategy.validateOrder(
                soldContract = contract("460", OptionType.CALL),
                boughtContract = contract("450", OptionType.CALL),
                netCredit = Money(BigDecimal("1.50")),
            )
        assertFalse(result.isValid)
        assertTrue(result.reason.contains("Short strike must be < long strike"))
    }

    @Test
    fun `rejects a spread mixing puts and calls`() {
        val result =
            strategy.validateOrder(
                soldContract = contract("450", OptionType.PUT),
                boughtContract = contract("460", OptionType.CALL),
                netCredit = Money(BigDecimal("1.50")),
            )
        assertFalse(result.isValid)
        assertTrue(result.reason.contains("same option type"))
    }

    @Test
    fun `rejects non-positive net credit`() {
        val result =
            strategy.validateOrder(
                soldContract = contract("450", OptionType.PUT),
                boughtContract = contract("440", OptionType.PUT),
                netCredit = Money(BigDecimal.ZERO),
            )
        assertFalse(result.isValid)
        assertTrue(result.reason.contains("Net credit must be positive"))
    }
}
