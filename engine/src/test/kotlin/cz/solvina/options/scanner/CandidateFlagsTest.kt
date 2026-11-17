package cz.solvina.options.scanner

import cz.solvina.options.domain.features.regime.RegimeSignal
import cz.solvina.options.domain.features.regime.TrendRegime
import cz.solvina.options.domain.features.scanner.CandidateFlag
import cz.solvina.options.domain.features.scanner.CandidateFlags
import cz.solvina.options.domain.features.scanner.ScannerPoolConfig
import cz.solvina.options.domain.features.spread.model.StrategyId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class CandidateFlagsTest {
    private val config = ScannerPoolConfig()

    private fun signal(
        close: String,
        sma50: String,
        change10d: Double,
    ) = RegimeSignal(TrendRegime.NEUTRAL, BigDecimal(close), BigDecimal(sma50), null, change10dPct = change10d)

    @Test
    fun `VLO on 2026-11-04 - a bull put on a falling stock with extreme IV rank is flagged twice`() {
        val flags = CandidateFlags.of(StrategyId.BULL_PUT, 99.5, signal("387.65", "399.10", -10.6), config)

        assertEquals(setOf(CandidateFlag.FALLING, CandidateFlag.IV_EXTREME), flags)
    }

    @Test
    fun `below the 50-day average alone is falling for a bull put but fine for a bear call`() {
        val s = signal("95", "100", -2.0)

        assertEquals(setOf(CandidateFlag.FALLING), CandidateFlags.of(StrategyId.BULL_PUT, 50.0, s, config))
        assertEquals(emptySet(), CandidateFlags.of(StrategyId.BEAR_CALL, 50.0, s, config))
    }

    @Test
    fun `a bear call on a stock up more than 10 percent is flagged rising`() {
        val flags = CandidateFlags.of(StrategyId.BEAR_CALL, 50.0, signal("99", "100", 12.0), config)

        assertEquals(setOf(CandidateFlag.RISING), flags)
    }

    @Test
    fun `no trend data and a normal IV rank means no warnings`() {
        val unknown = RegimeSignal(TrendRegime.NEUTRAL, null, null, null)

        assertEquals(emptySet(), CandidateFlags.of(StrategyId.BULL_PUT, 60.0, unknown, config))
    }
}
