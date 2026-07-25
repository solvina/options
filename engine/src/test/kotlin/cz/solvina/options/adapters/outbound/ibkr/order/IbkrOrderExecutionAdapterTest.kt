package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrInstrumentsConfig
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * Leg-by-leg entries only report SUCCESS once both order-level fill deferreds already completed
 * FILLED (see [LegByLegOrderStrategy]) — that is authoritative. A slow account position feed lagging
 * behind that confirmation must not be treated as a rejection: doing so used to abandon (cancel)
 * already-filled orders and record the spread as CLOSED_REJECTED while a real, unmanaged position
 * sat in the account (no TP/SL/DTE). The adapter must trust the order-level fills and alert instead.
 */
class IbkrOrderExecutionAdapterTest {
    private val registry: IbkrOrderRegistry = mockk(relaxed = true)
    private val client: EClientSocket = mockk(relaxed = true)
    private val openOrdersAdapter: IbkrOpenOrdersAdapter = mockk(relaxed = true)
    private val strategyRouter: ExchangeStrategyRouter = mockk()
    private val reconciliationService: PositionReconciliationService = mockk()
    private val orderReplacementService: OrderReplacementService = mockk()
    private val alertPort: AlertPort = mockk(relaxed = true)

    private val adapter =
        IbkrOrderExecutionAdapter(
            registry = registry,
            client = client,
            openOrdersAdapter = openOrdersAdapter,
            strategyRouter = strategyRouter,
            reconciliationService = reconciliationService,
            orderReplacementService = orderReplacementService,
            instrumentsConfig = IbkrInstrumentsConfig(),
            alertPort = alertPort,
        )

    private val expiry = LocalDate.of(2025, 6, 20)
    private val soldContract = OptionContract(Symbol("SIE"), expiry, BigDecimal("180"), OptionType.PUT)
    private val boughtContract = OptionContract(Symbol("SIE"), expiry, BigDecimal("175"), OptionType.PUT)

    @Test
    fun `leg-by-leg reconciliation lag does not fail the entry - trusts the order-level fills and alerts`() =
        runTest {
            coEvery {
                strategyRouter.submitSpreadOrder(soldContract, boughtContract, any(), any(), any(), any())
            } returns
                OrderSubmissionResult(
                    status = SubmissionStatus.SUCCESS,
                    primaryOrderId = 10,
                    secondaryOrderId = 20,
                    message = "Both legs filled (LONG-first leg-in)",
                )
            coEvery {
                reconciliationService.verifyBothLegsFilled(
                    soldContract = soldContract,
                    boughtContract = boughtContract,
                    qty = 1,
                )
            } returns
                VerificationResult(
                    success = false,
                    shortLegFound = true,
                    longLegFound = false,
                    message = "Timeout waiting for position reconciliation (possible broken spread)",
                )

            val orderId =
                adapter.submitComboLimitOrder(
                    soldContract = soldContract,
                    boughtContract = boughtContract,
                    netCredit = Money(BigDecimal("2.00")),
                    qty = 1,
                    legQuotes = null,
                )

            assertEquals(10, orderId, "must return the primary (SHORT) order id — the position is tracked, not abandoned")
            coVerify { alertPort.send(AlertLevel.CRITICAL, any(), any()) }
        }

    @Test
    fun `leg-by-leg reconciliation success proceeds normally without alerting`() =
        runTest {
            coEvery {
                strategyRouter.submitSpreadOrder(soldContract, boughtContract, any(), any(), any(), any())
            } returns
                OrderSubmissionResult(
                    status = SubmissionStatus.SUCCESS,
                    primaryOrderId = 10,
                    secondaryOrderId = 20,
                    message = "Both legs filled (LONG-first leg-in)",
                )
            coEvery {
                reconciliationService.verifyBothLegsFilled(
                    soldContract = soldContract,
                    boughtContract = boughtContract,
                    qty = 1,
                )
            } returns
                VerificationResult(success = true, shortLegFound = true, longLegFound = true, message = "Both legs verified in account")

            val orderId =
                adapter.submitComboLimitOrder(
                    soldContract = soldContract,
                    boughtContract = boughtContract,
                    netCredit = Money(BigDecimal("2.00")),
                    qty = 1,
                    legQuotes = null,
                )

            assertEquals(10, orderId)
            coVerify(exactly = 0) { alertPort.send(any(), any(), any()) }
        }

    @Test
    fun `awaitFill falls back to the registry fill record when the deferred is gone`() =
        runTest {
            // A missing deferred does not always mean cancelled: the entry may have been consumed
            // while the order actually FILLED (e.g. a fill racing a cancel, or a deferred already
            // handed off elsewhere). The registry's fill record is the authoritative tiebreaker.
            val realRegistry = IbkrOrderRegistry()
            val realAdapter =
                IbkrOrderExecutionAdapter(
                    registry = realRegistry,
                    client = client,
                    openOrdersAdapter = openOrdersAdapter,
                    strategyRouter = strategyRouter,
                    reconciliationService = reconciliationService,
                    orderReplacementService = orderReplacementService,
                    instrumentsConfig = IbkrInstrumentsConfig(),
                    alertPort = alertPort,
                )

            // Order 42 filled, then its deferred was consumed/removed.
            realRegistry.pendingOrderStatus[42] = CompletableDeferred()
            realRegistry.onOrderStatus(42, "Filled", 1.23)
            realRegistry.pendingOrderStatus.remove(42)

            assertEquals(OrderStatus.FILLED, realAdapter.awaitFill(42), "filled order with a consumed deferred must report FILLED")
            assertEquals(OrderStatus.CANCELLED, realAdapter.awaitFill(43), "unknown order still reports CANCELLED")
        }
}
