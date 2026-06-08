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
}
