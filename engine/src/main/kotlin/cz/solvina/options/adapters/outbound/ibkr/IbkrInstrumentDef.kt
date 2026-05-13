package cz.solvina.options.adapters.outbound.ibkr

import org.springframework.boot.context.properties.ConfigurationProperties

data class InstrumentDef(
    val currency: String = "USD",
    val exchange: String = "SMART",
    val multiplier: String = "100",
)

@ConfigurationProperties(prefix = "ibkr")
data class IbkrInstrumentsConfig(
    val instruments: Map<String, InstrumentDef> = emptyMap(),
)
