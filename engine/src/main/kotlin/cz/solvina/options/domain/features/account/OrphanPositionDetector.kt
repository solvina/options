package cz.solvina.options.domain.features.account

import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.models.OptionContract
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Detects IBKR account positions that are NOT explained by the engine's currently-managed
 * OPEN spreads — i.e. orphans the engine isn't risk-managing (no TP/SL/DTE exits run on them).
 *
 * Pure logic, no I/O, so it is unit-testable. Detection only — never closes or adopts.
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
        openSpreads: List<BullPutSpread>,
        accountPositions: List<AccountPosition>,
    ): List<Orphan> {
        // Expected signed option quantity per contract key, summed across managed open spreads.
        val expected = HashMap<PosKey, Int>()
        for (spread in openSpreads) {
            addLeg(expected, keyOf(spread.soldLeg.contract), -spread.quantity)
            addLeg(expected, keyOf(spread.boughtLeg.contract), spread.quantity)
        }

        val orphans = mutableListOf<Orphan>()
        for (p in accountPositions) {
            val qty = p.quantity.toInt()
            if (qty == 0) continue
            when (p.secType) {
                "STK" -> orphans += Orphan(p, "stock position — bull-put strategy never holds stock (assignment/partial-fill artifact)")
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

    private fun addLeg(
        map: HashMap<PosKey, Int>,
        key: PosKey,
        signedQty: Int,
    ) {
        map[key] = (map[key] ?: 0) + signedQty
    }
}
