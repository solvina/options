package cz.solvina.options.domain.features.strategy

/**
 * Anything with a tunable parameter surface that the UI can render and the operator can override.
 *
 * Extracted from [StockStrategy] so the tuning machinery is not limited to the stock strategy
 * library. The flag strategy is a bespoke live service, not a [StockStrategy] — it cannot become one
 * cheaply, because its live-only 5-second `LIVE_BAR` trigger has no backtest counterpart (see the
 * warning in [StockStrategy]'s own documentation). It can, however, honestly describe its parameters,
 * and that is all the tuning layer needs.
 *
 * [id] is persisted in `strategy_default_params.strategy_id` and `strategy_symbol_params.strategy_id`.
 * Never rename one in place: the rows would silently orphan and every symbol would fall back to
 * descriptor defaults without saying so.
 */
interface TunableStrategy {
    /** Stable identifier, persisted on runs, assignments, positions and parameter overrides. */
    val id: String

    val displayName: String

    /** Declares the tunable surface — drives validation, the sweep grid and the generic UI form. */
    val params: List<ParamDescriptor>
}
