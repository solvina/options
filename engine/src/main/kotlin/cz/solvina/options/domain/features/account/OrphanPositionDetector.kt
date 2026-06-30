package cz.solvina.options.domain.features.account

import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.spread.model.Spread
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
 *  - bull flags — a long stock position guarded by a live bracket order, via [FlagPosition].
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

    private data class PosKey(
        val symbol: String,
        val strike: BigDecimal?,
        val right: String?,
        val expiry: LocalDate?,
    )

    fun detect(
        openSpreads: List<Spread>,
        openFlags: List<FlagPosition>,
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
                                "stock position with no managing OPEN flag (untracked)"
                            } else {
                                "stock quantity mismatch vs managed flags (expected $exp, held $qty)"
                            }
                        orphans += Orphan(p, reason)
                    }
                }
                "OPT" -> {
                    val key = PosKey(p.symbol, p.strike, p.optionRight, p.expiry)
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

    private fun keyOf(c: OptionContract) = PosKey(c.symbol.value, c.strike, c.type.ibkrCode, c.expiry)

    private fun stockKey(symbol: String) = PosKey(symbol, null, null, null)

    private fun addLeg(
        map: HashMap<PosKey, Int>,
        key: PosKey,
        signedQty: Int,
    ) {
        map[key] = (map[key] ?: 0) + signedQty
    }
}
