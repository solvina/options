package cz.solvina.options.adapters.outbound.ibkr

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ibkr.connection")
data class IbkrConnectionConfig(
    val host: String = "localhost",
    val port: Int = 7497,
    val clientId: Int = 1,
    val useSSL: Boolean = false,
    val enabled: Boolean = false,
    val autoReconnect: Boolean = true,
    val reconnectIntervalMs: Long = 10_000,
    val requestTimeoutMs: Long = 30_000,
) {
    override fun toString(): String =
        "IbkrConnectionConfig(host='$host', port=$port, clientId=$clientId, " +
            "useSSL=$useSSL, enabled=$enabled, autoReconnect=$autoReconnect, " +
            "reconnectIntervalMs=$reconnectIntervalMs, requestTimeoutMs=$requestTimeoutMs)"
}
