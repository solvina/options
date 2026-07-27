package cz.solvina.options.adapters.outbound.ibkr

import com.ib.client.ComboLeg
import com.ib.client.Contract
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

/**
 * Builds IBKR contracts from the DB-held routing of a symbol ([UniversePort.routingFor]); NULL
 * routing columns resolve to the US default (USD / SMART / SMART / 100), so only EU listings carry
 * values. Routing edited through `/universe` takes effect immediately — the persistence adapter
 * refreshes its cache on save.
 */
@Component
class IbkrContractFactory(
    private val universePort: UniversePort,
) {
    fun defFor(symbol: Symbol): InstrumentDef =
        universePort.routingFor(symbol).let {
            InstrumentDef(
                currency = it.currency,
                exchange = it.stockExchange,
                optionExchange = it.optionExchange,
                multiplier = it.multiplier,
                marketExchange = it.marketExchange,
            )
        }

    fun stockContract(symbol: Symbol): Contract =
        Contract().apply {
            val def = defFor(symbol)
            symbol(symbol.value)
            secType("STK")
            currency(def.currency)
            exchange(def.exchange)
        }

    fun optionContract(
        contract: OptionContract,
        exchangeOverride: String? = null,
        tradingClass: String? = null,
        multiplierOverride: String? = null,
    ): Contract =
        Contract().apply {
            val def = defFor(contract.symbol)
            symbol(contract.symbol.value)
            secType("OPT")
            currency(def.currency)
            exchange(exchangeOverride ?: def.optionExchange)
            lastTradeDateOrContractMonth(contract.expiry.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
            strike(contract.strike.toDouble())
            right(contract.type.ibkrCode)
            multiplier(multiplierOverride ?: def.multiplier)
            if (!tradingClass.isNullOrBlank()) tradingClass(tradingClass)
        }

    // IBKR requires an [exchange] on market-data requests (reqMktData/reqTickByTickData) even when a
    // conId uniquely identifies the instrument — without it the request is rejected with error 321
    // "Please enter exchange" (and 200 "field #207"), which the execution path then mislabels as a
    // no-market-data timeout. The conId already pins the instrument, so the exchange only routes the
    // data feed: SMART for US, the venue (e.g. EUREX) for EU. Pass def.optionExchange.
    fun conIdContract(
        conId: Int,
        exchange: String,
    ): Contract =
        Contract().apply {
            conid(conId)
            exchange(exchange)
        }

    fun bagContract(
        soldContract: OptionContract,
        soldConId: Int,
        boughtConId: Int,
    ): Contract {
        val def = defFor(soldContract.symbol)
        val legExchange = def.optionExchange
        val soldLeg =
            ComboLeg().apply {
                conid(soldConId)
                ratio(1)
                action("SELL")
                exchange(legExchange)
                openClose(ComboLeg.OpenClose.Open)
            }
        val boughtLeg =
            ComboLeg().apply {
                conid(boughtConId)
                ratio(1)
                action("BUY")
                exchange(legExchange)
                openClose(ComboLeg.OpenClose.Open)
            }
        return Contract().apply {
            symbol(soldContract.symbol.value)
            secType("BAG")
            currency(def.currency)
            exchange("SMART")
            comboLegs(listOf(soldLeg, boughtLeg))
        }
    }
}
