package cz.solvina.options.domain.features.account

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Read-only window onto what IBKR is actually saying about a held **stock** position, straight from
 * the streamed `updatePortfolio` feed.
 *
 * Strictly a reporting concern: it performs no arithmetic of its own beyond scale normalisation, and
 * nothing here participates in entry, exit, sizing or protection decisions. The point is that the
 * operator can compare our journal (`entryPrice`, `actualEntryPrice`, our own P&L estimate) against
 * the broker's own `avgCost` / `marketPrice` / `unrealizedPNL` — the figures TWS renders — without
 * either side being silently massaged into agreement.
 */
@Service
class BrokerStockPositionService(
    private val positionsPort: PositionsPort,
    private val clock: Clock,
    /** Age past which a push is reported as stale. Display hint only — the value is still shown. */
    @Value("\${account.portfolio-mark-max-age-minutes:20}") private val markMaxAgeMinutes: Long,
) {
    /**
     * One IBKR `updatePortfolio` row, verbatim. [unrealizedPnl] is the broker's own figure (against
     * [avgCost], so inclusive of commissions) — the number TWS shows, not a recomputation.
     */
    data class BrokerStockPosition(
        val shares: BigDecimal,
        val marketPrice: BigDecimal,
        val marketValue: BigDecimal,
        val avgCost: BigDecimal,
        val unrealizedPnl: BigDecimal?,
        /** Broker's realized P&L on this position for the session. Reported as sent, not derived. */
        val realizedPnl: BigDecimal?,
        /** When IBKR last pushed this row; null on feeds that do not stamp it. */
        val updatedAt: Instant?,
        /** True when the push is older than the configured age — the figures may lag TWS. */
        val stale: Boolean,
    )

    /** Broker stock rows keyed by symbol. One feed read; safe to call per request (in-memory). */
    suspend fun bySymbol(): Map<String, BrokerStockPosition> {
        val positions =
            runCatching { positionsPort.getPositions() }
                .onFailure { e -> logger.warn(e) { "Portfolio feed unavailable, no broker stock view: ${e.message}" } }
                .getOrDefault(emptyList())
        val cutoff = clock.instant().minus(Duration.ofMinutes(markMaxAgeMinutes))
        return positions
            .filter { it.secType == "STK" && it.quantity.compareTo(BigDecimal.ZERO) != 0 }
            .groupBy { it.symbol }
            .mapNotNull { (symbol, rows) ->
                // More than one stock row for a symbol (different currency/exchange listings) cannot be
                // collapsed without inventing a blended avgCost, so report nothing rather than a
                // number the broker never sent.
                if (rows.size > 1) {
                    logger.warn { "[$symbol] ${rows.size} broker stock rows — skipping broker view rather than blending avgCost" }
                    return@mapNotNull null
                }
                symbol to rows.single().toBrokerView(cutoff)
            }.toMap()
    }

    private fun AccountPosition.toBrokerView(cutoff: Instant) =
        BrokerStockPosition(
            shares = quantity,
            marketPrice = BigDecimal.valueOf(marketPrice).setScale(4, RoundingMode.HALF_UP),
            marketValue = BigDecimal.valueOf(marketValue).setScale(2, RoundingMode.HALF_UP),
            avgCost = avgCost,
            unrealizedPnl = unrealizedPnL?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) },
            realizedPnl = realizedPnL?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) },
            updatedAt = updatedAt,
            stale = updatedAt != null && updatedAt.isBefore(cutoff),
        )
}
