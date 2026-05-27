import { useQuery } from '@tanstack/react-query'
import { getSpreadAnalyticsOptions } from '../generated/spreads/@tanstack/react-query.gen'
import type {
  SpreadAnalyticsDto,
  StatusBreakdownDto,
  SymbolBreakdownDto,
  IvBucketBreakdownDto,
  PnlTimelinePointDto,
} from '../generated/spreads/types.gen'

function fmt(val: number | null | undefined, decimals = 2, prefix = '$') {
  if (val == null) return '—'
  const n = Number(val)
  const sign = n < 0 ? '-' : ''
  return `${sign}${prefix}${Math.abs(n).toFixed(decimals)}`
}

function fmtPct(val: number | null | undefined) {
  if (val == null) return '—'
  return `${(Number(val) * 100).toFixed(1)}%`
}

function PnlColor({ val, children }: { val: number | null | undefined; children: React.ReactNode }) {
  const n = val == null ? null : Number(val)
  const cls = n == null ? '' : n >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'
  return <span className={cls}>{children}</span>
}

function StatCard({ label, value, sub }: { label: string; value: React.ReactNode; sub?: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <p className="text-xs text-muted-foreground uppercase tracking-wide mb-1">{label}</p>
      <p className="text-lg font-semibold tabular-nums">{value}</p>
      {sub && <p className="text-xs text-muted-foreground mt-0.5">{sub}</p>}
    </div>
  )
}

function PnlChart({ timeline }: { timeline: PnlTimelinePointDto[] }) {
  if (timeline.length < 2) {
    return <p className="text-muted-foreground text-sm py-6 text-center">Not enough closed trades for chart.</p>
  }

  const W = 600
  const H = 120
  const PL = 52
  const PR = 10
  const PT = 12
  const PB = 22
  const cw = W - PL - PR
  const ch = H - PT - PB

  const dates = timeline.map((p) => new Date(p.date as string).getTime())
  const pnls = timeline.map((p) => Number(p.cumulativePnl))

  const minDate = Math.min(...dates)
  const maxDate = Math.max(...dates)
  const minPnl = Math.min(0, Math.min(...pnls))
  const maxPnl = Math.max(0, Math.max(...pnls))
  const dateRange = maxDate - minDate || 1
  const pnlRange = maxPnl - minPnl || 1

  const toX = (d: number) => PL + ((d - minDate) / dateRange) * cw
  const toY = (p: number) => PT + ch - ((p - minPnl) / pnlRange) * ch

  const zeroY = toY(0)
  const points = timeline.map((p) => `${toX(new Date(p.date as string).getTime())},${toY(Number(p.cumulativePnl))}`).join(' ')
  const last = pnls[pnls.length - 1]
  const strokeColor = last >= 0 ? '#22c55e' : '#ef4444'

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full h-28">
      <line x1={PL} y1={zeroY} x2={W - PR} y2={zeroY} stroke="currentColor" strokeOpacity="0.15" strokeWidth="1" strokeDasharray="4 3" />
      <polyline points={points} fill="none" stroke={strokeColor} strokeWidth="2" strokeLinejoin="round" />
      <text x={PL - 4} y={PT} textAnchor="end" dominantBaseline="middle" fontSize="9" fill="currentColor" opacity="0.4">
        ${maxPnl.toFixed(0)}
      </text>
      {minPnl < 0 && (
        <text x={PL - 4} y={H - PB} textAnchor="end" dominantBaseline="middle" fontSize="9" fill="currentColor" opacity="0.4">
          -${Math.abs(minPnl).toFixed(0)}
        </text>
      )}
      <text x={PL} y={H - 4} fontSize="9" fill="currentColor" opacity="0.4">
        {new Date(dates[0]).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}
      </text>
      <text x={W - PR} y={H - 4} textAnchor="end" fontSize="9" fill="currentColor" opacity="0.4">
        {new Date(dates[dates.length - 1]).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}
      </text>
    </svg>
  )
}

function StatusTable({ rows }: { rows: StatusBreakdownDto[] }) {
  if (rows.length === 0) return <p className="text-muted-foreground text-sm">No closed trades yet.</p>
  const thClass = 'px-3 py-2 text-left'
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
            <th className={thClass}>Close Reason</th>
            <th className={thClass}>Count</th>
            <th className={thClass}>Total P&amp;L</th>
            <th className={thClass}>Avg P&amp;L</th>
            <th className={thClass}>Avg Hold</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.status} className="border-b border-border hover:bg-muted/30 transition-colors">
              <td className="px-3 py-2 font-mono text-xs">{r.status}</td>
              <td className="px-3 py-2 tabular-nums">{r.count}</td>
              <td className="px-3 py-2 tabular-nums">
                <PnlColor val={Number(r.totalPnl)}>{fmt(Number(r.totalPnl))}</PnlColor>
              </td>
              <td className="px-3 py-2 tabular-nums">
                <PnlColor val={Number(r.avgPnl)}>{fmt(Number(r.avgPnl))}</PnlColor>
              </td>
              <td className="px-3 py-2 tabular-nums text-muted-foreground">{Number(r.avgHoldDays).toFixed(1)}d</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SymbolTable({ rows }: { rows: SymbolBreakdownDto[] }) {
  if (rows.length === 0) return null
  const thClass = 'px-3 py-2 text-left'
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
            <th className={thClass}>Symbol</th>
            <th className={thClass}>Trades</th>
            <th className={thClass}>Win%</th>
            <th className={thClass}>Total P&amp;L</th>
            <th className={thClass}>Avg P&amp;L</th>
            <th className={thClass}>Avg Credit Ratio</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.symbol} className="border-b border-border hover:bg-muted/30 transition-colors">
              <td className="px-3 py-2 font-mono font-medium">{r.symbol}</td>
              <td className="px-3 py-2 tabular-nums text-muted-foreground">{r.count} ({r.wins}W)</td>
              <td className="px-3 py-2 tabular-nums">
                <span className={Number(r.winRate) >= 0.5 ? 'text-green-600 dark:text-green-400' : 'text-red-500'}>
                  {fmtPct(Number(r.winRate))}
                </span>
              </td>
              <td className="px-3 py-2 tabular-nums">
                <PnlColor val={Number(r.totalPnl)}>{fmt(Number(r.totalPnl))}</PnlColor>
              </td>
              <td className="px-3 py-2 tabular-nums">
                <PnlColor val={Number(r.avgPnl)}>{fmt(Number(r.avgPnl))}</PnlColor>
              </td>
              <td className="px-3 py-2 tabular-nums text-muted-foreground">
                {(Number(r.avgCreditRatio) * 100).toFixed(1)}%
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function IvBucketTable({ rows }: { rows: IvBucketBreakdownDto[] }) {
  const hasData = rows.some((r) => r.count > 0)
  if (!hasData) return null
  const thClass = 'px-3 py-2 text-left'
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
            <th className={thClass}>IV Rank at Entry</th>
            <th className={thClass}>Trades</th>
            <th className={thClass}>Win%</th>
            <th className={thClass}>Avg P&amp;L</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.bucket} className="border-b border-border hover:bg-muted/30 transition-colors">
              <td className="px-3 py-2 font-mono text-xs">{r.bucket}</td>
              <td className="px-3 py-2 tabular-nums text-muted-foreground">{r.count}</td>
              <td className="px-3 py-2 tabular-nums">
                {r.count > 0 ? (
                  <span className={Number(r.winRate) >= 0.5 ? 'text-green-600 dark:text-green-400' : 'text-red-500'}>
                    {fmtPct(Number(r.winRate))}
                  </span>
                ) : '—'}
              </td>
              <td className="px-3 py-2 tabular-nums">
                {r.count > 0 ? <PnlColor val={Number(r.avgPnl)}>{fmt(Number(r.avgPnl))}</PnlColor> : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function AnalyticsPage() {
  const { data, isLoading, isError } = useQuery({
    ...getSpreadAnalyticsOptions(),
    refetchInterval: 60_000,
  })

  const a = data as SpreadAnalyticsDto | undefined

  const closedCount = a ? a.summary.totalTrades - a.summary.openTrades : 0
  const totalPnl = a ? Number(a.summary.totalRealizedPnl) : 0

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">Analytics</h1>

      {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
      {isError && <p className="text-destructive text-sm">Failed to load analytics.</p>}

      {a && (
        <>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <StatCard
              label="Total P&L"
              value={<PnlColor val={totalPnl}>{fmt(totalPnl)}</PnlColor>}
              sub={`${closedCount} closed trade${closedCount !== 1 ? 's' : ''}`}
            />
            <StatCard
              label="Win Rate"
              value={
                <span className={Number(a.summary.winRate) >= 0.5 ? 'text-green-600 dark:text-green-400' : 'text-red-500'}>
                  {fmtPct(Number(a.summary.winRate))}
                </span>
              }
              sub={`${a.summary.openTrades} open`}
            />
            <StatCard
              label="Avg P&L / Trade"
              value={<PnlColor val={Number(a.summary.avgPnlPerTrade)}>{fmt(Number(a.summary.avgPnlPerTrade))}</PnlColor>}
            />
            <StatCard
              label="Avg Hold Time"
              value={`${Number(a.summary.avgHoldDays).toFixed(1)}d`}
            />
          </div>

          {a.pnlTimeline.length >= 2 && (
            <section>
              <h2 className="text-base font-semibold mb-3">Cumulative P&amp;L</h2>
              <div className="rounded-lg border border-border bg-card p-3">
                <PnlChart timeline={a.pnlTimeline} />
              </div>
            </section>
          )}

          <section>
            <h2 className="text-base font-semibold mb-3">By Close Reason</h2>
            <StatusTable rows={a.byStatus} />
          </section>

          {a.bySymbol.length > 0 && (
            <section>
              <h2 className="text-base font-semibold mb-3">By Symbol</h2>
              <SymbolTable rows={a.bySymbol} />
            </section>
          )}

          {a.byEntryIvBucket.some((r) => r.count > 0) && (
            <section>
              <h2 className="text-base font-semibold mb-3">
                By IV Rank at Entry
                <span className="ml-2 text-xs font-normal text-muted-foreground">
                  — did high-IV entries perform better?
                </span>
              </h2>
              <IvBucketTable rows={a.byEntryIvBucket} />
            </section>
          )}
        </>
      )}
    </div>
  )
}
