package cz.solvina.options.domain.features.alert

/** Severity of an operational alert. */
enum class AlertLevel(
    val emoji: String,
) {
    INFO("ℹ️"),
    WARN("⚠️"),
    CRITICAL("🔴"),
}

/**
 * Outbound port for operational alerts (orphaned positions, stale market data, etc.).
 * Implementations must be best-effort and non-throwing — a failed alert must never break
 * the calling job.
 */
interface AlertPort {
    suspend fun send(
        level: AlertLevel,
        title: String,
        body: String,
    )
}
