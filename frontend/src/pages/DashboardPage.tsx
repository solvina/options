import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { getScannerStatusOptions, listSpreadsOptions } from '../generated/spreads/@tanstack/react-query.gen'
import {
  listBearCallSpreadsOptions,
  listBearCallDividendRiskOptions,
} from '../generated/bearcall/@tanstack/react-query.gen'
import { listFlagsOptions, getFlagConfigOptions } from '../generated/flags/@tanstack/react-query.gen'
import { ReportSection } from '../components/reports/ReportSection'
import type { PagedSpreadsDto, SpreadDto } from '../generated/spreads/types.gen'
import type { PagedBearCallSpreadsDto, BearCallSpreadDto } from '../generated/bearcall/types.gen'
import type { PagedFlagsDto, FlagPositionDto, FlagTradingConfigDto } from '../generated/flags/types.gen'

// Live tiles are deliberately dense: this strip answers "what is on right now", and the period
// performance numbers (win rate, realized P&L, trade counts) live in the report table below rather
// than being duplicated here as lifetime figures with no date context.
function Tile({
  label,
  value,
  sub,
  accent,
  to,
}: {
  label: string
  value: ReactNode
  sub?: string
  accent?: string
  to?: string
}) {
  const body = (
    <>
      <p className="text-[11px] uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className={`text-lg font-semibold tabular-nums leading-tight ${accent ?? ''}`}>{value}</p>
      {sub && <p className="text-[11px] text-muted-foreground leading-tight">{sub}</p>}
    </>
  )
  const className = 'rounded-lg border border-border bg-card px-3 py-2 space-y-0.5'
  return to ? (
    <Link to={to} className={`${className} hover:bg-accent/40 transition-colors`}>
      {body}
    </Link>
  ) : (
    <div className={className}>{body}</div>
  )
}

function pnlColor(v: number) {
  return v >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'
}

function fmtMoney(v: number) {
  return `${v < 0 ? '-' : v > 0 ? '+' : ''}$${Math.abs(v).toFixed(2)}`
}

function sumPnl(values: (number | null | undefined)[]): { total: number; known: number; missing: number } {
  let total = 0
  let known = 0
  let missing = 0
  for (const v of values) {
    if (v == null) missing++
    else {
      total += Number(v)
      known++
    }
  }
  return { total, known, missing }
}

export function DashboardPage() {
  const bullOpen = useQuery({
    ...listSpreadsOptions({ query: { status: 'OPEN', page: 0, size: 100 } }),
    refetchInterval: 30_000,
  })
  // Bear calls have no status filter on the list endpoint — pull a page and filter client-side.
  const bearAll = useQuery({
    ...listBearCallSpreadsOptions({ query: { page: 0, size: 500 } }),
    refetchInterval: 30_000,
  })
  const bearDividendRisk = useQuery({ ...listBearCallDividendRiskOptions(), refetchInterval: 60_000 })
  const flagsAll = useQuery({ ...listFlagsOptions({ query: { page: 0, size: 200 } }), refetchInterval: 30_000 })
  const flagConfig = useQuery({ ...getFlagConfigOptions(), refetchInterval: 300_000 })
  // Portfolio cap comes from engine config (scanner.max-open-spreads) — never hardcode it here.
  const scannerStatus = useQuery({ ...getScannerStatusOptions(), refetchInterval: 60_000 })

  const maxOpenSpreads = scannerStatus.data?.maxOpenSpreads
  const scannerPaused = scannerStatus.data?.scannerPaused === true

  const bullItems = ((bullOpen.data as PagedSpreadsDto | undefined)?.content ?? []) as SpreadDto[]
  const bullOpenCount = (bullOpen.data as PagedSpreadsDto | undefined)?.totalElements ?? 0

  const bearItems = ((bearAll.data as PagedBearCallSpreadsDto | undefined)?.content ?? []) as BearCallSpreadDto[]
  const bearOpen = bearItems.filter((s) => s.status === 'OPEN')

  const flagItems = ((flagsAll.data as PagedFlagsDto | undefined)?.content ?? []) as FlagPositionDto[]
  const flagOpen = flagItems.filter((p) => p.status === 'OPEN')
  const maxOpenFlags = (flagConfig.data as FlagTradingConfigDto | undefined)?.maxOpenPositions
  const flagsEnabled = (flagConfig.data as FlagTradingConfigDto | undefined)?.enabled

  const divRisk = (bearDividendRisk.data as BearCallSpreadDto[] | undefined) ?? []

  const spreadsOpen = bullOpenCount + bearOpen.length
  const atCap = maxOpenSpreads != null && spreadsOpen >= maxOpenSpreads

  // Open-position P&L, live. Spreads report IBKR's own unrealized figure; flags report the broker's
  // (brokerUnrealizedPnl is what TWS shows, unlike the engine's own estimate). Positions with no
  // broker figure yet are counted as missing rather than silently as zero.
  const spreadPnl = sumPnl([...bullItems, ...bearOpen].map((s) => s.unrealizedPnl))
  const flagPnl = sumPnl(flagOpen.map((p) => p.brokerUnrealizedPnl))
  const openPnl = spreadPnl.total + flagPnl.total
  const pnlMissing = spreadPnl.missing + flagPnl.missing

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h1 className="text-xl font-semibold tracking-tight">Overview</h1>
        <p className="text-sm text-muted-foreground">Live positions and per-strategy performance</p>
        {scannerPaused && (
          <span className="rounded-md border border-amber-500/40 bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-600 dark:text-amber-400">
            Scanner paused
          </span>
        )}
        {flagsEnabled === false && (
          <span className="rounded-md border border-amber-500/40 bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-600 dark:text-amber-400">
            Flag entries disabled
          </span>
        )}
      </div>

      <section>
        <h2 className="mb-2 text-sm font-medium text-muted-foreground">Open now</h2>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
          <Tile
            label="Spreads"
            value={`${spreadsOpen} / ${maxOpenSpreads ?? '…'}`}
            sub="shared cap"
            accent={atCap ? 'text-amber-500' : undefined}
          />
          <Tile label="Bull puts" value={bullOpenCount} to="/spreads/positions" />
          <Tile label="Bear calls" value={bearOpen.length} to="/bear-calls/positions" />
          <Tile
            label="Flags"
            value={maxOpenFlags != null ? `${flagOpen.length} / ${maxOpenFlags}` : flagOpen.length}
            sub="own cap"
            accent={maxOpenFlags != null && flagOpen.length >= maxOpenFlags ? 'text-amber-500' : undefined}
            to="/flags/positions"
          />
          <Tile
            label="Open P&L"
            value={fmtMoney(openPnl)}
            sub={pnlMissing > 0 ? `${pnlMissing} without a broker mark` : 'from the broker feed'}
            accent={pnlColor(openPnl)}
          />
          <Tile
            label="Dividend risk"
            value={divRisk.length}
            sub="bear calls near ex-div"
            accent={divRisk.length > 0 ? 'text-amber-500' : undefined}
          />
        </div>
      </section>

      {divRisk.length > 0 && (
        <section>
          <h2 className="mb-2 text-sm font-medium text-amber-500">⚠ Bear calls near ex-dividend</h2>
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/50 text-xs uppercase tracking-wide text-muted-foreground">
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

      <div className="h-px bg-border" />

      <ReportSection />
    </div>
  )
}
