package cz.solvina.options.adapters.inbound.diagnostics

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Installs kotlinx-coroutines [DebugProbes] at startup so [HangDiagnostics] can dump the live
 * coroutine tree (every suspended `await` and its chain). Behind a flag — enabled by default while
 * we hunt the hang; turn off (diagnostics.coroutine-debug.enabled=false) once it's diagnosed to
 * drop the (small) per-suspension overhead.
 *
 * Creation stack traces are disabled: they are the expensive part, and for a hang we want the
 * *current* suspension point, which the dump shows regardless.
 */
@Component
class CoroutineDebugInstaller(
    @Value("\${diagnostics.coroutine-debug.enabled:true}") private val enabled: Boolean,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    @EventListener(ApplicationReadyEvent::class)
    fun install() {
        if (!enabled) {
            logger.info { "Coroutine DebugProbes disabled (diagnostics.coroutine-debug.enabled=false)" }
            return
        }
        if (DebugProbes.isInstalled) return
        DebugProbes.enableCreationStackTraces = false
        DebugProbes.install()
        logger.warn {
            "Coroutine DebugProbes INSTALLED — hang dumps will include the coroutine tree. " +
                "Small runtime overhead; set diagnostics.coroutine-debug.enabled=false once diagnosed."
        }
    }
}
