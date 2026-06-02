package cz.solvina.options.adapters.outbound.influxdb

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "influxdb")
data class InfluxDbProperties(
    val url: String = "http://localhost:8086",
    val token: String = "",
    val org: String = "options",
    val bucket: String = "market_data",
)
