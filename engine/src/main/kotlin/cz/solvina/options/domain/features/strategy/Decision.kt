package cz.solvina.options.domain.features.strategy

import java.math.BigDecimal

/**
 * A strategy's intent to open a long position: the level it wants in at, and the risk frame around
 * it. Not an order — the host turns this into a simulated bracket (backtest) or a real broker
 * bracket (live), which is where order types, tick rounding and broker mechanics belong.
 *
 * [shares] is carried here for now because sizing currently lives inside the strategy. It belongs
 * in a shared `PositionSizer` both hosts call, so that a strategy expresses *risk* and the host
 * expresses *size*; moving it is a behaviour-neutral refactor deliberately deferred so that the
 * seam could be introduced with byte-identical backtest results.
 */
data class Decision(
    val entryPrice: BigDecimal,
    val stopLossPrice: BigDecimal,
    val profitTargetPrice: BigDecimal,
    val shares: Int,
)
