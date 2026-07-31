import { useEffect, useState } from 'react'

/** One persisted stock-backtest run. Mirrors StockBacktestApiController.StockRunDto. */
export interface StockRun {
  id: string
  createdAt: string
  strategy: string
  from: string
  to: string
  symbols: string[]
  symbolCount: number
  initialCapital: number
  finalCapital: number
  totalPnl: number
  totalPnlPct: number
  tradeCount: number
  winRate: number
  avgRMultiple: number | null
  profitFactor: number | null
  maxDrawdownPct: number
  buyHoldPnlPct: number | null
  totalCosts: number | null
  peakLeverage: number | null
  medianLeverage: number | null
  params: Record<string, unknown>
}

const pos = 'text-green-600 dark:text-green-400'
const neg = 'text-red-500 dark:text-red-400'

function money(v: number) {
  return `${v < 0 ? '-' : ''}$${Math.abs(v).toLocaleString(undefined, { maximumFractionDigits: 0 })}`
}

function num(v: number | null, digits = 2) {
  return v == null ? '—' : v.toFixed(digits)
}

/**
 * Renders the params blob as `name=value` pairs, defaults included — the stored blob is the
 * resolved one, so a row says exactly what ran rather than only what was typed. Host-level keys
 * come first because they change the answer as much as any strategy parameter does.
 */
const HOST_KEYS = ['timeframe', 'holdOvernight', 'trailStopRMultiple', 'costs']

function ParamPills({ params }: { params: Record<string, unknown> }) {
  const entries = Object.entries(params).filter(([, v]) => v !== null && v !== undefined)
  const host = entries.filter(([k]) => HOST_KEYS.includes(k))
  const rest = entries.filter(([k]) => !HOST_KEYS.includes(k))
  return (
    <div className="flex flex-wrap gap-1">
      {[...host, ...rest].map(([k, v]) => (
        <span
          key={k}
          className="rounded border border-border bg-muted/40 px-1.5 py-0.5 text-[11px] tabular-nums text-muted-foreground"
        >
          {k}=<span className="text-foreground">{String(v)}</span>
        </span>
      ))}
    </div>
  )
}

/**
 * History of stock-strategy backtests, newest first, each with the parameters it ran under.
 *
 * Clicking a row loads it back into the form via [onLoad] — the point of keeping history is to
 * return to a setting and vary it, which reading numbers off a table does not let you do. The
 * parameter pills moved to their own per-row toggle so the row click can mean the useful thing.
 *
 * Reloads whenever [refreshKey] changes so a run just executed on this page appears without a
 * manual refresh; the page bumps that key after a successful Run.
 */
export function StockRunHistory({
  refreshKey,
  strategy,
  onLoad,
}: {
  refreshKey: number
  strategy?: string
  onLoad?: (run: StockRun) => void
}) {
  const [runs, setRuns] = useState<StockRun[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [onlyThisStrategy, setOnlyThisStrategy] = useState(false)

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      setLoading(true)
      try {
        const q = onlyThisStrategy && strategy ? `?strategy=${encodeURIComponent(strategy)}` : ''
        const res = await fetch(`/api/backtest/stock/runs${q}`)
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const list: StockRun[] = await res.json()
        if (!cancelled) { setRuns(list); setError(null) }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'failed to load runs')
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [refreshKey, onlyThisStrategy, strategy])

  return (
    <div className="rounded-lg border border-border">
      <div className="flex flex-wrap items-center gap-3 border-b border-border px-3 py-2">
        <h2 className="text-sm font-semibold">Run history</h2>
        {onLoad && <span className="text-xs text-muted-foreground">click a row to load it into the form</span>}
        <span className="text-xs text-muted-foreground">{runs.length} run{runs.length === 1 ? '' : 's'}</span>
        {strategy && (
          <label className="ml-auto flex items-center gap-1.5 text-xs text-muted-foreground">
            <input
              type="checkbox"
              checked={onlyThisStrategy}
              onChange={(e) => setOnlyThisStrategy(e.target.checked)}
            />
            only {strategy}
          </label>
        )}
      </div>

      {loading && <p className="px-3 py-6 text-center text-sm text-muted-foreground">Loading…</p>}
      {error && <p className="px-3 py-6 text-center text-sm text-red-500">Could not load run history: {error}</p>}
      {!loading && !error && runs.length === 0 && (
        <p className="px-3 py-6 text-center text-sm text-muted-foreground">
          No runs stored yet — run a backtest and it will appear here.
        </p>
      )}

      {!loading && !error && runs.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/40 text-left text-xs uppercase tracking-wide text-muted-foreground">
                <th className="px-3 py-2 font-medium">When</th>
                <th className="px-3 py-2 font-medium">Strategy</th>
                <th className="px-3 py-2 font-medium">Period</th>
                <th className="px-3 py-2 font-medium text-right">Symbols</th>
                <th className="px-3 py-2 font-medium text-right">Trades</th>
                <th className="px-3 py-2 font-medium text-right">Win</th>
                <th className="px-3 py-2 font-medium text-right">Avg R</th>
                <th className="px-3 py-2 font-medium text-right">PF</th>
                <th className="px-3 py-2 font-medium text-right">Max DD</th>
                <th
                  className="px-3 py-2 font-medium text-right"
                  title="Gross exposure / equity: median, peak on hover. Over 2x could not be held overnight under Reg-T"
                >
                  Lev
                </th>
                <th className="px-3 py-2 font-medium text-right" title="Commission + slippage, already deducted from P&L">
                  Costs
                </th>
                <th className="px-3 py-2 font-medium text-right">Final</th>
                <th className="px-3 py-2 font-medium text-right">P&L</th>
                <th className="px-3 py-2 font-medium text-right" title="Buy & hold of the same symbols over the same window">
                  B&H
                </th>
                <th className="px-3 py-2 font-medium text-right" title="P&L minus buy & hold — the only column that decides anything">
                  vs B&H
                </th>
                <th className="px-2 py-2" />
              </tr>
            </thead>
            <tbody>
              {runs.map((r) => {
                const open = expanded === r.id
                return [
                  <tr
                    key={r.id}
                    onClick={() => onLoad?.(r)}
                    className={`border-t border-border ${onLoad ? 'cursor-pointer hover:bg-accent/40' : ''}`}
                    title={onLoad ? 'Click to load this run back into the form' : undefined}
                  >
                    <td className="px-3 py-2 whitespace-nowrap tabular-nums text-muted-foreground">
                      {new Date(r.createdAt).toLocaleString('en-GB', {
                        day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
                      })}
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap">{r.strategy}</td>
                    <td className="px-3 py-2 whitespace-nowrap tabular-nums text-muted-foreground">
                      {r.from} → {r.to}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums" title={r.symbols.join(', ')}>
                      {r.symbolCount}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{r.tradeCount}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{(r.winRate * 100).toFixed(0)}%</td>
                    <td className={`px-3 py-2 text-right tabular-nums ${(r.avgRMultiple ?? 0) >= 0 ? pos : neg}`}>
                      {num(r.avgRMultiple)}
                    </td>
                    <td className={`px-3 py-2 text-right tabular-nums ${(r.profitFactor ?? 0) >= 1 ? pos : neg}`}>
                      {num(r.profitFactor)}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
                      {r.maxDrawdownPct.toFixed(1)}%
                    </td>
                    <td
                      className={`px-3 py-2 text-right tabular-nums ${
                        r.peakLeverage == null ? 'text-muted-foreground' : r.peakLeverage > 2 ? neg : 'text-muted-foreground'
                      }`}
                      title={
                        r.peakLeverage == null
                          ? 'not recorded for this run'
                          : `peak ${r.peakLeverage.toFixed(2)}x` +
                            (r.peakLeverage > 2 ? ' — above the 2x Reg-T overnight limit, untradeable' : '')
                      }
                    >
                      {r.medianLeverage == null ? '—' : `${r.medianLeverage.toFixed(1)}x`}
                      {r.peakLeverage != null && r.peakLeverage > 2 && <span className="ml-1">!</span>}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
                      {r.totalCosts == null ? '—' : money(r.totalCosts)}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{money(r.finalCapital)}</td>
                    <td className={`px-3 py-2 text-right tabular-nums font-medium ${r.totalPnl >= 0 ? pos : neg}`}>
                      {r.totalPnl >= 0 ? '+' : ''}{r.totalPnlPct.toFixed(1)}%
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
                      {r.buyHoldPnlPct == null ? '—' : `${r.buyHoldPnlPct >= 0 ? '+' : ''}${r.buyHoldPnlPct.toFixed(1)}%`}
                    </td>
                    {/* A levered run beating unlevered buy & hold is not a win, so the verdict is
                        greyed out rather than shown green once the run could not have been held. */}
                    <td
                      className={`px-3 py-2 text-right tabular-nums font-medium ${
                        r.buyHoldPnlPct == null
                          ? ''
                          : r.peakLeverage != null && r.peakLeverage > 2
                            ? 'text-muted-foreground line-through decoration-1'
                            : r.totalPnlPct - r.buyHoldPnlPct >= 0
                              ? pos
                              : neg
                      }`}
                      title={
                        r.peakLeverage != null && r.peakLeverage > 2
                          ? `Not comparable: this run reached ${r.peakLeverage.toFixed(2)}x leverage against an unlevered benchmark`
                          : undefined
                      }
                    >
                      {r.buyHoldPnlPct == null
                        ? '—'
                        : `${r.totalPnlPct - r.buyHoldPnlPct >= 0 ? '+' : ''}${(r.totalPnlPct - r.buyHoldPnlPct).toFixed(1)}pp`}
                    </td>
                    <td className="px-2 py-2 text-right">
                      <button
                        type="button"
                        onClick={(e) => { e.stopPropagation(); setExpanded(open ? null : r.id) }}
                        className="rounded border border-border px-1.5 py-0.5 text-[11px] text-muted-foreground hover:text-foreground"
                        title="Show the parameters this run used"
                      >
                        {open ? '▲' : 'params'}
                      </button>
                    </td>
                  </tr>,
                  open && (
                    <tr key={`${r.id}-params`} className="border-t border-border bg-muted/20">
                      <td colSpan={16} className="px-3 py-2">
                        <ParamPills params={r.params} />
                        <p className="mt-1.5 text-[11px] text-muted-foreground">{r.symbols.join(', ')}</p>
                      </td>
                    </tr>
                  ),
                ]
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
