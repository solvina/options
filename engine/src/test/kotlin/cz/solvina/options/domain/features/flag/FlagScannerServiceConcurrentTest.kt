package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * SymbolMutexManager.kt:29 calls `symbolMutexes.getOrPut(symbol) { Mutex() }` on a field typed
     * `ConcurrentHashMap<Symbol, Mutex>`. Kotlin/JVM's default imports include `kotlin.concurrent.*`,
     * which declares a `ConcurrentMap`-specific `getOrPut` overload (get, then `putIfAbsent` — safe,
     * unlike the general `kotlin.collections.getOrPut`, which is a plain get-then-put and NOT atomic).
     * Because the receiver's *static* type here is `ConcurrentHashMap` (a `ConcurrentMap`), overload
     * resolution picks the safe version automatically — confirmed by decompiling the call site
     * (`ConcurrentMap.get` then `ConcurrentMap.putIfAbsent`, no plain `put`). So there is no getOrPut
     * race in the current code; a two-thread race test against the raw pattern (below) hands both
     * callers the *same* instance, and a companion test confirms `computeIfAbsent` behaves the same
     * way. What this test really guards against is a silent regression: if `symbolMutexes` were ever
     * declared/used as a plain `Map<Symbol, Mutex>` or `MutableMap<Symbol, Mutex>` instead of the
     * concrete `ConcurrentHashMap`/`ConcurrentMap` type, `getOrPut` would silently fall back to the
     * unsafe overload with no compile error — same source line, different (broken) behavior.
     */
    @Test
    fun `getOrPut on a ConcurrentHashMap receiver hands two racing callers the same Mutex instance`() {
        val map = ConcurrentHashMap<Symbol, Mutex>()
        val symbol = Symbol("AAPL")
        val threadAEnteredBuilder = CountDownLatch(1)
        val releaseThreadA = CountDownLatch(1)
        val mutexA = CompletableFuture<Mutex>()

        val threadA =
            Thread {
                val mutex =
                    map.getOrPut(symbol) {
                        // Stalls thread A after its own get(key) already returned null, so a second,
                        // genuinely concurrent caller races in below before A's builder finishes.
                        threadAEnteredBuilder.countDown()
                        releaseThreadA.await()
                        Mutex()
                    }
                mutexA.complete(mutex)
            }
        threadA.start()
        threadAEnteredBuilder.await()

        val mutexB = map.getOrPut(symbol) { Mutex() }

        releaseThreadA.countDown()
        threadA.join()

        assertEquals(
            mutexA.get(5, TimeUnit.SECONDS),
            mutexB,
            "the ConcurrentMap-specific getOrPut overload must hand every racing caller the same " +
                "instance for the same key",
        )
    }

    @Test
    fun `computeIfAbsent gives the same guarantee under the identical race`() {
        val map = ConcurrentHashMap<Symbol, Mutex>()
        val symbol = Symbol("AAPL")
        val threadAEnteredBuilder = CountDownLatch(1)
        val releaseThreadA = CountDownLatch(1)
        val mutexA = CompletableFuture<Mutex>()

        val threadA =
            Thread {
                val mutex =
                    map.computeIfAbsent(symbol) {
                        threadAEnteredBuilder.countDown()
                        releaseThreadA.await()
                        Mutex()
                    }
                mutexA.complete(mutex)
            }
        threadA.start()
        threadAEnteredBuilder.await()

        // computeIfAbsent locks the key's bin for the duration of the mapping function, so this
        // call blocks until thread A's builder finishes — it must NOT construct its own instance.
        val computeIfAbsentReturned = AtomicBoolean(false)
        val racingCaller =
            Thread {
                map.computeIfAbsent(symbol) { Mutex() }
                computeIfAbsentReturned.set(true)
            }
        racingCaller.start()

        // Give the racing caller ample opportunity to (incorrectly) return early if computeIfAbsent
        // were not actually atomic — it must still be blocked here.
        Thread.sleep(50)
        assertEquals(false, computeIfAbsentReturned.get(), "a second caller must block, not proceed, while the first is still constructing")

        releaseThreadA.countDown()
        threadA.join()
        racingCaller.join()

        assertEquals(
            mutexA.get(5, TimeUnit.SECONDS),
            map[symbol],
            "computeIfAbsent must hand every caller the same, single instance for the same key",
        )
    }

    /**
     * FlagScannerService.kt:272 — inside the coroutine `subscribe(symbol)` launches, once the live
     * bar stream's `collect{}` completes (naturally, e.g. after `.catch{}` swallows a transient
     * stream error — see FlagScannerService.kt:243), the coroutine unconditionally runs
     * `subscriptions.remove(symbol)` to drop its own now-stale entry. But it has no way to know
     * whether it is still the CURRENT mapping: if a resubscribe (watchdog, hot-subscribe, or the
     * EU/US market-open cron) already installed a new, live job for the same symbol in the meantime,
     * this unconditional remove evicts that live job instead of a stale one.
     *
     * `subscribe()` is private and deeply coupled to IBKR ports/coroutine scope, so reproducing the
     * exact production race through the real class would mean fighting the same kind of narrow,
     * hardware-dependent timing window that made the getOrPut stress test above unreliable. These two
     * tests instead prove the underlying map pattern directly and deterministically — no threads
     * needed, since the defect is an ordering/logic bug, not a timing-sensitive one once the ordering
     * itself is fixed in place: does an unconditional `remove(key)` evict a newer value that replaced
     * the caller's stale one, and does the compare-and-remove overload `remove(key, expectedValue)`
     * correctly leave it alone.
     */
    @Test
    fun `unconditional remove(key) evicts a newer job installed by a resubscribe`() {
        val subscriptions = ConcurrentHashMap<Symbol, Job>()
        val symbol = Symbol("AAPL")
        val staleJob = Job()
        val liveJob = Job()

        subscriptions[symbol] = staleJob
        // A resubscribe (watchdog/hot-subscribe/cron) lands first and installs the live job.
        subscriptions[symbol] = liveJob
        // staleJob's own end-of-stream cleanup then runs — unconditional, unaware it's stale.
        subscriptions.remove(symbol)

        assertEquals(
            null,
            subscriptions[symbol],
            "unconditional remove(key) evicted the live job installed by the resubscribe",
        )
    }

    @Test
    fun `compare-and-remove leaves a newer job installed by a resubscribe untouched`() {
        val subscriptions = ConcurrentHashMap<Symbol, Job>()
        val symbol = Symbol("AAPL")
        val staleJob = Job()
        val liveJob = Job()

        subscriptions[symbol] = staleJob
        subscriptions[symbol] = liveJob
        // The fix: only remove if the current mapping is still exactly the caller's own (stale) job.
        subscriptions.remove(symbol, staleJob)

        assertEquals(
            liveJob,
            subscriptions[symbol],
            "remove(key, expectedValue) must not evict a newer mapping installed after the caller's own snapshot",
        )
    }
}
