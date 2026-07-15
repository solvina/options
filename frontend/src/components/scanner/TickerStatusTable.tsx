import { Fragment, useState } from 'react'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getTickerStatusOptions } from '../../generated/spreads/@tanstack/react-query.gen'
import type { SymbolScanStatusDto } from '../../generated/spreads/types.gen'
import { usePersistentSortable, sorted, SortTh } from '../../lib/sort'
import { useLocalStorage } from '../../lib/useLocalStorage'

type Row = SymbolScanStatusDto

function num(v: number | null | undefined, decimals = 2): string {
  return v == null ? '—' : v.toFixed(decimals)
}

function relTime(iso: string): string {
  const secs = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000))
  if (secs < 60) return `${secs}s`
  if (secs < 3600) return `${Math.round(secs / 60)}m`
  return `${Math.round(secs / 3600)}h`
}

const OUTCOME_CLASS: Record<string, string> = {
  ENTERED: 'text-green-600 dark:text-green-400',
  REJECTED: 'text-muted-foreground',
  ALREADY_OPEN: 'text-blue-600 dark:text-blue-400',
  COOLDOWN: 'text-amber-600 dark:text-amber-400',
  GATE_SUPPRESSED: 'text-muted-foreground/70',
  ERROR: 'text-red-500 dark:text-red-400',
}

function OutcomeCell({ row }: { row: Row }) {
  const cls = OUTCOME_CLASS[row.outcome] ?? 'text-foreground'
  return <td className={`px-3 py-2 font-medium whitespace-nowrap ${cls}`}>{row.outcome.replace('_', ' ')}</td>
}

/** Greek-delivery coverage: withGreeks/requested, coloured red when the ratio is starved. */
function CoverageCell({ row }: { row: Row }) {
  const req = row.strikesRequested
  const got = row.strikesWithGreeks
  if (req == null || got == null) return <td className="px-3 py-2 text-muted-foreground tabular-nums">—</td>
  const ratio = req === 0 ? 1 : got / req
  const cls = ratio < 0.5 ? 'text-red-500 dark:text-red-400' : ratio < 0.8 ? 'text-amber-600 dark:text-amber-400' : 'text-green-600 dark:text-green-400'
  return <td className={`px-3 py-2 tabular-nums ${cls}`} title="Strikes with live greeks / requested">{got}/{req}</td>
}

interface Col {
  key: string
  label: string
  defaultVisible: boolean
  render: (r: Row) => ReactNode
  sortValue: (r: Row) => string | number | null
}

const numCell = (v: number | null | undefined, decimals = 2): ReactNode => (
  <td className="px-3 py-2 tabular-nums">{num(v, decimals)}</td>
)
const textCell = (v: string | null | undefined): ReactNode => (
  <td className="px-3 py-2 whitespace-nowrap">{v ?? '—'}</td>
)

const COLS: Col[] = [
  { key: 'symbol', label: 'Symbol', defaultVisible: true, render: (r) => <td className="px-3 py-2 font-medium">{r.symbol}</td>, sortValue: (r) => r.symbol },
  { key: 'outcome', label: 'Outcome', defaultVisible: true, render: (r) => <OutcomeCell row={r} />, sortValue: (r) => r.outcome },
  { key: 'rejectReason', label: 'Reason', defaultVisible: true, render: (r) => textCell(r.rejectReason?.replace(/_/g, ' ')), sortValue: (r) => r.rejectReason ?? null },
  { key: 'strategyId', label: 'Strat', defaultVisible: false, render: (r) => textCell(r.strategyId === 'BULL_PUT' ? 'BP' : r.strategyId === 'BEAR_CALL' ? 'BC' : r.strategyId), sortValue: (r) => r.strategyId ?? null },
  { key: 'ivRank', label: 'IV Rank', defaultVisible: true, render: (r) => numCell(r.ivRank, 1), sortValue: (r) => r.ivRank ?? null },
  { key: 'bias', label: 'Bias', defaultVisible: true, render: (r) => textCell(r.bias), sortValue: (r) => r.bias ?? null },
  { key: 'regime', label: 'Regime', defaultVisible: false, render: (r) => textCell(r.regime), sortValue: (r) => r.regime ?? null },
  { key: 'rsi', label: 'RSI', defaultVisible: false, render: (r) => numCell(r.rsi, 1), sortValue: (r) => r.rsi ?? null },
  { key: 'underlyingPrice', label: 'Underlying', defaultVisible: false, render: (r) => numCell(r.underlyingPrice, 2), sortValue: (r) => r.underlyingPrice ?? null },
  { key: 'shortStrike', label: 'Short K', defaultVisible: false, render: (r) => numCell(r.shortStrike, 2), sortValue: (r) => r.shortStrike ?? null },
  { key: 'shortDelta', label: 'Short Δ', defaultVisible: true, render: (r) => numCell(r.shortDelta, 3), sortValue: (r) => r.shortDelta ?? null },
  { key: 'dte', label: 'DTE', defaultVisible: false, render: (r) => numCell(r.dte, 0), sortValue: (r) => r.dte ?? null },
  { key: 'midCredit', label: 'Mid', defaultVisible: true, render: (r) => numCell(r.midCredit, 2), sortValue: (r) => r.midCredit ?? null },
  { key: 'bidCredit', label: 'Bid', defaultVisible: false, render: (r) => numCell(r.bidCredit, 2), sortValue: (r) => r.bidCredit ?? null },
  { key: 'width', label: 'Width', defaultVisible: false, render: (r) => numCell(r.width, 2), sortValue: (r) => r.width ?? null },
  { key: 'maxRiskPerShare', label: 'Max Risk', defaultVisible: false, render: (r) => numCell(r.maxRiskPerShare, 2), sortValue: (r) => r.maxRiskPerShare ?? null },
  { key: 'creditPctOfWidth', label: 'Cr/Width', defaultVisible: false, render: (r) => <td className="px-3 py-2 tabular-nums">{r.creditPctOfWidth == null ? '—' : `${(r.creditPctOfWidth * 100).toFixed(0)}%`}</td>, sortValue: (r) => r.creditPctOfWidth ?? null },
  { key: 'coverage', label: 'Greeks', defaultVisible: true, render: (r) => <CoverageCell row={r} />, sortValue: (r) => (r.strikesRequested ? (r.strikesWithGreeks ?? 0) / r.strikesRequested : null) },
  { key: 'evaluatedAt', label: 'Updated', defaultVisible: true, render: (r) => <td className="px-3 py-2 text-muted-foreground text-xs whitespace-nowrap">{relTime(r.evaluatedAt)} ago</td>, sortValue: (r) => new Date(r.evaluatedAt).getTime() },
]

const DEFAULT_VISIBLE = COLS.filter((c) => c.defaultVisible).map((c) => c.key)
const COL_BY_KEY = Object.fromEntries(COLS.map((c) => [c.key, c]))

export function TickerStatusTable() {
  const { sort, toggle } = usePersistentSortable('scanner-table', 'symbol', 'asc')
  const [visible, setVisible] = useLocalStorage<string[]>('scanner.visibleColumns', DEFAULT_VISIBLE)
  const [pickerOpen, setPickerOpen] = useState(false)

  const { data, isLoading, isError } = useQuery({
    ...getTickerStatusOptions(),
    refetchInterval: 10_000,
  })

  const rows = (data as Row[] | undefined) ?? []
  const visibleCols = COLS.filter((c) => visible.includes(c.key))
  const sortedRows = sorted(rows, sort, (r, k) => COL_BY_KEY[k]?.sortValue(r) ?? null)

  function toggleCol(key: string) {
    setVisible((v) => (v.includes(key) ? v.filter((x) => x !== key) : [...v, key]))
  }

  const thClass = 'px-3 py-2 text-left'

  return (
    <section>
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-base font-semibold">Ticker Status <span className="text-muted-foreground font-normal text-sm">({rows.length})</span></h2>
        <div className="relative">
          <button
            onClick={() => setPickerOpen((o) => !o)}
            className="px-3 py-1.5 text-xs rounded bg-muted text-muted-foreground hover:text-foreground border border-border"
          >
            Columns ▾
          </button>
          {pickerOpen && (
            <div className="absolute right-0 mt-1 z-10 w-44 max-h-80 overflow-y-auto rounded-lg border border-border bg-card p-2 shadow-lg">
              {COLS.map((c) => (
                <label key={c.key} className="flex items-center gap-2 px-2 py-1 text-sm rounded hover:bg-muted/60 cursor-pointer">
                  <input type="checkbox" checked={visible.includes(c.key)} onChange={() => toggleCol(c.key)} />
                  {c.label}
                </label>
              ))}
            </div>
          )}
        </div>
      </div>

      {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
      {isError && <p className="text-destructive text-sm">Failed to load ticker status.</p>}
      {!isLoading && !isError && rows.length === 0 && (
        <p className="text-muted-foreground text-sm">No scan has run yet, or the last scan evaluated no symbols.</p>
      )}

      {rows.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
                {visibleCols.map((c) => (
                  <SortTh key={c.key} label={c.label} col={c.key} sort={sort} onSort={toggle} className={thClass} />
                ))}
              </tr>
            </thead>
            <tbody>
              {sortedRows.map((r) => (
                <tr key={r.symbol} className="border-b border-border hover:bg-muted/40 transition-colors">
                  {visibleCols.map((c) => (
                    <Fragment key={c.key}>{c.render(r)}</Fragment>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
