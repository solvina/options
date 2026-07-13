package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.domain.features.market.MarketDataHealthTracker
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.lastOrNull
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

@Component
class IbkrMarketDataAdapter(
    private val registry: IbkrMarketDataRegistry,
    private val client: EClientSocket,
    private val admission: IbkrAdmissionController,
    private val contractFactory: IbkrContractFactory,
    private val historicalDataAdapter: IbkrHistoricalDataAdapter,
    private val healthTracker: MarketDataHealthTracker,
) : MarketDataPort {
    // Every underlying-price fetch feeds the market-data flow signal (see MarketDataHealthTracker):
    // success ⇒ data is live, the "No price data" failure ⇒ starved even if the socket is connected.
    override suspend fun getUnderlyingPrice(symbol: Symbol): Money =
        try {
            resolveUnderlyingPrice(symbol).also { healthTracker.recordSuccess() }
        } catch (e: Throwable) {
            healthTracker.recordFailure("[$symbol] ${e.message}")
            throw e
        }

    private suspend fun resolveUnderlyingPrice(symbol: Symbol): Money {
        val snapshot = reqMktDataSnapshot(registry, client, admission, contractFactory.stockContract(symbol), "", SnapshotReady.STOCK_PRICE)
        val price = snapshot.last.takeIf { it > 0 } ?: snapshot.close.takeIf { it > 0 }
        if (price != null) return Money(BigDecimal(price).setScale(2, RoundingMode.HALF_UP))

        // No live/delayed snapshot — fall back to last historical close (e.g. EU symbols on paper)
        logger.debug { "[$symbol] Live price unavailable, falling back to last historical close" }
        val lastBar = runCatching { historicalDataAdapter.fetchDailyPriceBars(symbol, 5).lastOrNull() }.getOrNull()
        val histClose = lastBar?.close ?: error("No price data available for $symbol")
        logger.info { "[$symbol] Using historical close price: $histClose" }
        return Money(histClose.setScale(2, RoundingMode.HALF_UP))
    }

    override suspend fun getOptionMidLive(contract: OptionContract): Money? {
        val snapshot =
            reqMktDataSnapshot(registry, client, admission, contractFactory.optionContract(contract), "", SnapshotReady.OPTION_PRICE)
        val mid = midPrice(snapshot.bid, snapshot.ask)
        // Live bid/ask only — deliberately no Black-Scholes / previous-day fallback. Price-based
        // exit decisions must not run on synthetic data; a null tells the caller to skip the cycle.
        return if (mid > BigDecimal.ZERO) Money(mid) else null
    }

    override suspend fun getOptionMid(contract: OptionContract): Money {
        val snapshot =
            reqMktDataSnapshot(registry, client, admission, contractFactory.optionContract(contract), "", SnapshotReady.OPTION_PRICE)
        val mid = midPrice(snapshot.bid, snapshot.ask)
        if (mid > BigDecimal.ZERO) return Money(mid)

        // No live market — do NOT fabricate a price. Log for investigation and return zero so callers
        // (reporting/monitoring) render "unavailable" rather than acting on calculated data.
        logger.warn {
            "[${contract.symbol}] getOptionMid: no live bid/ask for ${contract.strike}${contract.type} " +
                "exp=${contract.expiry} (bid=${snapshot.bid} ask=${snapshot.ask}) — returning 0, no BS fallback"
        }
        return Money(BigDecimal.ZERO)
    }
}
