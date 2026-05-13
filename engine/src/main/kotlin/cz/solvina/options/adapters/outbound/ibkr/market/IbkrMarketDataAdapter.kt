package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class IbkrMarketDataAdapter(
    private val registry: IbkrMarketDataRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
) : MarketDataPort {
    override suspend fun getUnderlyingPrice(symbol: Symbol): Money {
        val snapshot = reqMktDataSnapshot(registry, client, contractFactory.stockContract(symbol), "")
        val price =
            snapshot.last.takeIf { !it.isNaN() }
                ?: snapshot.close.takeIf { !it.isNaN() }
                ?: error("No price data available for $symbol")
        return Money(BigDecimal(price).setScale(2, RoundingMode.HALF_UP))
    }

    override suspend fun getOptionMid(contract: OptionContract): Money =
        reqMktDataSnapshot(registry, client, contractFactory.optionContract(contract), "")
            .let { snapshot -> Money(midPrice(snapshot.bid, snapshot.ask)) }
}
