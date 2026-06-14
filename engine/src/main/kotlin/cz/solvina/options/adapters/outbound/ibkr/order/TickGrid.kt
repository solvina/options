package cz.solvina.options.adapters.outbound.ibkr.order

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * IBKR minimum price variation grid for option limit prices: $0.01 below $3.00, $0.05 at or above
 * $3.00. Shared by the order strategies so the rounding rule lives in exactly one place (X1).
 */
private fun tickFor(price: BigDecimal): BigDecimal = if (price < BigDecimal("3.00")) BigDecimal("0.01") else BigDecimal("0.05")

/** Floor-rounds a price to the tick grid (used to keep SELL/credit limits marketable). */
internal fun BigDecimal.floorToTick(): BigDecimal {
    val tick = tickFor(this)
    return divide(tick, 0, RoundingMode.FLOOR).multiply(tick)
}

/** Ceil-rounds a price to the tick grid (used to keep BUY limits marketable). */
internal fun BigDecimal.ceilToTick(): BigDecimal {
    val tick = tickFor(this)
    return divide(tick, 0, RoundingMode.CEILING).multiply(tick)
}
