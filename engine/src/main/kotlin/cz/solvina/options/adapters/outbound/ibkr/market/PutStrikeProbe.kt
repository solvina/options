package cz.solvina.options.adapters.outbound.ibkr.market

import java.math.BigDecimal
import kotlin.math.abs

/**
 * Model-free location of the bull-put short strike. Put |delta| falls as the strike moves away
 * from spot, so a coarse probe across the band tells where the target delta sits; the refine round
 * then prices the strikes in between. Everything here works off live IBKR deltas.
 */
internal object PutStrikeProbe {
    /**
     * Live |delta| probes that straddle the target: [upper] is the nearest-to-spot side
     * (|delta| above target), [lower] the further-OTM side (|delta| at or below target).
     * A null side means the target lies beyond the probed range on that side.
     */
    data class Bracket(
        val upper: BigDecimal?,
        val upperDelta: Double?,
        val lower: BigDecimal?,
        val lowerDelta: Double?,
    )

    /** [count] strikes spread evenly across [strikes], always including both ends. */
    fun probeStrikes(
        strikes: List<BigDecimal>,
        count: Int,
    ): List<BigDecimal> {
        val sorted = strikes.distinct().sorted()
        if (sorted.size <= count) return sorted
        if (count < 2) return sorted.takeLast(count.coerceAtLeast(0))
        return (0 until count)
            .map { i -> sorted[Math.round(i * (sorted.size - 1).toDouble() / (count - 1)).toInt()] }
            .distinct()
    }

    /** Null when no probe returned a live delta. [probes] pairs a strike with its live |delta|. */
    fun bracket(
        probes: List<Pair<BigDecimal, Double?>>,
        targetDelta: Double,
    ): Bracket? {
        val live = probes.mapNotNull { (k, d) -> d?.let { k to it } }.sortedByDescending { it.first }
        if (live.isEmpty()) return null
        val idx = live.indexOfFirst { it.second <= targetDelta }
        return when (idx) {
            -1 -> Bracket(live.last().first, live.last().second, null, null)
            0 -> Bracket(null, null, live.first().first, live.first().second)
            else -> Bracket(live[idx - 1].first, live[idx - 1].second, live[idx].first, live[idx].second)
        }
    }

    /**
     * Up to [count] strikes strictly inside the bracket, nearest the strike where the target delta is
     * interpolated to sit. An open side extends to the end of [strikes].
     */
    fun refineStrikes(
        strikes: List<BigDecimal>,
        bracket: Bracket,
        count: Int,
        targetDelta: Double? = null,
    ): List<BigDecimal> {
        val hi = bracket.upper ?: strikes.maxOrNull() ?: return emptyList()
        val lo = bracket.lower ?: strikes.minOrNull() ?: return emptyList()
        // Exclusive of a probed side (already priced), inclusive of an open one.
        val inside =
            strikes.filter { k ->
                (if (bracket.upper != null) k < hi else k <= hi) && (if (bracket.lower != null) k > lo else k >= lo)
            }
        val anchor =
            if (targetDelta != null &&
                bracket.upperDelta != null &&
                bracket.lowerDelta != null &&
                bracket.upperDelta != bracket.lowerDelta
            ) {
                val f = (bracket.upperDelta - targetDelta) / (bracket.upperDelta - bracket.lowerDelta)
                hi.toDouble() - f * (hi.toDouble() - lo.toDouble())
            } else {
                // One side open: lean towards it, where the target delta must lie.
                when {
                    bracket.upper == null -> hi.toDouble()
                    bracket.lower == null -> lo.toDouble()
                    else -> (hi.toDouble() + lo.toDouble()) / 2
                }
            }
        return inside.sortedBy { abs(it.toDouble() - anchor) }.take(count)
    }
}
