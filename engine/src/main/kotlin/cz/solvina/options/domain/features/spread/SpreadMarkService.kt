package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.OptionContract
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

private val logger = KotlinLogging.logger {}
private val CONTRACT_MULTIPLIER = BigDecimal("100")

/**
 * Current value and unrealized P&L for live spreads, taken from the broker's streamed portfolio
 * feed instead of from [Spread.lastSpreadValue].
 *
 * `lastSpreadValue` is our own `soldMid − boughtMid` snapshot, written by the monitor cycle or by
 * the manual refresh endpoint — and deliberately *kept* when a leg has no live quote, so it can sit
 * frozen for hours (market closed, thin book, quote-feed hiccup) while the UI presents it as the
 * current number. That is what makes the dashboards disagree with TWS.
 *
 * IBKR pushes `updatePortfolio` for every held leg every few minutes with its own mark and its own
 * unrealized P&L — the same figures TWS renders. This service prefers those, in order:
 *
 *  1. [Source.IBKR_PNL] — both legs held at exactly this spread's quantity, so IBKR's per-leg
 *     unrealized P&L can be summed and attributed to this spread. Matches TWS to the cent
 *     (avgCost-based, i.e. **net of commissions**, unlike the credit-based derivation below).
 *  2. [Source.IBKR_MARK] — legs are in the feed but shared with another spread or partially held,
 *     so IBKR's P&L is not ours alone. The per-leg marks are still IBKR's, so the spread value is
 *     live; P&L is derived from it as `(credit − value) × qty × 100` (gross of commissions).
 *  3. [Source.LAST_MONITOR_MARK] — leg missing from the feed, or the feed is stale/cold. Falls back
 *     to `lastSpreadValue` with the old derivation, and says so via [SpreadMark.source] rather than
 *     passing it off as live.
 */
@Service
class SpreadMarkService(
    private val positionsPort: PositionsPort,
    private val clock: Clock,
    /**
     * Portfolio pushes older than this are treated as cold. IBKR repushes a changed position every
     * ~3 min while connected, so a gap this long means the feed — not the market — went quiet.
     */
    @Value("\${account.portfolio-mark-max-age-minutes:20}") private val markMaxAgeMinutes: Long,
) {
    enum class Source {
        IBKR_PNL,
        IBKR_MARK,
        LAST_MONITOR_MARK,
    }

    data class SpreadMark(
        /** Per-share value of the spread (cost to close). Null when no source could supply one. */
        val spreadValuePerShare: BigDecimal?,
        /** Total unrealized P&L in account currency, positive = profit. */
        val unrealizedPnl: BigDecimal?,
        val source: Source,
    ) {
        /** True when the numbers came from the broker feed rather than our own frozen snapshot. */
        val live: Boolean get() = source != Source.LAST_MONITOR_MARK
    }

    /** Marks for every live (OPEN/CLOSING) spread in [spreads], keyed by spread id. One feed read. */
    suspend fun marks(spreads: List<Spread>): Map<UUID, SpreadMark> {
        val live = spreads.filter { it.id != null && it.status in LIVE_STATUSES }
        if (live.isEmpty()) return emptyMap()
        val held = heldOptionLegs()
        return live.associate { it.id!! to markOf(it, held) }
    }

    /** Mark for a single spread. Prefer [marks] for lists — this reads the feed per call. */
    suspend fun mark(spread: Spread): SpreadMark = markOf(spread, heldOptionLegs())

    private suspend fun heldOptionLegs(): Map<PosKey, AccountPosition> {
        val positions =
            runCatching { positionsPort.getPositions() }
                .onFailure { e ->
                    logger.warn(e) { "Portfolio feed unavailable, spread marks fall back to last monitor mark: ${e.message}" }
                }.getOrDefault(emptyList())
        val cutoff = clock.instant().minus(Duration.ofMinutes(markMaxAgeMinutes))
        return positions
            .filter { it.secType == "OPT" }
            // A row the broker stopped pushing is not a current mark. Unstamped feeds (tests, non-IBKR)
            // are taken at face value.
            .filter { it.updatedAt == null || !it.updatedAt.isBefore(cutoff) }
            .associateBy { PosKey(it.symbol, it.strike?.stripTrailingZeros(), it.optionRight, it.expiry) }
    }

    private fun markOf(
        spread: Spread,
        held: Map<PosKey, AccountPosition>,
    ): SpreadMark {
        val sold = held[keyOf(spread.soldLeg.contract)]
        val bought = held[keyOf(spread.boughtLeg.contract)]
        if (sold == null || bought == null) {
            logger.debug {
                "[${spread.symbol}] no live portfolio mark (sold=${sold != null} bought=${bought != null}) — using last monitor mark"
            }
            return fallback(spread)
        }

        val value = spreadValue(sold, bought) ?: return fallback(spread)

        // IBKR's unrealized P&L covers the whole held quantity of each leg. Attribute it to this
        // spread only when the held legs are exactly this spread — otherwise (two spreads sharing
        // strikes, a partial fill, a leftover leg) it would double-count.
        val exclusive =
            sold.quantity.abs().compareTo(BigDecimal(spread.quantity)) == 0 &&
                bought.quantity.abs().compareTo(BigDecimal(spread.quantity)) == 0
        val brokerPnl =
            if (exclusive && sold.unrealizedPnL != null && bought.unrealizedPnL != null) {
                BigDecimal.valueOf(sold.unrealizedPnL + bought.unrealizedPnL).setScale(2, RoundingMode.HALF_UP)
            } else {
                null
            }

        return if (brokerPnl != null) {
            SpreadMark(value, brokerPnl, Source.IBKR_PNL)
        } else {
            SpreadMark(value, pnlFromValue(spread, value), Source.IBKR_MARK)
        }
    }

    /**
     * Cost to close, per share, from IBKR's own leg marks: short leg (buy back) minus long leg
     * (sell). Negative marks are nonsense and mean the feed has no price for that leg; a genuine
     * 0.00 on a worthless leg is real and kept — unlike the quote path, IBKR still marks a
     * position when the market is closed.
     */
    private fun spreadValue(
        sold: AccountPosition,
        bought: AccountPosition,
    ): BigDecimal? {
        if (sold.marketPrice < 0.0 || bought.marketPrice < 0.0) return null
        return BigDecimal
            .valueOf(sold.marketPrice - bought.marketPrice)
            .setScale(4, RoundingMode.HALF_UP)
    }

    private fun fallback(spread: Spread): SpreadMark {
        val value = spread.lastSpreadValue
        return SpreadMark(
            spreadValuePerShare = value,
            unrealizedPnl = value?.let { pnlFromValue(spread, it) },
            source = Source.LAST_MONITOR_MARK,
        )
    }

    private fun pnlFromValue(
        spread: Spread,
        valuePerShare: BigDecimal,
    ): BigDecimal =
        spread.creditPerShare
            .subtract(valuePerShare)
            .multiply(BigDecimal(spread.quantity))
            .multiply(CONTRACT_MULTIPLIER)
            .setScale(2, RoundingMode.HALF_UP)

    /** Contract identity shared with held positions (mirrors OrphanPositionDetector's key). */
    private data class PosKey(
        val symbol: String,
        val strike: BigDecimal?,
        val right: String?,
        val expiry: LocalDate?,
    )

    private fun keyOf(c: OptionContract) = PosKey(c.symbol.value, c.strike.stripTrailingZeros(), c.type.ibkrCode, c.expiry)

    private companion object {
        val LIVE_STATUSES = setOf(SpreadStatus.OPEN, SpreadStatus.CLOSING)
    }
}
