package cz.solvina.options.adapters.outbound.ibkr

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ibkr.connection")
data class IbkrConnectionConfig(
    val host: String = "localhost",
    val port: Int = 7497,
    val clientId: Int = 1,
    val account: String = "",
    val useSSL: Boolean = false,
    val enabled: Boolean = false,
    val autoReconnect: Boolean = true,
    val reconnectIntervalMs: Long = 10_000,
    val paperAccount: Boolean = false,
    val useLiveMarketData: Boolean = false,
    /**
     * EUREX leg-by-leg: when the protective LONG leg fills but the SHORT leg does not, controls the
     * stranded long put. false (default, safe for paper) = keep it open, record BROKEN_LONG_ONLY and
     * alert for manual handling. true = immediately sell the long back to flat.
     */
    val unwindStrandedLongLeg: Boolean = false,
    /** EUREX leg-by-leg: how long to wait for each individual leg to fill before treating it as failed. */
    val legFillTimeoutSeconds: Long = 30,
) {
    override fun toString(): String =
        "IbkrConnectionConfig(host='$host', port=$port, clientId=$clientId, " +
            "useSSL=$useSSL, enabled=$enabled, autoReconnect=$autoReconnect, " +
            "reconnectIntervalMs=$reconnectIntervalMs)"
}
