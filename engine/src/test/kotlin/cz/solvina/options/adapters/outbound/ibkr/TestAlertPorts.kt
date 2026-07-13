package cz.solvina.options.adapters.outbound.ibkr

import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import java.util.concurrent.CopyOnWriteArrayList

/** No-op alert sink for tests that don't assert on alerting. */
object NoopAlertPort : AlertPort {
    override suspend fun send(
        level: AlertLevel,
        title: String,
        body: String,
    ) {}
}

/** Records alerts so tests can assert starvation/limit alerting. */
class RecordingAlertPort : AlertPort {
    val alerts = CopyOnWriteArrayList<Triple<AlertLevel, String, String>>()

    override suspend fun send(
        level: AlertLevel,
        title: String,
        body: String,
    ) {
        alerts += Triple(level, title, body)
    }
}
