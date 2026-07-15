import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  listSpreadsOptions,
  softCloseSpreadMutation,
  forceCloseSpreadMutation,
  refreshSpreadPnlMutation,
} from '../generated/spreads/@tanstack/react-query.gen'
import type { SpreadDto, PagedSpreadsDto } from '../generated/spreads/types.gen'
import { SpreadStatusBadge } from '../components/spreads/SpreadStatusBadge'
import { usePersistentSortable, sorted, SortTh } from '../lib/sort'

const STATUS_FILTERS = ['ALL', 'OPEN', 'CLOSED_PROFIT', 'CLOSED_STOP', 'CLOSED_TIME', 'CLOSED_MANUAL'] as const
type StatusFilter = (typeof STATUS_FILTERS)[number]

function dte(expiryDate: string): number {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const expiry = new Date(expiryDate)
  return Math.round((expiry.getTime() - today.getTime()) / 86_400_000)
}

function fmt(val: number | null | undefined, decimals = 2) {
  return val == null ? '—' : val.toFixed(decimals)
}

function PnlCell({ pnl, credit, quantity }: { pnl: number | null | undefined; credit: number | null | undefined; quantity?: number | null }) {
  if (pnl == null || credit == null || credit === 0) return <td className="px-3 py-2 text-muted-foreground tabular-nums">—</td>
  const pct = (pnl / credit) * 100
  // Actual position P&L in dollars: per-share P&L × contract multiplier (100) × contracts.
  const dollars = pnl * 100 * (quantity ?? 1)
  const color = pnl >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'
  return (
    <td
      className={`px-3 py-2 tabular-nums font-medium ${color}`}
      title={`${pnl >= 0 ? '+' : ''}${pnl.toFixed(4)} per share`}
    >
      {dollars >= 0 ? '+' : '−'}${Math.abs(dollars).toFixed(2)} ({pct >= 0 ? '+' : ''}{pct.toFixed(0)}%)
    </td>
  )
}

function SpreadRow({ spread }: { spread: SpreadDto }) {
  const qc = useQueryClient()
  const [confirming, setConfirming] = useState(false)

  const softClose = useMutation({
    ...softCloseSpreadMutation(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['listSpreads'] }),
  })
  const forceClose = useMutation({
    ...forceCloseSpreadMutation(),
    onSuccess: () => {
      setConfirming(false)
      qc.invalidateQueries({ queryKey: ['listSpreads'] })
    },
  })
  const refreshPnl = useMutation({
    ...refreshSpreadPnlMutation(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['listSpreads'] }),
  })

  const daysToExpiry = spread.expiryDate ? dte(spread.expiryDate.toString()) : null

  return (
    <tr className="border-b border-border hover:bg-muted/40 transition-colors">
      <td className="px-3 py-2 font-medium">{spread.symbol}</td>
      <td className="px-3 py-2 tabular-nums">{fmt(spread.soldStrike)}</td>
      <td className="px-3 py-2 tabular-nums">{fmt(spread.boughtStrike)}</td>
      <td className="px-3 py-2 tabular-nums">{spread.expiryDate?.toString() ?? '—'}</td>
      <td className="px-3 py-2 tabular-nums">{daysToExpiry ?? '—'}</td>
      <td className="px-3 py-2 tabular-nums">{fmt(spread.creditPerShare, 4)}</td>
      <td className="px-3 py-2 tabular-nums">{fmt(spread.maxRiskPerShare, 4)}</td>
      <td className="px-3 py-2">{spread.quantity}</td>
      <td className="px-3 py-2">
        <SpreadStatusBadge status={spread.status ?? ''} />
      </td>
      <PnlCell pnl={spread.currentPnl != null ? Number(spread.currentPnl) : null} credit={Number(spread.creditPerShare)} quantity={spread.quantity} />
      <td className="px-1 py-2">
        {spread.status === 'OPEN' && (
          <button
            onClick={() => refreshPnl.mutate({ path: { id: spread.id! } })}
            disabled={refreshPnl.isPending}
            title="Refresh P&L"
            className="text-muted-foreground hover:text-foreground disabled:opacity-40 text-xs px-1"
          >
            {refreshPnl.isPending ? '…' : '↺'}
          </button>
        )}
      </td>
      <td className="px-3 py-2 text-muted-foreground text-xs">
        {spread.openedAt ? new Date(spread.openedAt).toLocaleDateString() : '—'}
      </td>
      <td className="px-3 py-2">
        {spread.status === 'OPEN' && (
          <div className="flex gap-1.5">
            <button
              onClick={() => softClose.mutate({ path: { id: spread.id! } })}
              disabled={softClose.isPending || forceClose.isPending}
              className="px-2 py-1 text-xs rounded bg-secondary text-secondary-foreground hover:bg-secondary/80 disabled:opacity-50"
            >
              {softClose.isPending ? '…' : 'Close'}
            </button>
            {confirming ? (
              <>
                <button
                  onClick={() => forceClose.mutate({ path: { id: spread.id! } })}
                  disabled={forceClose.isPending}
                  className="px-2 py-1 text-xs rounded bg-destructive text-white hover:bg-destructive/80 disabled:opacity-50"
                >
                  {forceClose.isPending ? '…' : 'Confirm'}
                </button>
                <button
                  onClick={() => setConfirming(false)}
                  className="px-2 py-1 text-xs rounded bg-muted text-muted-foreground hover:bg-muted/80"
                >
                  Cancel
                </button>
              </>
            ) : (
              <button
                onClick={() => setConfirming(true)}
                disabled={softClose.isPending || forceClose.isPending}
                className="px-2 py-1 text-xs rounded bg-destructive/20 text-destructive hover:bg-destructive/30 disabled:opacity-50"
              >
                Force
              </button>
            )}
          </div>
        )}
        {spread.closeReason && (
          <span className="text-xs text-muted-foreground">{spread.closeReason}</span>
        )}
      </td>
    </tr>
  )
}

export function SpreadsPage() {
  const [filter, setFilter] = useState<StatusFilter>('ALL')
  const [page, setPage] = useState(0)
  const { sort, toggle } = usePersistentSortable('spreads', 'openedAt', 'desc')

  const { data, isLoading, isError } = useQuery({
    ...listSpreadsOptions({
      query: { ...(filter !== 'ALL' ? { status: filter } : {}), page, size: 25 },
    }),
    refetchInterval: 30_000,
  })

  const paged = data as PagedSpreadsDto | undefined
  const spreads = paged?.content ?? []
  const totalPages = paged?.totalPages ?? 0
  const totalElements = paged?.totalElements ?? 0

  const sortedSpreads = sorted(spreads as SpreadDto[], sort, (s, k) => {
    if (k === 'dte') return s.expiryDate ? dte(s.expiryDate.toString()) : null
    if (k === 'pnl') return s.currentPnl != null ? Number(s.currentPnl) : null
    if (k === 'openedAt') return s.openedAt ? new Date(s.openedAt).getTime() : null
    return (s as Record<string, unknown>)[k]
  })

  function changeFilter(f: StatusFilter) {
    setFilter(f)
    setPage(0)
  }

  const thClass = 'px-3 py-2 text-left'

  return (
    <div>
      <h1 className="text-xl font-semibold mb-4">Spreads</h1>

      <div className="flex gap-1 mb-4 flex-wrap">
        {STATUS_FILTERS.map((s) => (
          <button
            key={s}
            onClick={() => changeFilter(s)}
            className={`px-3 py-1 rounded text-xs font-medium transition-colors ${
              filter === s
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground hover:text-foreground'
            }`}
          >
            {s === 'ALL' ? 'All' : s.replace('CLOSED_', '').replace('_', ' ')}
          </button>
        ))}
      </div>

      {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
      {isError && <p className="text-destructive text-sm">Failed to load spreads.</p>}

      {!isLoading && !isError && spreads.length === 0 && (
        <p className="text-muted-foreground text-sm">No spreads found.</p>
      )}

      {spreads.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
                  <SortTh label="Symbol" col="symbol" sort={sort} onSort={toggle} className={thClass} />
                  <th className={thClass}>Sold</th>
                  <th className={thClass}>Bought</th>
                  <SortTh label="Expiry" col="expiryDate" sort={sort} onSort={toggle} className={thClass} />
                  <SortTh label="DTE" col="dte" sort={sort} onSort={toggle} className={thClass} />
                  <SortTh label="Credit" col="creditPerShare" sort={sort} onSort={toggle} className={thClass} />
                  <th className={thClass}>Max Risk</th>
                  <th className={thClass}>Qty</th>
                  <th className={thClass}>Status</th>
                  <SortTh label="P&L" col="pnl" sort={sort} onSort={toggle} className={thClass} />
                  <th className="px-1 py-2"></th>
                  <SortTh label="Opened" col="openedAt" sort={sort} onSort={toggle} className={thClass} />
                  <th className={thClass}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {sortedSpreads.map((spread) => (
                  <SpreadRow key={spread.id} spread={spread} />
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex items-center gap-2 mt-3 text-sm">
            <button
              onClick={() => setPage((p) => p - 1)}
              disabled={page === 0}
              className="px-3 py-1 rounded bg-muted text-muted-foreground hover:text-foreground disabled:opacity-40"
            >
              &larr;
            </button>
            <span className="text-muted-foreground">
              {page + 1} / {totalPages}
            </span>
            <button
              onClick={() => setPage((p) => p + 1)}
              disabled={page >= totalPages - 1}
              className="px-3 py-1 rounded bg-muted text-muted-foreground hover:text-foreground disabled:opacity-40"
            >
              &rarr;
            </button>
            <span className="text-muted-foreground text-xs ml-1">
              {totalElements} total
            </span>
          </div>
        </>
      )}
    </div>
  )
}
