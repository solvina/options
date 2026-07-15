package cz.solvina.options.domain.features.market.model

import java.time.Instant

/**
 * Greek-delivery coverage for the most recent option-chain fetch of a symbol: how many candidate
 * strikes were requested vs how many returned a live greek (delta) tick. A low ratio is the
 * signature of market-data starvation (e.g. an IBKR competing-session denial) rather than a genuine
 * absence of tradeable strikes — surfacing it makes that failure visible instead of silent.
 */
data class ChainCoverage(
    val strikesRequested: Int,
    val strikesWithGreeks: Int,
    val fetchedAt: Instant,
)
