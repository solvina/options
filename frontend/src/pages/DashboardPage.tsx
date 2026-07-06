import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { getScannerStatusOptions, getSpreadAnalyticsOptions, listSpreadsOptions } from '../generated/spreads/@tanstack/react-query.gen'
import {
  listBearCallSpreadsOptions,
  listBearCallDividendRiskOptions,
} from '../generated/bearcall/@tanstack/react-query.gen'
import type { SpreadAnalyticsDto, PagedSpreadsDto } from '../generated/spreads/types.gen'
import type { PagedBearCallSpreadsDto, BearCallSpreadDto } from '../generated/bearcall/types.gen'

function StatCard({ label, value, sub, accent }: { label: string; value: ReactNode; sub?: string; accent?: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <p className="text-xs text-muted-foreground uppercase tracking-wide mb-1">{label}</p>
      <p className={`text-lg font-semibold tabular-nums ${accent ?? ''}`}>{value}</p>
      {sub && <p className="text-xs text-muted-foreground mt-0.5">{sub}</p>}
    </div>
  )
}

function pnlColor(v: number) {
  return v >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'
}

function signed(v: number, decimals = 2) {
  return `${v >= 0 ? '+' : ''}${v.toFixed(decimals)}`
}

export function DashboardPage() {
  // Bull puts: rich analytics endpoint already exists.
  const bullAnalytics = useQuery({ ...getSpreadAnalyticsOptions(), refetchInterval: 60_000 })
  const bullOpen = useQuery({
    ...listSpreadsOptions({ query: { status: 'OPEN', page: 0, size: 100 } }),
    refetchInterval: 30_000,
  })

  // Bear calls: no analytics endpoint yet — aggregate client-side from a single large page.
  const bearAll = useQuery({
    ...listBearCallSpreadsOptions({ query: { page: 0, size: 500 } }),
    refetchInterval: 30_000,
  })
  const bearDividendRisk = useQuery({
    ...listBearCallDividendRiskOptions(),
    refetchInterval: 60_000,
  })
  // Portfolio cap comes from engine config (scanner.max-open-spreads) — never hardcode it here.
  const scannerStatus = useQuery({ ...getScannerStatusOptions(), refetchInterval: 60_000 })
  const maxOpenSpreads = scannerStatus.data?.maxOpenSpreads

  const bull = bullAnalytics.data as SpreadAnalyticsDto | undefined
  const bullOpenCount = (bullOpen.data as PagedSpreadsDto | undefined)?.totalElements ?? 0

  const bearItems = ((bearAll.data as PagedBearCallSpreadsDto | undefined)?.content ?? []) as BearCallSpreadDto[]
  const bearOpenCount = bearItems.filter((s) => s.status === 'OPEN').length
  const bearClosed = bearItems.filter((s) => s.status?.startsWith('CLOSED'))
  const bearRealizedPnl = bearClosed.reduce((acc, s) => acc + (s.currentPnl != null ? Number(s.currentPnl) : 0), 0)
  const bearWins = bearClosed.filter((s) => (s.currentPnl != null ? Number(s.currentPnl) : 0) > 0).length
  const bearWinRate = bearClosed.length > 0 ? (bearWins / bearClosed.length) * 100 : 0
  const divRisk = (bearDividendRisk.data as BearCallSpreadDto[] | undefined) ?? []

  const activeTotal = bullOpenCount + bearOpenCount

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold">Dashboard</h1>

      {/* Portfolio cap — shared across both strategies */}
      <section>
        <h2 className="text-sm font-medium text-muted-foreground mb-2">Portfolio</h2>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <StatCard
            label="Active spreads"
            value={`${activeTotal} / ${maxOpenSpreads ?? '…'}`}
            sub="shared cap across strategies"
            accent={maxOpenSpreads != null && activeTotal >= maxOpenSpreads ? 'text-amber-500' : undefined}
          />
          <StatCard label="Bull put open" value={bullOpenCount} />
          <StatCard label="Bear call open" value={bearOpenCount} />
          <StatCard
            label="Dividend risk"
            value={divRisk.length}
            sub="bear calls near ex-div"
            accent={divRisk.length > 0 ? 'text-amber-500' : undefined}
          />
        </div>
      </section>

      {/* Bull puts */}
      <section>
        <div className="flex items-center justify-between mb-2">
          <h2 className="text-sm font-medium text-muted-foreground">Bull Puts</h2>
          <Link to="/spreads/positions" className="text-xs text-muted-foreground hover:text-foreground">View →</Link>
        </div>
        {bullAnalytics.isLoading ? (
          <p className="text-muted-foreground text-sm">Loading…</p>
        ) : (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard label="Open" value={bull?.summary.openTrades ?? bullOpenCount} />
            <StatCard label="Total trades" value={bull?.summary.totalTrades ?? 0} />
            <StatCard label="Win rate" value={`${((bull?.summary.winRate ?? 0) * 100).toFixed(0)}%`} />
            <StatCard
              label="Realized P&L"
              value={signed(bull?.summary.totalRealizedPnl ?? 0)}
              accent={pnlColor(bull?.summary.totalRealizedPnl ?? 0)}
            />
          </div>
        )}
      </section>

      {/* Bear calls */}
      <section>
        <div className="flex items-center justify-between mb-2">
          <h2 className="text-sm font-medium text-muted-foreground">Bear Calls</h2>
          <Link to="/bear-calls/positions" className="text-xs text-muted-foreground hover:text-foreground">View →</Link>
        </div>
        {bearAll.isLoading ? (
          <p className="text-muted-foreground text-sm">Loading…</p>
        ) : (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard label="Open" value={bearOpenCount} />
            <StatCard label="Total trades" value={bearItems.length} />
            <StatCard label="Win rate" value={bearClosed.length > 0 ? `${bearWinRate.toFixed(0)}%` : '—'} />
            <StatCard label="Realized P&L" value={signed(bearRealizedPnl)} accent={pnlColor(bearRealizedPnl)} />
          </div>
        )}
      </section>

      {/* Dividend-risk detail */}
      {divRisk.length > 0 && (
        <section>
          <h2 className="text-sm font-medium text-amber-500 mb-2">⚠ Bear calls near ex-dividend</h2>
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
                  <th className="px-3 py-2 text-left">Symbol</th>
                  <th className="px-3 py-2 text-left">Sold</th>
                  <th className="px-3 py-2 text-left">Ex-Div</th>
                  <th className="px-3 py-2 text-left">Expiry</th>
                </tr>
              </thead>
              <tbody>
                {divRisk.map((s) => (
                  <tr key={s.id} className="border-b border-border">
                    <td className="px-3 py-2 font-medium">{s.symbol}</td>
                    <td className="px-3 py-2 tabular-nums">{s.soldStrike?.toFixed(2) ?? '—'}</td>
                    <td className="px-3 py-2 tabular-nums text-amber-500">{s.exDividendDate?.toString() ?? '—'}</td>
                    <td className="px-3 py-2 tabular-nums">{s.expiryDate?.toString() ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  )
}
