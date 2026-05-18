package cz.solvina.options.adapters.outbound.ibkr

import org.springframework.boot.context.properties.ConfigurationProperties

data class ExchangeHours(
    val timezone: String,
    val open: String,
    val close: String,
)

data class InstrumentDef(
    val currency: String = "USD",
    val exchange: String = "SMART",
    val optionExchange: String = "SMART",
    val multiplier: String = "100",
    val marketExchange: String = "US",
)

@ConfigurationProperties(prefix = "ibkr")
data class IbkrInstrumentsConfig(
    val exchanges: Map<String, ExchangeHours> = emptyMap(),
    val instruments: Map<String, InstrumentDef> = emptyMap(),
)
