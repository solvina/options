package cz.solvina.options.domain.features.market

import cz.solvina.options.domain.models.Symbol
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-symbol record of whether market data is live, delayed, or blind.
 *
 * IBKR's `marketDataType(reqId, type)` callback (1=live, 2=frozen, 3=delayed, 4=delayed-frozen) is
 * otherwise discarded, so nothing told us the feed quality per symbol. Flag symbols additionally get
 * a definitive signal from the real-time bars stream itself: `reqRealTimeBars` is served only on a
 * live subscription, so bars flowing ⇒ genuinely live, and a rejected bars request ⇒ BLIND (the EU
 * delayed/unentitled case). Read by the flag scanner status.
 */
@Component
class MarketDataTypeTracker(
    private val clock: Clock,
) {
    enum class Feed { LIVE, FROZEN, DELAYED, DELAYED_FROZEN, BLIND, UNKNOWN }

    data class Info(
        val feed: Feed,
        val detail: String,
        val updatedAt: Instant,
    )

    private val bySymbol = ConcurrentHashMap<String, Info>()

    /** From the IBKR `marketDataType` callback. */
    fun recordType(
        symbol: Symbol,
        ibkrType: Int,
    ) {
        val feed =
            when (ibkrType) {
                1 -> Feed.LIVE
                2 -> Feed.FROZEN
                3 -> Feed.DELAYED
                4 -> Feed.DELAYED_FROZEN
                else -> Feed.UNKNOWN
            }
        put(symbol, feed, "marketDataType=$ibkrType")
    }

    /** Real-time bars are flowing — authoritative "live" for flag symbols (bars are live-only). */
    fun recordLiveBars(symbol: Symbol) = put(symbol, Feed.LIVE, "real-time bars flowing")

    /** Real-time bars rejected — no live feed for this symbol (delayed/unentitled venue). */
    fun recordBlind(
        symbol: Symbol,
        reason: String,
    ) = put(symbol, Feed.BLIND, reason)

    fun feedFor(symbol: Symbol): Feed = bySymbol[symbol.value]?.feed ?: Feed.UNKNOWN

    fun snapshot(): Map<String, Info> = bySymbol.toMap()

    private fun put(
        symbol: Symbol,
        feed: Feed,
        detail: String,
    ) {
        bySymbol[symbol.value] = Info(feed, detail, clock.instant())
    }
}
