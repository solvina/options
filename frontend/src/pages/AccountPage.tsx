import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { cancelOrder, closePosition } from '../generated/account/sdk.gen'
import { getAccountOverviewOptions } from '../generated/account/@tanstack/react-query.gen'
import type { AccountPositionDto, OpenOrderDto, OpenPositionDto } from '../generated/account/types.gen'
import { useSortable, sorted, SortTh } from '../lib/sort'

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <p className="text-xs text-muted-foreground uppercase tracking-wide mb-1">{label}</p>
      <p className="text-lg font-semibold tabular-nums">{value}</p>
    </div>
  )
}

function fmt(val: number | null | undefined, prefix = '$') {
  if (val == null) return '—'
  return `${prefix}${val.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function fmtPnl(val: number | null | undefined) {
  if (val == null) return '—'
  const sign = val >= 0 ? '+' : ''
  return `${sign}$${val.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function dte(expiryDate: string): number {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.round((new Date(expiryDate).getTime() - today.getTime()) / 86_400_000)
}

function EnginePositionsTable({ positions }: { positions: OpenPositionDto[] }) {
  const { sort, toggle } = useSortable('dte', 'asc')

  if (positions.length === 0)
    return <p className="text-muted-foreground text-sm">No engine-tracked positions.</p>

  const sortedPositions = sorted(positions, sort, (p, k) => {
    if (k === 'dte') return p.expiryDate ? dte(p.expiryDate.toString()) : null
    if (k === 'pnl') return p.unrealizedPnL != null ? Number(p.unrealizedPnL) : null
    if (k === 'creditPerShare') return p.creditPerShare ?? null
    if (k === 'cushion') return p.distanceToShortStrikePct != null ? Number(p.distanceToShortStrikePct) : null
    return (p as Record<string, unknown>)[k]
  })

  const thClass = 'px-3 py-2 text-left'

  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
            <SortTh label="Symbol" col="symbol" sort={sort} onSort={toggle} className={thClass} />
            <th className={thClass}>Sold / Bought</th>
            <th className={thClass}>Expiry</th>
            <SortTh label="DTE" col="dte" sort={sort} onSort={toggle} className={thClass} />
            <SortTh label="Credit/sh" col="creditPerShare" sort={sort} onSort={toggle} className={thClass} />
            <th className={thClass}>Max Risk</th>
            <th className={thClass}>Qty</th>
            <th className={thClass}>Underlying</th>
            <SortTh label="Cushion" col="cushion" sort={sort} onSort={toggle} className={thClass} />
            <th className={thClass}>IV Rank</th>
            <SortTh label="P&L" col="pnl" sort={sort} onSort={toggle} className={thClass} />
          </tr>
        </thead>
        <tbody>
          {sortedPositions.map((p) => {
            const pnl = p.unrealizedPnL != null ? Number(p.unrealizedPnL) : null
            const pnlColor = pnl == null ? '' : pnl >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
            return (
            <tr key={p.id} className="border-b border-border hover:bg-muted/40 transition-colors">
              <td className="px-3 py-2 font-medium">{p.symbol}</td>
              <td className="px-3 py-2 tabular-nums">
                {p.soldStrike?.toFixed(2)} / {p.boughtStrike?.toFixed(2)}
              </td>
              <td className="px-3 py-2 tabular-nums">{p.expiryDate?.toString() ?? '—'}</td>
              <td className="px-3 py-2 tabular-nums">{p.expiryDate ? dte(p.expiryDate.toString()) : '—'}</td>
              <td className="px-3 py-2 tabular-nums">{p.creditPerShare?.toFixed(4) ?? '—'}</td>
              <td className="px-3 py-2 tabular-nums">{fmt(p.maxRiskTotal)}</td>
              <td className="px-3 py-2">{p.quantity}</td>
              <td className="px-3 py-2 tabular-nums">
                {p.underlyingPriceNow != null ? p.underlyingPriceNow.toFixed(2) : '—'}
                {p.underlyingPriceNow != null && p.soldStrike != null && (
                  <span className="text-muted-foreground text-xs"> / {p.soldStrike.toFixed(0)}</span>
                )}
              </td>
              <td className={`px-3 py-2 tabular-nums ${
                p.distanceToShortStrikePct == null
                  ? ''
                  : p.distanceToShortStrikePct >= 5
                    ? 'text-green-600 dark:text-green-400'
                    : p.distanceToShortStrikePct >= 0
                      ? 'text-amber-600 dark:text-amber-400'
                      : 'text-red-600 dark:text-red-400'
              }`}>
                {p.distanceToShortStrikePct != null ? `${p.distanceToShortStrikePct.toFixed(1)}%` : '—'}
              </td>
              <td className="px-3 py-2 tabular-nums">
                {p.ivRankAtEntry != null ? `${p.ivRankAtEntry.toFixed(1)}%` : '—'}
              </td>
              <td className={`px-3 py-2 tabular-nums font-medium ${pnlColor}`}>
                {pnl != null ? fmtPnl(pnl) : '—'}
              </td>
            </tr>
          )
          })}
        </tbody>
      </table>
    </div>
  )
}

function OpenOrdersTable({ orders, onCancel }: { orders: OpenOrderDto[]; onCancel: (orderId: number) => void }) {
  const { sort, toggle } = useSortable('orderId', 'asc')

  if (orders.length === 0) return <p className="text-muted-foreground text-sm">No open orders.</p>

  const sortedOrders = sorted(orders, sort, (o, k) => {
    if (k === 'limitPrice') return o.limitPrice != null ? Number(o.limitPrice) : null
    return (o as Record<string, unknown>)[k]
  })

  const thClass = 'px-3 py-2 text-left'

  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
            <SortTh label="Order ID" col="orderId" sort={sort} onSort={toggle} className={thClass} />
            <SortTh label="Symbol" col="symbol" sort={sort} onSort={toggle} className={thClass} />
            <th className={thClass}>Action</th>
            <th className={thClass}>Type</th>
            <SortTh label="Limit Price" col="limitPrice" sort={sort} onSort={toggle} className={thClass} />
            <th className={thClass}>Status</th>
            <th className={thClass}></th>
          </tr>
        </thead>
        <tbody>
          {sortedOrders.map((o) => (
            <tr key={o.orderId} className="border-b border-border hover:bg-muted/40 transition-colors">
              <td className="px-3 py-2 tabular-nums text-muted-foreground">{o.orderId}</td>
              <td className="px-3 py-2 font-medium">{o.symbol}</td>
              <td className="px-3 py-2">{o.action}</td>
              <td className="px-3 py-2 text-muted-foreground">{o.orderType}</td>
              <td className="px-3 py-2 tabular-nums">{o.limitPrice != null ? fmt(Number(o.limitPrice)) : '—'}</td>
              <td className="px-3 py-2">{o.status}</td>
              <td className="px-3 py-2">
                <button
                  onClick={() => onCancel(o.orderId)}
                  className="text-xs px-2 py-1 rounded border border-destructive text-destructive hover:bg-destructive hover:text-destructive-foreground transition-colors"
                >
                  Cancel
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function IbkrPositionsTable({
  positions,
  onKill,
  showReason = false,
}: {
  positions: AccountPositionDto[]
  onKill: (conId: number, quantity: number) => void
  showReason?: boolean
}) {
  const [killing, setKilling] = useState<Set<number>>(new Set())
  const { sort, toggle } = useSortable('symbol', 'asc')

  if (positions.length === 0)
    return <p className="text-muted-foreground text-sm">None.</p>

  const sortedPositions = sorted(positions, sort, (p, k) => {
    if (k === 'pnl') return p.unrealizedPnL != null ? Number(p.unrealizedPnL) : null
    if (k === 'expiry') return p.expiry?.toString() ?? null
    return (p as Record<string, unknown>)[k]
  })

  function handleKill(conId: number, quantity: number) {
    setKilling(prev => new Set(prev).add(conId))
    onKill(conId, quantity)
  }

  const thClass = 'px-3 py-2 text-left'

  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
            <SortTh label="Symbol" col="symbol" sort={sort} onSort={toggle} className={thClass} />
            <th className={thClass}>Type</th>
            <th className={thClass}>Right</th>
            <SortTh label="Strike" col="strike" sort={sort} onSort={toggle} className={thClass} />
            <SortTh label="Expiry" col="expiry" sort={sort} onSort={toggle} className={thClass} />
            <th className={thClass}>Qty</th>
            <th className={thClass}>Avg Cost</th>
            <SortTh label="P&L" col="pnl" sort={sort} onSort={toggle} className={thClass} />
            <th className={thClass}>CCY</th>
            {showReason && <th className={thClass}>Reason</th>}
            <th className={thClass}></th>
          </tr>
        </thead>
        <tbody>
          {sortedPositions.map((p, i) => {
            const pnl = p.unrealizedPnL != null ? Number(p.unrealizedPnL) : null
            const pnlColor = pnl == null ? '' : pnl >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
            return (
            <tr key={i} className="border-b border-border hover:bg-muted/40 transition-colors">
              <td className="px-3 py-2 font-medium">{p.symbol}</td>
              <td className="px-3 py-2 text-muted-foreground">{p.secType}</td>
              <td className="px-3 py-2">{p.optionRight ?? '—'}</td>
              <td className="px-3 py-2 tabular-nums">{p.strike?.toFixed(2) ?? '—'}</td>
              <td className="px-3 py-2 tabular-nums">{p.expiry?.toString() ?? '—'}</td>
              <td className="px-3 py-2 tabular-nums">{p.quantity?.toFixed(0)}</td>
              <td className="px-3 py-2 tabular-nums">{p.avgCost?.toFixed(2) ?? '—'}</td>
              <td className={`px-3 py-2 tabular-nums font-medium ${pnlColor}`}>
                {pnl != null ? fmtPnl(pnl) : '—'}
              </td>
              <td className="px-3 py-2 text-muted-foreground">{p.currency}</td>
              {showReason && (
                <td className="px-3 py-2 text-xs text-amber-600 dark:text-amber-400">{p.orphanReason ?? '—'}</td>
              )}
              <td className="px-3 py-2">
                {p.conId !== 0 && (
                  <button
                    onClick={() => handleKill(p.conId, Number(p.quantity))}
                    disabled={killing.has(p.conId)}
                    className="text-xs px-2 py-1 rounded border border-destructive text-destructive hover:bg-destructive hover:text-destructive-foreground transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    {killing.has(p.conId) ? '…' : 'Kill'}
                  </button>
                )}
              </td>
            </tr>
          )
          })}
        </tbody>
      </table>
    </div>
  )
}

// One held option leg shown inside its spread group.
function SpreadLegRow({ p }: { p: AccountPositionDto }) {
  const pnl = p.unrealizedPnL != null ? Number(p.unrealizedPnL) : null
  const pnlColor = pnl == null ? '' : pnl >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
  const roleColor = p.legRole === 'SHORT' ? 'text-red-600 dark:text-red-400' : 'text-blue-600 dark:text-blue-400'
  return (
    <tr className="border-b border-border last:border-0 hover:bg-muted/40 transition-colors">
      <td className={`px-3 py-2 font-medium ${roleColor}`}>{p.legRole ?? '—'}</td>
      <td className="px-3 py-2">{p.optionRight ?? '—'}</td>
      <td className="px-3 py-2 tabular-nums">{p.strike?.toFixed(2) ?? '—'}</td>
      <td className="px-3 py-2 tabular-nums">{p.expiry?.toString() ?? '—'}</td>
      <td className="px-3 py-2 tabular-nums">{p.quantity?.toFixed(0)}</td>
      <td className="px-3 py-2 tabular-nums">{p.avgCost?.toFixed(2) ?? '—'}</td>
      <td className={`px-3 py-2 tabular-nums font-medium ${pnlColor}`}>{pnl != null ? fmtPnl(pnl) : '—'}</td>
    </tr>
  )
}

// Tracked spread legs, grouped so a short + long leg render together as the pair they are.
function TrackedSpreadGroups({ positions }: { positions: AccountPositionDto[] }) {
  if (positions.length === 0)
    return <p className="text-muted-foreground text-sm">No spread legs held at IBKR.</p>

  const groups = new Map<string, { label: string; legs: AccountPositionDto[] }>()
  for (const p of positions) {
    const key = p.spreadId ?? p.spreadLabel ?? 'unknown'
    const g = groups.get(key) ?? { label: p.spreadLabel ?? p.symbol, legs: [] }
    g.legs.push(p)
    groups.set(key, g)
  }
  // SHORT leg first within each pair.
  for (const g of groups.values())
    g.legs.sort((a, b) => (a.legRole === 'SHORT' ? -1 : 1) - (b.legRole === 'SHORT' ? -1 : 1))

  const thClass = 'px-3 py-2 text-left'

  return (
    <div className="space-y-3">
      {[...groups.entries()].map(([key, g]) => {
        const groupPnl = g.legs.reduce((sum, l) => sum + (l.unrealizedPnL != null ? Number(l.unrealizedPnL) : 0), 0)
        const pnlColor = groupPnl >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
        const incomplete = g.legs.length !== 2
        return (
          <div key={key} className="rounded-lg border border-border overflow-hidden">
            <div className="flex items-center justify-between bg-muted/50 px-3 py-2">
              <span className="font-medium text-sm">
                {g.label}
                {incomplete && (
                  <span className="ml-2 text-xs text-amber-600 dark:text-amber-400">
                    ({g.legs.length} of 2 legs held)
                  </span>
                )}
              </span>
              <span className={`text-sm font-medium tabular-nums ${pnlColor}`}>{fmtPnl(groupPnl)}</span>
            </div>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-muted-foreground text-xs uppercase tracking-wide">
                  <th className={thClass}>Leg</th>
                  <th className={thClass}>Right</th>
                  <th className={thClass}>Strike</th>
                  <th className={thClass}>Expiry</th>
                  <th className={thClass}>Qty</th>
                  <th className={thClass}>Avg Cost</th>
                  <th className={thClass}>P&L</th>
                </tr>
              </thead>
              <tbody>
                {g.legs.map((l) => (
                  <SpreadLegRow key={l.conId} p={l} />
                ))}
              </tbody>
            </table>
          </div>
        )
      })}
    </div>
  )
}

export function AccountPage() {
  const { data, isLoading, isError, refetch } = useQuery({
    ...getAccountOverviewOptions(),
    refetchInterval: 30_000,
  })

  const { mutate: doCancel } = useMutation({
    mutationFn: (orderId: number) => cancelOrder({ path: { orderId } }),
    onSuccess: () => { refetch() },
  })

  const { mutate: doKill } = useMutation({
    mutationFn: ({ conId, quantity }: { conId: number; quantity: number }) =>
      closePosition({ body: { conId, quantity } }),
    onSuccess: () => { setTimeout(() => refetch(), 2000) },
  })

  const pnlColor =
    data?.unrealizedPnL == null
      ? ''
      : data.unrealizedPnL >= 0
        ? 'text-green-600 dark:text-green-400'
        : 'text-red-600 dark:text-red-400'

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">Account</h1>

      {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
      {isError && <p className="text-destructive text-sm">Failed to load account data.</p>}

      {data && (
        <>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <StatCard label="Net Liquidation" value={fmt(data.totalCapital)} />
            <StatCard label="Available Funds" value={fmt(data.availableFunds)} />
            <StatCard
              label="Unrealized P&L"
              value={fmtPnl(data.unrealizedPnL)}
            />
            <StatCard label="Open Spreads" value={String(data.openPositionCount ?? 0)} />
          </div>
          {data.unrealizedPnL != null && (
            <p className={`-mt-6 text-sm font-medium ${pnlColor}`}>
              {data.unrealizedPnL >= 0 ? 'Profit' : 'Loss'} on open positions
            </p>
          )}

          <section>
            <h2 className="text-base font-semibold mb-3">
              Engine Positions
              <span className="ml-2 text-xs text-muted-foreground font-normal">
                ({data.openPositionCount} spread{data.openPositionCount !== 1 ? 's' : ''})
              </span>
            </h2>
            <EnginePositionsTable positions={data.openPositions ?? []} />
          </section>

          {(() => {
            const all = data.accountPositions ?? []
            const orphans = all.filter((p) => p.orphan)
            const trackedLegs = all.filter((p) => !p.orphan && p.spreadId != null)
            const otherManaged = all.filter((p) => !p.orphan && p.spreadId == null)
            return (
              <>
                <section>
                  <h2 className="text-base font-semibold mb-3">
                    Tracked Spread Legs
                    <span className="ml-2 text-xs text-muted-foreground font-normal">
                      ({trackedLegs.length} leg{trackedLegs.length !== 1 ? 's' : ''} across{' '}
                      {new Set(trackedLegs.map((p) => p.spreadId)).size} spread
                      {new Set(trackedLegs.map((p) => p.spreadId)).size !== 1 ? 's' : ''})
                    </span>
                  </h2>
                  <TrackedSpreadGroups positions={trackedLegs} />
                </section>

                {otherManaged.length > 0 && (
                  <section>
                    <h2 className="text-base font-semibold mb-3">
                      Other Managed
                      <span className="ml-2 text-xs text-muted-foreground font-normal">
                        ({otherManaged.length}) — e.g. flag stock
                      </span>
                    </h2>
                    <IbkrPositionsTable
                      positions={otherManaged}
                      onKill={(conId, quantity) => doKill({ conId, quantity })}
                    />
                  </section>
                )}

                <section>
                  <h2 className="text-base font-semibold mb-3">
                    Orphans / Manual
                    <span className="ml-2 text-xs text-muted-foreground font-normal">
                      ({orphans.length}) — held positions the engine is not managing
                    </span>
                  </h2>
                  {orphans.length > 0 && (
                    <p className="text-xs text-amber-600 dark:text-amber-400 mb-2">
                      These have no automated exit (no TP/SL/DTE). Close manually if unintended.
                    </p>
                  )}
                  <IbkrPositionsTable
                    positions={orphans}
                    onKill={(conId, quantity) => doKill({ conId, quantity })}
                    showReason
                  />
                </section>
              </>
            )
          })()}

          <section>
            <h2 className="text-base font-semibold mb-3">
              Open IBKR Orders
              <span className="ml-2 text-xs text-muted-foreground font-normal">
                ({data.openOrderCount ?? 0})
              </span>
            </h2>
            <OpenOrdersTable orders={data.openOrders ?? []} onCancel={doCancel} />
          </section>
        </>
      )}
    </div>
  )
}
