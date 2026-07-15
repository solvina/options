package cz.solvina.options.backtest

import cz.solvina.options.domain.features.execution.SpreadEntryWriterRegistry
import cz.solvina.options.domain.features.execution.TradeExecutionService
import cz.solvina.options.domain.features.scanner.BearCallCandidateSelector
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutCandidateSelector
import cz.solvina.options.domain.features.scanner.BullPutScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerService
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.spread.SpreadCloserRegistry
import cz.solvina.options.domain.features.spread.SpreadManagementService
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.features.spread.service.QuoteHealthService
import cz.solvina.options.domain.features.spread.strategy.SpreadStrategyRegistry
import cz.solvina.options.domain.features.spread.strategy.bearcall.BearCallSpreadCloser
import cz.solvina.options.domain.features.spread.strategy.bullput.BullPutSpreadCloser
import cz.solvina.options.domain.features.spread.strategy.bullput.BullPutSpreadEntryWriter
import cz.solvina.options.domain.features.spread.strategy.bullput.BullPutStrategy
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.IvRankService
import cz.solvina.options.domain.models.Symbol
import cz.solvina.options.testutil.InMemoryBearCallSpreadPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertTrue

/**
 * End-to-end backtest smoke test — no Spring context, no TWS connection.
 *
 * Wires together all backtest adapters with the real domain services
 * (ScannerService, SpreadManagementService, IvRankService, TradeExecutionService)
 * and runs a 3-month simulation on SPY using fixture data.
 *
 * Assertions are intentionally loose — we verify structural correctness
 * (the engine runs, produces a result, no crash) rather than exact P&L
 * figures which depend on the synthetic price fixture.
 *
 * Prerequisites:
 *   - src/test/resources/fixtures/iv/SPY.csv      (committed)
 *   - src/test/resources/fixtures/prices/SPY.csv  (committed synthetic fixture)
 */
class BacktestSmokeTest {
    @Test
    fun `backtest runs without errors and produces plausible results`() {
        val startDate = LocalDate.of(2025, 1, 2)
        val endDate = LocalDate.of(2025, 3, 31)
        val initialCapital = BigDecimal("50000")

        val config =
            ScannerConfig(
                watchlist = listOf("SPY"),
                maxOpenSpreads = 3,
            )
        val bullPutConfig =
            BullPutScannerConfig(
                ivRankThreshold = 20.0, // lower threshold so we get some entries in the test period
                minDte = 30,
                maxDte = 60,
                preferredDte = 45,
                targetDelta = 0.20,
                deltaMin = 0.10,
                deltaMax = 0.35,
                spreadWidthUsd = BigDecimal("5.0"),
                minCreditPerShare = BigDecimal("0.10"),
                maxRiskPercent = 0.10,
                takeProfitPercent = 0.50,
                stopLossPercent = 2.00,
                timeProfitDte = 14,
            )

        val clock = MutableClock(startDate)
        val historicalAdapter = BacktestHistoricalDataAdapter(clock)
        val marketAdapter = BacktestMarketDataAdapter(clock)
        val optionChainAdapter = BacktestOptionChainAdapter(clock, bullPutConfig)
        val accountAdapter = BacktestAccountAdapter(initialCapital)
        val orderAdapter = BacktestOrderAdapter()
        val spreadAdapter = BacktestSpreadAdapter()
        val marketTickAdapter = BacktestMarketTickAdapter(clock, marketAdapter)
        val orderExecutionAdapter = BacktestOrderExecutionAdapter()

        val universePort =
            object : UniversePort {
                override fun getWatchlist() = listOf(Symbol("SPY"))

                override fun getFlagWatchlist() = emptyList<Symbol>()

                override fun getActiveSymbols() = listOf(Symbol("SPY"))

                override fun isMarketOpen(symbol: Symbol) = true

                override fun getMarketSchedule(symbol: Symbol) =
                    cz.solvina.options.domain.features.universe.MarketSchedule(
                        zone = java.time.ZoneId.of("America/New_York"),
                        open = java.time.LocalTime.of(9, 30),
                        close = java.time.LocalTime.of(16, 0),
                        session = "US",
                    )

                override suspend fun getAll(): List<InstrumentConfig> = listOf(InstrumentConfig(Symbol("SPY")))

                override suspend fun get(symbol: Symbol): InstrumentConfig? = null

                override suspend fun save(config: InstrumentConfig): InstrumentConfig = config

                override suspend fun delete(symbol: Symbol) = Unit
            }

        val noopIvStore =
            object : cz.solvina.options.domain.features.volatility.IvRankStorePort {
                override fun loadAll() = emptyMap<Symbol, cz.solvina.options.domain.features.volatility.StoredIvRank>()

                override suspend fun save(
                    symbol: Symbol,
                    value: cz.solvina.options.domain.features.volatility.StoredIvRank,
                ) = Unit
            }
        val ivRankService =
            IvRankService(
                historicalAdapter,
                config,
                clock,
                noopIvStore,
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            )

        val spreadQuery = SpreadQueryFacade(spreadAdapter, InMemoryBearCallSpreadPort())
        val strategyParams = StrategyParamsRegistry(listOf(bullPutConfig, BearCallScannerConfig()))
        val executionService =
            TradeExecutionService(
                marketTickPort = marketTickAdapter,
                orderExecutionPort = orderExecutionAdapter,
                spreadQuery = spreadQuery,
                universePort = universePort,
                writerRegistry = SpreadEntryWriterRegistry(listOf(BullPutSpreadEntryWriter(spreadAdapter, clock))),
                validator =
                    cz.solvina.options.domain.features.execution.PreTradeValidator(
                        spreadQuery = spreadQuery,
                        orderExecutionPort = orderExecutionAdapter,
                        effectiveAccount =
                            cz.solvina.options.domain.features.account
                                .EffectiveAccountService(accountAdapter, null),
                        config = config,
                    ),
                config = config,
                strategyParams = strategyParams,
                clock = clock,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

        val candidateSelector =
            BullPutCandidateSelector(
                volatilityPort = ivRankService,
                marketDataPort = marketAdapter,
                optionChainPort = optionChainAdapter,
                universePort = universePort,
                config = bullPutConfig,
                clock = clock,
            )

        val bearCallConfig = BearCallScannerConfig()
        val bearCallSelector =
            BearCallCandidateSelector(
                volatilityPort = ivRankService,
                marketDataPort = marketAdapter,
                optionChainPort = optionChainAdapter,
                universePort = universePort,
                config = bearCallConfig,
                clock = clock,
            )

        val scanner =
            ScannerService(
                universePort = universePort,
                bullPutSelector = candidateSelector,
                bearCallSelector = bearCallSelector,
                bearCallConfig = bearCallConfig,
                effectiveAccount =
                    cz.solvina.options.domain.features.account
                        .EffectiveAccountService(accountAdapter, null),
                executionPort = executionService,
                spreadQuery = spreadQuery,
                config = config,
                clock = clock,
            )

        val spreadManager =
            SpreadManagementService(
                closers =
                    SpreadCloserRegistry(
                        listOf(
                            BullPutSpreadCloser(spreadAdapter, clock),
                            BearCallSpreadCloser(InMemoryBearCallSpreadPort(), clock),
                        ),
                    ),
                marketDataPort = marketAdapter,
                orderPort = orderAdapter,
                universePort = universePort,
                volatilityPort = ivRankService,
                executionPort = executionService,
                config = config,
                clock = clock,
                quoteHealthService = QuoteHealthService(60, 300, 2),
                strategyRegistry = SpreadStrategyRegistry(listOf(BullPutStrategy())),
                strategyParams = strategyParams,
            )

        val engine =
            BacktestEngine(
                clock = clock,
                scanner = scanner,
                spreadManager = spreadManager,
                spreadStore = spreadAdapter,
                accountAdapter = accountAdapter,
            )

        val result = engine.run(startDate, endDate)
        println(result.summary())
        result.trades.forEach { t ->
            println(
                "  ${t.symbol} ${t.openDate}→${t.closeDate} " +
                    "credit=\$${t.creditPerShare} close=\$${t.closePricePerShare} " +
                    "P&L/contract=\$${t.pnlPerContract} reason=${t.closeReason}",
            )
        }

        // Structural correctness assertions
        assertTrue(result.startDate == startDate)
        assertTrue(result.endDate == endDate)
        assertTrue(result.initialCapital == initialCapital)
        assertTrue(result.tradeCount >= 0, "tradeCount should be non-negative")
        assertTrue(result.winRate in 0.0..1.0, "winRate should be 0–1")
        assertTrue(result.maxDrawdownPct >= 0.0, "maxDrawdown should be non-negative")
        // Capital should never go negative
        assertTrue(
            result.finalCapital > BigDecimal.ZERO,
            "finalCapital should be positive, got ${result.finalCapital}",
        )
    }
}
