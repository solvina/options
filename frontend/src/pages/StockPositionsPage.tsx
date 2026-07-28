import { useEffect, useState } from 'react'

type StockPosition = {
  id: string
  strategyId: string
  symbol: string
  timeframe: string
  status: string
  signalPrice: number
  limitPrice: number
  stopPrice: number
  targetPrice: number | null
  shares: number
  actualEntryPrice: number | null
  entrySlippage: number | null
  closePrice: number | null
  realizedPnl: number | null
  closeReason: string | null
  signalledAt: string
  openedAt: string | null
  closedAt: string | null
}

type Summary = {
  enabled: boolean
  maxOpenPositions: number
  livePositions: number
  signalled: number
  filled: number
  unfilled: number
  fillRate: number | null
  avgEntrySlippage: number | null
}

const STATUS_STYLE: Record<string, string> = {
  PENDING: 'bg-amber-500/15 text-amber-600',
  OPEN: 'bg-emerald-500/15 text-emerald-600',
  ENTRY_UNFILLED: 'bg-muted text-muted-foreground',
  CLOSED_TARGET: 'bg-emerald-500/15 text-emerald-600',
  CLOSED_STOP: 'bg-red-500/15 text-red-600',
  CLOSED_MANUAL: 'bg-muted text-muted-foreground',
  CLOSED_EXTERNAL: 'bg-orange-500/15 text-orange-600',
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded border px-3 py-2">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-lg font-semibold tabular-nums">{value}</div>
      {hint && <div className="text-xs text-muted-foreground">{hint}</div>}
    </div>
  )
}

export function StockPositionsPage() {
  const [positions, setPositions] = useState<StockPosition[]>([])
  const [summary, setSummary] = useState<Summary | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    async function load() {
      const [p, s] = await Promise.all([
        fetch('/api/stock-positions').then((r) => r.json()),
        fetch('/api/stock-positions/summary').then((r) => r.json()),
      ])
      setPositions(p)
      setSummary(s)
      setLoading(false)
    }
    load().catch(() => setLoading(false))
    const t = setInterval(() => load().catch(() => {}), 30_000)
    return () => clearInterval(t)
  }, [])

  if (loading) return <div className="p-6 text-muted-foreground">Loading…</div>

  return (
    <div className="p-6 space-y-4">
      <div>
        <h1 className="text-xl font-semibold">Stock Strategy Positions</h1>
        <p className="text-sm text-muted-foreground">
          Live positions from the strategy library. Fill rate is the number to watch — the backtest assumes every entry fills.
        </p>
      </div>

      {summary && (
        <>
          {!summary.enabled && (
            <div className="rounded border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm">
              Stock strategies are <strong>disabled</strong>. The runner evaluates nothing and places no orders, however many
              assignments are enabled. Set <code>stock-strategies.enabled=true</code> to arm it.
            </div>
          )}
          <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
            <Stat label="Live" value={`${summary.livePositions} / ${summary.maxOpenPositions}`} hint="portfolio cap" />
            <Stat label="Signalled" value={String(summary.signalled)} />
            <Stat label="Filled" value={String(summary.filled)} />
            <Stat label="Unfilled" value={String(summary.unfilled)} hint="limit expired" />
            <Stat
              label="Fill rate"
              value={summary.fillRate == null ? '—' : `${(summary.fillRate * 100).toFixed(1)}%`}
              hint={summary.avgEntrySlippage == null ? 'backtest assumes 100%' : `avg slip ${summary.avgEntrySlippage.toFixed(4)}`}
            />
          </div>
        </>
      )}

      {positions.length === 0 ? (
        <div className="rounded border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          No positions yet.
        </div>
      ) : (
        <table className="w-full text-sm">
          <thead className="text-left text-muted-foreground border-b">
            <tr>
              <th className="py-2">Symbol</th>
              <th>Strategy</th>
              <th>Status</th>
              <th className="text-right">Shares</th>
              <th className="text-right">Signal</th>
              <th className="text-right">Limit</th>
              <th className="text-right">Entry</th>
              <th className="text-right">Slip</th>
              <th className="text-right">Stop</th>
              <th className="text-right">P&amp;L</th>
              <th>Signalled</th>
            </tr>
          </thead>
          <tbody>
            {positions.map((p) => (
              <tr key={p.id} className="border-b last:border-0">
                <td className="py-2 font-medium">{p.symbol}</td>
                <td className="text-muted-foreground">{p.strategyId}</td>
                <td>
                  <span className={`px-2 py-0.5 rounded text-xs ${STATUS_STYLE[p.status] ?? 'bg-muted text-muted-foreground'}`}>
                    {p.status.toLowerCase().replace('_', ' ')}
                  </span>
                </td>
                <td className="text-right tabular-nums">{p.shares}</td>
                <td className="text-right tabular-nums">{p.signalPrice.toFixed(2)}</td>
                <td className="text-right tabular-nums">{p.limitPrice.toFixed(2)}</td>
                <td className="text-right tabular-nums">{p.actualEntryPrice?.toFixed(2) ?? '—'}</td>
                <td className={`text-right tabular-nums ${(p.entrySlippage ?? 0) > 0 ? 'text-red-600' : ''}`}>
                  {p.entrySlippage?.toFixed(3) ?? '—'}
                </td>
                <td className="text-right tabular-nums">{p.stopPrice.toFixed(2)}</td>
                <td className={`text-right tabular-nums ${(p.realizedPnl ?? 0) < 0 ? 'text-red-600' : (p.realizedPnl ?? 0) > 0 ? 'text-emerald-600' : ''}`}>
                  {p.realizedPnl?.toFixed(2) ?? '—'}
                </td>
                <td className="text-muted-foreground text-xs">{new Date(p.signalledAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
