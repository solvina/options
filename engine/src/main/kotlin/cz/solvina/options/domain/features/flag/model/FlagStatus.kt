package cz.solvina.options.domain.features.flag.model

enum class FlagStatus {
    /** Bracket order submitted — awaiting parent fill. */
    PENDING,

    /** Parent (entry) order filled — position is live. */
    OPEN,

    /** Close order submitted — waiting for broker terminal status. */
    CLOSING,

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

    /**
     * The exit already filled at the broker while the engine was down (e.g. the trailing
     * stop fired during a restart). Closed administratively — realized P&L
     * is unknown because the actual exit price was never observed.
     */
    CLOSED_EXTERNAL,
    ;

    companion object {
        val ACTIVE_STATUSES: Set<FlagStatus> = setOf(PENDING, OPEN, CLOSING)
        val BROKER_POSITION_STATUSES: Set<FlagStatus> = setOf(OPEN, CLOSING)
        val TERMINAL_STATUSES: Set<FlagStatus> =
            setOf(
                CLOSED_PROFIT,
                CLOSED_STOP,
                CLOSED_EOD,
                CLOSED_MANUAL,
                ENTRY_TIMEOUT,
                CLOSED_EXTERNAL,
            )
    }
}

val FlagStatus.isActive: Boolean
    get() = this in FlagStatus.ACTIVE_STATUSES

val FlagStatus.hasBrokerPosition: Boolean
    get() = this in FlagStatus.BROKER_POSITION_STATUSES

val FlagStatus.isTerminal: Boolean
    get() = this in FlagStatus.TERMINAL_STATUSES
