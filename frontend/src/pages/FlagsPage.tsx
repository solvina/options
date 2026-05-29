import { useState, useEffect, useCallback } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  listFlagsOptions,
  getFlagConfigOptions,
  updateFlagConfigMutation,
  pauseFlagScannerMutation,
  resumeFlagScannerMutation,
  closeFlagPositionMutation,
} from '../generated/flags/@tanstack/react-query.gen'
import type { FlagPositionDto, FlagTradingConfigDto } from '../generated/flags/types.gen'

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
      {status}
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
  const d = new Date(iso)
  return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function PnlSpan({ val, placeholder = '—' }: { val: number | null | undefined; placeholder?: string }) {
  const n = val == null ? null : Number(val)
  const cls = n == null ? 'text-muted-foreground' : n > 0 ? 'text-green-600 dark:text-green-400' : n < 0 ? 'text-red-500 dark:text-red-400' : ''
  return <span className={`tabular-nums font-medium ${cls}`}>{n == null ? placeholder : fmtMoney(n)}</span>
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
  const ageMs = Date.now() - new Date(lastCandleAt).getTime()
  return ageMs < 15 * 60_000 ? 'fresh' : 'stale'
}

function StateChip({ state }: { state: string }) {
  const isBreakout = state.startsWith('BREAKOUT')
  const isFlag = state.startsWith('Flag')
  const isPole = state.startsWith('Pole')
  const cls = isBreakout
    ? 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300 font-semibold'
    : isFlag
    ? 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300'
    : isPole
    ? 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300'
    : 'bg-muted text-muted-foreground'
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs ${cls}`}>
      {state}
    </span>
  )
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
    } catch {
      setError(true)
    }
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
          {error ? (
            <span className="text-destructive">fetch error</span>
          ) : lastRefresh ? (
            `updated ${lastRefresh.toLocaleTimeString()}`
          ) : 'loading…'}
        </span>
      </div>

      {statuses.length === 0 && !error && (
        <p className="text-sm text-muted-foreground">No active subscriptions.</p>
      )}

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
                const dot = !s.subscriptionActive
                  ? '🔴'
                  : stale === 'none'
                  ? '🟡'
                  : stale === 'stale'
                  ? '🟡'
                  : '🟢'
                const lastBarLabel = s.lastCandleAt
                  ? new Date(s.lastCandleAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
                  : '—'
                return (
                  <tr key={s.symbol} className="hover:bg-muted/30 transition-colors">
                    <td className="py-1.5 font-medium">{s.symbol}</td>
                    <td className="py-1.5">{dot}</td>
                    <td className="py-1.5 text-right tabular-nums">{s.candlesBuffered}</td>
                    <td className="py-1.5 pl-4 text-muted-foreground tabular-nums">{lastBarLabel}</td>
                    <td className="py-1.5 pl-4"><StateChip state={s.patternState} /></td>
                    <td className="py-1.5 text-right tabular-nums text-muted-foreground">
                      {s.poleHeightPct != null ? `${s.poleHeightPct}%` : '—'}
                    </td>
                    <td className="py-1.5 text-right tabular-nums text-muted-foreground">
                      {s.flagBars ?? '—'}
                    </td>
                    <td className="py-1.5 text-right tabular-nums text-muted-foreground">
                      {s.flagRetracementPct != null ? `${s.flagRetracementPct}%` : '—'}
                    </td>
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
// Status filter tabs
// ─────────────────────────────────────────────

const STATUS_FILTERS = ['ALL', 'PENDING', 'OPEN', 'CLOSED_PROFIT', 'CLOSED_STOP', 'CLOSED_EOD', 'CLOSED_MANUAL'] as const
type StatusFilter = (typeof STATUS_FILTERS)[number]

// ─────────────────────────────────────────────
// Config panel
// ─────────────────────────────────────────────

type ConfigForm = Omit<FlagTradingConfigDto, 'enabled'>

function ConfigPanel({ config }: { config: FlagTradingConfigDto }) {
  const qc = useQueryClient()

  // Numeric fields — separate from the enabled toggle
  const [form, setForm] = useState<ConfigForm>({
    riskPerTrade: config.riskPerTrade,
    maxOpenPositions: config.maxOpenPositions,
    entryBlockMinutesBeforeClose: config.entryBlockMinutesBeforeClose,
    eodLiqMinutesBeforeClose: config.eodLiqMinutesBeforeClose,
  })
  const [dirty, setDirty] = useState(false)

  // Optimistic enabled state: set immediately on click, cleared when server confirms
  const [optimisticEnabled, setOptimisticEnabled] = useState<boolean | null>(null)
  const isEnabled = optimisticEnabled ?? config.enabled

  // Sync form fields if server data changes while form is clean
  useEffect(() => {
    if (!dirty) {
      setForm({
        riskPerTrade: config.riskPerTrade,
        maxOpenPositions: config.maxOpenPositions,
        entryBlockMinutesBeforeClose: config.entryBlockMinutesBeforeClose,
        eodLiqMinutesBeforeClose: config.eodLiqMinutesBeforeClose,
      })
    }
  }, [config, dirty])

  const invalidate = () => qc.invalidateQueries({ queryKey: ['getFlagConfig'] })

  const update = useMutation({
    ...updateFlagConfigMutation(),
    onSuccess: () => { setDirty(false); invalidate() },
  })

  const pause = useMutation({
    ...pauseFlagScannerMutation(),
    onMutate: () => setOptimisticEnabled(false),
    onSettled: () => { setOptimisticEnabled(null); invalidate() },
  })

  const resume = useMutation({
    ...resumeFlagScannerMutation(),
    onMutate: () => setOptimisticEnabled(true),
    onSettled: () => { setOptimisticEnabled(null); invalidate() },
  })

  function change<K extends keyof ConfigForm>(key: K, value: ConfigForm[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
    setDirty(true)
  }

  function save() {
    update.mutate({ body: { ...form, enabled: config.enabled } })
  }

  const toggling = pause.isPending || resume.isPending

  return (
    <div className="rounded-lg border border-border bg-card p-5 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold">Scanner Config</h2>
        {/* Toggle — flips immediately, confirmed by server in background */}
        <div className="flex items-center gap-3">
          <span className={`text-sm font-medium transition-colors ${isEnabled ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'}`}>
            {isEnabled ? '● Active' : '○ Paused'}
          </span>
          <button
            onClick={() => isEnabled ? pause.mutate({}) : resume.mutate({})}
            disabled={toggling}
            className={`px-3 py-1.5 text-sm rounded-md transition-colors disabled:opacity-40 ${
              isEnabled
                ? 'bg-red-500/10 text-red-600 hover:bg-red-500/20 dark:text-red-400'
                : 'bg-green-500/10 text-green-600 hover:bg-green-500/20 dark:text-green-400'
            }`}
          >
            {toggling ? '…' : isEnabled ? 'Pause' : 'Resume'}
          </button>
        </div>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <label className="space-y-1">
          <span className="text-xs text-muted-foreground uppercase tracking-wide">Risk / Trade ($)</span>
          <input
            type="number"
            step="10"
            min="1"
            value={form.riskPerTrade}
            onChange={(e) => change('riskPerTrade', Number(e.target.value))}
            className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm tabular-nums"
          />
        </label>
        <label className="space-y-1">
          <span className="text-xs text-muted-foreground uppercase tracking-wide">Max Open Positions</span>
          <input
            type="number"
            step="1"
            min="1"
            value={form.maxOpenPositions}
            onChange={(e) => change('maxOpenPositions', Number(e.target.value))}
            className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm tabular-nums"
          />
        </label>
        <label className="space-y-1">
          <span className="text-xs text-muted-foreground uppercase tracking-wide">Entry Block (min before close)</span>
          <input
            type="number"
            step="5"
            min="0"
            value={form.entryBlockMinutesBeforeClose}
            onChange={(e) => change('entryBlockMinutesBeforeClose', Number(e.target.value))}
            className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm tabular-nums"
          />
        </label>
        <label className="space-y-1">
          <span className="text-xs text-muted-foreground uppercase tracking-wide">EOD Liq (min before close)</span>
          <input
            type="number"
            step="5"
            min="0"
            value={form.eodLiqMinutesBeforeClose}
            onChange={(e) => change('eodLiqMinutesBeforeClose', Number(e.target.value))}
            className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm tabular-nums"
          />
        </label>
      </div>

      {dirty && (
        <div className="flex gap-2 justify-end">
          <button
            onClick={() => {
              setForm({
                riskPerTrade: config.riskPerTrade,
                maxOpenPositions: config.maxOpenPositions,
                entryBlockMinutesBeforeClose: config.entryBlockMinutesBeforeClose,
                eodLiqMinutesBeforeClose: config.eodLiqMinutesBeforeClose,
              })
              setDirty(false)
            }}
            className="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-accent transition-colors"
          >
            Reset
          </button>
          <button
            onClick={save}
            disabled={update.isPending}
            className="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:opacity-90 transition-opacity disabled:opacity-50"
          >
            {update.isPending ? 'Saving…' : 'Save Config'}
          </button>
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────
// Position row
// ─────────────────────────────────────────────

function FlagDetailRow({ position }: { position: FlagPositionDto }) {
  const slippage = position.actualEntryPrice != null
    ? Number(position.actualEntryPrice) - Number(position.entryPrice)
    : null
  return (
    <tr className="bg-muted/20 border-b border-border">
      <td colSpan={13} className="px-4 py-2">
        <div className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-muted-foreground">
          <span>Strategy: <span className="text-foreground font-medium">{position.strategyName ?? 'bull_flag'}</span></span>
          {position.actualEntryPrice != null && (
            <span>Actual fill: <span className="text-foreground font-medium">${fmt(position.actualEntryPrice, 4)}</span>
              {slippage != null && <span className={slippage < 0 ? 'text-green-500' : slippage > 0 ? 'text-red-500' : ''}> ({slippage > 0 ? '+' : ''}{fmt(slippage, 4)} slippage)</span>}
            </span>
          )}
          {position.highestPriceSeen != null && <span>High seen: <span className="text-foreground font-medium">${fmt(position.highestPriceSeen, 4)}</span></span>}
          {position.lowestPriceSeen != null && <span>Low seen: <span className="text-foreground font-medium">${fmt(position.lowestPriceSeen, 4)}</span></span>}
          {position.maxFavorableExcursion != null && <span>MFE: <span className="text-green-600 dark:text-green-400 font-medium">{fmtMoney(position.maxFavorableExcursion)}</span></span>}
          {position.maxAdverseExcursion != null && <span>MAE: <span className="text-red-500 font-medium">-${fmt(position.maxAdverseExcursion)}</span></span>}
          {position.flagpoleHeight != null && <span>Pole: <span className="text-foreground font-medium">${fmt(position.flagpoleHeight, 2)}</span></span>}
          {position.flagRetracement != null && <span>Retrace: <span className="text-foreground font-medium">{fmt(Number(position.flagRetracement) * 100, 1)}%</span></span>}
        </div>
      </td>
    </tr>
  )
}

function FlagRow({ position }: { position: FlagPositionDto }) {
  const qc = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const [expanded, setExpanded] = useState(false)

  const closePos = useMutation({
    ...closeFlagPositionMutation(),
    onSuccess: () => {
      setConfirming(false)
      qc.invalidateQueries({ queryKey: ['listFlags'] })
    },
    onError: () => setConfirming(false),
  })

  const isCloseable = position.status === 'PENDING' || position.status === 'OPEN'

  return (
    <>
    <tr className="border-b border-border hover:bg-muted/30 transition-colors cursor-pointer" onClick={() => setExpanded(e => !e)}>
      <td className="px-3 py-2 font-mono font-medium text-sm">{position.symbol}</td>
      <td className="px-3 py-2"><StatusBadge status={position.status} /></td>
      <td className="px-3 py-2 tabular-nums text-sm">{fmt(position.entryPrice, 4)}</td>
      <td className="px-3 py-2 tabular-nums text-sm text-muted-foreground">{position.currentPrice != null ? fmt(position.currentPrice, 4) : '—'}</td>
      <td className="px-3 py-2 tabular-nums text-sm text-red-500">{fmt(position.stopLossPrice, 4)}</td>
      <td className="px-3 py-2 tabular-nums text-sm text-green-600 dark:text-green-400">{fmt(position.profitTargetPrice, 4)}</td>
      <td className="px-3 py-2 tabular-nums text-sm">{position.shares}</td>
      <td className="px-3 py-2 tabular-nums text-sm">${fmt(position.riskAmount)}</td>
      <td className="px-3 py-2 tabular-nums text-sm">
        <PnlSpan val={position.unrealizedPnl} placeholder={isCloseable ? '…' : '—'} />
      </td>
      <td className="px-3 py-2 tabular-nums text-sm"><PnlSpan val={position.realizedPnl} /></td>
      <td className="px-3 py-2 text-muted-foreground text-xs">{fmtDate(position.openedAt)}</td>
      <td className="px-3 py-2 text-muted-foreground text-xs">{fmtDate(position.closedAt)}</td>
      <td className="px-3 py-2">
        {isCloseable && (
          confirming ? (
            <div className="flex items-center gap-1">
              <button
                onClick={() => closePos.mutate({ path: { id: position.id } })}
                disabled={closePos.isPending}
                className="px-2 py-1 text-xs rounded bg-red-500 text-white hover:bg-red-600 disabled:opacity-50 transition-colors"
              >
                {closePos.isPending ? '…' : 'Confirm'}
              </button>
              <button
                onClick={() => setConfirming(false)}
                className="px-2 py-1 text-xs rounded border border-border hover:bg-accent transition-colors"
              >
                Cancel
              </button>
            </div>
          ) : (
            <button
              onClick={() => setConfirming(true)}
              className="px-2 py-1 text-xs rounded border border-border text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
            >
              Close
            </button>
          )
        )}
      </td>
    </tr>
    {expanded && <FlagDetailRow position={position} />}
    </>
  )
}

// ─────────────────────────────────────────────
// Main page
// ─────────────────────────────────────────────

export function FlagsPage() {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')

  const { data: pagedData, isLoading, isError } = useQuery({
    ...listFlagsOptions({
      query: {
        status: statusFilter === 'ALL' ? undefined : statusFilter,
        size: 50,
      },
    }),
    refetchInterval: 5_000,
  })

  const { data: config, isLoading: configLoading } = useQuery({
    ...getFlagConfigOptions(),
    refetchInterval: 30_000,
  })

  const positions = pagedData?.content ?? []

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Bull Flag Positions</h1>
        {pagedData && (
          <span className="text-xs text-muted-foreground">
            {pagedData.totalElements} total
          </span>
        )}
      </div>

      {/* Config panel */}
      {configLoading && <p className="text-muted-foreground text-sm">Loading config…</p>}
      {config && <ConfigPanel config={config} />}

      {/* Scanner status */}
      <ScannerStatusPanel />

      {/* Status filter */}
      <div className="flex flex-wrap gap-1">
        {STATUS_FILTERS.map((s) => (
          <button
            key={s}
            onClick={() => setStatusFilter(s)}
            className={`px-3 py-1 text-xs rounded-full transition-colors ${
              statusFilter === s
                ? 'bg-primary text-primary-foreground'
                : 'border border-border text-muted-foreground hover:text-foreground hover:bg-accent'
            }`}
          >
            {s}
          </button>
        ))}
      </div>

      {/* Positions table */}
      {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
      {isError && <p className="text-destructive text-sm">Failed to load positions.</p>}

      {!isLoading && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
                <th className="px-3 py-2 text-left">Symbol</th>
                <th className="px-3 py-2 text-left">Status</th>
                <th className="px-3 py-2 text-left">Entry</th>
                <th className="px-3 py-2 text-left">Current</th>
                <th className="px-3 py-2 text-left">Stop</th>
                <th className="px-3 py-2 text-left">Target</th>
                <th className="px-3 py-2 text-left">Shares</th>
                <th className="px-3 py-2 text-left">Risk</th>
                <th className="px-3 py-2 text-left">Unreal. P&amp;L</th>
                <th className="px-3 py-2 text-left">Real. P&amp;L</th>
                <th className="px-3 py-2 text-left">Opened</th>
                <th className="px-3 py-2 text-left">Closed</th>
                <th className="px-3 py-2 text-left"></th>
              </tr>
            </thead>
            <tbody>
              {positions.length === 0 ? (
                <tr>
                  <td colSpan={13} className="px-3 py-8 text-center text-muted-foreground text-sm">
                    No positions found.
                  </td>
                </tr>
              ) : (
                positions.map((p) => <FlagRow key={p.id} position={p} />)
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
