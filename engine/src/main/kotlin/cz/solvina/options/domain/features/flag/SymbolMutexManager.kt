package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Per-symbol mutual exclusion manager.
 *
 * Prevents concurrent entries/signals for the same symbol by enforcing per-symbol locks.
 * Different symbols can enter concurrently (no global lock), but within a symbol,
 * only one signal is processed at a time.
 */
@Component
class SymbolMutexManager {
    private val symbolMutexes = ConcurrentHashMap<Symbol, Mutex>()

    /**
     * Acquire lock for a symbol and execute the given block.
     * If lock is already held, waits indefinitely.
     */
    suspend fun <T> withSymbolLock(
        symbol: Symbol,
        block: suspend () -> T,
    ): T {
        val mutex = symbolMutexes.getOrPut(symbol) { Mutex() }
        return mutex.withLock {
            block()
        }
    }

    /**
     * Get all symbols that have active mutexes.
     */
    fun getRegisteredSymbols(): Set<String> = symbolMutexes.keys.map { it.value }.toSet()

    /**
     * Get count of symbols with active mutexes.
     */
    fun getLockedSymbolCount(): Int = symbolMutexes.size
}
