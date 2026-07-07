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

    /**
     * Startup recovery could not confirm a live position for a PENDING entry (order gone from the
     * broker's open orders and both legs not held) — treated as never-opened. Distinct from
     * CLOSED_MANUAL so recovery artifacts never appear as deliberate user closes in analytics.
     */
    CLOSED_RECOVERY_UNKNOWN,

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
    ;

    companion object {
        /**
         * Statuses under which no position ever existed (or does not exist yet, for PENDING).
         * The complement is "a fill happened" — used e.g. by the daily entry throttle and by
         * analytics that must not count entry attempts as trades.
         */
        val NOT_FILLED = setOf(PENDING, CLOSED_TIMEOUT, CLOSED_REJECTED, CLOSED_RECOVERY_UNKNOWN)
    }
}
