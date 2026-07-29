package cz.solvina.options.domain.features.strategy.tuning

/**
 * Persistence for the two override layers above a strategy's descriptor defaults.
 *
 * Both stores speak raw `Map<String, Any?>` rather than a typed config: the tuning layer must not
 * know what any particular strategy's parameters mean, or adding a parameter would cost a schema
 * change again. Typing happens once, at the edge, in
 * [cz.solvina.options.domain.features.strategy.StrategyParams.resolve].
 *
 * Absent = inherit the layer above. Neither store ever writes an "empty" row to mean defaults.
 */
interface StrategyDefaultParamsPort {
    /** Tuned baseline for [strategyId], or null when nothing has been saved (use descriptor defaults). */
    suspend fun get(strategyId: String): Map<String, Any?>?

    suspend fun upsert(
        strategyId: String,
        params: Map<String, Any?>,
    )

    /** Reset to descriptor defaults. Deleting is the reset — there is no second copy of the values. */
    suspend fun delete(strategyId: String)
}

interface StrategySymbolParamsPort {
    suspend fun get(
        strategyId: String,
        symbol: String,
        timeframe: String = ANY_TIMEFRAME,
    ): Map<String, Any?>?

    /** Every symbol override for [strategyId], keyed by symbol. Drives the "customised" UI badge. */
    suspend fun allForStrategy(strategyId: String): Map<String, Map<String, Any?>>

    suspend fun upsert(
        strategyId: String,
        symbol: String,
        params: Map<String, Any?>,
        timeframe: String = ANY_TIMEFRAME,
    )

    suspend fun delete(
        strategyId: String,
        symbol: String,
        timeframe: String = ANY_TIMEFRAME,
    )

    companion object {
        /** Applies at every timeframe. See the v38 migration for why this is '*' and not NULL. */
        const val ANY_TIMEFRAME = "*"
    }
}
