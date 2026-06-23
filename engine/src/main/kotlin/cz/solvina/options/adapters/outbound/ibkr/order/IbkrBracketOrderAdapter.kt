package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.flag.BracketOrderIds
import cz.solvina.options.domain.features.flag.BracketOrderPort
import cz.solvina.options.domain.features.flag.EntryFill
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

@Component
class IbkrBracketOrderAdapter(
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
    private val connectionConfig: IbkrConnectionConfig,
    private val contractCache: IbkrContractCache,
) : BracketOrderPort {
    override suspend fun submitBracketOrder(
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

        val entryId = registry.nextOrderId()
        val trailId = registry.nextOrderId()

        // Parent: Stop-Market BUY at the breakout level.
        val parent =
            Order().apply {
                action("BUY")
                orderType("STP")
                auxPrice(entry.toDouble())
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

        registry.pendingOrderStatus[entryId] = CompletableDeferred()
        registry.pendingOrderStatus[trailId] = CompletableDeferred()

        logger.info {
            "[$symbol] Placing entry + trailing stop: entry=$entry initialStop=$stop trail=$trail " +
                "qty=$shares entryId=$entryId trailId=$trailId"
        }

        client.placeOrder(entryId, contract, parent)
        client.placeOrder(trailId, contract, trailStop)

        // Single protective order — return its id as both stop and target so close logic cancels it.
        return BracketOrderIds(entryId, trailId, trailId)
    }

    override suspend fun cancelOrder(orderId: Int) {
        logger.info { "Cancelling order $orderId" }
        registry.markSelfCancelled(orderId)
        client.cancelOrder(orderId, OrderCancel())
        // Give IBKR a moment to acknowledge; don't block on confirmation
        delay(200)
    }

    // Parent is a DAY order — can't survive past a single trading session
    override suspend fun awaitParentFill(orderId: Int): EntryFill {
        val status = awaitFill(orderId, PARENT_TIMEOUT_MS)
        val avgPrice = registry.consumeFillPrice(orderId)
        return EntryFill(status, avgPrice)
    }

    // Children are GTC — give them a generous safety-net before treating as stuck
    override suspend fun awaitChildFill(orderId: Int): OrderStatus = awaitFill(orderId, CHILD_TIMEOUT_MS)

    override suspend fun submitMarketSell(
        symbol: Symbol,
        shares: Int,
    ): Int {
        val contract = contractFactory.stockContract(symbol)
        val orderId = registry.nextOrderId()

        val order =
            Order().apply {
                action("SELL")
                orderType("MKT")
                totalQuantity(Decimal.get(shares.toLong()))
                tif("DAY")
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }

        registry.pendingOrderStatus[orderId] = CompletableDeferred()

        logger.info { "[$symbol] Market SELL $shares shares (orderId=$orderId)" }
        client.placeOrder(orderId, contract, order)
        return orderId
    }

    private suspend fun awaitFill(
        orderId: Int,
        timeoutMs: Long,
    ): OrderStatus {
        val deferred = registry.pendingOrderStatus[orderId] ?: return OrderStatus.CANCELLED
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "awaitFill($orderId) timed out after ${timeoutMs / 3_600_000}h — treating as CANCELLED" }
            OrderStatus.CANCELLED
        } finally {
            registry.pendingOrderStatus.remove(orderId)
        }
    }

    companion object {
        private const val PARENT_TIMEOUT_MS = 10L * 3_600_000 // 10 h — one trading session
        private const val CHILD_TIMEOUT_MS = 30L * 24 * 3_600_000 // 30 days — GTC safety net
    }
}
