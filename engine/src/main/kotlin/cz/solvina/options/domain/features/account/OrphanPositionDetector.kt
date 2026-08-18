package cz.solvina.options.domain.features.account

import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.strategy.live.StockPosition
import cz.solvina.options.domain.features.strategy.live.StockPositionStatus
import cz.solvina.options.domain.models.OptionContract
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Detects IBKR account positions that are NOT explained by anything the engine is actively
 * managing — i.e. orphans with no automated exit (no spread TP/SL/DTE exit, no flag bracket).
 *
 * Every managed strategy contributes its expected holdings:
 *  - credit spreads (bull put, bear call) — two option legs each, via [Spread];
 *  - bull flags — a long stock position guarded by a live bracket order, via [FlagPosition];
 *  - strategy-library stock positions — long stock under a live bracket, via [StockPosition].
 *
 * A held position is an orphan only when its signed quantity does not match the sum of what the
 * managed positions expect for that exact contract. Pure logic, no I/O, so it is unit-testable.
 * Detection only — never closes or adopts.
 */
@Component
class OrphanPositionDetector {
    data class Orphan(
        val position: AccountPosition,
        val reason: String,
    )

    /**
     * A contract the engine expects to hold (leg of an OPEN spread / flag stock) that the broker
     * account does not fully hold. The inverse of an orphan — [detect] iterates held positions, so
     * a leg that is entirely ABSENT never surfaces there (COHR 280/270 incident, 2026-07-10: the
     * long 270P vanished from the account and nothing alerted while the short ran naked).
     */
    data class MissingLeg(
        val description: String,
        val expected: Int,
        val held: Int,
        val owners: List<String>,
    )

    private data class PosKey(
        val symbol: String,
        val strike: BigDecimal?,
        val right: String?,
        val expiry: LocalDate?,
    )

    fun detect(
        openSpreads: List<Spread>,
        openFlags: List<FlagPosition>,
        stockPositions: List<StockPosition>,
        accountPositions: List<AccountPosition>,
    ): List<Orphan> {
        // Expected signed quantity per contract key, summed across everything the engine manages.
        val expected = HashMap<PosKey, Int>()
        for (spread in openSpreads) {
            addLeg(expected, keyOf(spread.soldLeg.contract), -spread.quantity)
            addLeg(expected, keyOf(spread.boughtLeg.contract), spread.quantity)
        }
        for (flag in openFlags) {
            // Bull flags hold long stock (+shares). Keyed by symbol only — stock has no
            // strike/right/expiry. If short (bear) flags are ever added, sign must follow direction.
            addLeg(expected, stockKey(flag.symbol.value), flag.shares)
        }
        addStockPositions(expected, stockPositions)

        val orphans = mutableListOf<Orphan>()
        for (p in accountPositions) {
            val qty = p.quantity.toInt()
            if (qty == 0) continue
            when (p.secType) {
                "STK" -> {
                    val exp = expected[stockKey(p.symbol)] ?: 0
                    if (qty != exp) {
                        val reason =
                            if (exp == 0) {
                                "stock position with no managing OPEN flag or stock strategy (untracked)"
                            } else {
                                "stock quantity mismatch vs managed flags/stock strategies (expected $exp, held $qty)"
                            }
                        orphans += Orphan(p, reason)
                    }
                }
                "OPT" -> {
                    val key = PosKey(p.symbol, p.strike?.normalized(), p.optionRight, p.expiry)
                    val exp = expected[key] ?: 0
                    if (qty != exp) {
                        val reason =
                            if (exp == 0) {
                                "no managing OPEN spread (naked/untracked leg)"
                            } else {
                                "quantity mismatch vs managed spreads (expected $exp, held $qty)"
                            }
                        orphans += Orphan(p, reason)
                    }
                }
                else -> orphans += Orphan(p, "unexpected secType '${p.secType}'")
            }
        }
        return orphans
    }

    /**
     * The reverse check of [detect]: contracts the engine expects (legs of OPEN spreads, flag
     * stock) whose held quantity at the broker differs from the expectation. Keys with a matching
     * hold are fine; a key held at 0 is invisible to [detect] and only caught here. BigDecimal
     * strikes are normalized so 280 and 280.00 compare equal.
     */
    fun detectMissing(
        openSpreads: List<Spread>,
        openFlags: List<FlagPosition>,
        stockPositions: List<StockPosition>,
        accountPositions: List<AccountPosition>,
    ): List<MissingLeg> {
        val expected = HashMap<PosKey, Int>()
        val owners = HashMap<PosKey, MutableList<String>>()
        for (spread in openSpreads) {
            val label =
                "${spread.symbol.value} ${spread.soldLeg.contract.strike}/${spread.boughtLeg.contract.strike} " +
                    "exp ${spread.soldLeg.contract.expiry} [${spread.strategyId} ${spread.status}]"
            addLeg(expected, keyOf(spread.soldLeg.contract), -spread.quantity)
            addLeg(expected, keyOf(spread.boughtLeg.contract), spread.quantity)
            owners.getOrPut(keyOf(spread.soldLeg.contract)) { mutableListOf() }.add(label)
            owners.getOrPut(keyOf(spread.boughtLeg.contract)) { mutableListOf() }.add(label)
        }
        for (flag in openFlags) {
            addLeg(expected, stockKey(flag.symbol.value), flag.shares)
            owners.getOrPut(stockKey(flag.symbol.value)) { mutableListOf() }.add("${flag.symbol.value} flag ×${flag.shares}")
        }
        for (sp in stockPositions.filter { it.status == StockPositionStatus.OPEN }) {
            owners.getOrPut(stockKey(sp.symbol.value)) { mutableListOf() }.add("${sp.symbol.value} ${sp.strategyId} ×${sp.shares}")
        }
        addStockPositions(expected, stockPositions)

        val held = HashMap<PosKey, Int>()
        for (p in accountPositions) {
            val key =
                if (p.secType == "OPT") PosKey(p.symbol, p.strike?.normalized(), p.optionRight, p.expiry) else stockKey(p.symbol)
            held[key] = (held[key] ?: 0) + p.quantity.toInt()
        }

        return expected.mapNotNull { (key, exp) ->
            val have = held[key] ?: 0
            if (have == exp) return@mapNotNull null
            val desc =
                if (key.strike != null) {
                    "${key.symbol} ${key.right}${key.strike} exp ${key.expiry}"
                } else {
                    "${key.symbol} STK"
                }
            MissingLeg(description = desc, expected = exp, held = have, owners = owners[key] ?: emptyList())
        }
    }

    /**
     * Strategy-library stock positions hold long stock under a live bracket, exactly like a flag, so
     * they contribute the same symbol-keyed expectation. Only [StockPositionStatus.OPEN] holds
     * shares — a PENDING entry limit is still working at the broker and owns nothing yet, and
     * counting it would invert the bug by reporting a *missing* stock position for every unfilled
     * entry. Filtering here rather than at the call sites keeps the three callers from having to
     * remember that distinction; passing `findLive()` straight through stays correct.
     */
    private fun addStockPositions(
        expected: HashMap<PosKey, Int>,
        stockPositions: List<StockPosition>,
    ) {
        for (sp in stockPositions) {
            if (sp.status != StockPositionStatus.OPEN) continue
            addLeg(expected, stockKey(sp.symbol.value), sp.shares)
        }
    }

    private fun keyOf(c: OptionContract) = PosKey(c.symbol.value, c.strike.normalized(), c.type.ibkrCode, c.expiry)

    private fun BigDecimal.normalized(): BigDecimal = stripTrailingZeros()

    private fun stockKey(symbol: String) = PosKey(symbol, null, null, null)

    private fun addLeg(
        map: HashMap<PosKey, Int>,
        key: PosKey,
        signedQty: Int,
    ) {
        map[key] = (map[key] ?: 0) + signedQty
    }
}
