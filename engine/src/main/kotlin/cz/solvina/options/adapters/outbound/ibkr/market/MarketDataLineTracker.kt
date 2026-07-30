package cz.solvina.options.adapters.outbound.ibkr.market

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger("MARKET_DATA_LINES")

/**
 * Process-wide count of active TWS market-data lines (reqMktData / reqTickByTickData /
 * reqRealTimeBars), for visibility into how close we run to IBKR's per-connection line limit.
 * Deliberately just an AtomicInteger + a log line per +1/-1 — no pooling, capping, or locking.
 */
object MarketDataLineTracker {
    private val active = AtomicInteger(0)

    fun subscribed(what: String) {
        logger.info { "$what requested, active market data lines=${active.incrementAndGet()}" }
    }

    fun unsubscribed(what: String) {
        logger.info { "$what unsubscribed, active market data lines=${active.decrementAndGet()}" }
    }
}
