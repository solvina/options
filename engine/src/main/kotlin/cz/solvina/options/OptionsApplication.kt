package cz.solvina.options

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.config.EnableWebFlux

private val logger = KotlinLogging.logger {}

@SpringBootApplication
@EnableWebFlux
@EnableScheduling
class OptionsApplication

fun main(args: Array<String>) {
    runApplication<OptionsApplication>(*args)
}
