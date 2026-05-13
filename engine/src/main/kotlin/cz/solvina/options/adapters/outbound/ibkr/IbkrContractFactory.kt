package cz.solvina.options.adapters.outbound.ibkr

import com.ib.client.Contract
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

@Component
class IbkrContractFactory(private val config: IbkrInstrumentsConfig) {

    fun defFor(symbol: Symbol): InstrumentDef = config.instruments[symbol.value] ?: InstrumentDef()

    fun stockContract(symbol: Symbol): Contract =
        Contract().apply {
            val def = defFor(symbol)
            symbol(symbol.value)
            secType("STK")
            currency(def.currency)
            exchange(def.exchange)
        }

    fun optionContract(contract: OptionContract): Contract =
        Contract().apply {
            val def = defFor(contract.symbol)
            symbol(contract.symbol.value)
            secType("OPT")
            currency(def.currency)
            exchange(def.exchange)
            lastTradeDateOrContractMonth(contract.expiry.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
            strike(contract.strike.toDouble())
            right(contract.type.ibkrCode)
            multiplier(def.multiplier)
        }
}
