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
    // Must stay zero: the admission controller exists to make these impossible.
    private val brokerLimitHits = ConcurrentHashMap<Int, AtomicLong>()

    /**
     * A broker-side limit error arrived (100 msg-rate / 101 line-cap / 162, 420 historical
     * pacing). The whole point of this controller is that these never fire — count them loudly.
     */
    fun noteBrokerLimitHit(code: Int) {
        val count = brokerLimitHits.computeIfAbsent(code) { AtomicLong() }.incrementAndGet()
        logger.error {
            "IBKR LIMIT HIT code=$code (count=$count) — admission control failed to prevent a " +
                    "broker-side pacing/limit violation; investigate which path bypassed it"
        }
        if (code == 100 || code == 101) {
            logger.error { "IBKR limit hit: error $code" }
            alertScope.launch {
                alertPort.send(
                    AlertLevel.CRITICAL,
                    "IBKR limit hit: error $code",
                    "IBKR reported ${if (code == 100) "max messages/sec exceeded" else "max market-data lines reached"} " +
                            "(occurrence #$count). The admission controller should make this impossible — " +
                            "some request path is bypassing it.",
                )
            }
        }
    }

}
