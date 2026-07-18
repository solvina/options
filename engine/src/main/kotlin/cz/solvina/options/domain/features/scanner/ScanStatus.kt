package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.regime.DirectionalBias
import cz.solvina.options.domain.features.regime.TrendRegime
import cz.solvina.options.domain.features.spread.model.StrategyId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Terminal disposition of a symbol in a single scan pass. */
enum class ScanOutcome {
    /** Qualified and an execution was launched. */
    ENTERED,

    /** Evaluated by a selector but did not qualify — see [RejectReason]. */
    REJECTED,

    /** Holds an open or in-flight spread; skipped before evaluation. */
    ALREADY_OPEN,

    /** Entry cooldown active; skipped before evaluation. */
    COOLDOWN,

    /** The directional gate suppressed the only eligible strategy for this symbol. */
    GATE_SUPPRESSED,

    /** Threw during evaluation (details in logs). */
    ERROR,
}

/**
 * Why a symbol did not produce a candidate. Mirrors the concrete decision points inside
 * [BullPutCandidateSelector] / [BearCallCandidateSelector] so the UI can show exactly where each
 * symbol fell out of the funnel.
 */
enum class RejectReason {
    IV_BELOW_THRESHOLD,
    NO_EXPIRY_IN_DTE,
    NO_VALID_STRIKES,
    NO_DELTA_IN_BAND,
    NO_BOUGHT_LEG,
    CREDIT_BELOW_MIN,
    CREDIT_EXCEEDS_WIDTH,
    CRASH_PRICED,
    RISK_EXCEEDS_BUDGET,
    COMBO_BID_BELOW_FLOOR,

    /** Bear-call only: within the ex-dividend entry buffer. */
    EX_DIVIDEND_BUFFER,

    /** The selected expiry spans the symbol's next scheduled earnings report. */
    EARNINGS_BEFORE_EXPIRY,
}

/**
 * Everything a selector computed for a symbol, populated as far as evaluation reached. Fields are
 * null until the corresponding stage runs, so a row rejected at the IV gate carries only [ivRank]
 * while a full candidate carries every field.
 */
data class ScanDetail(
    val strategyId: StrategyId,
    val ivRank: Double? = null,
    val ivRankThreshold: Double? = null,
    val underlyingPrice: BigDecimal? = null,
    val expiry: LocalDate? = null,
    val dte: Int? = null,
    val shortStrike: BigDecimal? = null,
    val shortDelta: Double? = null,
    val longStrike: BigDecimal? = null,
    val width: BigDecimal? = null,
    val midCredit: BigDecimal? = null,
    val bidCredit: BigDecimal? = null,
    val maxRiskPerShare: BigDecimal? = null,
    val creditPctOfWidth: Double? = null,
)

/**
 * Result of a candidate selection. Carries the [ScanDetail] on both branches so the scan-status
 * table can report the full evaluation for rejected symbols too, not just entries.
 */
sealed interface CandidateResult {
    val detail: ScanDetail

    data class Selected(
        val request: TradeExecutionRequest,
        override val detail: ScanDetail,
    ) : CandidateResult

    data class Rejected(
        val reason: RejectReason,
        override val detail: ScanDetail,
    ) : CandidateResult
}

/** The execution request when the symbol qualified, or null when it was rejected. */
val CandidateResult.requestOrNull: TradeExecutionRequest?
    get() = (this as? CandidateResult.Selected)?.request

/**
 * One row of the scanner's current in-memory per-symbol status, rebuilt each run. Merges the
 * selector's [ScanDetail], the orchestration-level outcome, the cached directional regime, and the
 * option-chain greek coverage for the symbol.
 */
data class SymbolScanStatus(
    val symbol: String,
    val runId: Long,
    val evaluatedAt: Instant,
    val outcome: ScanOutcome,
    val strategyId: StrategyId?,
    val rejectReason: RejectReason?,
    val detail: ScanDetail?,
    val regime: TrendRegime?,
    val rsi: Double?,
    val bias: DirectionalBias?,
    val strikesRequested: Int?,
    val strikesWithGreeks: Int?,
)
