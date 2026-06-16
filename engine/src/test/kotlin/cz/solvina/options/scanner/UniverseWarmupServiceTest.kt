package cz.solvina.options.scanner

import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.UniverseWarmupService
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.MarketSchedule
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.IvRank
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UniverseWarmupServiceTest {
    private fun sym(s: String) = Symbol(s)

    private fun universe(
        symbols: List<String>,
        open: Set<String>,
    ) = object : UniversePort {
        override fun getWatchlist(): List<Symbol> = symbols.map { sym(it) }

        override fun getFlagWatchlist(): List<Symbol> = emptyList()

        override fun getActiveSymbols(): List<Symbol> = symbols.filter { it in open }.map { sym(it) }

        override fun isMarketOpen(symbol: Symbol): Boolean = symbol.value in open

        override fun getMarketSchedule(symbol: Symbol) =
            MarketSchedule(ZoneId.of("America/New_York"), LocalTime.of(9, 30), LocalTime.of(16, 0), "US")

        override suspend fun getAll(): List<InstrumentConfig> = symbols.map { InstrumentConfig(sym(it)) }

        override suspend fun get(symbol: Symbol): InstrumentConfig? = null

        override suspend fun save(config: InstrumentConfig): InstrumentConfig = config

        override suspend fun delete(symbol: Symbol) = Unit
    }

    @Test
    fun `warms whole universe, open-market first, isolating failures`() =
        runTest {
            val callOrder = Collections.synchronizedList(mutableListOf<String>())
            val volatility =
                object : VolatilityPort {
                    override suspend fun getIvRank(symbol: Symbol): IvRank {
                        callOrder.add(symbol.value)
                        if (symbol.value == "CCC") error("no IV data")
                        return IvRank(rank = 50.0, currentIv = 0.3, calculatedAt = Instant.EPOCH)
                    }
                }
            val service =
                UniverseWarmupService(
                    universePort = universe(listOf("AAA", "BBB", "CCC", "DDD"), open = setOf("BBB", "DDD")),
                    volatilityPort = volatility,
                    config = ScannerConfig(warmupBatchSize = 10),
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            service.warmAll()

            val result = service.lastResult!!
            assertEquals(4, result.total)
            assertEquals(3, result.warmed, "3 succeed")
            assertEquals(1, result.failed, "CCC fails but does not abort the rest")
            assertTrue(result.done)
            assertEquals(setOf("BBB", "DDD"), callOrder.take(2).toSet(), "open-market symbols warmed first")
            assertEquals(setOf("AAA", "CCC"), callOrder.drop(2).toSet(), "closed-market symbols warmed after")
        }
}
