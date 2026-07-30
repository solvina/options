package cz.solvina.options.domain.features.account

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class AccountPosition(
    val account: String,
    val symbol: String,
    val secType: String,
    val currency: String,
    val expiry: LocalDate?,
    val strike: BigDecimal?,
    val optionRight: String?,
    val quantity: BigDecimal,
    val marketPrice: Double,
    val marketValue: Double,
    val avgCost: BigDecimal,
    val conId: Int = 0,
    val unrealizedPnL: Double? = null,
    val realizedPnL: Double? = null,
    /**
     * When the broker last pushed this row. Null on feeds that do not stamp it. Consumers that
     * display [marketPrice]/[unrealizedPnL] as "live" must check this — a dropped connection leaves
     * the last push in place indefinitely, and silently stale is exactly the failure mode this
     * field exists to make visible.
     */
    val updatedAt: Instant? = null,
)
