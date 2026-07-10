import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getReportSummaryOptions } from '../generated/reports/@tanstack/react-query.gen'
import type { StrategyReportDto } from '../generated/reports/types.gen'

// ─────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────

const STRATEGY_LABELS: Record<string, string> = {
  bull_put: 'Bull Puts',
  bear_call: 'Bear Calls',
  bull_flag: 'Flags',
  total: 'Total',
}

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10)
}

function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return isoDate(d)
}

function startOfMonth(): string {
  const d = new Date()
  return isoDate(new Date(d.getFullYear(), d.getMonth(), 1))
}

function startOfYear(): string {
  return isoDate(new Date(new Date().getFullYear(), 0, 1))
}

function fmtMoney(val: number | null | undefined): string {
  if (val == null) return '—'
  const n = Number(val)
  return `${n < 0 ? '-' : n > 0 ? '+' : ''}$${Math.abs(n).toFixed(2)}`
}

function fmtPct(val: number | null | undefined): string {
  if (val == null) return '—'
  return `${(Number(val) * 100).toFixed(0)}%`
}

function fmtDays(val: number | null | undefined): string {
  if (val == null) return '—'
  const n = Number(val)
  return n < 1 ? `${Math.round(n * 24)}h` : `${n.toFixed(1)}d`
}

function PnlSpan({ val, children }: { val: number | null | undefined; children: React.ReactNode }) {
  const n = val == null ? null : Number(val)
  const cls =
    n == null ? 'text-muted-foreground' : n >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'
  return <span className={`tabular-nums ${cls}`}>{children}</span>
}

// ─────────────────────────────────────────────
// Page
// ─────────────────────────────────────────────

const PRESETS: { label: string; from: () => string }[] = [
  { label: '7d', from: () => daysAgo(7) },
  { label: '30d', from: () => daysAgo(30) },
  { label: 'This month', from: startOfMonth },
  { label: 'YTD', from: startOfYear },
]

export function ReportsPage() {
  const [from, setFrom] = useState(() => daysAgo(30))
  const [to, setTo] = useState(() => isoDate(new Date()))

  const { data, isLoading, isError } = useQuery({
    ...getReportSummaryOptions({ query: { from, to } }),
    enabled: from <= to,
  })

  const rows: StrategyReportDto[] = data ? [...data.strategies, data.total] : []

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Reports</h1>
          <p className="text-sm text-muted-foreground">Per-strategy performance for a period</p>
        </div>
        <div className="ml-auto flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            From
            <input
              type="date"
              value={from}
              max={to}
              onChange={(e) => setFrom(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1.5 text-sm text-foreground"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            To
            <input
              type="date"
              value={to}
              min={from}
              onChange={(e) => setTo(e.target.value)}
              className="rounded-md border border-border bg-background px-2 py-1.5 text-sm text-foreground"
            />
          </label>
          <div className="flex gap-1">
            {PRESETS.map((p) => (
              <button
                key={p.label}
                type="button"
                onClick={() => {
                  setFrom(p.from())
                  setTo(isoDate(new Date()))
                }}
                className="rounded-md border border-border px-2 py-1.5 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {isLoading && <p className="text-sm text-muted-foreground py-8 text-center">Loading…</p>}
      {isError && <p className="text-sm text-red-500 py-8 text-center">Failed to load the report.</p>}

      {data && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/40 text-left text-xs uppercase tracking-wide text-muted-foreground">
                <th className="px-3 py-2 font-medium">Strategy</th>
                <th className="px-3 py-2 font-medium text-right">Opened</th>
                <th className="px-3 py-2 font-medium text-right">Still open</th>
                <th className="px-3 py-2 font-medium text-right">Closed</th>
                <th className="px-3 py-2 font-medium text-right">W / L</th>
                <th className="px-3 py-2 font-medium text-right">Win rate</th>
                <th className="px-3 py-2 font-medium text-right">Realized P&L</th>
                <th className="px-3 py-2 font-medium text-right">Avg</th>
                <th className="px-3 py-2 font-medium text-right">Best</th>
                <th className="px-3 py-2 font-medium text-right">Worst</th>
                <th className="px-3 py-2 font-medium text-right">Avg hold</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const isTotal = r.strategy === 'total'
                return (
                  <tr
                    key={r.strategy}
                    className={
                      isTotal
                        ? 'border-t-2 border-border bg-muted/30 font-semibold'
                        : 'border-t border-border hover:bg-accent/40'
                    }
                  >
                    <td className="px-3 py-2">{STRATEGY_LABELS[r.strategy] ?? r.strategy}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{r.opened}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{r.stillOpen || '—'}</td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      {r.closed}
                      {r.closedNoPnl > 0 && (
                        <span
                          className="ml-1 text-xs text-amber-600 dark:text-amber-400"
                          title={`${r.closedNoPnl} closed without a known P&L (exit filled while the engine was not watching)`}
                        >
                          ({r.closedNoPnl}?)
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      <span className="text-green-600 dark:text-green-400">{r.wins}</span>
                      <span className="text-muted-foreground"> / </span>
                      <span className="text-red-500 dark:text-red-400">{r.losses}</span>
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{fmtPct(r.winRate)}</td>
                    <td className="px-3 py-2 text-right">
                      <PnlSpan val={r.realizedPnl}>{fmtMoney(r.realizedPnl)}</PnlSpan>
                    </td>
                    <td className="px-3 py-2 text-right">
                      <PnlSpan val={r.avgPnl}>{fmtMoney(r.avgPnl)}</PnlSpan>
                    </td>
                    <td className="px-3 py-2 text-right">
                      <PnlSpan val={r.bestPnl}>{fmtMoney(r.bestPnl)}</PnlSpan>
                    </td>
                    <td className="px-3 py-2 text-right">
                      <PnlSpan val={r.worstPnl}>{fmtMoney(r.worstPnl)}</PnlSpan>
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{fmtDays(r.avgHoldDays)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {data && (
        <p className="text-xs text-muted-foreground">
          Dates are inclusive (Europe/Prague). “Opened” counts entries filled in the period; “Closed” and all P&L
          figures cover positions closed in the period regardless of when they were opened. Breakeven closes count as
          wins. <span className="text-amber-600 dark:text-amber-400">(n?)</span> marks closes with unknown P&L —
          exits that filled at the broker while the engine was not watching.
        </p>
      )}
    </div>
  )
}
