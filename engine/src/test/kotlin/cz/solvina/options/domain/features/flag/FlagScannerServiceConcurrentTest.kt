package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for SymbolMutexManager concurrent locking behavior.
 *
 * Verifies that per-symbol mutexes properly serialize access to the same symbol
 * while allowing concurrent access to different symbols.
 */
class FlagScannerServiceConcurrentTest {
    private val aapl = Symbol("AAPL")
    private val msft = Symbol("MSFT")

    @Test
    fun `symbol mutex manager serializes access to same symbol`() {
        val testDispatcher = StandardTestDispatcher()
        runTest(testDispatcher) {
            val manager = SymbolMutexManager()
            val executionOrder = mutableListOf<Int>()
            val lock = Any()

            repeat(5) { i ->
                launch {
                    manager.withSymbolLock(aapl) {
                        synchronized(lock) {
                            executionOrder.add(i)
                        }
                        delay(10)
                    }
                }
            }

            testDispatcher.scheduler.advanceUntilIdle()

            // All 5 executions should have completed in order (serialized)
            assertEquals(5, executionOrder.size, "All 5 locks should execute")
            assertEquals((0..4).toList(), executionOrder, "Executions should be serialized")
        }
    }

    @Test
    fun `symbol mutex manager allows concurrent access to different symbols`() {
        val testDispatcher = StandardTestDispatcher()
        runTest(testDispatcher) {
            val manager = SymbolMutexManager()
            val results = mutableListOf<String>()
            val lock = Any()

            repeat(3) { i ->
                launch {
                    manager.withSymbolLock(aapl) {
                        synchronized(lock) { results.add("AAPL-$i") }
                        delay(50)
                    }
                }
            }

            repeat(3) { i ->
                launch {
                    manager.withSymbolLock(msft) {
                        synchronized(lock) { results.add("MSFT-$i") }
                        delay(50)
                    }
                }
            }

            testDispatcher.scheduler.advanceUntilIdle()

            // Both symbols should have 3 executions each
            val aaplCount = results.count { it.startsWith("AAPL") }
            val msftCount = results.count { it.startsWith("MSFT") }

            assertEquals(3, aaplCount, "AAPL should have 3 executions")
            assertEquals(3, msftCount, "MSFT should have 3 executions")
        }
    }

    @Test
    fun `symbol mutex manager tracks registered symbols`() {
        val testDispatcher = StandardTestDispatcher()
        runTest(testDispatcher) {
            val manager = SymbolMutexManager()

            launch {
                manager.withSymbolLock(aapl) { delay(10) }
                manager.withSymbolLock(msft) { delay(10) }
            }

            testDispatcher.scheduler.advanceUntilIdle()

            val registered = manager.getRegisteredSymbols()
            assertTrue(
                registered.contains("AAPL") && registered.contains("MSFT"),
                "Both symbols should be registered",
            )
            assertEquals(2, manager.getLockedSymbolCount(), "Should have 2 locked symbols")
        }
    }

    @Test
    fun `symbol mutex manager prevents deadlock with multiple threads on same symbol`() {
        val testDispatcher = StandardTestDispatcher()
        runTest(testDispatcher) {
            val manager = SymbolMutexManager()
            val counter = AtomicInteger(0)

            repeat(10) {
                launch {
                    manager.withSymbolLock(aapl) {
                        counter.incrementAndGet()
                        delay(5)
                    }
                }
            }

            testDispatcher.scheduler.advanceUntilIdle()

            // All 10 should complete without deadlock
            assertEquals(10, counter.get(), "All 10 operations should complete")
        }
    }

    @Test
    fun `symbol mutex manager is reusable for same symbol across calls`() {
        val testDispatcher = StandardTestDispatcher()
        runTest(testDispatcher) {
            val manager = SymbolMutexManager()
            var count = 0

            // First batch of locks
            repeat(3) {
                launch {
                    manager.withSymbolLock(aapl) {
                        count++
                        delay(10)
                    }
                }
            }

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(3, count, "First batch should complete")

            // Second batch reuses the same manager
            repeat(2) {
                launch {
                    manager.withSymbolLock(aapl) {
                        count++
                        delay(10)
                    }
                }
            }

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(5, count, "Second batch should also complete")
        }
    }

    @Test
    fun `symbol mutex manager handles rapid successive locks`() {
        val testDispatcher = StandardTestDispatcher()
        runTest(testDispatcher) {
            val manager = SymbolMutexManager()
            val results = mutableListOf<Int>()
            val lock = Any()

            // Rapidly launch many concurrent locks on same symbol
            repeat(20) { i ->
                launch {
                    manager.withSymbolLock(aapl) {
                        synchronized(lock) { results.add(i) }
                    }
                }
            }

            testDispatcher.scheduler.advanceUntilIdle()

            // All 20 should complete successfully
            assertEquals(20, results.size, "All 20 locks should execute")
            assertEquals(20, results.distinct().size, "All 20 should have unique indices (no duplicates)")
        }
    }
}
