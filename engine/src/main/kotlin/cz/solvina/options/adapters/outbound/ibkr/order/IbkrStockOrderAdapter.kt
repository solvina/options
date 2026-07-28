package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOrdersRegistry
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderIdCounter
import cz.solvina.options.domain.features.strategy.live.StockOrderIds
import cz.solvina.options.domain.features.strategy.live.StockOrderPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

/**
 * Day-limit entry with broker-side OCA protection, for the stock strategy library.
 *
 * Separate from [IbkrBracketOrderAdapter] because the order shape is genuinely different, not
 * merely differently parameterised — see [StockOrderPort].
 */
@Profile("!backtest")
@Component
class IbkrStockOrderAdapter(
    private val registry: IbkrOrdersRegistry,
    private val ibkrOrderIdCounter: IbkrOrderIdCounter,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
    private val connectionConfig: IbkrConnectionConfig,
    private val contractCache: IbkrContractCache,
) : StockOrderPort {
    override fun reserveOrderIds(): StockOrderIds =
        StockOrderIds(
            entryOrderId = ibkrOrderIdCounter.nextOrderId(),
            stopOrderId = ibkrOrderIdCounter.nextOrderId(),
            targetOrderId = ibkrOrderIdCounter.nextOrderId(),
        )

    override suspend fun submitLimitEntryWithProtection(
        ids: StockOrderIds,
        symbol: Symbol,
        shares: Int,
        limitPrice: BigDecimal,
        stopPrice: BigDecimal,
        targetPrice: BigDecimal?,
    ): StockOrderIds {
        val contract = contractFactory.stockContract(symbol)
        val qty = Decimal.get(shares.toLong())

        // Snap to the contract's tick grid — IBKR rejects off-grid prices with err 110.
        val limit = contractCache.roundToTick(symbol, limitPrice)
        val stop = contractCache.roundToTick(symbol, stopPrice)
        val target = targetPrice?.let { contractCache.roundToTick(symbol, it) }

        // Children share an OCA group so a stop fill cancels the target and vice versa. Without it a
        // stopped-out position would leave a live sell target that could later go short on a bounce.
        val ocaGroup = "stk-${ids.entryOrderId}"

        val parent =
            Order().apply {
                action("BUY")
                orderType("LMT")
                lmtPrice(limit.toDouble())
                totalQuantity(qty)
                // DAY, not GTC: an unfilled entry must expire with the session rather than fill days
                // later against a signal that has gone stale.
                tif("DAY")
                transmit(false)
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }

        val stopChild =
            Order().apply {
                action("SELL")
                orderType("STP")
                auxPrice(stop.toDouble())
                totalQuantity(qty)
                // GTC so protection survives this process dying — the 2026-07-24 lesson.
                tif("GTC")
                parentId(ids.entryOrderId)
                ocaGroup(ocaGroup)
                ocaType(OCA_REDUCE_WITH_BLOCK)
                transmit(target == null)
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }

        logger.info {
            "[$symbol] Placing day-limit entry + protection: limit=$limit stop=$stop " +
                "target=${target ?: "none"} qty=$shares entryId=${ids.entryOrderId}"
        }

        client.placeOrder(ids.entryOrderId, contract, parent)
        client.placeOrder(ids.stopOrderId, contract, stopChild)

        if (target != null) {
            val targetChild =
                Order().apply {
                    action("SELL")
                    orderType("LMT")
                    lmtPrice(target.toDouble())
                    totalQuantity(qty)
                    tif("GTC")
                    parentId(ids.entryOrderId)
                    ocaGroup(ocaGroup)
                    ocaType(OCA_REDUCE_WITH_BLOCK)
                    transmit(true) // last child transmits the whole bracket
                    if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
                }
            client.placeOrder(ids.targetOrderId, contract, targetChild)
        }

        return ids
    }

    override suspend fun cancelOrder(orderId: Int) {
        logger.info { "Cancelling stock order $orderId" }
        registry.markSelfCancelled(orderId)
        client.cancelOrder(orderId, OrderCancel())
    }

    private companion object {
        /**
         * OCA type 1: cancel the sibling and block any further fills on it. Types 2 and 3 only
         * *reduce* the sibling's size and explicitly permit overfill — on a 1-lot protective pair
         * that would mean both legs filling and leaving a short.
         */
        const val OCA_REDUCE_WITH_BLOCK = 1
    }
}
