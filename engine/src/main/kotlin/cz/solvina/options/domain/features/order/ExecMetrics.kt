package cz.solvina.options.domain.features.order

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * One fill-quality line per order attempt for the TRADES journal (2026-11-27), e.g.
 * `EXEC   ENTRY QQQ 450P/445P  outcome=FILLED  mid=$1.49  start=$1.58  last=$1.52  fill=$1.53  fill_vs_mid=+2.7%  secs=212  amends=4  order=123`
 *
 * fill_vs_mid is signed in OUR favour: + means better than mid (more credit on an entry, less debit
 * on an exit). Grep `EXEC ` to measure fill rate, price vs mid, time on book and price changes.
 */
object ExecMetrics {
    fun line(
        side: String,
        symbol: String,
        legs: String,
        outcome: String,
        mid: BigDecimal?,
        start: BigDecimal,
        last: BigDecimal,
        fill: BigDecimal?,
        startedAt: Instant,
        endedAt: Instant,
        amends: Int,
        orderId: Int,
        creditSide: Boolean,
        note: String = "",
    ): String {
        val vsMid =
            if (fill != null && mid != null && mid.signum() != 0) {
                val diff = if (creditSide) fill.subtract(mid) else mid.subtract(fill)
                val pct = diff.divide(mid.abs(), 6, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)
                (if (pct.signum() >= 0) "+" else "") + pct.toPlainString() + "%"
            } else {
                "n/a"
            }
        val secs = Duration.between(startedAt, endedAt).seconds

        fun money(v: BigDecimal?) = v?.let { "$" + it.setScale(2, RoundingMode.HALF_UP).toPlainString() } ?: "n/a"
        return "EXEC   ${side.padEnd(5)} $symbol $legs  outcome=$outcome  mid=${money(mid)}  start=${money(start)}  " +
            "last=${money(last)}  fill=${money(fill)}  fill_vs_mid=$vsMid  secs=$secs  amends=$amends  order=$orderId" +
            if (note.isBlank()) "" else "  ($note)"
    }
}
