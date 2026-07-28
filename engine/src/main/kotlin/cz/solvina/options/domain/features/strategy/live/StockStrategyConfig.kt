package cz.solvina.options.domain.features.strategy.live

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Kill switch and portfolio limits for the live stock-strategy host.
 *
 * [enabled] defaults **false**: Phase 7 ships dark, so deploying the runner changes nothing until
 * it is deliberately switched on. Flipping this to true is the only step that makes a strategy
 * trade — assignments alone do not.
 */
@ConfigurationProperties(prefix = "stock-strategies")
data class StockStrategyConfig(
    val enabled: Boolean = false,
    /**
     * Cap on concurrent live positions across ALL stock strategies. Low by default: these names
     * share a large common factor (market beta), so N open positions is materially less than N
     * independent bets and a broad selloff fires several at once.
     */
    val maxOpenPositions: Int = 3,
    /**
     * Entry limit tolerance above the signal price, in ATR multiples. Scales across a EUR 40 and a
     * EUR 90 instrument, unlike a fixed cent amount. Fills on ordinary opens and refuses to chase a
     * gap — and a gap-up open is exactly when paying up hurts most.
     */
    val limitToleranceAtrMultiple: Double = 0.25,
)
