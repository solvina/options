package cz.solvina.options.adapters.outbound.ibkr.market

import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingMarketDataRequest
import cz.solvina.options.domain.features.scanner.ScanTelemetryPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val waitLogger = KotlinLogging.logger("SNAPSHOT_WAIT")

/**
 * OBSERVE-ONLY measurement of the market-data snapshot wait (2026-11-21). Changes nothing about how
 * long a request waits: it replays each finished request's tick history against candidate
 * "return once the ticks go quiet" rules and counts what such a rule would have saved and what it
 * would have missed (a delta, bid or ask that only arrived after the quiet point).
 *
 * Why: a July quiescence change (0.4s quiet, @832efe5) was lost with the admission-controller revert
 * and is remembered as risky. Before any early return is switched on, the live numbers must show it
 * costs (almost) no deltas. Summary per scan run via [drain]; each would-be miss of the reference
 * rule is logged on the SNAPSHOT_WAIT logger.
 */
@Component
class SnapshotWaitStats : ScanTelemetryPort {
    /** A candidate rule: never return before [minMs]; then return after [quietMs] with no new tick. */
    data class Rule(
        val minMs: Long,
        val quietMs: Long,
    ) {
        val label get() = "min$minMs/quiet$quietMs"
    }

    /** What a rule would have done to one request. */
    data class Replay(
        val returnAtMs: Long,
        val savedMs: Long,
        val lostDelta: Boolean,
        val lostBid: Boolean,
        val lostAsk: Boolean,
        val lostReady: Boolean,
        val lateArrivalMs: Long?,
    ) {
        val lostAnything get() = lostDelta || lostBid || lostAsk || lostReady
    }

    private class RuleTotals {
        var early = 0
        var savedMs = 0L
        var lostDelta = 0
        var lostBid = 0
        var lostAsk = 0
        var lostReady = 0
    }

    private class Totals {
        var count = 0
        var ready = 0
        var timedOut = 0
        var silent = 0
        var waitMs = 0L
        val rules = RULES.associateWith { RuleTotals() }
    }

    private val totals = mutableMapOf<String, Totals>()

    fun record(
        symbol: Symbol,
        purpose: String,
        pending: PendingMarketDataRequest,
        isReady: (MarketDataSnapshot) -> Boolean,
        endNanos: Long,
        timedOut: Boolean,
    ) {
        runCatching {
            val endMs = (endNanos - pending.startedNanos) / NANOS_PER_MS
            val ticks = pending.history.map { (it.atNanos - pending.startedNanos) / NANOS_PER_MS to it.snapshot }
            val finalSnapshot = pending.snapshot
            val replays = RULES.associateWith { replay(it, ticks, endMs, finalSnapshot, isReady) }
            val category = category(purpose)
            synchronized(this) {
                val t = totals.getOrPut(category) { Totals() }
                t.count++
                if (timedOut) t.timedOut++ else t.ready++
                if (ticks.isEmpty()) t.silent++
                t.waitMs += endMs
                replays.forEach { (rule, r) ->
                    val rt = t.rules.getValue(rule)
                    if (r.savedMs > 0) rt.early++
                    rt.savedMs += r.savedMs
                    if (r.lostDelta) rt.lostDelta++
                    if (r.lostBid) rt.lostBid++
                    if (r.lostAsk) rt.lostAsk++
                    if (r.lostReady) rt.lostReady++
                }
            }
            val ref = replays.getValue(REFERENCE)
            if (ref.lostAnything) {
                val missed =
                    listOfNotNull(
                        "delta".takeIf { ref.lostDelta },
                        "bid".takeIf { ref.lostBid },
                        "ask".takeIf { ref.lostAsk },
                        "ready".takeIf { ref.lostReady },
                    ).joinToString("+")
                waitLogger.info {
                    "LATE [$symbol] $category: ${REFERENCE.label} would return at ${ref.returnAtMs}ms and miss $missed " +
                        "(arrived ${ref.lateArrivalMs}ms, request ended ${endMs}ms${if (timedOut) " timeout" else ""}, " +
                        "ticks at ${ticks.joinToString(",") { it.first.toString() }})"
                }
            }
        }.onFailure { waitLogger.warn { "snapshot wait stats failed: ${it.message}" } }
    }

    /** Summary since the previous call, one segment per request category; resets the counters. */
    @Synchronized
    override fun drain(): String {
        val out =
            totals.entries.sortedBy { it.key }.joinToString(" | ") { (cat, t) ->
                val rules =
                    t.rules.entries.joinToString(" ") { (rule, r) ->
                        "[${rule.label} early=${r.early} saved=${r.savedMs / 1000}s lost:delta=${r.lostDelta} " +
                            "bid=${r.lostBid} ask=${r.lostAsk} ready=${r.lostReady}]"
                    }
                "$cat n=${t.count} ready=${t.ready} timeout=${t.timedOut} silent=${t.silent} " +
                    "avgWait=${if (t.count == 0) 0 else t.waitMs / t.count}ms $rules"
            }
        totals.clear()
        return out
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L

        val RULES = listOf(Rule(1000, 500), Rule(1500, 1000), Rule(2000, 1000))

        /** The rule a would-be miss is logged for (the one proposed to the user). */
        val REFERENCE = Rule(1500, 1000)

        fun category(purpose: String): String =
            when {
                purpose.startsWith("scanner candidate-strike greeks") -> "strike"
                purpose.startsWith("combo BAG quote") -> "combo"
                purpose.startsWith("underlying price") -> "underlying"
                else -> "other"
            }

        /**
         * Replays [rule] over one request's [ticks] (ms since the request, in arrival order). The rule
         * returns at the first moment T >= minMs with at least one tick so far and none in the last
         * quietMs. A request with no ticks at all is never cut short (silence is not settledness).
         * Anything that became present only after T is what the rule would have missed.
         */
        fun replay(
            rule: Rule,
            ticks: List<Pair<Long, MarketDataSnapshot>>,
            endMs: Long,
            finalSnapshot: MarketDataSnapshot,
            isReady: (MarketDataSnapshot) -> Boolean,
        ): Replay {
            var returnAt = endMs
            for (i in ticks.indices) {
                val candidate = maxOf(rule.minMs, ticks[i].first + rule.quietMs)
                val next = ticks.getOrNull(i + 1)?.first
                if (next == null || next > candidate) {
                    returnAt = minOf(candidate, endMs)
                    break
                }
            }
            if (returnAt >= endMs) return Replay(endMs, 0, false, false, false, false, null)
            val atReturn = ticks.lastOrNull { it.first <= returnAt }?.second ?: MarketDataSnapshot()
            val lostDelta = atReturn.delta.isNaN() && !finalSnapshot.delta.isNaN()
            val lostBid = !atReturn.bid.positive() && finalSnapshot.bid.positive()
            val lostAsk = !atReturn.ask.positive() && finalSnapshot.ask.positive()
            val lostReady = !isReady(atReturn) && isReady(finalSnapshot)
            val lateArrival =
                ticks
                    .firstOrNull { (at, s) ->
                        at > returnAt &&
                            ((lostDelta && !s.delta.isNaN()) || (lostBid && s.bid.positive()) || (lostAsk && s.ask.positive()))
                    }?.first
            return Replay(returnAt, endMs - returnAt, lostDelta, lostBid, lostAsk, lostReady, lateArrival)
        }

        private fun Double.positive() = !isNaN() && this > 0
    }
}
