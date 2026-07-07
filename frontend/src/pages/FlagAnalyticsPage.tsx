import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getFlagAnalyticsOptions, listFlagsOptions } from '../generated/flags/@tanstack/react-query.gen'
import type { FlagStatusBreakdownDto, FlagSymbolBreakdownDto, FlagPnlTimelinePointDto, FlagPositionDto } from '../generated/flags/types.gen'

// ─────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────

type SortField = 'openedAt' | 'closedAt' | 'realizedPnl' | 'rMultiple' | 'timeInTradeSeconds' | 'symbol' | 'entryPrice'
type SortDir = 'ASC' | 'DESC'

// ─────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────

function fmt(val: number | null | undefined, decimals = 2, prefix = ''): string {
  if (val == null) return '—'
  const n = Number(val)
  const sign = n < 0 ? '-' : ''
  return `${sign}${prefix}${Math.abs(n).toFixed(decimals)}`
}

function fmtMoney(val: number | null | undefined): string {
  if (val == null) return '—'
  const n = Number(val)
  return `${n < 0 ? '-' : n > 0 ? '+' : ''}$${Math.abs(n).toFixed(2)}`
}

function fmtPct(val: number | null | undefined) {
  if (val == null) return '—'
  return `${(Number(val) * 100).toFixed(1)}%`
}

function fmtDuration(seconds: number | null | undefined): string {
  if (seconds == null) return '—'
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function PnlColor({ val, children }: { val: number | null | undefined; children: React.ReactNode }) {
  const n = val == null ? null : Number(val)
  const cls = n == null ? '' : n >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'
  return <span className={cls}>{children}</span>
}

function RColor({ val }: { val: number | null | undefined }) {
  const n = val == null ? null : Number(val)
  if (n == null) return <span className="text-muted-foreground">—</span>
  const cls = n > 0 ? 'text-green-600 dark:text-green-400' : n < 0 ? 'text-red-500 dark:text-red-400' : ''
  return <span className={`tabular-nums ${cls}`}>{n > 0 ? '+' : ''}{n.toFixed(2)}R</span>
}

// ─────────────────────────────────────────────
// Stat card
// ─────────────────────────────────────────────

function StatCard({ label, value, sub }: { label: string; value: React.ReactNode; sub?: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <p className="text-xs text-muted-foreground uppercase tracking-wide mb-1">{label}</p>
      <p className="text-lg font-semibold tabular-nums">{value}</p>
      {sub && <p className="text-xs text-muted-foreground mt-0.5">{sub}</p>}
    </div>
  )
}

// ─────────────────────────────────────────────
// P&L chart
// ─────────────────────────────────────────────

function PnlChart({ timeline }: { timeline: FlagPnlTimelinePointDto[] }) {
  if (timeline.length < 2) return <p className="text-muted-foreground text-sm py-6 text-center">Not enough data.</p>

  const W = 600, H = 120, PL = 52, PR = 10, PT = 12, PB = 22
  const cw = W - PL - PR, ch = H - PT - PB
  const dates = timeline.map(p => new Date(p.date).getTime())
  const pnls = timeline.map(p => Number(p.cumulativePnl))
  const minDate = Math.min(...dates), maxDate = Math.max(...dates)
  const minPnl = Math.min(0, ...pnls), maxPnl = Math.max(0, ...pnls)
  const dateRange = maxDate - minDate || 1, pnlRange = maxPnl - minPnl || 1
  const toX = (d: number) => PL + ((d - minDate) / dateRange) * cw
  const toY = (p: number) => PT + ch - ((p - minPnl) / pnlRange) * ch
  const zeroY = toY(0)
  const points = timeline.map(p => `${toX(new Date(p.date).getTime())},${toY(Number(p.cumulativePnl))}`).join(' ')
  const last = pnls[pnls.length - 1]

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full h-28">
      <line x1={PL} y1={zeroY} x2={W - PR} y2={zeroY} stroke="currentColor" strokeOpacity="0.15" strokeWidth="1" strokeDasharray="4 3" />
      <polyline points={points} fill="none" stroke={last >= 0 ? '#22c55e' : '#ef4444'} strokeWidth="2" strokeLinejoin="round" />
      <text x={PL - 4} y={PT} textAnchor="end" dominantBaseline="middle" fontSize="9" fill="currentColor" opacity="0.4">${maxPnl.toFixed(0)}</text>
      {minPnl < 0 && <text x={PL - 4} y={H - PB} textAnchor="end" dominantBaseline="middle" fontSize="9" fill="currentColor" opacity="0.4">-${Math.abs(minPnl).toFixed(0)}</text>}
      <text x={PL} y={H - 4} fontSize="9" fill="currentColor" opacity="0.4">{new Date(dates[0]).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}</text>
      <text x={W - PR} y={H - 4} textAnchor="end" fontSize="9" fill="currentColor" opacity="0.4">{new Date(dates[dates.length - 1]).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}</text>
    </svg>
  )
}

// ─────────────────────────────────────────────
// Status & symbol breakdown tables
// ─────────────────────────────────────────────

function StatusTable({ rows }: { rows: FlagStatusBreakdownDto[] }) {
  if (rows.length === 0) return <p className="text-muted-foreground text-sm">No closed trades yet.</p>
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
            <th className="px-3 py-2 text-left">Close Reason</th>
            <th className="px-3 py-2 text-left">Count</th>
            <th className="px-3 py-2 text-left">Total P&amp;L</th>
            <th className="px-3 py-2 text-left">Avg P&amp;L</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.status} className="border-b border-border hover:bg-muted/30 transition-colors">
              <td className="px-3 py-2 font-mono text-xs">{r.status}</td>
              <td className="px-3 py-2 tabular-nums">{r.count}</td>
              <td className="px-3 py-2 tabular-nums"><PnlColor val={Number(r.totalPnl)}>{fmtMoney(Number(r.totalPnl))}</PnlColor></td>
              <td className="px-3 py-2 tabular-nums"><PnlColor val={Number(r.avgPnl)}>{fmtMoney(Number(r.avgPnl))}</PnlColor></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SymbolTable({ rows }: { rows: FlagSymbolBreakdownDto[] }) {
  if (rows.length === 0) return null
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
            <th className="px-3 py-2 text-left">Symbol</th>
            <th className="px-3 py-2 text-left">Trades</th>
            <th className="px-3 py-2 text-left">Win%</th>
            <th className="px-3 py-2 text-left">Total P&amp;L</th>
            <th className="px-3 py-2 text-left">Avg P&amp;L</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.symbol} className="border-b border-border hover:bg-muted/30 transition-colors">
              <td className="px-3 py-2 font-mono font-medium">{r.symbol}</td>
              <td className="px-3 py-2 tabular-nums text-muted-foreground">{r.count} ({r.wins}W)</td>
              <td className="px-3 py-2 tabular-nums">
                <span className={Number(r.winRate) >= 0.5 ? 'text-green-600 dark:text-green-400' : 'text-red-500'}>{fmtPct(Number(r.winRate))}</span>
              </td>
              <td className="px-3 py-2 tabular-nums"><PnlColor val={Number(r.totalPnl)}>{fmtMoney(Number(r.totalPnl))}</PnlColor></td>
              <td className="px-3 py-2 tabular-nums"><PnlColor val={Number(r.avgPnl)}>{fmtMoney(Number(r.avgPnl))}</PnlColor></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ─────────────────────────────────────────────
// Trade journal — sortable, pageable
// ─────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
  CLOSED_PROFIT: 'text-green-600 dark:text-green-400',
  CLOSED_STOP: 'text-red-500',
  CLOSED_EOD: 'text-orange-500',
  CLOSED_MANUAL: 'text-muted-foreground',
  CLOSED_EXTERNAL: 'text-purple-500',
  PENDING: 'text-yellow-600',
  OPEN: 'text-blue-600',
}

function SortTh({
  col, label, sort, sortDir, onSort, className = '',
}: {
  col: SortField; label: string; sort: SortField; sortDir: SortDir; onSort: (c: SortField) => void; className?: string
}) {
  const active = sort === col
  return (
    <th
      onClick={() => onSort(col)}
      className={`px-3 py-2 text-left cursor-pointer select-none hover:text-foreground transition-colors text-xs uppercase tracking-wide ${active ? 'text-foreground' : 'text-muted-foreground'} ${className}`}
    >
      {label}{active && <span className="ml-1 opacity-60">{sortDir === 'DESC' ? '↓' : '↑'}</span>}
    </th>
  )
}

function JournalRow({ p, expanded, onToggle }: { p: FlagPositionDto; expanded: boolean; onToggle: () => void }) {
  return (
    <>
      <tr className="border-b border-border hover:bg-muted/30 transition-colors cursor-pointer text-sm" onClick={onToggle}>
        <td className="px-3 py-2 text-xs text-muted-foreground tabular-nums">{fmtDate(p.openedAt)}</td>
        <td className="px-3 py-2 font-mono font-medium">{p.symbol}</td>
        <td className="px-3 py-2">
          <span className={`text-xs font-medium ${STATUS_COLORS[p.status] ?? ''}`}>{p.status.replace('CLOSED_', '')}</span>
        </td>
        <td className="px-3 py-2"><PnlColor val={p.realizedPnl}>{fmtMoney(p.realizedPnl)}</PnlColor></td>
        <td className="px-3 py-2"><RColor val={p.rMultiple} /></td>
        <td className="px-3 py-2 text-muted-foreground tabular-nums">{p.marketSession ?? '—'}</td>
        <td className="px-3 py-2 text-muted-foreground tabular-nums">{p.breakoutType ?? '—'}</td>
        <td className="px-3 py-2 text-muted-foreground tabular-nums">
          {p.stopDistancePct != null ? `${fmt(p.stopDistancePct, 2)}%` : '—'}
        </td>
        <td className="px-3 py-2 text-muted-foreground tabular-nums">{p.flagpoleBarCount ?? '—'}</td>
        <td className="px-3 py-2 text-muted-foreground tabular-nums">{p.flagBarCount ?? '—'}</td>
        <td className="px-3 py-2 text-muted-foreground tabular-nums">
          {p.flagpoleVolumeRatio != null ? `${fmt(p.flagpoleVolumeRatio, 1)}×` : '—'}
        </td>
        <td className="px-3 py-2 text-muted-foreground tabular-nums">
          {p.atrAtEntry != null ? `$${fmt(p.atrAtEntry, 2)}` : '—'}
        </td>
        <td className="px-3 py-2">
          {p.mfeR != null ? <span className="text-green-600 dark:text-green-400 tabular-nums">{fmt(p.mfeR, 2)}R</span> : <span className="text-muted-foreground">—</span>}
        </td>
        <td className="px-3 py-2">
          {p.maeR != null ? <span className="text-red-500 tabular-nums">{fmt(p.maeR, 2)}R</span> : <span className="text-muted-foreground">—</span>}
        </td>
        <td className="px-3 py-2 text-muted-foreground tabular-nums">{fmtDuration(p.timeInTradeSeconds)}</td>
      </tr>
      {expanded && (
        <tr className="bg-muted/20 border-b border-border">
          <td colSpan={15} className="px-4 py-3">
            <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-x-8 gap-y-1 text-xs">
              {([
                ['Entry price', p.entryPrice != null ? `$${fmt(p.entryPrice, 4)}` : null],
                ['Actual fill', p.actualEntryPrice != null ? `$${fmt(p.actualEntryPrice, 4)}` : null],
                ['Entry slippage', p.entrySlippage != null ? fmt(p.entrySlippage, 4) : null],
                ['Stop', p.stopLossPrice != null ? `$${fmt(p.stopLossPrice, 2)}` : null],
                ['Target', p.profitTargetPrice != null ? `$${fmt(p.profitTargetPrice, 2)}` : null],
                ['Shares', p.shares],
                ['Pole height', p.flagpoleHeight != null ? `$${fmt(p.flagpoleHeight, 2)}` : null],
                ['Retracement', p.flagRetracement != null ? `${fmt(Number(p.flagRetracement) * 100, 1)}%` : null],
                ['Channel slope', p.channelSlope != null ? fmt(p.channelSlope, 5) : null],
                ['VWAP@entry', p.vwapAtEntry != null ? `$${fmt(p.vwapAtEntry, 2)}` : null],
                ['Day open', p.dayOpenPrice != null ? `$${fmt(p.dayOpenPrice, 2)}` : null],
                ['Vol MA', p.volumeMaAtEntry != null ? Number(p.volumeMaAtEntry).toLocaleString() : null],
                ['MFE $', p.maxFavorableExcursion != null ? fmtMoney(p.maxFavorableExcursion) : null],
                ['MAE $', p.maxAdverseExcursion != null ? `-$${fmt(p.maxAdverseExcursion)}` : null],
                ['Min to close at entry', p.minutesToClose != null ? `${p.minutesToClose}m` : null],
                ['Pattern start', p.patternStartedAt ? fmtDate(p.patternStartedAt) : null],
              ] as [string, React.ReactNode][]).filter(([, v]) => v != null).map(([label, value]) => (
                <div key={label} className="flex gap-1">
                  <span className="text-muted-foreground shrink-0">{label}:</span>
                  <span className="font-medium">{value}</span>
                </div>
              ))}
            </div>
          </td>
        </tr>
      )}
    </>
  )
}

function TradeJournal() {
  const [sort, setSort] = useState<SortField>('openedAt')
  const [sortDir, setSortDir] = useState<SortDir>('DESC')
  const [page, setPage] = useState(0)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const PAGE_SIZE = 25

  function handleSort(col: SortField) {
    if (col === sort) setSortDir(d => d === 'DESC' ? 'ASC' : 'DESC')
    else { setSort(col); setSortDir('DESC') }
    setPage(0)
  }

  const { data, isLoading } = useQuery({
    ...listFlagsOptions({ query: { size: PAGE_SIZE, page, sort, sortDir } }),
    refetchInterval: 60_000,
  })

  const positions = data?.content ?? []

  return (
    <div className="space-y-3">
      {isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      <div className="overflow-x-auto rounded-lg border border-border">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border bg-muted/50">
              <SortTh col="openedAt" label="Date" sort={sort} sortDir={sortDir} onSort={handleSort} />
              <SortTh col="symbol" label="Symbol" sort={sort} sortDir={sortDir} onSort={handleSort} />
              <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Result</th>
              <SortTh col="realizedPnl" label="P&L" sort={sort} sortDir={sortDir} onSort={handleSort} />
              <SortTh col="rMultiple" label="R" sort={sort} sortDir={sortDir} onSort={handleSort} />
              <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Session</th>
              <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Breakout</th>
              <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Stop%</th>
              <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Pole bars</th>
              <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Flag bars</th>
              <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Vol ratio</th>
              <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">ATR</th>
              <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">MFE R</th>
              <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">MAE R</th>
              <SortTh col="timeInTradeSeconds" label="Duration" sort={sort} sortDir={sortDir} onSort={handleSort} />
            </tr>
          </thead>
          <tbody>
            {positions.length === 0 && !isLoading ? (
              <tr><td colSpan={15} className="px-3 py-8 text-center text-muted-foreground text-sm">No trades yet.</td></tr>
            ) : (
              positions.map(p => (
                <JournalRow
                  key={p.id}
                  p={p}
                  expanded={expandedId === p.id}
                  onToggle={() => setExpandedId(id => id === p.id ? null : p.id)}
                />
              ))
            )}
          </tbody>
        </table>
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between px-1 text-sm text-muted-foreground">
          <span>{page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, Number(data.totalElements))} of {data.totalElements}</span>
          <div className="flex items-center gap-1">
            <button onClick={() => setPage(0)} disabled={page === 0} className="px-2 py-1 rounded border border-border disabled:opacity-30 hover:bg-accent">«</button>
            <button onClick={() => setPage(p => p - 1)} disabled={page === 0} className="px-2 py-1 rounded border border-border disabled:opacity-30 hover:bg-accent">‹</button>
            <span className="px-2 tabular-nums">{page + 1} / {data.totalPages}</span>
            <button onClick={() => setPage(p => p + 1)} disabled={page >= data.totalPages - 1} className="px-2 py-1 rounded border border-border disabled:opacity-30 hover:bg-accent">›</button>
            <button onClick={() => setPage(data.totalPages - 1)} disabled={page >= data.totalPages - 1} className="px-2 py-1 rounded border border-border disabled:opacity-30 hover:bg-accent">»</button>
          </div>
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────
// Main page
// ─────────────────────────────────────────────

export function FlagAnalyticsPage() {
  const { data, isLoading, isError } = useQuery({
    ...getFlagAnalyticsOptions(),
    refetchInterval: 60_000,
  })

  const a = data
  const closedCount = a ? a.summary.totalTrades - a.summary.openTrades : 0
  const totalPnl = a ? Number(a.summary.totalRealizedPnl) : 0

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">Flag Analytics</h1>

      {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
      {isError && <p className="text-destructive text-sm">Failed to load analytics.</p>}

      {a && (
        <>
          {/* Primary performance metrics */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <StatCard
              label="Total P&L"
              value={<PnlColor val={totalPnl}>{fmtMoney(totalPnl)}</PnlColor>}
              sub={`${closedCount} closed trade${closedCount !== 1 ? 's' : ''}`}
            />
            <StatCard
              label="Win Rate"
              value={<span className={Number(a.summary.winRate) >= 0.5 ? 'text-green-600 dark:text-green-400' : 'text-red-500'}>{fmtPct(Number(a.summary.winRate))}</span>}
              sub={`${a.summary.openTrades} open`}
            />
            <StatCard
              label="Avg R-Multiple"
              value={<RColor val={a.summary.avgRMultiple ?? null} />}
              sub="avg realizedPnl / risk"
            />
            <StatCard
              label="Profit Factor"
              value={
                a.summary.profitFactor != null
                  ? <span className={Number(a.summary.profitFactor) >= 1 ? 'text-green-600 dark:text-green-400' : 'text-red-500'}>{fmt(a.summary.profitFactor, 2)}</span>
                  : <span className="text-muted-foreground">—</span>
              }
              sub="gross wins / gross losses"
            />
          </div>

          {/* Secondary metrics */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <StatCard label="Avg Winner" value={<PnlColor val={Number(a.summary.avgWinner)}>{fmtMoney(Number(a.summary.avgWinner))}</PnlColor>} />
            <StatCard label="Avg Loser" value={<PnlColor val={Number(a.summary.avgLoser)}>{fmtMoney(Number(a.summary.avgLoser))}</PnlColor>} />
            <StatCard label="Avg Hold Time" value={`${Number(a.summary.avgHoldMinutes).toFixed(0)} min`} />
            <StatCard
              label="EOD Cut %"
              value={fmtPct(Number(a.summary.eodCutPct))}
              sub="fraction closed by auto-liq"
            />
          </div>

          {/* Cumulative P&L chart */}
          {a.pnlTimeline.length >= 2 && (
            <section>
              <h2 className="text-base font-semibold mb-3">Cumulative P&amp;L</h2>
              <div className="rounded-lg border border-border bg-card p-3">
                <PnlChart timeline={a.pnlTimeline} />
              </div>
            </section>
          )}

          {/* Breakdowns */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
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
          </div>

          {/* Trade journal */}
          <section>
            <h2 className="text-base font-semibold mb-3">Trade Journal</h2>
            <p className="text-xs text-muted-foreground mb-3">Click any row to expand full trade details. Sort by clicking column headers.</p>
            <TradeJournal />
          </section>
        </>
      )}
    </div>
  )
}
