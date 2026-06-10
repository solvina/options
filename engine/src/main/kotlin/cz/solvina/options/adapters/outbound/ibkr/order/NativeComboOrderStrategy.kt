package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

/**
 * Strategy for US options exchanges (CBOE, ISE, etc.)
 *
 * These exchanges support native combo/complex orders:
 * - Both legs are guaranteed to fill together or not at all
 * - Single order submission with atomic execution
 * - Most reliable approach for credit spreads
 */
class NativeComboOrderStrategy(
    private val exchangeId: String = "CBOE",
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val contractCache: IbkrContractCache,
    private val connectionConfig: IbkrConnectionConfig,
) : OrderExecutionStrategy {
    override fun getExchangeId(): String = exchangeId

    override suspend fun submitSpreadOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
    ): OrderSubmissionResult {
        // Validate first
        val validation = validateOrder(soldContract, boughtContract, netCredit)
        if (!validation.isValid) {
            return OrderSubmissionResult(
                status = SubmissionStatus.REJECTED,
                primaryOrderId = 0,
                message = validation.reason,
            )
        }

        return try {
            // Build native combo contract
            val soldConId = contractCache.getOrFetchOptionConId(
                OptionContractKey(
                    symbol = soldContract.symbol,
                    expiry = soldContract.expiry,
                    strike = soldContract.strike,
                    optionType = soldContract.type,
                ),
            )
            val boughtConId = contractCache.getOrFetchOptionConId(
                OptionContractKey(
                    symbol = boughtContract.symbol,
                    expiry = boughtContract.expiry,
                    strike = boughtContract.strike,
                    optionType = boughtContract.type,
                ),
            )

            val bagContract =
                buildNativeComboContract(
                    soldConId = soldConId,
                    boughtConId = boughtConId,
                )

            // Create BUY order with negative limit (IBKR convention for net credit)
            val order =
                Order().apply {
                    action("BUY")
                    orderType("LMT")
                    lmtPrice(-netCredit.amount.floorToTick().toDouble()) // Negative = net credit accepted
                    totalQuantity(Decimal.get(qty.toLong()))
                    tif("DAY")
                    if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
                }

            val orderId = registry.nextOrderId()
            val deferred = CompletableDeferred<OrderStatus>()
            registry.pendingOrderStatus[orderId] = deferred

            logger.info {
                "[$exchangeId] Submitting NATIVE COMBO order: SELL ${soldContract.strike}P / BUY ${boughtContract.strike}P " +
                    "@ net \$${netCredit.amount} orderId=$orderId (both legs atomic)"
            }

            client.placeOrder(orderId, bagContract, order)

            OrderSubmissionResult(
                status = SubmissionStatus.SUCCESS,
                primaryOrderId = orderId,
                secondaryOrderId = null,
                requiresManualMatching = false,
            )
        } catch (e: Exception) {
            logger.error(e) { "[$exchangeId] Failed to submit native combo order" }
            OrderSubmissionResult(
                status = SubmissionStatus.SYSTEM_ERROR,
                primaryOrderId = 0,
                message = e.message ?: "Unknown error",
            )
        }
    }

    override fun validateOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
    ): ValidationResult {
        // Validate expiry matches
        if (soldContract.expiry != boughtContract.expiry) {
            return ValidationResult(false, "Legs must have same expiry date")
        }

        // Validate strike relationship (sold should be higher for put spreads)
        if (soldContract.strike <= boughtContract.strike) {
            return ValidationResult(false, "Short strike must be > long strike for put spread")
        }

        // Validate credit is positive
        if (netCredit.amount <= BigDecimal.ZERO) {
            return ValidationResult(false, "Net credit must be positive (you're receiving money)")
        }

        return ValidationResult(true)
    }

    override fun notes(): String = "Native combo order (atomic): both legs fill together or order cancelled. Most reliable."

    private fun buildNativeComboContract(
        soldConId: Int,
        boughtConId: Int,
    ): Contract {
        val soldLeg =
            com.ib.client.ComboLeg().apply {
                conid(soldConId)
                ratio(1)
                action("SELL")
                exchange(exchangeId)
            }

        val boughtLeg =
            com.ib.client.ComboLeg().apply {
                conid(boughtConId)
                ratio(1)
                action("BUY")
                exchange(exchangeId)
            }

        return Contract().apply {
            symbol("") // Empty for BAG orders
            secType("BAG")
            exchange(exchangeId)
            currency("USD")
            comboLegs(listOf(soldLeg, boughtLeg))
        }
    }
}

/** Floor-rounds a credit amount to the IBKR minimum price variation grid ($0.01 below $3, $0.05 at or above $3). */
private fun BigDecimal.floorToTick(): BigDecimal {
    val tick = if (this < BigDecimal("3.00")) BigDecimal("0.01") else BigDecimal("0.05")
    return divide(tick, 0, RoundingMode.FLOOR).multiply(tick)
}
