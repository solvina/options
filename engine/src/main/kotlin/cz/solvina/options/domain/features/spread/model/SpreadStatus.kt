package cz.solvina.options.domain.features.spread.model

enum class SpreadStatus {
    PENDING,
    OPEN,
    CLOSING,
    CLOSED_PROFIT,
    CLOSED_STOP,
    CLOSED_TIME,

    /** Closed manually via API. */
    CLOSED_MANUAL,

    /** Entry order rejected by broker — no position was opened. */
    CLOSED_REJECTED,

    /** Entry order never filled (timed out / drift aborted / floor reached) — no position was opened. */
    CLOSED_TIMEOUT,

    /** Bear call force-closed to avoid ex-dividend early-assignment on the short call (US only). */
    CLOSED_DIVIDEND_RISK,

    /** Partial-fill rollback failed — unhedged exposure may exist. Requires manual intervention. */
    ROLLBACK_FAILED,

    /**
     * Leg-by-leg entry where the protective LONG leg filled but the SHORT leg did not, and
     * auto-unwind is disabled. A bounded long-debit position is open (never a naked short).
     * Surfaced for manual handling; not a clean spread.
     */
    BROKEN_LONG_ONLY,
}
