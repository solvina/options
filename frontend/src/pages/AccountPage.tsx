import { useQuery } from '@tanstack/react-query'
import { getAccountOverviewOptions } from '../generated/account/@tanstack/react-query.gen'
import type { AccountPositionDto, OpenPositionDto } from '../generated/account/types.gen'

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
  if (positions.length === 0)
    return <p className="text-muted-foreground text-sm">No engine-tracked positions.</p>

  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
            <th className="px-3 py-2 text-left">Symbol</th>
            <th className="px-3 py-2 text-left">Sold / Bought</th>
            <th className="px-3 py-2 text-left">Expiry</th>
            <th className="px-3 py-2 text-left">DTE</th>
            <th className="px-3 py-2 text-left">Credit/sh</th>
            <th className="px-3 py-2 text-left">Max Risk</th>
            <th className="px-3 py-2 text-left">Qty</th>
            <th className="px-3 py-2 text-left">IV Rank</th>
          </tr>
        </thead>
        <tbody>
          {positions.map((p) => (
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
                {p.ivRankAtEntry != null ? `${p.ivRankAtEntry.toFixed(1)}%` : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function IbkrPositionsTable({ positions }: { positions: AccountPositionDto[] }) {
  if (positions.length === 0)
    return <p className="text-muted-foreground text-sm">No IBKR positions.</p>

  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
            <th className="px-3 py-2 text-left">Symbol</th>
            <th className="px-3 py-2 text-left">Type</th>
            <th className="px-3 py-2 text-left">Right</th>
            <th className="px-3 py-2 text-left">Strike</th>
            <th className="px-3 py-2 text-left">Expiry</th>
            <th className="px-3 py-2 text-left">Qty</th>
            <th className="px-3 py-2 text-left">Avg Cost</th>
            <th className="px-3 py-2 text-left">CCY</th>
          </tr>
        </thead>
        <tbody>
          {positions.map((p, i) => (
            <tr key={i} className="border-b border-border hover:bg-muted/40 transition-colors">
              <td className="px-3 py-2 font-medium">{p.symbol}</td>
              <td className="px-3 py-2 text-muted-foreground">{p.secType}</td>
              <td className="px-3 py-2">{p.optionRight ?? '—'}</td>
              <td className="px-3 py-2 tabular-nums">{p.strike?.toFixed(2) ?? '—'}</td>
              <td className="px-3 py-2 tabular-nums">{p.expiry?.toString() ?? '—'}</td>
              <td className="px-3 py-2 tabular-nums">{p.quantity?.toFixed(0)}</td>
              <td className="px-3 py-2 tabular-nums">{p.avgCost?.toFixed(4) ?? '—'}</td>
              <td className="px-3 py-2 text-muted-foreground">{p.currency}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function AccountPage() {
  const { data, isLoading, isError } = useQuery({
    ...getAccountOverviewOptions(),
    refetchInterval: 30_000,
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

          <section>
            <h2 className="text-base font-semibold mb-3">
              All IBKR Positions
              <span className="ml-2 text-xs text-muted-foreground font-normal">
                ({data.accountPositionCount})
              </span>
            </h2>
            <IbkrPositionsTable positions={data.accountPositions ?? []} />
          </section>
        </>
      )}
    </div>
  )
}
