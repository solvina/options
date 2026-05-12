import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  listSpreadsOptions,
  softCloseSpreadMutation,
  forceCloseSpreadMutation,
} from '../generated/spreads/@tanstack/react-query.gen'
import type { SpreadDto } from '../generated/spreads/types.gen'
import { SpreadStatusBadge } from '../components/spreads/SpreadStatusBadge'

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

  const { data, isLoading, isError } = useQuery({
    ...listSpreadsOptions({ query: filter === 'ALL' ? {} : { status: filter } }),
    refetchInterval: 30_000,
  })

  const spreads = (data ?? []) as SpreadDto[]

  return (
    <div>
      <h1 className="text-xl font-semibold mb-4">Spreads</h1>

      <div className="flex gap-1 mb-4 flex-wrap">
        {STATUS_FILTERS.map((s) => (
          <button
            key={s}
            onClick={() => setFilter(s)}
            className={`px-3 py-1 rounded text-xs font-medium transition-colors ${
              filter === s
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground hover:text-foreground'
            }`}
          >
            {s === 'ALL' ? 'All' : s.replace('CLOSED_', '').replace('_', ' ')}
            {s !== 'ALL' && data != null && (
              <span className="ml-1 opacity-60">
                ({spreads.filter((sp) => sp.status === s).length})
              </span>
            )}
          </button>
        ))}
      </div>

      {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
      {isError && <p className="text-destructive text-sm">Failed to load spreads.</p>}

      {!isLoading && !isError && spreads.length === 0 && (
        <p className="text-muted-foreground text-sm">No spreads found.</p>
      )}

      {spreads.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
                <th className="px-3 py-2 text-left">Symbol</th>
                <th className="px-3 py-2 text-left">Sold</th>
                <th className="px-3 py-2 text-left">Bought</th>
                <th className="px-3 py-2 text-left">Expiry</th>
                <th className="px-3 py-2 text-left">DTE</th>
                <th className="px-3 py-2 text-left">Credit</th>
                <th className="px-3 py-2 text-left">Max Risk</th>
                <th className="px-3 py-2 text-left">Qty</th>
                <th className="px-3 py-2 text-left">Status</th>
                <th className="px-3 py-2 text-left">Opened</th>
                <th className="px-3 py-2 text-left">Actions</th>
              </tr>
            </thead>
            <tbody>
              {spreads.map((spread) => (
                <SpreadRow key={spread.id} spread={spread} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
