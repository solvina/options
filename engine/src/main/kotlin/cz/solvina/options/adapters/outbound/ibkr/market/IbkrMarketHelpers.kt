package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingMarketDataRequest
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.CompletableDeferred
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter

internal suspend fun reqMktDataSnapshot(
    registry: IbkrMarketDataRegistry,
    client: EClientSocket,
    contract: Contract,
    genericTickList: String,
): MarketDataSnapshot {
    val reqId = registry.nextReqId()
    val deferred = CompletableDeferred<MarketDataSnapshot>()
    registry.pendingMarketData[reqId] = PendingMarketDataRequest(deferred)
    client.reqMktData(reqId, contract, genericTickList, true, false, null)
    return deferred.await()
}

internal fun buildStockContract(symbol: Symbol): Contract =
    Contract().apply {
        symbol(symbol.value)
        secType("STK")
        currency("USD")
        exchange("SMART")
    }

internal fun buildOptionContract(contract: OptionContract): Contract =
    Contract().apply {
        symbol(contract.symbol.value)
        secType("OPT")
        currency("USD")
        exchange("SMART")
        lastTradeDateOrContractMonth(contract.expiry.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
        strike(contract.strike.toDouble())
        right(contract.type.ibkrCode)
        multiplier("100")
    }

internal fun midPrice(
    bid: Double,
    ask: Double,
): BigDecimal {
    val b = bid.takeIf { !it.isNaN() && it > 0 }
    val a = ask.takeIf { !it.isNaN() && it > 0 }
    return when {
        b != null && a != null -> BigDecimal((b + a) / 2).setScale(4, RoundingMode.HALF_UP)
        b != null -> BigDecimal(b).setScale(4, RoundingMode.HALF_UP)
        a != null -> BigDecimal(a).setScale(4, RoundingMode.HALF_UP)
        else -> BigDecimal.ZERO
    }
}
