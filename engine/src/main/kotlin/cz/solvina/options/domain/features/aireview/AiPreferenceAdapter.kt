package cz.solvina.options.domain.features.aireview

import cz.solvina.options.domain.features.scanner.AiPreferencePort
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Symbol
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Review tasks are keyed by the New York trading date, like the rest of the AI review. */
@Component
class AiPreferenceAdapter(
    private val service: AiReviewService,
    private val clock: Clock,
) : AiPreferencePort {
    override suspend fun preferredSwitch(symbol: Symbol): StrategyId? =
        service.preferredSwitch(LocalDate.now(clock.withZone(NEW_YORK)), symbol)

    private companion object {
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    }
}
