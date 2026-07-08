package cz.solvina.options.domain.features.account

import cz.solvina.options.domain.models.Money
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Resolves the account size that all SIZING and LIMIT calculations run off — as opposed to the raw
 * broker figures (which display/diagnostics keep using).
 *
 * When [account.effective-account-size] is set, every capital figure (net liquidation + available
 * funds) is capped at it: `min(realValue, cap)`. This lets a large paper balance behave like a small
 * account for all limit math, and acts as a hard exposure ceiling on the live account. Because it is a
 * CAP (not an override), a real account smaller than the cap is respected — the cap only ever lowers,
 * never inflates, the figures used to size positions.
 *
 * When the property is absent/blank the real broker figures are used unchanged.
 */
@Component
class EffectiveAccountService(
    private val accountPort: AccountPort,
    @param:Value("\${account.effective-account-size:#{null}}")
    private val effectiveAccountSize: BigDecimal?,
) {
    /**
     * The account snapshot to drive limits off: the real broker snapshot with each capital amount
     * capped at [effectiveAccountSize] (when configured). Null when the broker snapshot isn't
     * available yet — callers already treat that as "don't size / don't trade".
     */
    fun detail(): AccountDetail? {
        val real = accountPort.accountDetail.value ?: return null
        val cap = effectiveAccountSize ?: return real
        return real.copy(
            totalCapital = real.totalCapital.capAt(cap),
            availableFunds = real.availableFunds.capAt(cap),
        )
    }

    private fun Money?.capAt(cap: BigDecimal): Money? = this?.let { Money(it.amount.min(cap), it.currency) }
}
