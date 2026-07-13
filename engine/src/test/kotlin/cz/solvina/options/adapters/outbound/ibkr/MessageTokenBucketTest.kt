package cz.solvina.options.adapters.outbound.ibkr

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageTokenBucketTest {
    @Test
    fun `takes are free while tokens remain`() {
        var now = 0L
        val bucket = MessageTokenBucket(ratePerSecond = 10) { now }
        repeat(10) { assertEquals(0L, bucket.take(), "take within capacity must not wait") }
        assertTrue(bucket.level() < 1.0, "bucket must be empty after taking full capacity")
    }

    @Test
    fun `tokens refill with elapsed time up to capacity`() {
        var now = 0L
        val bucket = MessageTokenBucket(ratePerSecond = 10) { now }
        repeat(10) { bucket.take() }
        now += 500_000_000L // +0.5s → 5 tokens back
        assertEquals(5.0, bucket.level(), 0.01)
        now += 10_000_000_000L // way past capacity
        assertEquals(10.0, bucket.level(), 0.01, "refill must clamp at capacity")
    }

    @Test
    fun `an empty bucket blocks the taker until refill`() {
        // Real time: rate 5/s → a take on an empty bucket waits ~200ms for the next token.
        val bucket = MessageTokenBucket(ratePerSecond = 5)
        repeat(5) { bucket.take() }
        val waited = bucket.take()
        assertTrue(waited >= 100, "take on empty bucket should wait ~200ms, waited ${waited}ms")
    }
}
