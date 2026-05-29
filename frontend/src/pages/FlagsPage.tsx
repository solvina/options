import { useState } from 'react'
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

function PnlSpan({ val }: { val: number | null | undefined }) {
  const n = val == null ? null : Number(val)
  const cls = n == null ? 'text-muted-foreground' : n > 0 ? 'text-green-600 dark:text-green-400' : n < 0 ? 'text-red-500 dark:text-red-400' : ''
  return <span className={`tabular-nums font-medium ${cls}`}>{fmtMoney(n)}</span>
}

// ─────────────────────────────────────────────
// Status filter tabs
// ─────────────────────────────────────────────

const STATUS_FILTERS = ['ALL', 'PENDING', 'OPEN', 'CLOSED_PROFIT', 'CLOSED_STOP', 'CLOSED_EOD', 'CLOSED_MANUAL'] as const
type StatusFilter = (typeof STATUS_FILTERS)[number]

// ─────────────────────────────────────────────
// Config panel
// ─────────────────────────────────────────────

function ConfigPanel({ config }: { config: FlagTradingConfigDto }) {
  const qc = useQueryClient()
  const [form, setForm] = useState<FlagTradingConfigDto>(config)
  const [dirty, setDirty] = useState(false)

  const update = useMutation({
    ...updateFlagConfigMutation(),
    onSuccess: () => {
      setDirty(false)
      qc.invalidateQueries({ queryKey: ['getFlagConfig'] })
    },
  })

  const pause = useMutation({
    ...pauseFlagScannerMutation(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['getFlagConfig'] }),
  })

  const resume = useMutation({
    ...resumeFlagScannerMutation(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['getFlagConfig'] }),
  })

  function change<K extends keyof FlagTradingConfigDto>(key: K, value: FlagTradingConfigDto[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
    setDirty(true)
  }

  function save() {
    update.mutate({ body: form })
  }

  const isEnabled = config.enabled

  return (
    <div className="rounded-lg border border-border bg-card p-5 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold">Scanner Config</h2>
        {/* Prominent enable/disable toggle */}
        <div className="flex items-center gap-3">
          <span className={`text-sm font-medium ${isEnabled ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'}`}>
            {isEnabled ? '● Active' : '○ Paused'}
          </span>
          {isEnabled ? (
            <button
              onClick={() => pause.mutate({})}
              disabled={pause.isPending}
              className="px-3 py-1.5 text-sm rounded-md bg-red-500/10 text-red-600 hover:bg-red-500/20 dark:text-red-400 transition-colors disabled:opacity-50"
            >
              {pause.isPending ? 'Pausing…' : 'Pause Scanner'}
            </button>
          ) : (
            <button
              onClick={() => resume.mutate({})}
              disabled={resume.isPending}
              className="px-3 py-1.5 text-sm rounded-md bg-green-500/10 text-green-600 hover:bg-green-500/20 dark:text-green-400 transition-colors disabled:opacity-50"
            >
              {resume.isPending ? 'Resuming…' : 'Resume Scanner'}
            </button>
          )}
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
            onClick={() => { setForm(config); setDirty(false) }}
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

function FlagRow({ position }: { position: FlagPositionDto }) {
  const qc = useQueryClient()
  const [confirming, setConfirming] = useState(false)

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
    <tr className="border-b border-border hover:bg-muted/30 transition-colors">
      <td className="px-3 py-2 font-mono font-medium text-sm">{position.symbol}</td>
      <td className="px-3 py-2"><StatusBadge status={position.status} /></td>
      <td className="px-3 py-2 tabular-nums text-sm">{fmt(position.entryPrice, 4)}</td>
      <td className="px-3 py-2 tabular-nums text-sm text-red-500">{fmt(position.stopLossPrice, 4)}</td>
      <td className="px-3 py-2 tabular-nums text-sm text-green-600 dark:text-green-400">{fmt(position.profitTargetPrice, 4)}</td>
      <td className="px-3 py-2 tabular-nums text-sm">{position.shares}</td>
      <td className="px-3 py-2 tabular-nums text-sm">${fmt(position.riskAmount)}</td>
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
                <th className="px-3 py-2 text-left">Stop</th>
                <th className="px-3 py-2 text-left">Target</th>
                <th className="px-3 py-2 text-left">Shares</th>
                <th className="px-3 py-2 text-left">Risk</th>
                <th className="px-3 py-2 text-left">P&amp;L</th>
                <th className="px-3 py-2 text-left">Opened</th>
                <th className="px-3 py-2 text-left">Closed</th>
                <th className="px-3 py-2 text-left"></th>
              </tr>
            </thead>
            <tbody>
              {positions.length === 0 ? (
                <tr>
                  <td colSpan={11} className="px-3 py-8 text-center text-muted-foreground text-sm">
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
