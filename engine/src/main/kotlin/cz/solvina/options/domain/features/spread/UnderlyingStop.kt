package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.models.OptionType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Underlying price at which the underlying stop closes a spread: the short strike, moved [bufferPct]
 * of it to the safe side — above the strike for a short put, below it for a short call.
 */
fun underlyingStopTrigger(
    shortStrike: BigDecimal,
    shortType: OptionType,
    bufferPct: Double,
): BigDecimal {
    val shift = shortStrike.multiply(BigDecimal(bufferPct))
    return when (shortType) {
        OptionType.PUT -> shortStrike.add(shift)
        OptionType.CALL -> shortStrike.subtract(shift)
    }.setScale(2, RoundingMode.HALF_UP)
}

/** True when [spot] has traded through [trigger] on the losing side for a short [shortType]. */
fun underlyingStopBreached(
    spot: BigDecimal,
    trigger: BigDecimal,
    shortType: OptionType,
): Boolean =
    when (shortType) {
        OptionType.PUT -> spot <= trigger
        OptionType.CALL -> spot >= trigger
    }
