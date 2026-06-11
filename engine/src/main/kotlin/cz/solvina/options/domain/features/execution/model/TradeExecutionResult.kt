package cz.solvina.options.domain.features.execution.model

import java.math.BigDecimal

data class TradeExecutionResult(
    val outcome: ExecutionOutcome,
    val creditAchieved: BigDecimal? = null,
    val comboOrderId: Int? = null,
)

enum class ExecutionOutcome {
    /** Both legs filled atomically via BAG order. */
    FILLED,

    /** Underlying moved more than driftProtectionPct while execution was in progress. */
    DRIFT_ABORTED,

    /** executionTimeoutMinutes elapsed with no fill. */
    TIMED_OUT,

    /** Price laddered down to floorCredit with no fill. */
    FLOOR_REACHED,

    /** Pre-trade check: sold or bought leg bid-ask spread exceeds maxLegBidAskSpreadPct. */
    LIQUIDITY_REJECTED,

    /** Pre-trade check: symbol already has an open spread or an in-flight execution. */
    EXPOSURE_REJECTED,

    /** Pre-trade check: available funds < maxRiskPerContract. */
    CAPITAL_REJECTED,

    /** Broker rejected the order (e.g. paper-account "guaranteed-to-lose" limit, error 201). */
    ORDER_REJECTED,

    /** Trading is disabled via scanner.trading-enabled=false; execution skipped intentionally. */
    DIAGNOSTIC_SKIPPED,

    /** Market moved >$0.05 from scanner's mid-price before submission; aborting to prevent stale-price order rejection. */
    MARKET_MOVED_TOO_FAR,
}
