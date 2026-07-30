package cz.solvina.options.domain.features.strategy

import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MfiOversoldStrategyTest {
    private val symbol = Symbol("TEST")

    private fun bar(
        px: Double,
        volume: Long = 1_000,
        range: Double = 1.0,
    ) = Candle(Instant.EPOCH, open = px, high = px + range, low = px - range, close = px, volume = volume)

    /** A decline steep enough to pin MFI at (or near) zero, then the bar we decide on. */
    private fun washout(bars: Int = 30): List<Candle> = (0 until bars).map { bar(100.0 - it) }

    private fun ctx(
        candle: Candle,
        equity: Double = 100_000.0,
        open: Int = 0,
    ) = StrategyContext(
        symbol = symbol,
        candle = candle,
        equity = BigDecimal(equity),
        openPositions = open,
        pendingPositions = 0,
    )

    private fun feed(
        s: MfiOversoldStrategy,
        bars: List<Candle>,
    ): Decision? {
        var last: Decision? = null
        bars.forEach { last = s.decide(ctx(it)) }
        return last
    }

    @Test
    fun `fires on a money-flow washout`() {
        val s = MfiOversoldStrategy()
        val d = feed(s, washout())
        assertNotNull(d, "an unbroken decline should drive MFI under the threshold")
        assertTrue(d.shares > 0)
    }

    @Test
    fun `does not fire on an advance`() {
        val s = MfiOversoldStrategy()
        assertNull(feed(s, (0 until 30).map { bar(100.0 + it) }))
    }

    @Test
    fun `entry sits above the close by the limit tolerance, and the bracket hangs off it`() {
        val s = MfiOversoldStrategy(MfiOversoldStrategy.Params(limitToleranceAtr = 1.0))
        val bars = washout()
        val d = feed(s, bars)!!
        val close = bars.last().close
        // ATR of this synthetic series is a steady 2.0 (range 2, plus a 1-point gap each bar).
        assertTrue(
            d.entryPrice.toDouble() > close,
            "a limit entry must rest above the signal close, not at it (got ${d.entryPrice} vs $close)",
        )
        // Stop and target are measured from the entry, not from the close — live places the bracket
        // off the same level it rests the limit at.
        val entry = d.entryPrice.toDouble()
        val risk = entry - d.stopLossPrice.toDouble()
        val reward = d.profitTargetPrice.toDouble() - entry
        assertEquals(2.0, reward / risk, 0.05, "3.0/1.5 ATR defaults are a 2:1 bracket")
    }

    @Test
    fun `zero tolerance rests the limit exactly at the close`() {
        val s = MfiOversoldStrategy(MfiOversoldStrategy.Params(limitToleranceAtr = 0.0))
        val bars = washout()
        val d = feed(s, bars)!!
        assertEquals(bars.last().close, d.entryPrice.toDouble(), 0.01)
    }

    @Test
    fun `declares a LIMIT entry so the host does not fill it like a breakout`() {
        assertEquals(StrategyEntryMode.LIMIT, MfiOversoldStrategy().entryMode)
    }

    @Test
    fun `the original last-high target is rejected when the limit lands above that high`() {
        // targetLastHigh aims at the signal bar's high. With a full-ATR limit offset the entry can
        // sit above it, which would book a target behind the entry — EntrySizer must decline.
        val s =
            MfiOversoldStrategy(
                MfiOversoldStrategy.Params(targetLastHigh = true, limitToleranceAtr = 1.0),
            )
        assertNull(feed(s, washout()), "target behind the entry is not a trade")
    }

    @Test
    fun `the original last-high target is taken when the limit stays below that high`() {
        val s =
            MfiOversoldStrategy(
                MfiOversoldStrategy.Params(targetLastHigh = true, limitToleranceAtr = 0.0),
            )
        val bars = washout()
        val d = feed(s, bars)
        assertNotNull(d)
        assertEquals(bars.last().high, d.profitTargetPrice.toDouble(), 0.01)
    }

    @Test
    fun `respects the position cap`() {
        val s = MfiOversoldStrategy(MfiOversoldStrategy.Params(maxOpenPositions = 1))
        val bars = washout()
        var last: Decision? = null
        bars.forEach { last = s.decide(ctx(it, open = 1)) }
        assertNull(last, "at the cap, no new entry")
    }

    @Test
    fun `indicator state advances even on bars the cap blocks`() {
        // Two runs over identical data: one always capped until the final bar, one never capped.
        // If state only advanced on tradeable bars the capped run would see a different MFI.
        val bars = washout()
        val free = MfiOversoldStrategy()
        val capped = MfiOversoldStrategy()
        bars.dropLast(1).forEach {
            free.decide(ctx(it))
            capped.decide(ctx(it, open = 99))
        }
        val a = free.decide(ctx(bars.last()))
        val b = capped.decide(ctx(bars.last()))
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a.entryPrice, b.entryPrice)
        assertEquals(a.stopLossPrice, b.stopLossPrice)
    }

    @Test
    fun `warmup seeds the same state a replay would produce`() {
        val bars = washout()
        val replayed = MfiOversoldStrategy()
        bars.dropLast(1).forEach { replayed.decide(ctx(it)) }

        val warmed = MfiOversoldStrategy()
        warmed.warmup(symbol, mapOf(Timeframe.DAILY to bars.dropLast(1)))

        val a = replayed.decide(ctx(bars.last()))
        val b = warmed.decide(ctx(bars.last()))
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a.entryPrice, b.entryPrice)
        assertEquals(a.profitTargetPrice, b.profitTargetPrice)
    }

    @Test
    fun `validation rejects unusable params`() {
        val s = MfiOversoldStrategy()
        assertNotNull(MfiOversoldStrategy.validationError(MfiOversoldStrategy.Params(mfiPeriod = 0)))
        assertNotNull(MfiOversoldStrategy.validationError(MfiOversoldStrategy.Params(mfiThreshold = 0.0)))
        assertNotNull(MfiOversoldStrategy.validationError(MfiOversoldStrategy.Params(mfiThreshold = 101.0)))
        assertNotNull(MfiOversoldStrategy.validationError(MfiOversoldStrategy.Params(limitToleranceAtr = -1.0)))
        assertNull(MfiOversoldStrategy.validationError(MfiOversoldStrategy.Params()))
        // The descriptor defaults must themselves be valid, or the library ships unrunnable.
        assertNull(s.validate(StrategyParams.resolve(s.params, emptyMap())))
    }

    @Test
    fun `warmup requirement covers both indicators`() {
        val s = MfiOversoldStrategy(MfiOversoldStrategy.Params(mfiPeriod = 20, atrPeriod = 5))
        assertEquals(21, s.inputs.warmupBars)
        assertEquals(listOf(Timeframe.DAILY), s.inputs.timeframes)
    }
}
