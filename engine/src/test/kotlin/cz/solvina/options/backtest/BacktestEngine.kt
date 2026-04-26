package cz.solvina.options.backtest

import cz.solvina.options.domain.features.scanner.ScannerService
import cz.solvina.options.domain.features.spread.SpreadManagementService
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Drives the backtest simulation.
 *
 * Each market day (Mon–Fri):
 *   1. Advance the clock to that date.
 *   2. Run ScannerService.scan() — looks for new entries.
 *   3. Run SpreadManagementService.checkExits() — closes positions at TP/SL/DTE.
 *   4. Debit collateral for newly opened spreads; credit it back (± P&L) for newly closed ones.
 *
 * Capital model:
 *   - When a spread opens: reserve maxRiskPerContract (maxRiskPerShare × 100) as collateral.
 *   - When a spread closes: release collateral and book P&L =
 *       (creditPerShare − closePricePerShare) × 100 − 4 × commission (open+close, 2 legs each).
 *
 * Weekends are skipped; no public-holiday calendar is applied — the fixture
 * adapter falls back to the last available bar on non-trading days.
 */
class BacktestEngine(
    private val clock: MutableClock,
    private val scanner: ScannerService,
    private val spreadManager: SpreadManagementService,
    private val spreadStore: BacktestSpreadAdapter,
    private val accountAdapter: BacktestAccountAdapter,
    private val commissionPerLeg: Double = 0.65,
) {
    fun run(
        startDate: LocalDate,
        endDate: LocalDate,
    ): BacktestResult =
        runBlocking {
            val initialCapital =
                accountAdapter.accountDetail.value
                    ?.totalCapital
                    ?.amount
                    ?: error("Account not initialised")

            var peak = initialCapital
            var maxDrawdownPct = 0.0
            val processedClosedIds = mutableSetOf<UUID>()

            var date = startDate
            while (!date.isAfter(endDate)) {
                if (isMarketDay(date)) {
                    clock.advanceTo(date)
                    logger.debug { "--- $date ---" }

                    val openBefore = spreadStore.findOpen().map { it.id }.toSet()

                    scanner.scan()
                    spreadManager.checkExits()

                    // Newly opened spreads — debit collateral
                    for (spread in spreadStore.findOpen().filter { it.id !in openBefore }) {
                        val collateral = spread.maxRiskPerShare.multiply(BigDecimal("100"))
                        accountAdapter.debit(collateral)
                        logger.info { "[${spread.symbol}] Opened — collateral reserved: \$$collateral" }
                    }

                    // Newly closed spreads — credit collateral + book P&L
                    for (spread in newlyClosed(processedClosedIds)) {
                        settleClosedSpread(spread, processedClosedIds)
                    }

                    // Drawdown tracking
                    val capital =
                        accountAdapter.accountDetail.value
                            ?.totalCapital
                            ?.amount ?: initialCapital
                    if (capital > peak) peak = capital
                    val ddPct =
                        peak
                            .subtract(capital)
                            .divide(peak, 6, RoundingMode.HALF_UP)
                            .toDouble() * 100.0
                    if (ddPct > maxDrawdownPct) maxDrawdownPct = ddPct
                }
                date = date.plusDays(1)
            }

            buildResult(startDate, endDate, initialCapital, maxDrawdownPct)
        }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private suspend fun newlyClosed(processedIds: Set<UUID>): List<BullPutSpread> =
        spreadStore.findAll().filter { it.status != SpreadStatus.OPEN && it.id != null && it.id !in processedIds }

    private fun settleClosedSpread(
        spread: BullPutSpread,
        processedIds: MutableSet<UUID>,
    ) {
        val id = spread.id ?: return
        processedIds.add(id)
        val collateral = spread.maxRiskPerShare.multiply(BigDecimal("100"))
        val closePrice = spread.closePricePerShare ?: BigDecimal.ZERO
        val pnlPerContract = spread.creditPerShare.subtract(closePrice).multiply(BigDecimal("100"))
        val commission = BigDecimal(commissionPerLeg * 4) // 2 legs × (open + close)
        val netPnl = pnlPerContract.subtract(commission)
        accountAdapter.credit(collateral.add(netPnl))
        logger.info {
            "[${spread.symbol}] Closed (${spread.status}) — " +
                "P&L/contract \$${"%.2f".format(pnlPerContract)} net \$${"%.2f".format(netPnl)}"
        }
    }

    private fun isMarketDay(date: LocalDate): Boolean {
        val dow = date.dayOfWeek
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
    }

    private suspend fun buildResult(
        startDate: LocalDate,
        endDate: LocalDate,
        initialCapital: BigDecimal,
        maxDrawdownPct: Double,
    ): BacktestResult {
        val finalCapital =
            accountAdapter.accountDetail.value
                ?.totalCapital
                ?.amount
                ?: initialCapital
        val trades =
            spreadStore
                .findAll()
                .filter { it.status != SpreadStatus.OPEN }
                .mapNotNull { spread ->
                    val closePrice = spread.closePricePerShare ?: return@mapNotNull null
                    val pnlPerShare = spread.creditPerShare.subtract(closePrice)
                    ClosedTrade(
                        symbol = spread.symbol,
                        openDate = LocalDate.ofInstant(spread.openedAt, clock.zone),
                        closeDate = LocalDate.ofInstant(spread.closedAt ?: spread.openedAt, clock.zone),
                        creditPerShare = spread.creditPerShare,
                        closePricePerShare = closePrice,
                        pnlPerShare = pnlPerShare,
                        pnlPerContract = pnlPerShare.multiply(BigDecimal("100")),
                        closeReason = spread.closeReason,
                    )
                }

        val wins = trades.count { it.pnlPerShare > BigDecimal.ZERO }
        return BacktestResult(
            startDate = startDate,
            endDate = endDate,
            initialCapital = initialCapital,
            finalCapital = finalCapital,
            totalPnl = finalCapital.subtract(initialCapital),
            tradeCount = trades.size,
            winCount = wins,
            winRate = if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size,
            maxDrawdownPct = maxDrawdownPct,
            trades = trades,
        )
    }
}
