package cz.solvina.options

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springdoc.webflux.ui.SwaggerConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

private val logger = KotlinLogging.logger {}

@SpringBootApplication(exclude = [SwaggerConfig::class])
@EnableScheduling
@ConfigurationPropertiesScan
class OptionsApplication

fun main(args: Array<String>) {
    runApplication<OptionsApplication>(*args)
}
