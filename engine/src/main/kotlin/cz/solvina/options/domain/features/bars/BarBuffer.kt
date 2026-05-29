package cz.solvina.options.domain.features.bars

/** Fixed-capacity circular buffer of completed 5-minute candles per symbol. */
class BarBuffer(
    private val capacity: Int = 200,
) {
    private val deque = ArrayDeque<FiveMinuteBar>(capacity)

    fun add(bar: FiveMinuteBar) {
        if (deque.size >= capacity) deque.removeFirst()
        deque.addLast(bar)
    }

    fun addAll(bars: List<FiveMinuteBar>) {
        bars.forEach { add(it) }
    }

    /** Returns a snapshot of the current bars, oldest first. */
    fun snapshot(): List<FiveMinuteBar> = deque.toList()

    val size: Int get() = deque.size
}
