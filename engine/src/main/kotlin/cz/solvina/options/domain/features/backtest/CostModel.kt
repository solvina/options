package cz.solvina.options.domain.features.backtest

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Round-trip trading costs — broker commission plus the spread you actually cross.
 *
 * Charged as **cash against realized P&L**, never folded into the fill price. Adjusting the fill
 * would move the entry, which moves the stop distance, which silently rescales every R-multiple in
 * the run — the cost would then be invisible in the one place a reader looks to judge it. As a cash
 * line it stays separable and [BacktestEngine.Summary.totalCosts] reports the drag outright.
 *
 * [NONE] is the default everywhere so existing runs and stored sweeps reproduce byte-identically;
 * the stock backtest endpoint opts into a realistic model, because that is where the go/no-go
 * calls get made and a gross-P&L answer there is worse than no answer.
 *
 * The defaults below are list-price approximations, not quotes — tune them to your own fills.
 */
data class CostModel(
    /** Per-share commission (IBKR US tiered ≈ $0.0035). */
    val commissionPerShare: BigDecimal = BigDecimal.ZERO,
    /** Commission as a percent of notional (European venues price this way). */
    val commissionPctOfNotional: BigDecimal = BigDecimal.ZERO,
    /** Per-order floor applied after the two above are summed. */
    val minCommissionPerOrder: BigDecimal = BigDecimal.ZERO,
    /**
     * Half-spread plus market impact, in basis points of notional, charged on EVERY side. The
     * backtest fills at the next bar's open — a price no live order is guaranteed — so this is the
     * honesty knob for that assumption, not an optional extra.
     */
    val slippageBps: BigDecimal = BigDecimal.ZERO,
) {
    /** Cost of ONE side (entry or exit): [shares] at [price]. */
    fun oneWay(
        shares: Int,
        price: BigDecimal,
    ): BigDecimal {
        if (shares <= 0 || price <= BigDecimal.ZERO) return BigDecimal.ZERO
        val notional = price.multiply(BigDecimal(shares))
        val commission =
            commissionPerShare
                .multiply(BigDecimal(shares))
                .add(notional.multiply(commissionPctOfNotional).divide(HUNDRED, 6, RoundingMode.HALF_UP))
                .max(minCommissionPerOrder)
        val slippage = notional.multiply(slippageBps).divide(BPS, 6, RoundingMode.HALF_UP)
        return commission.add(slippage).setScale(2, RoundingMode.HALF_UP)
    }

    /** Entry + exit for one position. */
    fun roundTrip(
        shares: Int,
        entryPrice: BigDecimal,
        exitPrice: BigDecimal,
    ): BigDecimal = oneWay(shares, entryPrice).add(oneWay(shares, exitPrice))

    val isFree: Boolean
        get() = this == NONE

    companion object {
        private val HUNDRED = BigDecimal("100")
        private val BPS = BigDecimal("10000")

        /** Gross P&L — the historical behaviour of every run made before costs existed. */
        val NONE = CostModel()

        /** IBKR US tiered stocks: $0.0035/share, $0.35 order floor, 5 bps crossed. */
        val IBKR_US_STOCK =
            CostModel(
                commissionPerShare = BigDecimal("0.0035"),
                minCommissionPerOrder = BigDecimal("0.35"),
                slippageBps = BigDecimal("5"),
            )

        /**
         * IBKR Xetra/IBIS ETFs: 0.05% of notional with a €4 floor, 10 bps crossed — European
         * sector ETFs quote wider than US large caps, and the floor bites hard on small orders.
         */
        val IBKR_EU_ETF =
            CostModel(
                commissionPctOfNotional = BigDecimal("0.05"),
                minCommissionPerOrder = BigDecimal("4.00"),
                slippageBps = BigDecimal("10"),
            )

        /**
         * A short label for a stored run's parameter record. A preset gets its name; anything else
         * gets its four numbers spelled out, so a hand-tuned model is still legible a month later
         * rather than collapsing to "custom".
         */
        fun nameOf(model: CostModel): String =
            when (model) {
                NONE -> "none"
                IBKR_US_STOCK -> "ibkr_us_stock"
                IBKR_EU_ETF -> "ibkr_eu_etf"
                else ->
                    "custom(perShare=${model.commissionPerShare}, pctNotional=${model.commissionPctOfNotional}, " +
                        "min=${model.minCommissionPerOrder}, slipBps=${model.slippageBps})"
            }
    }
}
