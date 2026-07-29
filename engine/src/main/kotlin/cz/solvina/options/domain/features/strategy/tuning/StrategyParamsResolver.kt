package cz.solvina.options.domain.features.strategy.tuning

import cz.solvina.options.domain.features.strategy.ParamDescriptor
import cz.solvina.options.domain.features.strategy.StrategyParams
import cz.solvina.options.domain.features.strategy.tuning.StrategySymbolParamsPort.Companion.ANY_TIMEFRAME
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * The single place the three tuning layers are collapsed into one answer:
 *
 *     ParamDescriptor.default  ->  strategy_default_params  ->  strategy_symbol_params
 *
 * Every consumer — the live flag scanner, the tuning API, the UI badge — resolves here, so
 * "what parameters is this symbol actually running with?" has exactly one implementation and cannot
 * drift between the value a screen shows and the value a trade was taken on.
 */
@Component
class StrategyParamsResolver(
    private val registry: TunableStrategyRegistry,
    private val defaults: StrategyDefaultParamsPort,
    private val symbolParams: StrategySymbolParamsPort,
) {
    /** Descriptor defaults only — the absolute defaults the Reset button restores. */
    fun descriptorDefaults(strategyId: String): StrategyParams = StrategyParams.resolve(descriptorsOf(strategyId))

    /** Descriptor defaults with the saved global baseline applied. What an un-overridden symbol runs. */
    suspend fun globalParams(strategyId: String): StrategyParams =
        StrategyParams.resolve(descriptorsOf(strategyId), sanitise(strategyId, defaults.get(strategyId)))

    /**
     * The effective parameters for [symbol]: global baseline with that symbol's override applied.
     *
     * Overrides are merged key-by-key rather than wholesale, so an override that names one parameter
     * keeps inheriting the other fourteen — otherwise saving a single custom value would silently
     * freeze a symbol against every future change to the global baseline.
     */
    suspend fun effectiveParams(
        strategyId: String,
        symbol: String,
        timeframe: String = ANY_TIMEFRAME,
    ): StrategyParams {
        val merged =
            sanitise(strategyId, defaults.get(strategyId)) +
                sanitise(strategyId, symbolParams.get(strategyId, symbol, timeframe))
        return StrategyParams.resolve(descriptorsOf(strategyId), merged)
    }

    /**
     * Which parameters [symbol] overrides, as `name -> (custom, inherited)`. Empty when the symbol
     * runs the global baseline. Drives the Candle Scanner marker and its hover detail.
     */
    suspend fun overridesFor(
        strategyId: String,
        symbol: String,
        timeframe: String = ANY_TIMEFRAME,
    ): Map<String, ParamOverride> {
        val override = sanitise(strategyId, symbolParams.get(strategyId, symbol, timeframe))
        if (override.isEmpty()) return emptyMap()
        val global = globalParams(strategyId).asMap()
        // A row whose value equals the inherited one is not an override in any sense the operator
        // cares about — badging it would cry wolf on every symbol someone opened and saved unchanged.
        return override
            .filter { (name, value) -> !valuesEqual(value, global[name]) }
            .mapValues { (name, value) -> ParamOverride(custom = value, inherited = global[name]) }
    }

    /** Every symbol with a real override for [strategyId]. One query, for list screens. */
    suspend fun symbolsWithOverrides(strategyId: String): Map<String, Map<String, ParamOverride>> {
        val all = symbolParams.allForStrategy(strategyId)
        if (all.isEmpty()) return emptyMap()
        val global = globalParams(strategyId).asMap()
        return all
            .mapValues { (_, override) ->
                sanitise(strategyId, override)
                    .filter { (name, value) -> !valuesEqual(value, global[name]) }
                    .mapValues { (name, value) -> ParamOverride(custom = value, inherited = global[name]) }
            }.filterValues { it.isNotEmpty() }
    }

    private fun descriptorsOf(strategyId: String): List<ParamDescriptor> = registry.require(strategyId).params

    /**
     * Drops keys the strategy no longer declares.
     *
     * [StrategyParams.resolve] rejects unknown keys outright, which is right for an API request — a
     * typo must not quietly produce a different strategy than the one that was backtested. It is
     * wrong for a stored row: renaming a parameter would leave every symbol permanently unresolvable
     * and stop the scanner dead. Persisted rows are therefore filtered and the drop is logged.
     */
    private fun sanitise(
        strategyId: String,
        stored: Map<String, Any?>?,
    ): Map<String, Any?> {
        if (stored.isNullOrEmpty()) return emptyMap()
        val known = descriptorsOf(strategyId).map { it.name }.toSet()
        val stale = stored.keys - known
        if (stale.isNotEmpty()) {
            logger.warn {
                "[$strategyId] Ignoring stored params no longer declared by the strategy: ${stale.sorted()} " +
                    "— they will be dropped on the next save"
            }
        }
        return stored.filterKeys { it in known }
    }

    /** Numeric comparison by value, so 5 loaded as 5.0 from JSON is not reported as an override. */
    private fun valuesEqual(
        a: Any?,
        b: Any?,
    ): Boolean =
        when {
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            else -> a == b
        }
}

data class ParamOverride(
    val custom: Any?,
    val inherited: Any?,
)
