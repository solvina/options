package cz.solvina.options.domain.features.market

import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.flow.Flow

interface MarketTickPort {
    /**
     * Continuous underlying price stream (last or close price).
     * Cancelling the flow cancels the subscription and releases the IBKR request.
     */
    fun streamUnderlyingPrice(symbol: Symbol): Flow<Double>

    /**
     * Continuous net-credit stream for a bull-put spread.
     * Emits on every bid/ask tick for either leg via `reqTickByTick("BidAsk")`.
     * [SpreadCreditTick.soldDelta] and [SpreadCreditTick.boughtDelta] are populated from
     * `tickOptionComputation` and may be null until the first Greeks callback arrives.
     * Cancelling the flow cancels all four underlying IBKR subscriptions.
     */
    fun streamSpreadCredit(
        soldContract: OptionContract,
        boughtContract: OptionContract,
    ): Flow<SpreadCreditTick>
}

data class SpreadCreditTick(
    val soldBid: Double,
    val soldAsk: Double,
    val boughtBid: Double,
    val boughtAsk: Double,
    /** Approximate net credit: soldMid − boughtMid. */
    val netCredit: Double,
    /** Latest delta for the sold leg from tickOptionComputation; null before first Greeks update. */
    val soldDelta: Double? = null,
    /** Latest delta for the bought leg from tickOptionComputation; null before first Greeks update. */
    val boughtDelta: Double? = null,
)
