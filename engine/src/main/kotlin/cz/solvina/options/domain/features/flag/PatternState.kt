package cz.solvina.options.domain.features.flag

sealed interface PatternState {
    /** No setup detected yet — watching for a flagpole. */
    data object Idle : PatternState

    /** A momentum pole has been confirmed; waiting for consolidation to form. */
    data class FlagpoleDetected(
        val pole: Flagpole,
        val consolidationBars: Int = 0,
    ) : PatternState

    /** A flag (consolidation channel) is forming after a confirmed pole. */
    data class FlagForming(
        val pole: Flagpole,
        val flag: Flag,
    ) : PatternState

    /** Flag resistance line has been broken — ready to enter. */
    data class BreakoutReady(
        val pole: Flagpole,
        val flag: Flag,
        /** The resistance level at the breakout bar index */
        val resistanceLevel: Double,
    ) : PatternState
}
