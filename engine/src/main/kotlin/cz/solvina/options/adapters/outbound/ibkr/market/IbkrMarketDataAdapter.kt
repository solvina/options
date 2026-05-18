package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.domain.features.market.BlackScholes
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.lastOrNull
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

private const val RISK_FREE_RATE = 0.05

@Component
class IbkrMarketDataAdapter(
    private val registry: IbkrMarketDataRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
    private val historicalDataAdapter: IbkrHistoricalDataAdapter,
    private val volatilityPort: VolatilityPort,
) : MarketDataPort {
    override suspend fun getUnderlyingPrice(symbol: Symbol): Money {
        val snapshot = reqMktDataSnapshot(registry, client, contractFactory.stockContract(symbol), "")
        val price = snapshot.last.takeIf { !it.isNaN() } ?: snapshot.close.takeIf { !it.isNaN() }
        if (price != null) return Money(BigDecimal(price).setScale(2, RoundingMode.HALF_UP))

        // No live/delayed snapshot — fall back to last historical close (e.g. EU symbols on paper)
        logger.debug { "[$symbol] Live price unavailable, falling back to last historical close" }
        val lastBar = runCatching { historicalDataAdapter.fetchDailyPriceBars(symbol, 5).lastOrNull() }.getOrNull()
        val histClose = lastBar?.close ?: error("No price data available for $symbol")
        logger.info { "[$symbol] Using historical close price: $histClose" }
        return Money(histClose.setScale(2, RoundingMode.HALF_UP))
    }

    override suspend fun getOptionMid(contract: OptionContract): Money {
        val snapshot = reqMktDataSnapshot(registry, client, contractFactory.optionContract(contract), "")
        val mid = midPrice(snapshot.bid, snapshot.ask)
        if (mid > BigDecimal.ZERO) return Money(mid)

        // No live/delayed data — fall back to Black-Scholes (e.g. US options on paper account)
        val symbol = contract.symbol
        val spot = runCatching { getUnderlyingPrice(symbol).amount.toDouble() }.getOrNull() ?: return Money(BigDecimal.ZERO)
        val sigma = volatilityPort.getIvRank(symbol).currentIv
        if (sigma <= 0.0) {
            logger.debug { "[$symbol] getOptionMid: no IV data, returning 0" }
            return Money(BigDecimal.ZERO)
        }
        val tte = ChronoUnit.DAYS.between(LocalDate.now(), contract.expiry).toInt() / 365.0
        if (tte <= 0.0) return Money(BigDecimal.ZERO)
        if (contract.type != OptionType.PUT) {
            logger.debug { "[$symbol] getOptionMid BS fallback only supports PUT, returning 0 for ${contract.type}" }
            return Money(BigDecimal.ZERO)
        }
        val bsMid = BlackScholes.putPrice(spot, contract.strike.toDouble(), tte, RISK_FREE_RATE, sigma)
        logger.debug { "[$symbol] getOptionMid BS fallback: ${contract.strike}${contract.type} bs=${"%.4f".format(bsMid)}" }
        return Money(BigDecimal(bsMid).setScale(4, RoundingMode.HALF_UP))
    }
}
