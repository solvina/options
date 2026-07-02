package cz.solvina.options.domain.features.order

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * IBKR minimum price variation grid for US option limit prices: $0.01 below $3.00, $0.05 at or
 * above $3.00. Shared by the execution and close paths so the rounding rule lives in exactly one
 * place. EUREX and other non-US exchanges have their own MPV tables, not modelled here yet — the
 * leg-by-leg strategy prices directly off fresh per-leg quotes and doesn't ladder, so this default
 * grid is not currently applied to EUREX order prices.
 */
fun optionTickFor(price: BigDecimal): BigDecimal = if (price < BigDecimal("3.00")) BigDecimal("0.01") else BigDecimal("0.05")

/** Floor-rounds a price to the tick grid (keeps SELL/credit limits marketable). */
fun BigDecimal.floorToOptionTick(): BigDecimal {
    val tick = optionTickFor(this)
    return divide(tick, 0, RoundingMode.FLOOR).multiply(tick)
}

/** Ceil-rounds a price to the tick grid (keeps BUY limits marketable). */
fun BigDecimal.ceilToOptionTick(): BigDecimal {
    val tick = optionTickFor(this)
    return divide(tick, 0, RoundingMode.CEILING).multiply(tick)
}

/** Rounds a price to the nearest tick (used for close-order limits priced at the mid). */
fun BigDecimal.roundToOptionTick(): BigDecimal {
    val tick = optionTickFor(this)
    return divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).setScale(2)
}
