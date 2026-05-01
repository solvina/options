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
}
