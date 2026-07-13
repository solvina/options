package cz.solvina.options.adapters.inbound.monitoring

import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Live view of IBKR admission control under /actuator/health (component "ibkrAdmission") — our own
 * early-warning version of TWS's "too many data requests": per-class line usage/waits/timeouts,
 * message-bucket level, and broker-side limit-error counts. Always UP — purely informational;
 * pressure shows up in the numbers (and pages via the starvation alerts), never as a DOWN that
 * would trip deploy checks.
 */
@Component("ibkrAdmission")
class AdmissionHealthIndicator(
    private val admission: IbkrAdmissionController,
) : HealthIndicator {
    override fun health(): Health {
        val snap = admission.snapshot()
        val builder =
            Health
                .up()
                .withDetail("linesTotal", snap.linesTotal)
                .withDetail("linesAvailable", snap.linesAvailable)
                .withDetail("messageTokenLevel", "%.1f".format(snap.messageTokenLevel))
                .withDetail("messagesSent", snap.messagesSent)
                .withDetail("messageWaitTotalMs", snap.messageWaitTotalMs)
                .withDetail("messageWaitMaxMs", snap.messageWaitMaxMs)
                .withDetail("scannerHeadroomWaits", snap.scannerHeadroomWaits)
                .withDetail("brokerLimitHits", snap.brokerLimitHits)
        snap.lineClasses.forEach { (name, c) ->
            builder.withDetail(
                "lines$name",
                mapOf(
                    "held" to c.held,
                    "reserve" to c.reserve,
                    "acquires" to c.acquires,
                    "timeouts" to c.timeouts,
                    "waitTotalMs" to c.waitTotalMs,
                    "waitMaxMs" to c.waitMaxMs,
                ),
            )
        }
        return builder.build()
    }
}
