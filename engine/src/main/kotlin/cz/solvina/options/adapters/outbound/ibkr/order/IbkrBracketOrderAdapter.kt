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
import cz.solvina.options.domain.features.flag.BracketOrderIds
import cz.solvina.options.domain.features.flag.BracketOrderPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

@Component
class IbkrBracketOrderAdapter(
    private val registry: IbkrOrdersRegistry,
    private val ibkrOrderIdCounter: IbkrOrderIdCounter,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
    private val connectionConfig: IbkrConnectionConfig,
    private val contractCache: IbkrContractCache,
) : BracketOrderPort {
    override fun reserveBracketOrderIds(): BracketOrderIds {
        val entryId = ibkrOrderIdCounter.nextOrderId()
        val trailId = ibkrOrderIdCounter.nextOrderId()
        return BracketOrderIds(entryId, trailId, trailId)
    }

    override fun reserveOrderId(): Int = ibkrOrderIdCounter.nextOrderId()

    override suspend fun submitBracketOrder(
        ids: BracketOrderIds,
        symbol: Symbol,
        shares: Int,
        entryPrice: BigDecimal,
        stopLossPrice: BigDecimal,
        trailAmount: BigDecimal,
    ): BracketOrderIds {
        val contract = contractFactory.stockContract(symbol)
        val qty = Decimal.get(shares.toLong())

        // Snap prices/distance to the contract's valid tick grid (IBKR rejects off-grid with err 110).
        val entry = contractCache.roundToTick(symbol, entryPrice)
        val stop = contractCache.roundToTick(symbol, stopLossPrice)
        val trail = contractCache.roundToTick(symbol, trailAmount)
        // A TRAIL child can only attach to a limit / stop-limit parent (IBKR err 328), so the entry
        // is a stop-LIMIT: trigger at the breakout, limit 0.3% above to fill with bounded slippage.
        val entryLimit = contractCache.roundToTick(symbol, entryPrice.multiply(BigDecimal("1.003")))

        val entryId = ids.entryOrderId
        // A trailing stop is represented by one order id in both legacy fields.
        val trailId = ids.stopLossOrderId
        require(ids.stopLossOrderId == ids.profitTargetOrderId) { "Flag bracket must use one trailing-stop child id" }

        // Parent: Stop-LIMIT BUY at the breakout level (stop-limit so the TRAIL child can attach).
        val parent =
            Order().apply {
                action("BUY")
                orderType("STP LMT")
                auxPrice(entry.toDouble())
                lmtPrice(entryLimit.toDouble())
                totalQuantity(qty)
                tif("DAY")
                transmit(false) // hold — submit with the child
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }

        // Child: Trailing-Stop SELL — rides the move, exits on a [trail] pullback, never below the
        // initial stop. GTC so it holds overnight (no fixed target, no EOD force-close) = best config.
        val trailStop =
            Order().apply {
                action("SELL")
                orderType("TRAIL")
                auxPrice(trail.toDouble()) // trailing distance
                trailStopPrice(stop.toDouble()) // initial stop trigger
                totalQuantity(qty)
                tif("GTC")
                parentId(entryId)
                transmit(true) // transmit the pair
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }

        logger.info {
            "[$symbol] Placing entry + trailing stop: entry=$entry initialStop=$stop trail=$trail " +
                "qty=$shares entryId=$entryId trailId=$trailId"
        }

        client.placeOrder(entryId, contract, parent)
        client.placeOrder(trailId, contract, trailStop)

        // Single protective order — return its id as both stop and target so close logic cancels it.
        return ids
    }

    override suspend fun cancelOrder(orderId: Int) {
        logger.info { "Cancelling order $orderId" }
        registry.markSelfCancelled(orderId)
        client.cancelOrder(orderId, OrderCancel())
    }

    override suspend fun submitTrailingStopSell(
        symbol: Symbol,
        shares: Int,
        initialStop: BigDecimal,
        trailAmount: BigDecimal,
    ): Int {
        val contract = contractFactory.stockContract(symbol)
        val stop = contractCache.roundToTick(symbol, initialStop)
        val trail = contractCache.roundToTick(symbol, trailAmount)
        val orderId = ibkrOrderIdCounter.nextOrderId()

        val trailStop =
            Order().apply {
                action("SELL")
                orderType("TRAIL")
                auxPrice(trail.toDouble())
                trailStopPrice(stop.toDouble())
                totalQuantity(Decimal.get(shares.toLong()))
                tif("GTC")
                transmit(true)
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }
        logger.info { "[$symbol] Re-protecting: trailing-stop SELL $shares shares stop=$stop trail=$trail (orderId=$orderId)" }
        client.placeOrder(orderId, contract, trailStop)
        return orderId
    }

    override suspend fun submitMarketSell(
        orderId: Int,
        symbol: Symbol,
        shares: Int,
    ): Int {
        val contract = contractFactory.stockContract(symbol)

        val order =
            Order().apply {
                action("SELL")
                orderType("MKT")
                totalQuantity(Decimal.get(shares.toLong()))
                tif("DAY")
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }

        logger.info { "[$symbol] Market SELL $shares shares (orderId=$orderId)" }
        client.placeOrder(orderId, contract, order)
        return orderId
    }
}
