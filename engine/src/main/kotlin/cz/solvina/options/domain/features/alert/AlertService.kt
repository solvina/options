package cz.solvina.options.domain.features.alert

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class AlertService(
    private val alertPort: AlertPort,
    private val alertScope: CoroutineScope,
) {
    private val logger = KotlinLogging.logger {}

    // --- Broker-side limit errors (100 = msg rate, 101 = line cap, 162/420 = historical pacing).
    // Must stay zero: broker-side limits mean requests are reaching IBKR faster than it will accept.
    private val brokerLimitHits = ConcurrentHashMap<Int, AtomicLong>()

    /**
     * A broker-side limit error arrived (100 msg-rate / 101 line-cap / 162, 420 historical
     * pacing). Count them loudly so request pacing can be tightened.
     */
    fun noteBrokerLimitHit(code: Int) {
        val count = brokerLimitHits.computeIfAbsent(code) { AtomicLong() }.incrementAndGet()
        logger.error {
            "IBKR LIMIT HIT code=$code (count=$count) — IBKR reported a broker-side " +
                "pacing/limit violation; reduce request concurrency or inspect pacing"
        }
        if (code == 100 || code == 101) {
            logger.error { "IBKR limit hit: error $code" }
            alertScope.launch {
                alertPort.send(
                    AlertLevel.CRITICAL,
                    "IBKR limit hit: error $code",
                    "IBKR reported ${if (code == 100) "max messages/sec exceeded" else "max market-data lines reached"} " +
                        "(occurrence #$count). Reduce request concurrency or inspect broker request pacing.",
                )
            }
        }
    }

}
