package cz.solvina.options.domain.features.scanner

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Selection rules shared by the bull put and bear call selectors. */
object SpreadSelectionRules {
    sealed interface ExpiryPick {
        data class Picked(
            val expiry: LocalDate,
        ) : ExpiryPick

        data object NoneInBand : ExpiryPick

        /** Every in-band expiry spans earnings; [preferred] is the one the DTE preference wanted. */
        data class AllSpanEarnings(
            val preferred: LocalDate,
            val earnings: LocalDate,
        ) : ExpiryPick
    }

    /**
     * The in-band expiry closest to [preferredDte] that finishes BEFORE the next earnings report.
     * Previously the closest expiry was picked first and the symbol rejected if it spanned earnings,
     * even when an earlier in-band expiry would have avoided it (CRM 2026-10-28: Dec 4 picked and
     * rejected for Dec 2 earnings while Nov 27, 30 DTE, was in band). A null or past earnings date
     * imposes no constraint.
     */
    fun pickExpiry(
        available: Collection<LocalDate>,
        today: LocalDate,
        minDte: Int,
        maxDte: Int,
        preferredDte: Int,
        earnings: LocalDate?,
    ): ExpiryPick {
        fun dte(exp: LocalDate) = ChronoUnit.DAYS.between(today, exp).toInt()
        val inBand = available.filter { dte(it) in minDte..maxDte }
        val preferred = inBand.minByOrNull { abs(dte(it) - preferredDte) } ?: return ExpiryPick.NoneInBand
        if (earnings == null || earnings.isBefore(today)) return ExpiryPick.Picked(preferred)
        val clear = inBand.filter { it.isBefore(earnings) }.minByOrNull { abs(dte(it) - preferredDte) }
        return if (clear != null) ExpiryPick.Picked(clear) else ExpiryPick.AllSpanEarnings(preferred, earnings)
    }

    fun widthExceedsCap(
        actualWidth: BigDecimal,
        targetWidth: BigDecimal,
        maxWidthMultiple: Double,
    ): Boolean = actualWidth > targetWidth.multiply(BigDecimal(maxWidthMultiple))

    /** Fraction of the mid credit the market will actually pay; null when mid is not positive. */
    fun comboBidPctOfMid(
        bidCredit: BigDecimal,
        midCredit: BigDecimal,
    ): Double? = if (midCredit.signum() <= 0) null else bidCredit.divide(midCredit, 4, RoundingMode.HALF_UP).toDouble()
}
