package cz.solvina.options.adapters.inbound.jobs

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
class SchedulerPoolConfig {
    @Bean
    fun criticalTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 2
            setThreadNamePrefix("critical-scheduling-")
            setRemoveOnCancelPolicy(true)
        }

    @Bean
    fun backgroundTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 4
            setThreadNamePrefix("background-scheduling-")
            setRemoveOnCancelPolicy(true)
        }
}
