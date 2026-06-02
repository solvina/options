import { useState, useEffect, useCallback } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  listFlagsOptions,
  getFlagConfigOptions,
  updateFlagConfigMutation,
  pauseFlagScannerMutation,
  resumeFlagScannerMutation,
  subscribeFlagSymbolMutation,
  closeFlagPositionMutation,
} from '../generated/flags/@tanstack/react-query.gen'
import type { FlagPositionDto, FlagTradingConfigDto } from '../generated/flags/types.gen'

// ─────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────

type SortField = 'openedAt' | 'closedAt' | 'realizedPnl' | 'rMultiple' | 'timeInTradeSeconds' | 'symbol' | 'entryPrice'
type SortDir = 'ASC' | 'DESC'

// ─────────────────────────────────────────────
// Status badge
// ─────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/40 dark:text-yellow-300',
  OPEN: 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300',
  CLOSED_PROFIT: 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300',
  CLOSED_STOP: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300',
  CLOSED_EOD: 'bg-orange-100 text-orange-800 dark:bg-orange-900/40 dark:text-orange-300',
  CLOSED_MANUAL: 'bg-muted text-muted-foreground',
}

function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[status] ?? 'bg-muted text-muted-foreground'}`}>
      {status.replace('CLOSED_', '')}
    </span>
  )
}

// ─────────────────────────────────────────────
// Formatting helpers
// ─────────────────────────────────────────────

function fmt(val: number | null | undefined, decimals = 2): string {
  return val == null ? '—' : Number(val).toFixed(decimals)
}

function fmtMoney(val: number | null | undefined): string {
  if (val == null) return '—'
  const n = Number(val)
  const sign = n < 0 ? '-' : n > 0 ? '+' : ''
  return `${sign}$${Math.abs(n).toFixed(2)}`
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function fmtDuration(seconds: number | null | undefined): string {
  if (seconds == null) return '—'
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`
}

function PnlSpan({ val, placeholder = '—' }: { val: number | null | undefined; placeholder?: string }) {
  const n = val == null ? null : Number(val)
  const cls = n == null ? 'text-muted-foreground' : n > 0 ? 'text-green-600 dark:text-green-400' : n < 0 ? 'text-red-500 dark:text-red-400' : ''
  return <span className={`tabular-nums font-medium ${cls}`}>{n == null ? placeholder : fmtMoney(n)}</span>
}

function RSpan({ val }: { val: number | null | undefined }) {
  const n = val == null ? null : Number(val)
  const cls = n == null ? 'text-muted-foreground' : n > 0 ? 'text-green-600 dark:text-green-400' : n < 0 ? 'text-red-500 dark:text-red-400' : ''
  return <span className={`tabular-nums font-medium ${cls}`}>{n == null ? '—' : `${n > 0 ? '+' : ''}${n.toFixed(2)}R`}</span>
}

// ─────────────────────────────────────────────
// Sort header
// ─────────────────────────────────────────────

function SortTh({
  col, label, sort, sortDir, onSort, className = '',
}: {
  col: SortField; label: string; sort: SortField; sortDir: SortDir; onSort: (c: SortField) => void; className?: string
}) {
  const active = sort === col
  return (
    <th
      className={`px-3 py-2 text-left cursor-pointer select-none hover:text-foreground transition-colors ${active ? 'text-foreground' : 'text-muted-foreground'} text-xs uppercase tracking-wide ${className}`}
      onClick={() => onSort(col)}
    >
      {label}
      {active && <span className="ml-1 opacity-60">{sortDir === 'DESC' ? '↓' : '↑'}</span>}
    </th>
  )
}

// ─────────────────────────────────────────────
// Pagination controls
// ─────────────────────────────────────────────

function Pagination({ page, totalPages, totalElements, size, onPage }: {
  page: number; totalPages: number; totalElements: number; size: number; onPage: (p: number) => void
}) {
  if (totalPages <= 1) return null
  const from = page * size + 1
  const to = Math.min((page + 1) * size, totalElements)
  return (
    <div className="flex items-center justify-between px-1 py-2 text-sm text-muted-foreground">
      <span>{from}–{to} of {totalElements}</span>
      <div className="flex items-center gap-1">
        <button
          onClick={() => onPage(0)}
          disabled={page === 0}
          className="px-2 py-1 rounded border border-border disabled:opacity-30 hover:bg-accent transition-colors"
        >«</button>
        <button
          onClick={() => onPage(page - 1)}
          disabled={page === 0}
          className="px-2 py-1 rounded border border-border disabled:opacity-30 hover:bg-accent transition-colors"
        >‹</button>
        <span className="px-2 tabular-nums">{page + 1} / {totalPages}</span>
        <button
          onClick={() => onPage(page + 1)}
          disabled={page >= totalPages - 1}
          className="px-2 py-1 rounded border border-border disabled:opacity-30 hover:bg-accent transition-colors"
        >›</button>
        <button
          onClick={() => onPage(totalPages - 1)}
          disabled={page >= totalPages - 1}
          className="px-2 py-1 rounded border border-border disabled:opacity-30 hover:bg-accent transition-colors"
        >»</button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// Scanner status panel
// ─────────────────────────────────────────────

type SymbolScannerStatus = {
  symbol: string
  subscriptionActive: boolean
  candlesBuffered: number
  lastCandleAt: string | null
  patternState: string
  poleHeightPct: number | null
  flagBars: number | null
  flagRetracementPct: number | null
}

function staleness(lastCandleAt: string | null): 'fresh' | 'stale' | 'none' {
  if (!lastCandleAt) return 'none'
  return Date.now() - new Date(lastCandleAt).getTime() < 15 * 60_000 ? 'fresh' : 'stale'
}

function StateChip({ state }: { state: string }) {
  const isBreakout = state.startsWith('BREAKOUT')
  const isFlag = state.startsWith('Flag')
  const isPole = state.startsWith('Pole')
  const cls = isBreakout
    ? 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300 font-semibold'
    : isFlag ? 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300'
    : isPole ? 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300'
    : 'bg-muted text-muted-foreground'
  return <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs ${cls}`}>{state}</span>
}

function ScannerStatusPanel() {
  const [statuses, setStatuses] = useState<SymbolScannerStatus[]>([])
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const [error, setError] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const res = await fetch('/api/flags/scanner/status')
      if (!res.ok) throw new Error()
      setStatuses(await res.json())
      setLastRefresh(new Date())
      setError(false)
    } catch { setError(true) }
  }, [])

  useEffect(() => {
    refresh()
    const id = setInterval(refresh, 10_000)
    return () => clearInterval(id)
  }, [refresh])

  return (
    <div className="rounded-lg border border-border bg-card p-5 space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold">Candle Scanner</h2>
        <span className="text-xs text-muted-foreground">
          {error ? <span className="text-destructive">fetch error</span>
            : lastRefresh ? `updated ${lastRefresh.toLocaleTimeString()}` : 'loading…'}
        </span>
      </div>
      {statuses.length === 0 && !error && <p className="text-sm text-muted-foreground">No active subscriptions.</p>}
      {statuses.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-muted-foreground text-xs uppercase tracking-wide">
                <th className="pb-1 text-left">Symbol</th>
                <th className="pb-1 text-left">Stream</th>
                <th className="pb-1 text-right">Candles</th>
                <th className="pb-1 text-left pl-4">Last bar</th>
                <th className="pb-1 text-left pl-4">Pattern</th>
                <th className="pb-1 text-right">Pole %</th>
                <th className="pb-1 text-right">Flag bars</th>
                <th className="pb-1 text-right">Retrace %</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {statuses.map((s) => {
                const stale = staleness(s.lastCandleAt)
                const dot = !s.subscriptionActive ? '🔴' : stale !== 'fresh' ? '🟡' : '🟢'
                return (
                  <tr key={s.symbol} className="hover:bg-muted/30 transition-colors">
                    <td className="py-1.5 font-medium">{s.symbol}</td>
                    <td className="py-1.5">{dot}</td>
                    <td className="py-1.5 text-right tabular-nums">{s.candlesBuffered}</td>
                    <td className="py-1.5 pl-4 text-muted-foreground tabular-nums">
                      {s.lastCandleAt ? new Date(s.lastCandleAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '—'}
                    </td>
                    <td className="py-1.5 pl-4"><StateChip state={s.patternState} /></td>
                    <td className="py-1.5 text-right tabular-nums text-muted-foreground">{s.poleHeightPct != null ? `${s.poleHeightPct}%` : '—'}</td>
                    <td className="py-1.5 text-right tabular-nums text-muted-foreground">{s.flagBars ?? '—'}</td>
                    <td className="py-1.5 text-right tabular-nums text-muted-foreground">{s.flagRetracementPct != null ? `${s.flagRetracementPct}%` : '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────
// Config panel
// ─────────────────────────────────────────────

type ConfigForm = Omit<FlagTradingConfigDto, 'enabled'>

function ConfigPanel({ config }: { config: FlagTradingConfigDto }) {
  const qc = useQueryClient()
  const [form, setForm] = useState<ConfigForm>({
    riskPerTrade: config.riskPerTrade,
    maxOpenPositions: config.maxOpenPositions,
    entryBlockMinutesBeforeClose: config.entryBlockMinutesBeforeClose,
    eodLiqMinutesBeforeClose: config.eodLiqMinutesBeforeClose,
  })
  const [dirty, setDirty] = useState(false)
  const [optimisticEnabled, setOptimisticEnabled] = useState<boolean | null>(null)
  const isEnabled = optimisticEnabled ?? config.enabled

  useEffect(() => {
    if (!dirty) setForm({
      riskPerTrade: config.riskPerTrade,
      maxOpenPositions: config.maxOpenPositions,
      entryBlockMinutesBeforeClose: config.entryBlockMinutesBeforeClose,
      eodLiqMinutesBeforeClose: config.eodLiqMinutesBeforeClose,
    })
  }, [config, dirty])

  const invalidate = () => qc.invalidateQueries({ queryKey: ['getFlagConfig'] })
  const update = useMutation({ ...updateFlagConfigMutation(), onSuccess: () => { setDirty(false); invalidate() } })
  const pause = useMutation({ ...pauseFlagScannerMutation(), onMutate: () => setOptimisticEnabled(false), onSettled: () => { setOptimisticEnabled(null); invalidate() } })
  const resume = useMutation({ ...resumeFlagScannerMutation(), onMutate: () => setOptimisticEnabled(true), onSettled: () => { setOptimisticEnabled(null); invalidate() } })

  function change<K extends keyof ConfigForm>(key: K, value: ConfigForm[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
    setDirty(true)
  }

  return (
    <div className="rounded-lg border border-border bg-card p-5 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold">Scanner Config</h2>
        <div className="flex items-center gap-3">
          <span className={`text-sm font-medium ${isEnabled ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'}`}>
            {isEnabled ? '● Active' : '○ Paused'}
          </span>
          <button
            onClick={() => isEnabled ? pause.mutate({}) : resume.mutate({})}
            disabled={pause.isPending || resume.isPending}
            className={`px-3 py-1.5 text-sm rounded-md transition-colors disabled:opacity-40 ${isEnabled ? 'bg-red-500/10 text-red-600 hover:bg-red-500/20 dark:text-red-400' : 'bg-green-500/10 text-green-600 hover:bg-green-500/20 dark:text-green-400'}`}
          >
            {pause.isPending || resume.isPending ? '…' : isEnabled ? 'Pause' : 'Resume'}
          </button>
        </div>
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        {([
          ['Risk / Trade ($)', 'riskPerTrade', 10, 1] as const,
          ['Max Open Positions', 'maxOpenPositions', 1, 1] as const,
          ['Entry Block (min before close)', 'entryBlockMinutesBeforeClose', 5, 0] as const,
          ['EOD Liq (min before close)', 'eodLiqMinutesBeforeClose', 5, 0] as const,
        ]).map(([label, key, step, min]) => (
          <label key={key} className="space-y-1">
            <span className="text-xs text-muted-foreground uppercase tracking-wide">{label}</span>
            <input
              type="number" step={step} min={min}
              value={form[key]}
              onChange={(e) => change(key, Number(e.target.value))}
              className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm tabular-nums"
            />
          </label>
        ))}
      </div>
      {dirty && (
        <div className="flex gap-2 justify-end">
          <button
            onClick={() => { setForm({ riskPerTrade: config.riskPerTrade, maxOpenPositions: config.maxOpenPositions, entryBlockMinutesBeforeClose: config.entryBlockMinutesBeforeClose, eodLiqMinutesBeforeClose: config.eodLiqMinutesBeforeClose }); setDirty(false) }}
            className="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-accent transition-colors"
          >Reset</button>
          <button onClick={() => update.mutate({ body: { ...form, enabled: config.enabled } })} disabled={update.isPending}
            className="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:opacity-90 disabled:opacity-50">
            {update.isPending ? 'Saving…' : 'Save Config'}
          </button>
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────
// Subscribe symbol panel
// ─────────────────────────────────────────────

function SubscribeSymbolPanel() {
  const [symbol, setSymbol] = useState('')
  const [session, setSession] = useState<'US' | 'EU'>('US')
  const [lastResult, setLastResult] = useState<string | null>(null)
  const subscribe = useMutation({
    ...subscribeFlagSymbolMutation(),
    onSuccess: () => {
      setLastResult(`Subscribed ${symbol.toUpperCase()} (${session})`)
      setSymbol('')
    },
    onError: () => setLastResult('Failed — check server logs'),
  })

  return (
    <div className="rounded-lg border border-border bg-card p-4 space-y-3">
      <h2 className="text-sm font-semibold text-muted-foreground uppercase tracking-wide">Hot-Add Symbol</h2>
      <div className="flex items-center gap-2">
        <input
          type="text"
          placeholder="AAPL"
          value={symbol}
          onChange={(e) => { setSymbol(e.target.value.toUpperCase()); setLastResult(null) }}
          className="w-28 rounded-md border border-border bg-background px-3 py-1.5 text-sm uppercase"
        />
        <select
          value={session}
          onChange={(e) => setSession(e.target.value as 'US' | 'EU')}
          className="rounded-md border border-border bg-background px-2 py-1.5 text-sm"
        >
          <option value="US">US</option>
          <option value="EU">EU</option>
        </select>
        <button
          onClick={() => subscribe.mutate({ body: { symbol: symbol.trim(), session: session as 'US' | 'EU' } })}
          disabled={!symbol.trim() || subscribe.isPending}
          className="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:opacity-90 disabled:opacity-50"
        >
          {subscribe.isPending ? '…' : 'Subscribe'}
        </button>
        {lastResult && <span className="text-xs text-muted-foreground">{lastResult}</span>}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────
// Expanded detail row
// ─────────────────────────────────────────────

function DetailGrid({ items }: { items: [string, React.ReactNode][] }) {
  const visible = items.filter(([, v]) => v != null && v !== '—')
  if (visible.length === 0) return null
  return (
    <div className="flex flex-wrap gap-x-5 gap-y-1 text-xs text-muted-foreground">
      {visible.map(([label, value]) => (
        <span key={label}>{label}: <span className="text-foreground font-medium">{value}</span></span>
      ))}
    </div>
  )
}

function FlagDetailRow({ position: p, colSpan }: { position: FlagPositionDto; colSpan: number }) {
  const slippage = p.actualEntryPrice != null ? Number(p.actualEntryPrice) - Number(p.entryPrice) : null
  return (
    <tr className="bg-muted/20 border-b border-border">
      <td colSpan={colSpan} className="px-4 py-3 space-y-2">
        {/* Execution */}
        <DetailGrid items={[
          ['Strategy', p.strategyName ?? 'bull_flag'],
          ['Breakout type', p.breakoutType ?? null],
          ['Session', p.marketSession ?? null],
          ['Min to close', p.minutesToClose != null ? `${p.minutesToClose}m` : null],
          ['Actual fill', p.actualEntryPrice != null ? `$${fmt(p.actualEntryPrice, 4)}` : null],
          ['Entry slippage', slippage != null ? <span className={slippage < 0 ? 'text-green-500' : slippage > 0 ? 'text-red-500' : ''}>{slippage > 0 ? '+' : ''}{fmt(slippage, 4)}</span> : null],
          ['Time in trade', fmtDuration(p.timeInTradeSeconds)],
        ]} />
        {/* Pattern */}
        <DetailGrid items={[
          ['Pole height', p.flagpoleHeight != null ? `$${fmt(p.flagpoleHeight, 2)}` : null],
          ['Pole bars', p.flagpoleBarCount ?? null],
          ['Flag bars', p.flagBarCount ?? null],
          ['Retracement', p.flagRetracement != null ? `${fmt(Number(p.flagRetracement) * 100, 1)}%` : null],
          ['Channel slope', p.channelSlope != null ? fmt(p.channelSlope, 5) : null],
          ['Stop distance', p.stopDistancePct != null ? `${fmt(p.stopDistancePct, 2)}%` : null],
        ]} />
        {/* Volume & volatility */}
        <DetailGrid items={[
          ['ATR@entry', p.atrAtEntry != null ? `$${fmt(p.atrAtEntry, 3)}` : null],
          ['Pole vol ratio', p.flagpoleVolumeRatio != null ? `${fmt(p.flagpoleVolumeRatio, 1)}×` : null],
          ['Pole avg vol', p.flagpoleAvgVolume != null ? Number(p.flagpoleAvgVolume).toLocaleString() : null],
          ['Flag avg vol', p.flagAvgVolume != null ? Number(p.flagAvgVolume).toLocaleString() : null],
          ['Vol MA', p.volumeMaAtEntry != null ? Number(p.volumeMaAtEntry).toLocaleString() : null],
          ['VWAP@entry', p.vwapAtEntry != null ? `$${fmt(p.vwapAtEntry, 2)}` : null],
          ['Day open', p.dayOpenPrice != null ? `$${fmt(p.dayOpenPrice, 2)}` : null],
        ]} />
        {/* Trade performance */}
        <DetailGrid items={[
          ['High seen', p.highestPriceSeen != null ? `$${fmt(p.highestPriceSeen, 4)}` : null],
          ['Low seen', p.lowestPriceSeen != null ? `$${fmt(p.lowestPriceSeen, 4)}` : null],
          ['MFE', p.maxFavorableExcursion != null ? <span className="text-green-600 dark:text-green-400">{fmtMoney(p.maxFavorableExcursion)}</span> : null],
          ['MFE R', p.mfeR != null ? <span className="text-green-600 dark:text-green-400">{fmt(p.mfeR, 2)}R</span> : null],
          ['MAE', p.maxAdverseExcursion != null ? <span className="text-red-500">-${fmt(p.maxAdverseExcursion)}</span> : null],
          ['MAE R', p.maeR != null ? <span className="text-red-500">{fmt(p.maeR, 2)}R</span> : null],
          ['R-multiple', p.rMultiple != null ? <RSpan val={p.rMultiple} /> : null],
        ]} />
      </td>
    </tr>
  )
}

// ─────────────────────────────────────────────
// Live position card (for OPEN / PENDING)
// ─────────────────────────────────────────────

function LivePositionCard({ position: p }: { position: FlagPositionDto }) {
  const qc = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const [expanded, setExpanded] = useState(false)

  const closePos = useMutation({
    ...closeFlagPositionMutation(),
    onSuccess: () => { setConfirming(false); qc.invalidateQueries({ queryKey: ['listFlags'] }) },
    onError: () => setConfirming(false),
  })

  const unrealPnl = p.unrealizedPnl != null ? Number(p.unrealizedPnl) : null
  const entryPrice = Number(p.entryPrice)
  const stopPct = ((entryPrice - Number(p.stopLossPrice)) / entryPrice * 100).toFixed(1)
  const elapsed = p.openedAt ? Math.floor((Date.now() - new Date(p.openedAt).getTime()) / 60_000) : null

  return (
    <div className={`rounded-lg border ${p.status === 'OPEN' ? 'border-blue-300 dark:border-blue-700 bg-blue-50/40 dark:bg-blue-900/10' : 'border-yellow-300 dark:border-yellow-700 bg-yellow-50/40 dark:bg-yellow-900/10'} p-4`}>
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-center gap-3">
          <span className="text-lg font-mono font-bold">{p.symbol}</span>
          <StatusBadge status={p.status} />
          {elapsed != null && <span className="text-xs text-muted-foreground">{elapsed}m ago</span>}
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => setExpanded(e => !e)} className="text-xs text-muted-foreground hover:text-foreground transition-colors">
            {expanded ? 'less' : 'details'}
          </button>
          {confirming ? (
            <>
              <button onClick={() => closePos.mutate({ path: { id: p.id } })} disabled={closePos.isPending}
                className="px-2 py-1 text-xs rounded bg-red-500 text-white hover:bg-red-600 disabled:opacity-50">
                {closePos.isPending ? '…' : 'Confirm'}
              </button>
              <button onClick={() => setConfirming(false)} className="px-2 py-1 text-xs rounded border border-border hover:bg-accent">Cancel</button>
            </>
          ) : (
            <button onClick={() => setConfirming(true)} className="px-2 py-1 text-xs rounded border border-border text-muted-foreground hover:text-foreground hover:bg-accent">
              Close
            </button>
          )}
        </div>
      </div>

      <div className="mt-3 grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-x-4 gap-y-1 text-sm">
        <div><span className="text-xs text-muted-foreground block">Entry</span><span className="tabular-nums font-medium">${fmt(p.entryPrice, 2)}</span></div>
        <div><span className="text-xs text-muted-foreground block">Current</span>
          <span className="tabular-nums font-medium">{p.currentPrice != null ? `$${fmt(p.currentPrice, 2)}` : '—'}</span>
        </div>
        <div><span className="text-xs text-muted-foreground block">Stop</span><span className="tabular-nums text-red-500">${fmt(p.stopLossPrice, 2)} <span className="text-xs opacity-70">(-{stopPct}%)</span></span></div>
        <div><span className="text-xs text-muted-foreground block">Target</span><span className="tabular-nums text-green-600 dark:text-green-400">${fmt(p.profitTargetPrice, 2)}</span></div>
        <div><span className="text-xs text-muted-foreground block">Shares</span><span className="tabular-nums">{p.shares}</span></div>
        <div><span className="text-xs text-muted-foreground block">Unreal. P&amp;L</span><PnlSpan val={unrealPnl} placeholder="…" /></div>
      </div>

      {expanded && (
        <div className="mt-3 pt-3 border-t border-border space-y-1.5">
          <DetailGrid items={[
            ['Strategy', p.strategyName ?? 'bull_flag'],
            ['Breakout type', p.breakoutType ?? null],
            ['Session', p.marketSession ?? null],
            ['Pole bars', p.flagpoleBarCount ?? null],
            ['Flag bars', p.flagBarCount ?? null],
            ['Pole vol ratio', p.flagpoleVolumeRatio != null ? `${fmt(p.flagpoleVolumeRatio, 1)}×` : null],
            ['ATR@entry', p.atrAtEntry != null ? `$${fmt(p.atrAtEntry, 3)}` : null],
            ['VWAP@entry', p.vwapAtEntry != null ? `$${fmt(p.vwapAtEntry, 2)}` : null],
            ['Stop %', p.stopDistancePct != null ? `${fmt(p.stopDistancePct, 2)}%` : null],
          ]} />
          <DetailGrid items={[
            ['High seen', p.highestPriceSeen != null ? `$${fmt(p.highestPriceSeen, 4)}` : null],
            ['Low seen', p.lowestPriceSeen != null ? `$${fmt(p.lowestPriceSeen, 4)}` : null],
            ['MFE', p.maxFavorableExcursion != null ? fmtMoney(p.maxFavorableExcursion) : null],
            ['MAE', p.maxAdverseExcursion != null ? `-$${fmt(p.maxAdverseExcursion)}` : null],
          ]} />
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────
// History row (closed trades)
// ─────────────────────────────────────────────

function FlagHistoryRow({ position }: { position: FlagPositionDto }) {
  const [expanded, setExpanded] = useState(false)
  return (
    <>
      <tr
        className="border-b border-border hover:bg-muted/30 transition-colors cursor-pointer"
        onClick={() => setExpanded(e => !e)}
      >
        <td className="px-3 py-2 font-mono font-medium text-sm">{position.symbol}</td>
        <td className="px-3 py-2"><StatusBadge status={position.status} /></td>
        <td className="px-3 py-2 tabular-nums text-sm">${fmt(position.entryPrice, 2)}</td>
        <td className="px-3 py-2 tabular-nums text-sm text-red-500">${fmt(position.stopLossPrice, 2)}</td>
        <td className="px-3 py-2 tabular-nums text-sm text-green-600 dark:text-green-400">${fmt(position.profitTargetPrice, 2)}</td>
        <td className="px-3 py-2 tabular-nums text-sm">{position.shares}</td>
        <td className="px-3 py-2 tabular-nums text-sm"><PnlSpan val={position.realizedPnl} /></td>
        <td className="px-3 py-2 tabular-nums text-sm"><RSpan val={position.rMultiple} /></td>
        <td className="px-3 py-2 text-muted-foreground text-xs">{fmtDate(position.openedAt)}</td>
        <td className="px-3 py-2 text-muted-foreground text-xs">{fmtDate(position.closedAt)}</td>
        <td className="px-3 py-2 tabular-nums text-xs text-muted-foreground">{fmtDuration(position.timeInTradeSeconds)}</td>
        <td className="px-3 py-2 text-xs text-muted-foreground">{position.marketSession ?? '—'}</td>
      </tr>
      {expanded && <FlagDetailRow position={position} colSpan={12} />}
    </>
  )
}

// ─────────────────────────────────────────────
// Status filter tabs
// ─────────────────────────────────────────────

const HISTORY_FILTERS = ['ALL', 'CLOSED_PROFIT', 'CLOSED_STOP', 'CLOSED_EOD', 'CLOSED_MANUAL', 'PENDING', 'OPEN'] as const
type HistoryFilter = (typeof HISTORY_FILTERS)[number]

// ─────────────────────────────────────────────
// Main page
// ─────────────────────────────────────────────

export function FlagsPage() {
  const [statusFilter, setStatusFilter] = useState<HistoryFilter>('ALL')
  const [sort, setSort] = useState<SortField>('openedAt')
  const [sortDir, setSortDir] = useState<SortDir>('DESC')
  const [page, setPage] = useState(0)

  const PAGE_SIZE = 20

  function handleSort(col: SortField) {
    if (col === sort) {
      setSortDir(d => d === 'DESC' ? 'ASC' : 'DESC')
    } else {
      setSort(col)
      setSortDir('DESC')
    }
    setPage(0)
  }

  // Live positions — always fetched separately, refreshes every 5s
  const { data: openData } = useQuery({
    ...listFlagsOptions({ query: { status: 'OPEN', size: 50 } }),
    refetchInterval: 5_000,
  })
  const { data: pendingData } = useQuery({
    ...listFlagsOptions({ query: { status: 'PENDING', size: 50 } }),
    refetchInterval: 5_000,
  })
  const livePositions = [...(openData?.content ?? []), ...(pendingData?.content ?? [])]

  // History table
  const { data: pagedData, isLoading, isError } = useQuery({
    ...listFlagsOptions({
      query: {
        status: statusFilter === 'ALL' ? undefined : statusFilter,
        size: PAGE_SIZE,
        page,
        sort,
        sortDir,
      },
    }),
    refetchInterval: 15_000,
  })

  const { data: config, isLoading: configLoading } = useQuery({
    ...getFlagConfigOptions(),
    refetchInterval: 30_000,
  })

  // Reset page when filter/sort changes
  useEffect(() => { setPage(0) }, [statusFilter, sort, sortDir])

  const positions = pagedData?.content ?? []

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Bull Flag Positions</h1>
        {pagedData && <span className="text-xs text-muted-foreground">{pagedData.totalElements} total</span>}
      </div>

      {configLoading && <p className="text-muted-foreground text-sm">Loading config…</p>}
      {config && <ConfigPanel config={config} />}

      <ScannerStatusPanel />
      <SubscribeSymbolPanel />

      {/* Live positions — always visible */}
      {livePositions.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-base font-semibold flex items-center gap-2">
            Live Positions
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300">
              {livePositions.length}
            </span>
          </h2>
          <div className="space-y-2">
            {livePositions.map(p => <LivePositionCard key={p.id} position={p} />)}
          </div>
        </section>
      )}

      {/* History table */}
      <section className="space-y-3">
        <h2 className="text-base font-semibold">Trade History</h2>

        {/* Status filter tabs */}
        <div className="flex flex-wrap gap-1">
          {HISTORY_FILTERS.map((s) => (
            <button
              key={s}
              onClick={() => setStatusFilter(s)}
              className={`px-3 py-1 text-xs rounded-full transition-colors ${
                statusFilter === s
                  ? 'bg-primary text-primary-foreground'
                  : 'border border-border text-muted-foreground hover:text-foreground hover:bg-accent'
              }`}
            >
              {s === 'ALL' ? 'All' : s.replace('CLOSED_', '')}
            </button>
          ))}
        </div>

        {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
        {isError && <p className="text-destructive text-sm">Failed to load positions.</p>}

        {!isLoading && (
          <>
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border bg-muted/50">
                    <SortTh col="symbol" label="Symbol" sort={sort} sortDir={sortDir} onSort={handleSort} />
                    <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Status</th>
                    <SortTh col="entryPrice" label="Entry" sort={sort} sortDir={sortDir} onSort={handleSort} />
                    <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Stop</th>
                    <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Target</th>
                    <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Shares</th>
                    <SortTh col="realizedPnl" label="P&L" sort={sort} sortDir={sortDir} onSort={handleSort} />
                    <SortTh col="rMultiple" label="R" sort={sort} sortDir={sortDir} onSort={handleSort} />
                    <SortTh col="openedAt" label="Opened" sort={sort} sortDir={sortDir} onSort={handleSort} />
                    <SortTh col="closedAt" label="Closed" sort={sort} sortDir={sortDir} onSort={handleSort} />
                    <SortTh col="timeInTradeSeconds" label="Duration" sort={sort} sortDir={sortDir} onSort={handleSort} />
                    <th className="px-3 py-2 text-left text-muted-foreground text-xs uppercase tracking-wide">Session</th>
                  </tr>
                </thead>
                <tbody>
                  {positions.length === 0 ? (
                    <tr>
                      <td colSpan={12} className="px-3 py-8 text-center text-muted-foreground text-sm">No positions found.</td>
                    </tr>
                  ) : (
                    positions.map(p => <FlagHistoryRow key={p.id} position={p} />)
                  )}
                </tbody>
              </table>
            </div>

            {pagedData && (
              <Pagination
                page={pagedData.page}
                totalPages={pagedData.totalPages}
                totalElements={Number(pagedData.totalElements)}
                size={PAGE_SIZE}
                onPage={setPage}
              />
            )}
          </>
        )}
      </section>
    </div>
  )
}
