package cz.solvina.options.adapters.outbound.ibkr

import com.ib.client.ComboLeg
import com.ib.client.Contract
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

@Component
class IbkrContractFactory(
    private val config: IbkrInstrumentsConfig,
) {
    fun defFor(symbol: Symbol): InstrumentDef = config.instruments[symbol.value] ?: InstrumentDef()

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

    fun conIdContract(conId: Int): Contract = Contract().apply { conid(conId) }

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
