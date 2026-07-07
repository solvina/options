package cz.solvina.options.scanner

import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutScannerConfig
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.OptionType
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class StrategyParamsRegistryTest {
    private val registry = StrategyParamsRegistry(listOf(BullPutScannerConfig(), BearCallScannerConfig()))

    @Test
    fun `bull put resolves PUT and its own exit and execution params`() {
        val p = registry.forStrategy(StrategyId.BULL_PUT)
        assertEquals(OptionType.PUT, p.optionType)
        // Defaults from ScannerConfig — the strategy-agnostic core must apply these for bull puts.
        assertEquals(0.01, p.driftProtectionPct)
        assertEquals(14, p.timeProfitDte)
    }

    @Test
    fun `bear call resolves CALL and its own exit and execution params (the dead-config fix)`() {
        val p = registry.forStrategy(StrategyId.BEAR_CALL)
        assertEquals(OptionType.CALL, p.optionType)
        // These are the values that were declared but never applied before the seam (B2/B3).
        // 2026-07-07: stop-loss default 2.00 → 1.00, and its basis moved to the entry mid.
        assertEquals(1.00, p.stopLossPercent)
        assertEquals(21, p.timeProfitDte)
        assertEquals(0.05, p.driftProtectionPct)
        assertEquals(BigDecimal("0.40"), p.minCreditPerShare)
    }
}
