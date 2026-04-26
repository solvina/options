package cz.solvina.options

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class AppConfig {
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
