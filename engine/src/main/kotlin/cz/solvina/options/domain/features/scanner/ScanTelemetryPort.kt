package cz.solvina.options.domain.features.scanner

/** Adapter-side measurements summarised at the end of each scan run (observe-only). */
fun interface ScanTelemetryPort {
    /** Summary since the previous call; resets the counters. */
    fun drain(): String

    companion object {
        val NoOp = ScanTelemetryPort { "" }
    }
}
