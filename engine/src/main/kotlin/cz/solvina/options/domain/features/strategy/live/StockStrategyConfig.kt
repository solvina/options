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
     * Entry limit tolerance above the strategy's entry price, in ATR multiples.
     *
     * **Zero by default, and raising it is a measurable loss** for any strategy whose Decision
     * carries absolute stop and target prices — which is all of them. Those prices are fixed off the
     * signal price and do not follow the fill, so a wider limit fills lower (a gap-down fills at the
     * open) and leaves the stop further from the fill than intended and the target nearer. On MFI,
     * 0.0 -> 1.0 ATR walked the win rate 25% -> 15% purely from that anchoring (commit cc3a7b2).
     *
     * It also breaks the twin property this host is built on: the backtest never sees this number,
     * so any non-zero value means live is trading a rule that was never measured. A strategy that
     * wants a tolerance should own it as a parameter, as MfiOversoldStrategy.limitToleranceAtr does.
     */
    val limitToleranceAtrMultiple: Double = 0.0,
)
