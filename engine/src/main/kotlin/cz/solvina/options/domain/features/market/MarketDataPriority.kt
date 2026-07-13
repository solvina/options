package cz.solvina.options.domain.features.market

import kotlin.coroutines.CoroutineContext

/**
 * Priority class a coroutine carries into the IBKR admission layer, as a [CoroutineContext]
 * element so domain ports stay priority-free: a service tags its scope once —
 * `withContext(MarketDataPriority.SCANNER) { … }` — and every market-data request made below it
 * inherits the class. Adapters read `coroutineContext[MarketDataPriority]`, defaulting to [EXEC]
 * so an untagged caller lands in a reserved (never-starved) class rather than the scanner's
 * leftover budget.
 */
enum class MarketDataPriority : CoroutineContext.Element {
    /** Flag strategy's constant real-time bars feed — reserved lines, never starved. */
    FLAG,

    /** Trade execution: entry/reprice/close quote streams — reserved lines, never starved. */
    EXEC,

    /** Exit monitor: open-position quotes for TP/SL/DTE decisions — reserved lines, never starved. */
    EXIT,

    /** Entry scanner, diagnostics, warmup, dividend refresh — leftover capacity only, may wait/skip. */
    SCANNER,
    ;

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<MarketDataPriority>
}
