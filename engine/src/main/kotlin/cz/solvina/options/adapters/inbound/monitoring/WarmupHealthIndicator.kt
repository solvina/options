package cz.solvina.options.adapters.inbound.monitoring

import cz.solvina.options.adapters.outbound.ibkr.IbkrRateLimiter
import cz.solvina.options.domain.features.scanner.UniverseWarmupService
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Surfaces startup-warmup progress and remaining IBKR market-data line headroom under
 * /actuator/health (component "warmup"). Always reports UP — it is purely informational and must
 * never flip the overall health status (which deploy checks and monitoring rely on).
 */
@Component("warmup")
class WarmupHealthIndicator(
    private val warmup: UniverseWarmupService,
    private val rateLimiter: IbkrRateLimiter,
) : HealthIndicator {
    override fun health(): Health {
        val r = warmup.lastResult
        return Health
            .up()
            .withDetail("total", r?.total ?: 0)
            .withDetail("warmed", r?.warmed ?: 0)
            .withDetail("failed", r?.failed ?: 0)
            .withDetail("done", r?.done ?: false)
            .withDetail("marketDataLinesAvailable", rateLimiter.availableMarketDataLines())
            .build()
    }
}
