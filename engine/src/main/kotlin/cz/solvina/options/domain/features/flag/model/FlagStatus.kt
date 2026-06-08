package cz.solvina.options.domain.features.flag.model

enum class FlagStatus {
    /** Bracket order submitted — awaiting parent fill. */
    PENDING,

    /** Parent (entry) order filled — position is live. */
    OPEN,

    /** Profit target child order filled. */
    CLOSED_PROFIT,

    /** Stop-loss child order filled. */
    CLOSED_STOP,

    /** Closed by end-of-day auto-liquidation (close − 15 min). */
    CLOSED_EOD,

    /** Closed manually via API. */
    CLOSED_MANUAL,

    /** Entry order placed but never filled within the timeout — no position was opened. */
    ENTRY_TIMEOUT,
}

val FlagStatus.isTerminal: Boolean
    get() =
        this in
            setOf(
                FlagStatus.CLOSED_PROFIT,
                FlagStatus.CLOSED_STOP,
                FlagStatus.CLOSED_EOD,
                FlagStatus.CLOSED_MANUAL,
                FlagStatus.ENTRY_TIMEOUT,
            )
