package cz.solvina.options

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class AppConfig {
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()

    /** Scope used by [cz.solvina.options.domain.features.execution.TradeExecutionService]
     *  to launch background execution coroutines. */
    @Bean
    fun executionCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
