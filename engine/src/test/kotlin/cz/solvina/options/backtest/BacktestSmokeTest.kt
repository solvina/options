package cz.solvina.options.backtest

import cz.solvina.options.domain.features.execution.TradeExecutionService
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerService
import cz.solvina.options.domain.features.spread.SpreadManagementService
import cz.solvina.options.domain.features.volatility.IvRankService
import cz.solvina.options.domain.features.watchlist.WatchlistPort
import cz.solvina.options.domain.models.Symbol
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
                maxOpenSpreads = 3,
                takeProfitPercent = 0.50,
                stopLossPercent = 2.00,
                timeProfitDte = 14,
            )

        val clock = MutableClock(startDate)
        val historicalAdapter = BacktestHistoricalDataAdapter(clock)
        val marketAdapter = BacktestMarketDataAdapter(clock)
        val optionChainAdapter = BacktestOptionChainAdapter(clock, config)
        val accountAdapter = BacktestAccountAdapter(initialCapital)
        val orderAdapter = BacktestOrderAdapter()
        val spreadAdapter = BacktestSpreadAdapter()
        val marketTickAdapter = BacktestMarketTickAdapter(clock, marketAdapter)
        val orderExecutionAdapter = BacktestOrderExecutionAdapter()

        val watchlistPort =
            object : WatchlistPort {
                override fun getWatchlist() = listOf(Symbol("SPY"))
            }
        val ivRankService = IvRankService(historicalAdapter, config, clock)

        val executionService =
            TradeExecutionService(
                marketTickPort = marketTickAdapter,
                orderExecutionPort = orderExecutionAdapter,
                spreadPort = spreadAdapter,
                accountPort = accountAdapter,
                config = config,
                clock = clock,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

        val scanner =
            ScannerService(
                watchlistPort = watchlistPort,
                volatilityPort = ivRankService,
                marketDataPort = marketAdapter,
                optionChainPort = optionChainAdapter,
                accountPort = accountAdapter,
                executionService = executionService,
                spreadPort = spreadAdapter,
                config = config,
                clock = clock,
            )

        val spreadManager =
            SpreadManagementService(
                spreadPort = spreadAdapter,
                marketDataPort = marketAdapter,
                orderPort = orderAdapter,
                config = config,
                clock = clock,
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
