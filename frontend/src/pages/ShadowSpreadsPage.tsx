import { useQuery } from '@tanstack/react-query'
import { listShadowSpreadsOptions } from '../generated/spreads/@tanstack/react-query.gen'
import type { ShadowSpreadDto } from '../generated/spreads/types.gen'

// Would-be spreads: everything paper didn't fill, followed as if filled at mid (live market makers
// fill near mid; paper's simulator never does). Estimates only — nothing here places orders.

const usd = (x: number | null | undefined) => (x == null ? '—' : `${x < 0 ? '−' : ''}$${Math.abs(x).toFixed(2)}`)
const pnlClass = (x: number | null | undefined) =>
  x == null ? '' : x >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'

function Summary({ title, rows }: { title: string; rows: ShadowSpreadDto[] }) {
  const closed = rows.filter((r) => r.status !== 'OPEN' && r.pnlAtMid != null)
  const open = rows.filter((r) => r.status === 'OPEN')
  const wins = closed.filter((r) => (r.pnlAtMid ?? 0) > 0)
  const losses = closed.filter((r) => (r.pnlAtMid ?? 0) <= 0)
  const sum = (xs: ShadowSpreadDto[], k: 'pnlAtMid' | 'pnlAt95PctMid') => xs.reduce((a, r) => a + (r[k] ?? 0), 0)
  const avg = (xs: ShadowSpreadDto[]) => (xs.length ? sum(xs, 'pnlAtMid') / xs.length : null)
  const cell = (label: string, value: React.ReactNode) => (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-sm font-medium tabular-nums">{value}</p>
    </div>
  )
  return (
    <section className="rounded-lg border border-border bg-card p-4">
      <h2 className="text-sm font-semibold mb-3">{title}</h2>
      <div className="grid grid-cols-3 sm:grid-cols-6 gap-4">
        {cell('Closed', `${closed.length} (${wins.length} won)`)}
        {cell('Win rate', closed.length ? `${Math.round((wins.length / closed.length) * 100)}%` : '—')}
        {cell('Avg win / loss', `${usd(avg(wins))} / ${usd(avg(losses))}`)}
        {cell('P&L at mid', <span className={pnlClass(sum(closed, 'pnlAtMid'))}>{usd(sum(closed, 'pnlAtMid'))}</span>)}
        {cell('P&L at 95% mid', <span className={pnlClass(sum(closed, 'pnlAt95PctMid'))}>{usd(sum(closed, 'pnlAt95PctMid'))}</span>)}
        {cell('Open (marked)', <span className={pnlClass(sum(open, 'pnlAtMid'))}>{`${open.length} · ${usd(sum(open, 'pnlAtMid'))}`}</span>)}
      </div>
    </section>
  )
}

export function ShadowSpreadsPage() {
  const { data, isLoading, isError } = useQuery({ ...listShadowSpreadsOptions(), refetchInterval: 60_000 })
  if (isLoading) return <p className="text-muted-foreground text-sm">Loading…</p>
  if (isError || !data) return <p className="text-destructive text-sm">Failed to load would-be trades.</p>
  const rows = data as ShadowSpreadDto[]
  const live = rows.filter((r) => !r.backfilled)
  const backfilled = rows.filter((r) => r.backfilled)

  return (
    <div className="space-y-6 max-w-6xl">
      <div>
        <h1 className="text-lg font-semibold mb-1">Would-be Trades</h1>
        <p className="text-sm text-muted-foreground">
          Spreads paper didn't fill, followed as if filled at mid, revalued daily from real quotes and closed by the
          same rules as real spreads (take-profit, stock-price stop, time exit, expiry). An estimate of live results —
          nothing here places orders. P&amp;L per 1 contract, before commissions.
        </p>
      </div>
      <Summary title="Recorded live (since 2026-10-30)" rows={live} />
      {backfilled.length > 0 && <Summary title="Back-filled history (approximate: daily closes + model prices)" rows={backfilled} />}
      <section className="rounded-lg border border-border bg-card p-4 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-xs text-muted-foreground text-left">
              {['Opened', 'Symbol', 'Spread', 'Expiry', 'Mid', 'Natural', 'Source', 'Status', 'Value', 'P&L at mid', 'Reason'].map((h) => (
                <th key={h} className="font-normal py-1 pr-3">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className="border-t border-border">
                <td className="py-1.5 pr-3 whitespace-nowrap">
                  {new Date(r.openedAt).toLocaleDateString()}
                  {r.backfilled && <span className="ml-1 text-xs text-muted-foreground">(bf)</span>}
                </td>
                <td className="py-1.5 pr-3 font-medium">{r.symbol}</td>
                <td className="py-1.5 pr-3 whitespace-nowrap">
                  {r.soldStrike}/{r.boughtStrike}
                  {r.optionType === 'PUT' ? 'P' : 'C'}
                </td>
                <td className="py-1.5 pr-3 whitespace-nowrap">{r.expiry}</td>
                <td className="py-1.5 pr-3 tabular-nums">{usd(r.midCredit)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{usd(r.naturalCredit)}</td>
                <td className="py-1.5 pr-3 text-xs">{r.sourceOutcome}</td>
                <td className="py-1.5 pr-3 text-xs">{r.status}</td>
                <td className="py-1.5 pr-3 tabular-nums">{usd(r.exitValue ?? r.lastValue)}</td>
                <td className={`py-1.5 pr-3 tabular-nums ${pnlClass(r.pnlAtMid)}`}>{usd(r.pnlAtMid)}</td>
                <td className="py-1.5 pr-3 text-xs text-muted-foreground">{r.closeReason ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <p className="text-sm text-muted-foreground">Nothing recorded yet.</p>}
      </section>
    </div>
  )
}
