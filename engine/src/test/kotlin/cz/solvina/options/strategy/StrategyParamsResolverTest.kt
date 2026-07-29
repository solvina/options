package cz.solvina.options.strategy

import cz.solvina.options.domain.features.flag.config.FLAG_STRATEGY_ID
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import cz.solvina.options.domain.features.flag.config.FlagStrategyDescriptors
import cz.solvina.options.domain.features.flag.config.from
import cz.solvina.options.domain.features.strategy.tuning.StrategyDefaultParamsPort
import cz.solvina.options.domain.features.strategy.tuning.StrategyParamsResolver
import cz.solvina.options.domain.features.strategy.tuning.StrategySymbolParamsPort
import cz.solvina.options.domain.features.strategy.tuning.TunableStrategyRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The resolver decides what parameters a trade is actually taken on, so its precedence has to be
 * exact. A silent fall-through to a different layer would mean a symbol trading rules nobody chose.
 */
class StrategyParamsResolverTest {
    private class InMemoryDefaults : StrategyDefaultParamsPort {
        var stored: Map<String, Any?>? = null

        override suspend fun get(strategyId: String) = stored

        override suspend fun upsert(
            strategyId: String,
            params: Map<String, Any?>,
        ) {
            stored = params
        }

        override suspend fun delete(strategyId: String) {
            stored = null
        }
    }

    private class InMemorySymbols : StrategySymbolParamsPort {
        val stored = mutableMapOf<String, Map<String, Any?>>()

        override suspend fun get(
            strategyId: String,
            symbol: String,
            timeframe: String,
        ) = stored[symbol]

        override suspend fun allForStrategy(strategyId: String) = stored.toMap()

        override suspend fun upsert(
            strategyId: String,
            symbol: String,
            params: Map<String, Any?>,
            timeframe: String,
        ) {
            stored[symbol] = params
        }

        override suspend fun delete(
            strategyId: String,
            symbol: String,
            timeframe: String,
        ) {
            stored.remove(symbol)
        }
    }

    private val defaults = InMemoryDefaults()
    private val symbols = InMemorySymbols()
    private val resolver =
        StrategyParamsResolver(
            registry = TunableStrategyRegistry(listOf(FlagStrategyDescriptors())),
            defaults = defaults,
            symbolParams = symbols,
        )

    @Test
    fun `descriptor defaults are the tuned values, not the old placeholders`() {
        val config = FlagStrategyConfig.from(resolver.descriptorDefaults(FLAG_STRATEGY_ID))
        // The pre-2026-07-29 data-class defaults disabled these filters (0, 0.0, 0.0, 1). If they
        // ever come back, entries stop being filtered at all and the change looks like a no-op.
        assertEquals(90, config.skipFirstRthMinutes)
        assertEquals(1.5, config.minFlagpoleAtrMultiple)
        assertEquals(0.15, config.minFlagRetracementPct)
        assertEquals(5, config.minFlagBarsForEntry)
    }

    @Test
    fun `global baseline overrides descriptor defaults`() =
        runTest {
            defaults.stored = mapOf("skipFirstRthMinutes" to 30)
            val config = FlagStrategyConfig.from(resolver.globalParams(FLAG_STRATEGY_ID))
            assertEquals(30, config.skipFirstRthMinutes)
            // Untouched parameters keep inheriting rather than reverting to anything else.
            assertEquals(1.5, config.minFlagpoleAtrMultiple)
        }

    @Test
    fun `symbol override wins over the global baseline and merges key by key`() =
        runTest {
            defaults.stored = mapOf("skipFirstRthMinutes" to 30, "minFlagBarsForEntry" to 7)
            symbols.stored["AAPL"] = mapOf("skipFirstRthMinutes" to 0)

            val aapl = FlagStrategyConfig.from(resolver.effectiveParams(FLAG_STRATEGY_ID, "AAPL"))
            assertEquals(0, aapl.skipFirstRthMinutes)
            // The one-key override must not freeze AAPL against the rest of the baseline.
            assertEquals(7, aapl.minFlagBarsForEntry)

            val msft = FlagStrategyConfig.from(resolver.effectiveParams(FLAG_STRATEGY_ID, "MSFT"))
            assertEquals(30, msft.skipFirstRthMinutes)
        }

    @Test
    fun `deleting the baseline resets to descriptor defaults`() =
        runTest {
            defaults.stored = mapOf("skipFirstRthMinutes" to 30)
            defaults.delete(FLAG_STRATEGY_ID)
            assertEquals(90, FlagStrategyConfig.from(resolver.globalParams(FLAG_STRATEGY_ID)).skipFirstRthMinutes)
        }

    @Test
    fun `a stored value equal to the inherited one is not reported as an override`() =
        runTest {
            defaults.stored = mapOf("skipFirstRthMinutes" to 30)
            symbols.stored["AAPL"] = mapOf("skipFirstRthMinutes" to 30)
            assertTrue(resolver.overridesFor(FLAG_STRATEGY_ID, "AAPL").isEmpty())

            symbols.stored["MSFT"] = mapOf("skipFirstRthMinutes" to 45)
            val overrides = resolver.overridesFor(FLAG_STRATEGY_ID, "MSFT")
            assertEquals(45, overrides["skipFirstRthMinutes"]?.custom)
            assertEquals(30, overrides["skipFirstRthMinutes"]?.inherited)
        }

    @Test
    fun `parameters the strategy no longer declares are ignored rather than fatal`() =
        runTest {
            // A renamed or removed parameter left in a stored row must not make the symbol
            // unresolvable — that would stop the scanner for a purely cosmetic change.
            defaults.stored = mapOf("skipFirstRthMinutes" to 30, "aParameterThatNoLongerExists" to 1)
            assertEquals(30, FlagStrategyConfig.from(resolver.globalParams(FLAG_STRATEGY_ID)).skipFirstRthMinutes)
        }

    @Test
    fun `symbolsWithOverrides lists only genuinely customised symbols`() =
        runTest {
            symbols.stored["AAPL"] = mapOf("skipFirstRthMinutes" to 45)
            symbols.stored["MSFT"] = mapOf("skipFirstRthMinutes" to 90) // equals the descriptor default
            val customised = resolver.symbolsWithOverrides(FLAG_STRATEGY_ID)
            assertEquals(setOf("AAPL"), customised.keys)
        }
}
