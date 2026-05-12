package cz.solvina.options.backtest

import cz.solvina.options.domain.features.market.BlackScholes
import cz.solvina.options.domain.features.market.MarketTickPort
import cz.solvina.options.domain.features.market.SpreadCreditTick
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Backtest implementation of [MarketTickPort].
 *
 * Emits a single tick per call, derived from fixture data at the current simulated date.
 * This is sufficient for the optimistic fill model used in [BacktestOrderExecutionAdapter]:
 * the execution service receives one credit tick, does not trigger repricing, and the
 * fill watcher immediately returns FILLED.
 */
class BacktestMarketTickAdapter(
    private val clock: MutableClock,
    private val marketDataAdapter: BacktestMarketDataAdapter,
) : MarketTickPort {
    override fun streamUnderlyingPrice(symbol: Symbol): Flow<Double> =
        flow {
            val price = marketDataAdapter.getUnderlyingPrice(symbol)
            emit(price.amount.toDouble())
        }

    override fun streamSpreadCredit(
        soldContract: OptionContract,
        boughtContract: OptionContract,
    ): Flow<SpreadCreditTick> =
        flow {
            val soldMid = marketDataAdapter.getOptionMid(soldContract).amount.toDouble()
            val boughtMid = marketDataAdapter.getOptionMid(boughtContract).amount.toDouble()

            // Approximate bid/ask as mid ± 5 %
            val soldBid = soldMid * 0.95
            val soldAsk = soldMid * 1.05
            val boughtBid = boughtMid * 0.95
            val boughtAsk = boughtMid * 1.05

            val spot = marketDataAdapter.getUnderlyingPrice(soldContract.symbol).amount.toDouble()
            val iv = marketDataAdapter.getIv(soldContract.symbol)
            val tteDays =
                java.time.temporal.ChronoUnit.DAYS
                    .between(clock.currentDate(), soldContract.expiry)
                    .toDouble()
            val tte = tteDays / 365.0

            val soldDelta = if (tte > 0) BlackScholes.putDelta(spot, soldContract.strike.toDouble(), tte, 0.05, iv) else null
            val boughtDelta = if (tte > 0) BlackScholes.putDelta(spot, boughtContract.strike.toDouble(), tte, 0.05, iv) else null

            emit(
                SpreadCreditTick(
                    soldBid = soldBid,
                    soldAsk = soldAsk,
                    boughtBid = boughtBid,
                    boughtAsk = boughtAsk,
                    netCredit = soldMid - boughtMid,
                    soldDelta = soldDelta,
                    boughtDelta = boughtDelta,
                ),
            )
        }
}
