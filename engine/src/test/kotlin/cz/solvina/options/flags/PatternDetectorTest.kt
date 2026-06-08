package cz.solvina.options.flags

import cz.solvina.options.domain.features.bars.BarBuffer
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.flag.PatternDetector
import cz.solvina.options.domain.features.flag.PatternState
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Bull-flag pattern state machine:
 *
 *   Idle → FlagpoleDetected → FlagForming → BreakoutReady → (resets to Idle)
 *
 * Flagpole conditions:
 *   — Move height ≥ atrMultiplier × ATR (default 2×)
 *   — At least one pole bar has volume > volumeSpikeMultiplier × volume MA (default 1.5×)
 *
 * Flag (consolidation) conditions:
 *   — flagMinBars ≤ consolidation bars ≤ flagMaxBars
 *   — Retracement from pole top ≤ maxRetracementPct (default 50 %)
 *   — Upper channel slope ≤ 0.05 × poleHeight (flat or descending)
 *
 * Breakout condition:
 *   — Candle close > upper resistance line at that bar index
 */
class PatternDetectorTest {
    // Small periods keep test data compact without changing the detection logic.
    // volumeMaPeriod=5 ensures base bars dominate the moving average so that pole bars
    // (volume=3000) produce a genuine spike above 1.5×volMa. With period=3 and only 2
    // pole bars it is mathematically impossible for any bar to spike above its own average.
    private val config =
        FlagStrategyConfig(
            atrPeriod = 3,
            atrMultiplier = 2.0,
            volumeMaPeriod = 5,
            volumeSpikeMultiplier = 1.5,
            poleMinBars = 2,
            poleMaxBars = 10,
            flagMinBars = 3,
            flagMaxBars = 15,
            maxRetracementPct = 0.50,
        )

    // Each call to next() advances the clock by one 5-minute bar.
    private var t = Instant.EPOCH

    private fun next() = t.also { t = t.plusSeconds(300) }

    private fun bar(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long = 1_000,
    ) = FiveMinuteBar(time = next(), open = open, high = high, low = low, close = close, volume = volume)

    /** Flat candle: range [price, price+1], neutral volume (low=open so wide windows don't lower pole height). */
    private fun flat(
        price: Double = 100.0,
        volume: Long = 1_000,
    ) = bar(price, price + 1.0, price, price, volume)

    private fun buildDetector(): Pair<PatternDetector, BarBuffer> {
        val buffer = BarBuffer()
        val detector = PatternDetector("TEST", buffer, config)
        return detector to buffer
    }

    /** Feeds bars into the buffer and repeatedly calls onNewBar, returning the final state. */
    private fun feed(
        detector: PatternDetector,
        buffer: BarBuffer,
        bars: List<FiveMinuteBar>,
    ): PatternState {
        var state: PatternState = PatternState.Idle
        for (bar in bars) {
            buffer.add(bar)
            state = detector.onNewBar(bar)
        }
        return state
    }

    // -------------------------------------------------------------------------
    // Flagpole detection
    // -------------------------------------------------------------------------

    @Test
    fun `stays Idle with no bars`() {
        val (detector, buffer) = buildDetector()
        assertEquals(PatternState.Idle, detector.state)
    }

    @Test
    fun `stays Idle when candle moves are too small to form a flagpole`() {
        // ATR period = 3 → need ≥ 4 bars for ATR. With atrMultiplier=2 and ATR≈2, we need
        // height ≥ 4.0. The "weak pole" bars only move 2.5, which is below the threshold.
        val (detector, buffer) = buildDetector()

        // Weak bars use volume=2000 (below the 2100 spike threshold at volumeMaPeriod=5).
        // The move is also below 2×ATR, but the volume gate is the active guard here.
        val weakMove =
            listOf(
                flat(), // base bar 0
                flat(), // base bar 1
                flat(), // base bar 2
                flat(), // base bar 3 — ATR now calculable
                bar(100.0, 101.5, 100.0, 101.5, volume = 2_000), // weak bar 1: no volume spike
                bar(101.5, 102.5, 101.5, 102.5, volume = 2_000), // weak bar 2: no volume spike
            )

        val state = feed(detector, buffer, weakMove)

        assertEquals(PatternState.Idle, state, "A move without a volume spike must not be recognised as a flagpole")
    }

    @Test
    fun `transitions to FlagpoleDetected after a strong momentum move with a volume spike`() {
        // 4 flat base bars → ATR ≈ 2.0, volume MA ≈ 1000.
        // Pole bars: total height = 107 − 100 = 7.0 ≥ 2.0 × 2.0 = 4.0 ✓
        //            volume 2000 > 1.5 × 1000 = 1500 ✓
        val (detector, buffer) = buildDetector()

        val bars =
            listOf(
                flat(100.0), // base 0
                flat(100.0), // base 1
                flat(100.0), // base 2
                flat(100.0), // base 3 — ATR calculable after this
                bar(100.0, 103.0, 100.0, 103.0, volume = 3_000), // pole bar 1
                bar(103.0, 107.0, 103.0, 107.0, volume = 3_000), // pole bar 2 — height=7, spike ✓
            )

        val state = feed(detector, buffer, bars)

        assertIs<PatternState.FlagpoleDetected>(state)
    }

    // -------------------------------------------------------------------------
    // Flag (consolidation) detection
    // -------------------------------------------------------------------------

    @Test
    fun `transitions to FlagForming once minimum consolidation bars accumulate after the pole`() {
        // After a confirmed pole, the detector waits for flagMinBars (3) consolidation bars.
        // These bars have declining highs (negative slope) and shallow retracement.
        val (detector, buffer) = buildDetector()

        val poleTop = 107.0
        val bars =
            listOf(
                flat(100.0),
                flat(100.0),
                flat(100.0),
                flat(100.0),
                bar(100.0, 103.0, 100.0, 103.0, volume = 3_000),
                bar(103.0, poleTop, 103.0, poleTop, volume = 3_000),
                // 3 consolidation bars: declining highs, retracement well below 50 %
                bar(107.0, 107.5, 106.0, 106.5), // consol 1
                bar(106.5, 107.0, 106.0, 106.2), // consol 2
                bar(106.2, 106.8, 105.8, 106.0), // consol 3 — flagMinBars reached
            )

        val state = feed(detector, buffer, bars)

        assertIs<PatternState.FlagForming>(state)
    }

    @Test
    fun `resets to Idle when retracement during consolidation exceeds 50 percent of pole height`() {
        // Pole height = 7.0. A drop to 103.5 from peak 107 = retracement 50.7 % > 50 % → reset.
        val (detector, buffer) = buildDetector()

        val bars =
            listOf(
                flat(100.0),
                flat(100.0),
                flat(100.0),
                flat(100.0),
                bar(100.0, 103.0, 100.0, 103.0, volume = 3_000),
                bar(103.0, 107.0, 103.0, 107.0, volume = 3_000),
                // Retracement: (107 − 103.4) / 7.0 = 51.4 % > 50 % → Idle
                bar(107.0, 107.2, 103.4, 104.0),
                bar(104.0, 104.5, 103.4, 103.8),
                bar(103.8, 104.2, 103.4, 103.6),
            )

        val state = feed(detector, buffer, bars)

        assertEquals(PatternState.Idle, state, "Excessive retracement must reset the pattern")
    }

    @Test
    fun `resets to Idle when the upper channel slope is too positive (rising wedge, not a flag)`() {
        // A rising-wedge pattern has higher highs — it's a continuation, not a bull flag.
        // Highs [105, 107, 109] → slope ≈ +2.0 >> 0.05 × 7 = 0.35 → reset.
        val (detector, buffer) = buildDetector()

        val bars =
            listOf(
                flat(100.0),
                flat(100.0),
                flat(100.0),
                flat(100.0),
                bar(100.0, 103.0, 100.0, 103.0, volume = 3_000),
                bar(103.0, 107.0, 103.0, 107.0, volume = 3_000),
                bar(107.0, 105.0, 104.0, 104.5), // highs: 105
                bar(104.5, 107.0, 104.0, 106.0), // highs: 107
                bar(106.0, 109.0, 105.5, 108.5), // highs: 109 → rising wedge
            )

        val state = feed(detector, buffer, bars)

        assertEquals(PatternState.Idle, state, "A rising-wedge upper channel must not qualify as a flag")
    }

    // -------------------------------------------------------------------------
    // Breakout detection
    // -------------------------------------------------------------------------

    @Test
    fun `signals BreakoutReady when a candle closes above the upper resistance line`() {
        // After a valid flag forms, a close above the extrapolated resistance line signals entry.
        // Upper resistance from highs [107.5, 107.0, 106.8] has slope ≈ −0.35, intercept ≈ 107.45.
        // Resistance at bar index 3 ≈ 107.45 − 0.35×3 = 106.4; close = 108.0 > 106.4 → breakout.
        val (detector, buffer) = buildDetector()

        val bars =
            listOf(
                flat(100.0),
                flat(100.0),
                flat(100.0),
                flat(100.0),
                bar(100.0, 103.0, 100.0, 103.0, volume = 3_000),
                bar(103.0, 107.0, 103.0, 107.0, volume = 3_000),
                bar(107.0, 107.5, 106.0, 106.5),
                bar(106.5, 107.0, 106.0, 106.2),
                bar(106.2, 106.8, 105.8, 106.0),
                bar(106.0, 108.5, 105.9, 108.0), // close=108.0 > resistance≈106.4 → breakout
            )

        val state = feed(detector, buffer, bars)

        assertIs<PatternState.BreakoutReady>(state)
    }

    @Test
    fun `does not signal breakout when close stays below the resistance line`() {
        // Same setup as the breakout test but close = 106.0, which is below the resistance ≈ 106.4.
        val (detector, buffer) = buildDetector()

        val bars =
            listOf(
                flat(100.0),
                flat(100.0),
                flat(100.0),
                flat(100.0),
                bar(100.0, 103.0, 100.0, 103.0, volume = 3_000),
                bar(103.0, 107.0, 103.0, 107.0, volume = 3_000),
                bar(107.0, 107.5, 106.0, 106.5),
                bar(106.5, 107.0, 106.0, 106.2),
                bar(106.2, 106.8, 105.8, 106.0),
                bar(106.0, 106.3, 105.9, 106.0), // close=106.0 < resistance≈106.4 → still forming
            )

        val state = feed(detector, buffer, bars)

        assertIs<PatternState.FlagForming>(state)
    }

    @Test
    fun `resets to Idle after signalling BreakoutReady so each pattern fires only once`() {
        // After a breakout is signalled, the next bar call must reset to Idle.
        // Without this reset the same signal would fire repeatedly every bar.
        val (detector, buffer) = buildDetector()

        val bars =
            listOf(
                flat(100.0),
                flat(100.0),
                flat(100.0),
                flat(100.0),
                bar(100.0, 103.0, 100.0, 103.0, volume = 3_000),
                bar(103.0, 107.0, 103.0, 107.0, volume = 3_000),
                bar(107.0, 107.5, 106.0, 106.5),
                bar(106.5, 107.0, 106.0, 106.2),
                bar(106.2, 106.8, 105.8, 106.0),
                bar(106.0, 108.5, 105.9, 108.0), // breakout fires → BreakoutReady
            )
        feed(detector, buffer, bars)

        // One more bar arrives — the BreakoutReady state must consume itself and reset.
        val nextBar = bar(108.0, 108.5, 107.5, 108.0)
        buffer.add(nextBar)
        val state = detector.onNewBar(nextBar)

        assertEquals(PatternState.Idle, state, "State must reset to Idle after a breakout signal is consumed")
    }
}
