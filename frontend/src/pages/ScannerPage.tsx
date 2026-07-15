import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getScannerStatusOptions,
  triggerScanMutation,
  pauseScannerMutation,
  resumeScannerMutation,
  pauseMonitorMutation,
  resumeMonitorMutation,
} from '../generated/spreads/@tanstack/react-query.gen'
import { TickerStatusTable } from '../components/scanner/TickerStatusTable'

function KillSwitchRow({
  label,
  paused,
  onPause,
  onResume,
  isPending,
}: {
  label: string
  paused: boolean
  onPause: () => void
  onResume: () => void
  isPending: boolean
}) {
  return (
    <div className="flex items-center justify-between py-3 border-b border-border last:border-0">
      <div>
        <p className="text-sm font-medium">{label}</p>
        <p className={`text-xs mt-0.5 ${paused ? 'text-amber-600 dark:text-amber-400' : 'text-green-600 dark:text-green-400'}`}>
          {paused ? 'PAUSED' : 'ACTIVE'}
        </p>
      </div>
      <div className="flex gap-2">
        <button
          onClick={onPause}
          disabled={paused || isPending}
          className="px-3 py-1.5 text-xs rounded bg-amber-100 text-amber-800 hover:bg-amber-200 dark:bg-amber-900/30 dark:text-amber-300 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          Pause
        </button>
        <button
          onClick={onResume}
          disabled={!paused || isPending}
          className="px-3 py-1.5 text-xs rounded bg-green-100 text-green-800 hover:bg-green-200 dark:bg-green-900/30 dark:text-green-300 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          Resume
        </button>
      </div>
    </div>
  )
}

export function ScannerPage() {
  const qc = useQueryClient()
  const invalidate = () => qc.invalidateQueries({ queryKey: ['getScannerStatus'] })

  const { data, isLoading, isError } = useQuery({
    ...getScannerStatusOptions(),
    refetchInterval: 15_000,
  })

  const triggerScan = useMutation({ ...triggerScanMutation() })
  const pauseScanner = useMutation({ ...pauseScannerMutation(), onSuccess: invalidate })
  const resumeScanner = useMutation({ ...resumeScannerMutation(), onSuccess: invalidate })
  const pauseMonitor = useMutation({ ...pauseMonitorMutation(), onSuccess: invalidate })
  const resumeMonitor = useMutation({ ...resumeMonitorMutation(), onSuccess: invalidate })

  return (
    <div className="space-y-8 max-w-6xl">
      <h1 className="text-xl font-semibold">Scanner</h1>

      {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
      {isError && <p className="text-destructive text-sm">Failed to load scanner status.</p>}

      {data && (
        <>
          <section className="rounded-lg border border-border bg-card p-4 space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">Last scan</span>
              <span>{data.lastRunAt ? new Date(data.lastRunAt).toLocaleString() : 'Never'}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">Open spreads</span>
              <span>{data.openSpreadCount}</span>
            </div>
          </section>

          <section>
            <h2 className="text-base font-semibold mb-3">Kill Switches</h2>
            <div className="rounded-lg border border-border bg-card px-4">
              <KillSwitchRow
                label="New Entry Scanner"
                paused={data.scannerPaused}
                onPause={() => pauseScanner.mutate({})}
                onResume={() => resumeScanner.mutate({})}
                isPending={pauseScanner.isPending || resumeScanner.isPending}
              />
              <KillSwitchRow
                label="Exit Monitor"
                paused={data.monitorPaused}
                onPause={() => pauseMonitor.mutate({})}
                onResume={() => resumeMonitor.mutate({})}
                isPending={pauseMonitor.isPending || resumeMonitor.isPending}
              />
            </div>
          </section>

          <section>
            <h2 className="text-base font-semibold mb-3">Manual Scan</h2>
            <button
              onClick={() => triggerScan.mutate({})}
              disabled={triggerScan.isPending || data.scannerPaused}
              className="px-4 py-2 rounded bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {triggerScan.isPending ? 'Running…' : 'Run Scan Now'}
            </button>
            {data.scannerPaused && (
              <p className="mt-2 text-xs text-muted-foreground">Resume the scanner to trigger a new scan.</p>
            )}
            {triggerScan.isSuccess && (
              <p className="mt-2 text-xs text-green-600 dark:text-green-400">Scan started.</p>
            )}
          </section>

          <TickerStatusTable />
        </>
      )}
    </div>
  )
}
