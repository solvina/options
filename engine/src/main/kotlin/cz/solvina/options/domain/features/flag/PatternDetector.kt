package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.bars.AtrCalculator
import cz.solvina.options.domain.features.bars.BarBuffer
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.bars.LinearRegression
import cz.solvina.options.domain.features.bars.VolumeAnalysis
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Stateful per-symbol pattern detector.
 *
 * Call [onNewBar] after every completed 5-minute candle is added to [buffer].
 * Returns the current [PatternState] — progresses through:
 * Idle → FlagpoleDetected → FlagForming → BreakoutReady (then resets to Idle)
 */
class PatternDetector(
    private val symbol: String,
    private val buffer: BarBuffer,
    private val config: FlagStrategyConfig,
) {
    var state: PatternState = PatternState.Idle
        private set

    /**
     * Evaluate the [newBar] (already appended to [buffer]) against the current state.
     * Returns the updated [PatternState].
     */
    fun onNewBar(newBar: FiveMinuteBar): PatternState {
        val bars = buffer.snapshot()

        state =
            when (val s = state) {
                is PatternState.Idle -> detectPole(bars)
                is PatternState.FlagpoleDetected -> detectFlag(bars, s.pole)
                is PatternState.FlagForming -> checkBreakout(bars, s.pole, s.flag, newBar)
                is PatternState.BreakoutReady -> {
                    // Already signalled — reset so we don't fire twice
                    PatternState.Idle
                }
            }

        return state
    }

    /** Reset to Idle (called after an entry is fired or market close). */
    fun reset() {
        state = PatternState.Idle
    }

    // -------------------------------------------------------------------------
    // Phase 1: Flagpole detection
    // -------------------------------------------------------------------------

    private fun detectPole(bars: List<FiveMinuteBar>): PatternState {
        if (bars.size < config.atrPeriod + config.poleMinBars + 1) return PatternState.Idle

        val atr = AtrCalculator.atr(bars, config.atrPeriod)
        if (atr.isNaN() || atr == 0.0) return PatternState.Idle

        val volMa = VolumeAnalysis.volumeMa(bars, config.volumeMaPeriod)
        if (volMa.isNaN()) return PatternState.Idle

        // Scan the last poleMaxBars candles for a strong up-move
        val window = bars.takeLast(config.poleMaxBars)
        for (startIdx in 0..(window.size - config.poleMinBars)) {
            val start = window[startIdx]
            for (endIdx in (startIdx + config.poleMinBars - 1)..window.lastIndex) {
                val end = window[endIdx]
                val height = end.high - start.low
                if (height < config.atrMultiplier * atr) continue

                // Volume spike in at least one pole bar
                val poleSlice = window.subList(startIdx, endIdx + 1)
                val hasVolumeSpike = poleSlice.any { it.volume > config.volumeSpikeMultiplier * volMa }
                if (!hasVolumeSpike) continue

                val avgVol = poleSlice.map { it.volume.toDouble() }.average()
                val pole = Flagpole(start, end, height, avgVol, endIdx - startIdx + 1)
                logger.debug { "[$symbol] Flagpole detected: height=${"%.2f".format(height)} atr=${"%.2f".format(atr)}" }
                return PatternState.FlagpoleDetected(pole)
            }
        }

        return PatternState.Idle
    }

    // -------------------------------------------------------------------------
    // Phase 2: Flag (consolidation) detection
    // -------------------------------------------------------------------------

    private fun detectFlag(
        bars: List<FiveMinuteBar>,
        pole: Flagpole,
    ): PatternState {
        // Collect bars after the pole top
        val postPoleIdx = bars.indexOfLast { it.time == pole.endBar.time }
        if (postPoleIdx < 0) return PatternState.Idle
        val consolBars = bars.drop(postPoleIdx + 1)

        if (consolBars.size < config.flagMinBars) return PatternState.FlagpoleDetected(pole, consolBars.size)
        if (consolBars.size > config.flagMaxBars) {
            // Flag window exceeded — too much chop; reset
            logger.debug { "[$symbol] Flag window exceeded ${config.flagMaxBars} bars — resetting" }
            return PatternState.Idle
        }

        val lowestLow = consolBars.minOf { it.low }
        val retracement = (pole.endBar.high - lowestLow) / pole.height
        if (retracement > config.maxRetracementPct) {
            logger.debug { "[$symbol] Retracement ${"%.1f".format(retracement * 100)}% > max ${config.maxRetracementPct * 100}% — resetting" }
            return PatternState.Idle
        }

        // Fit regression lines through highs and lows
        val highs = consolBars.map { it.high }
        val lows = consolBars.map { it.low }
        val upperLine = LinearRegression.fit(highs) ?: return PatternState.FlagpoleDetected(pole, consolBars.size)
        val lowerLine = LinearRegression.fit(lows) ?: return PatternState.FlagpoleDetected(pole, consolBars.size)

        // Channel should be flat or descending (slope ≤ 0 for highs)
        if (upperLine.slope > 0.05 * pole.height) {
            // Slope rising — not a flag, might be another pole forming
            return PatternState.Idle
        }

        // Volume should be declining vs. MA during consolidation
        val volMa = VolumeAnalysis.volumeMa(bars, config.volumeMaPeriod)
        val consolidationAvgVol = consolBars.map { it.volume.toDouble() }.average()
        if (!volMa.isNaN() && consolidationAvgVol >= volMa) {
            logger.debug { "[$symbol] Flag volume not drying up (${consolidationAvgVol.toLong()} >= ${volMa.toLong()}) — continuing to watch" }
            // Not a hard rejection — keep watching; volume may dry up
        }

        val flag = Flag(consolBars, lowestLow, upperLine, lowerLine, retracement)
        logger.debug { "[$symbol] Flag forming: ${consolBars.size} bars, retracement=${"%.1f".format(retracement * 100)}%" }
        return PatternState.FlagForming(pole, flag)
    }

    // -------------------------------------------------------------------------
    // Phase 3: Breakout detection (runs on each new 5-sec bar close too)
    // -------------------------------------------------------------------------

    /**
     * Check if a live 5-second bar close pierces the flag's upper resistance.
     * Returns [PatternState.BreakoutReady] if yes, null otherwise.
     * Does NOT mutate [state] — the caller is responsible for calling [reset] after acting on the signal.
     */
    fun checkBreakoutOnLiveBar(
        liveClose: Double,
        pole: Flagpole,
        flag: Flag,
    ): PatternState.BreakoutReady? {
        val barIndex = flag.bars.size
        val resistance = flag.upperResistance.valueAt(barIndex)
        if (liveClose > resistance) {
            logger.info { "[$symbol] BREAKOUT on 5-sec bar: close=$liveClose > resistance=${"%.2f".format(resistance)}" }
            return PatternState.BreakoutReady(pole, flag, resistance)
        }
        return null
    }

    private fun checkBreakout(
        bars: List<FiveMinuteBar>,
        pole: Flagpole,
        flag: Flag,
        newBar: FiveMinuteBar,
    ): PatternState {
        val barIndex = flag.bars.size
        val resistance = flag.upperResistance.valueAt(barIndex)

        if (newBar.close > resistance) {
            logger.info { "[$symbol] BREAKOUT on 5-min bar: close=${newBar.close} > resistance=${"%.2f".format(resistance)}" }
            return PatternState.BreakoutReady(pole, flag, resistance)
        }

        // Update the flag with the new bar if still consolidating
        val updatedBars = flag.bars + newBar
        if (updatedBars.size > config.flagMaxBars) {
            logger.debug { "[$symbol] Flag expired (>${config.flagMaxBars} bars) — resetting" }
            return PatternState.Idle
        }

        val lowestLow = updatedBars.minOf { it.low }
        val retracement = (pole.endBar.high - lowestLow) / pole.height
        if (retracement > config.maxRetracementPct) {
            logger.debug { "[$symbol] Retracement exceeded during flag — resetting" }
            return PatternState.Idle
        }

        val upperLine = LinearRegression.fit(updatedBars.map { it.high }) ?: return PatternState.FlagForming(pole, flag)
        val lowerLine = LinearRegression.fit(updatedBars.map { it.low }) ?: return PatternState.FlagForming(pole, flag)
        val updatedFlag = flag.copy(bars = updatedBars, lowestLow = lowestLow, upperResistance = upperLine, lowerSupport = lowerLine, retracement = retracement)

        return PatternState.FlagForming(pole, updatedFlag)
    }
}
