package cz.solvina.options.domain.features.scanner

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory, per-symbol snapshot of the most recent scan pass. Purely observational — nothing
 * downstream reads it for trading decisions; it exists so the UI can render a live table of what the
 * scanner is seeing for each ticker (IV rank, greeks, funnel outcome, greek coverage, freshness).
 *
 * Rebuilt every run: [beginRun] bumps the run id, and reads filter to the latest run id so callers
 * only ever see the symbols evaluated in the last session — never a stale union across runs. Kept
 * deliberately un-persisted; it reflects live process state and is cheap to repopulate.
 */
@Component
class ScanStatusRegistry {
    private val rows = ConcurrentHashMap<String, SymbolScanStatus>()
    private val runIdSeq = AtomicLong(0)

    @Volatile private var currentRunId: Long = 0

    /** Starts a new scan pass and returns its run id. Rows from prior runs are cleared. */
    fun beginRun(): Long {
        val id = runIdSeq.incrementAndGet()
        currentRunId = id
        rows.clear()
        return id
    }

    /** Records (or overwrites) the status for a symbol in the current run. */
    fun record(status: SymbolScanStatus) {
        rows[status.symbol] = status
    }

    /** Snapshot of the latest run's rows. Filters defensively to the current run id. */
    fun snapshot(): List<SymbolScanStatus> = rows.values.filter { it.runId == currentRunId }

    fun latestRunId(): Long = currentRunId
}
