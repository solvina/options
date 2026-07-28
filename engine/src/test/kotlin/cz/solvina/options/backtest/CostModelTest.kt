package cz.solvina.options.backtest

import cz.solvina.options.domain.features.backtest.CostModel
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cost model decides go/no-go on every strategy whose profit factor sits near 1, so its
 * arithmetic is pinned here rather than trusted to inspection.
 */
class CostModelTest {
    @Test
    fun `NONE charges nothing so pre-cost runs reproduce`() {
        val free = CostModel.NONE
        assertEquals(BigDecimal("0.00"), free.oneWay(1000, BigDecimal("50")))
        assertEquals(BigDecimal("0.00"), free.roundTrip(1000, BigDecimal("50"), BigDecimal("55")))
        assertTrue(free.isFree)
    }

    @Test
    fun `per-share commission and slippage both apply on each side`() {
        // 100 sh @ $50 = $5,000 notional.
        //   commission = 100 x 0.0035 = 0.35, which equals the floor, so 0.35
        //   slippage   = 5,000 x 5bps = 2.50
        val cost = CostModel.IBKR_US_STOCK.oneWay(100, BigDecimal("50"))
        assertEquals(BigDecimal("2.85"), cost)
    }

    @Test
    fun `order floor dominates a tiny order`() {
        // 10 sh @ $20 = $200. Per-share commission is 10 x 0.0035 = 0.035, far under the 0.35
        // floor — the floor is what makes small, frequent trades expensive.
        val model = CostModel(commissionPerShare = BigDecimal("0.0035"), minCommissionPerOrder = BigDecimal("0.35"))
        assertEquals(BigDecimal("0.35"), model.oneWay(10, BigDecimal("20")))
    }

    @Test
    fun `percent-of-notional pricing tracks trade size`() {
        // EU venues price on notional: 1,000 sh @ EUR 90 = EUR 90,000 x 0.05% = EUR 45 commission,
        // plus 10bps slippage = EUR 90. Well clear of the EUR 4 floor.
        val cost = CostModel.IBKR_EU_ETF.oneWay(1000, BigDecimal("90"))
        assertEquals(BigDecimal("135.00"), cost)
    }

    @Test
    fun `round trip charges entry and exit at their own prices`() {
        val model = CostModel.IBKR_US_STOCK
        val entry = model.oneWay(100, BigDecimal("50"))
        val exit = model.oneWay(100, BigDecimal("60"))
        assertEquals(entry.add(exit), model.roundTrip(100, BigDecimal("50"), BigDecimal("60")))
        // The exit side is dearer because slippage scales with the (higher) exit notional.
        assertTrue(exit > entry)
    }

    @Test
    fun `zero or negative size is free rather than throwing`() {
        val model = CostModel.IBKR_US_STOCK
        assertEquals(BigDecimal.ZERO, model.oneWay(0, BigDecimal("50")))
        assertEquals(BigDecimal.ZERO, model.oneWay(-5, BigDecimal("50")))
        assertEquals(BigDecimal.ZERO, model.oneWay(100, BigDecimal.ZERO))
    }
}
