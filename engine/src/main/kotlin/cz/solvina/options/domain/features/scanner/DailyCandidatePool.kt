package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.models.Symbol
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * `scanner.pool.*`. When enabled, scans only collect candidates; orders go out at the decision times,
 * best first. Entering each candidate the moment it qualified gave limited slots to whichever came
 * first, and the best titles often came late (2026-11-03: GILD 18:52, AMT 19:21, US open 15:30).
 */
@ConfigurationProperties("scanner.pool")
data class ScannerPoolConfig(
    val enabled: Boolean = false,
    // Real orders per decision, best first; the portfolio caps still apply in TradeExecutionService.
    val maxEntriesPerDecision: Int = 5,
    // Limits of the simulated "selected" portfolio on the Would-be Trades page. Separate from what
    // paper actually trades, so the page can show what a small live account would have held.
    val portfolioMaxOpen: Int = 3,
    val portfolioMaxNewPerDay: Int = 1,
    val portfolioMaxLossUsd: Double = 500.0,
)

/**
 * Titles that qualified today and wait for the next decision. In memory: a restart loses only the
 * first-seen times, because the next scans refill it and a decision re-prices every title anyway.
 */
@Component
class DailyCandidatePool(
    private val clock: Clock,
) {
    private val firstSeen = ConcurrentHashMap<Symbol, Instant>()

    @Volatile private var day: LocalDate? = null

    private fun rollOver() {
        val today = LocalDate.now(clock)
        if (day != today) {
            firstSeen.clear()
            day = today
        }
    }

    fun offer(symbol: Symbol) {
        rollOver()
        firstSeen.putIfAbsent(symbol, Instant.now(clock))
    }

    fun contains(symbol: Symbol): Boolean {
        rollOver()
        return firstSeen.containsKey(symbol)
    }

    fun firstSeenAt(symbol: Symbol): Instant? = firstSeen[symbol]

    fun symbols(): List<Symbol> {
        rollOver()
        return firstSeen.keys.toList()
    }

    fun remove(symbol: Symbol) {
        firstSeen.remove(symbol)
    }

    fun clear() = firstSeen.clear()
}
