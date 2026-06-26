package cz.solvina.options.domain.features.spread.model

/**
 * Identifies which spread strategy owns a position — the discriminator behind the strategy seam.
 * The generic core dispatches on this instead of branching on the concrete [Spread] subtype.
 */
enum class StrategyId {
    BULL_PUT,
    BEAR_CALL,
}
