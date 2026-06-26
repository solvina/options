package cz.solvina.options.domain.features.spread.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

enum class QuoteHealth {
    LIVE,
    STALE,
    BLIND,
}

data class QuoteAgeState(
    val symbol: String,
    val health: QuoteHealth,
    val ageSeconds: Long,
    val consecutiveBlindCycles: Int = 0,
)

@Service
class QuoteHealthService(
    @Value("\${quote-monitoring.stale-seconds:60}")
    private val quoteStaleSeconds: Long,
    @Value("\${quote-monitoring.blind-seconds:300}")
    private val quoteBlindSeconds: Long,
    @Value("\${quote-monitoring.blind-cycles-before-exit:2}")
    private val blindCyclesBeforeExit: Int,
) {
    private val blindCycleCounts = ConcurrentHashMap<String, Int>()

    suspend fun classifyHealth(
        symbol: String,
        asOf: Instant?,
    ): QuoteAgeState {
        if (asOf == null) return QuoteAgeState(symbol, QuoteHealth.BLIND, Long.MAX_VALUE)

        val now = Instant.now()
        val ageSeconds = Duration.between(asOf, now).seconds

        val health = when {
            ageSeconds < quoteStaleSeconds -> QuoteHealth.LIVE
            ageSeconds < quoteBlindSeconds -> QuoteHealth.STALE
            else -> QuoteHealth.BLIND
        }

        if (health == QuoteHealth.BLIND) {
            blindCycleCounts[symbol] = blindCycleCounts.getOrDefault(symbol, 0) + 1
        } else {
            blindCycleCounts.remove(symbol)
        }

        return QuoteAgeState(
            symbol = symbol,
            health = health,
            ageSeconds = ageSeconds,
            consecutiveBlindCycles = blindCycleCounts.getOrDefault(symbol, 0),
        )
    }

    suspend fun canExecuteStop(state: QuoteAgeState): Boolean =
        state.health == QuoteHealth.BLIND && state.consecutiveBlindCycles >= blindCyclesBeforeExit

    fun resetBlindCount(symbol: String) {
        blindCycleCounts.remove(symbol)
    }

    fun getBlindCount(symbol: String): Int = blindCycleCounts.getOrDefault(symbol, 0)
}
