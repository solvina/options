package cz.solvina.options.domain.features.shadow

import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ShadowStatus { OPEN, CLOSED_PROFIT, CLOSED_STOP, CLOSED_TIME, EXPIRED }

/**
 * A spread the engine wanted but did not get on paper, tracked as if it had filled at [midCredit].
 * Paper only fills when the visible bid reaches the limit; live market makers fill near mid (the
 * user's ORCL/NUE/WELL fills), so these are the best available estimate of what the strategy
 * would earn live. Notification/analysis only — nothing here ever places an order.
 */
data class ShadowSpread(
    val id: UUID = UUID.randomUUID(),
    val strategyId: StrategyId,
    val symbol: Symbol,
    val optionType: OptionType,
    val expiry: LocalDate,
    val soldStrike: BigDecimal,
    val boughtStrike: BigDecimal,
    val openedAt: Instant,
    /** FLOOR_REACHED / TIMED_OUT / LIQUIDITY_REJECTED (never ordered) / BACKFILL. */
    val sourceOutcome: String,
    val midCredit: BigDecimal,
    val floorCredit: BigDecimal? = null,
    val naturalCredit: BigDecimal? = null,
    val underlyingAtEntry: BigDecimal? = null,
    val ivRankAtEntry: Double? = null,
    val status: ShadowStatus = ShadowStatus.OPEN,
    val closeReason: String? = null,
    val closedAt: Instant? = null,
    /** Spread value per share when closed (cost to buy back). */
    val exitValue: BigDecimal? = null,
    val lastValue: BigDecimal? = null,
    val lastUnderlying: BigDecimal? = null,
    val lastValuedAt: Instant? = null,
    val backfilled: Boolean = false,
) {
    val width: BigDecimal get() = soldStrike.subtract(boughtStrike).abs()
}

interface ShadowSpreadPort {
    suspend fun findOpen(): List<ShadowSpread>

    suspend fun findAll(): List<ShadowSpread>

    suspend fun save(shadow: ShadowSpread): ShadowSpread
}
