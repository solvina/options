import React, { useState } from 'react'

interface BacktestTradeDto {
  id: string
  symbol: string
  status: string
  openedAt: string
  closedAt: string | null
  closeReason: string | null
  entryPrice: number
  actualEntryPrice: number | null
  stopLossPrice: number
  profitTargetPrice: number
  closePriceActual: number | null
  shares: number
  riskAmount: number
  realizedPnl: number | null
  rMultiple: number | null
  mfeR: number | null
  maeR: number | null
  timeInTradeSeconds: number | null
  marketSession: string | null
  breakoutType: string | null
  flagpoleHeight: number | null
  flagRetracement: number | null
  flagBarCount: number | null
  flagpoleBarCount: number | null
  atrAtEntry: number | null
  channelSlope: number | null
  vwapAtEntry: number | null
  stopDistancePct: number | null
}

interface BacktestResult {
  symbols: string[]
  from: string
  to: string
  initialCapital: number
  finalCapital: number
  totalPnl: number
  totalPnlPct: number
  tradeCount: number
  winCount: number
  lossCount: number
  eodCount: number
  winRate: number
  avgRMultiple: number | null
  avgWinR: number | null
  avgLossR: number | null
  profitFactor: number | null
  maxDrawdownPct: number
  trades: BacktestTradeDto[]
}

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

function monthsAgo(n: number): string {
  const d = new Date()
  d.setMonth(d.getMonth() - n)
  return d.toISOString().slice(0, 10)
}

function fmt(v: number | null | undefined, digits = 2): string {
  if (v == null) return '—'
  return v.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

function fmtPct(v: number | null | undefined): string {
  if (v == null) return '—'
  return `${fmt(v)}%`
}

function fmtMoney(v: number | null | undefined): string {
  if (v == null) return '—'
  return `$${fmt(v)}`
}

function fmtDuration(seconds: number | null | undefined): string {
  if (seconds == null) return '—'
  const m = Math.floor(seconds / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  const rem = m % 60
  return `${h}h ${rem}m`
}

function closeReasonBadge(reason: string | null): React.ReactElement {
  if (!reason) return <span className="text-muted-foreground">—</span>
  const styles: Record<string, string> = {
    profit_target: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
    stop_loss: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
    eod_liquidation: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400',
  }
  const labels: Record<string, string> = {
    profit_target: 'PT',
    stop_loss: 'SL',
    eod_liquidation: 'EOD',
  }
  return (
    <span className={`inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium ${styles[reason] ?? 'bg-muted text-muted-foreground'}`}>
      {labels[reason] ?? reason}
    </span>
  )
}

function StatCard({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="border border-border rounded-lg p-4">
      <div className="text-xs text-muted-foreground mb-1">{label}</div>
      <div className="text-xl font-semibold tabular-nums">{value}</div>
      {sub && <div className="text-xs text-muted-foreground mt-0.5">{sub}</div>}
    </div>
  )
}

function SummaryGrid({ r }: { r: BacktestResult }) {
  const pnlColor = r.totalPnl >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-3">
      <StatCard label="Final capital" value={fmtMoney(r.finalCapital)} sub={`started ${fmtMoney(r.initialCapital)}`} />
      <div className="border border-border rounded-lg p-4">
        <div className="text-xs text-muted-foreground mb-1">Total P&L</div>
        <div className={`text-xl font-semibold tabular-nums ${pnlColor}`}>{fmtMoney(r.totalPnl)}</div>
        <div className="text-xs text-muted-foreground mt-0.5">{fmtPct(r.totalPnlPct)}</div>
      </div>
      <StatCard label="Trades" value={String(r.tradeCount)} sub={`${r.winCount}W / ${r.lossCount}L / ${r.eodCount}EOD`} />
      <StatCard label="Win rate" value={fmtPct(r.winRate * 100)} sub="excl. EOD" />
      <StatCard label="Avg R" value={fmt(r.avgRMultiple)} sub={`W:${fmt(r.avgWinR)} L:${fmt(r.avgLossR)}`} />
      <StatCard label="Profit factor" value={fmt(r.profitFactor)} sub={`Max DD ${fmtPct(r.maxDrawdownPct)}`} />
    </div>
  )
}

function TradesTable({ trades }: { trades: BacktestTradeDto[] }) {
  if (trades.length === 0) return <p className="text-sm text-muted-foreground">No trades.</p>
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-b border-border text-left text-muted-foreground">
            {['Symbol', 'Opened', 'Closed', 'Result', 'Entry', 'Stop', 'PT', 'Close', 'Shares', 'P&L', 'R', 'MFE-R', 'MAE-R', 'Hold'].map(h => (
              <th key={h} className="pb-2 pr-3 font-medium whitespace-nowrap">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {trades.map(t => {
            const pnlColor = (t.realizedPnl ?? 0) >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
            return (
              <tr key={t.id} className="border-b border-border/40 last:border-0 hover:bg-muted/30 transition-colors">
                <td className="py-1.5 pr-3 font-mono font-medium">{t.symbol}</td>
                <td className="py-1.5 pr-3 tabular-nums text-muted-foreground whitespace-nowrap">
                  {t.openedAt ? new Date(t.openedAt).toLocaleString('en-GB', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'America/New_York' }) : '—'}
                </td>
                <td className="py-1.5 pr-3 tabular-nums text-muted-foreground whitespace-nowrap">
                  {t.closedAt ? new Date(t.closedAt).toLocaleString('en-GB', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'America/New_York' }) : '—'}
                </td>
                <td className="py-1.5 pr-3">{closeReasonBadge(t.closeReason)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{fmt(t.actualEntryPrice ?? t.entryPrice)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{fmt(t.stopLossPrice)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{fmt(t.profitTargetPrice)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{fmt(t.closePriceActual)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{t.shares}</td>
                <td className={`py-1.5 pr-3 tabular-nums font-medium ${pnlColor}`}>{fmtMoney(t.realizedPnl)}</td>
                <td className={`py-1.5 pr-3 tabular-nums ${pnlColor}`}>{fmt(t.rMultiple)}R</td>
                <td className="py-1.5 pr-3 tabular-nums text-green-600 dark:text-green-400">{fmt(t.mfeR)}R</td>
                <td className="py-1.5 pr-3 tabular-nums text-red-600 dark:text-red-400">{fmt(t.maeR)}R</td>
                <td className="py-1.5 pr-3 tabular-nums text-muted-foreground">{fmtDuration(t.timeInTradeSeconds)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export function BacktestPage() {
  const [symbols, setSymbols] = useState('SPY')
  const [from, setFrom] = useState(monthsAgo(3))
  const [to, setTo] = useState(today())
  const [initialCapital, setInitialCapital] = useState('20000')
  const [riskPerTrade, setRiskPerTrade] = useState('100')
  const [maxOpenPositions, setMaxOpenPositions] = useState('3')
  const [entryBlock, setEntryBlock] = useState('30')

  // Quality filters — defaults match application.yml tuned values
  const [skipFirstRthMinutes, setSkipFirstRthMinutes] = useState('90')
  const [requireNegativeSlope, setRequireNegativeSlope] = useState(true)
  const [minPoleAtr, setMinPoleAtr] = useState('2.0')
  const [maxPoleAtr, setMaxPoleAtr] = useState('4.0')
  const [minRetracement, setMinRetracement] = useState('0.25')
  const [minFlagBars, setMinFlagBars] = useState('7')
  const [filtersOpen, setFiltersOpen] = useState(false)

  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<BacktestResult | null>(null)

  async function run() {
    setLoading(true)
    setError(null)
    setResult(null)
    try {
      const res = await fetch('/api/backtest/flag', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          symbols: symbols.split(',').map(s => s.trim().toUpperCase()).filter(Boolean),
          from,
          to,
          initialCapital: parseFloat(initialCapital),
          riskPerTrade: parseFloat(riskPerTrade),
          maxOpenPositions: parseInt(maxOpenPositions),
          entryBlockMinutesBeforeClose: parseInt(entryBlock),
          skipFirstRthMinutes: parseInt(skipFirstRthMinutes),
          requireNegativeChannelSlope: requireNegativeSlope,
          minFlagpoleAtrMultiple: parseFloat(minPoleAtr),
          maxFlagpoleAtrMultiple: parseFloat(maxPoleAtr),
          minFlagRetracementPct: parseFloat(minRetracement),
          minFlagBarsForEntry: parseInt(minFlagBars),
        }),
      })
      if (!res.ok) {
        const text = await res.text()
        throw new Error(`${res.status}: ${text}`)
      }
      setResult(await res.json())
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }

  const inputCls = 'border border-border rounded px-2 py-1 text-sm bg-background text-foreground'

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-lg font-semibold mb-1">Backtest — Bull Flag</h1>
        <p className="text-sm text-muted-foreground">
          Replays the flag strategy against historical 5-min bars from InfluxDB.
        </p>
      </div>

      {/* Config form */}
      <section className="space-y-4">
        <div className="flex flex-wrap gap-3 items-end">
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            Symbols
            <input
              type="text"
              value={symbols}
              onChange={e => setSymbols(e.target.value)}
              placeholder="SPY,QQQ,AAPL"
              className={`${inputCls} w-44`}
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            From
            <input type="date" value={from} onChange={e => setFrom(e.target.value)} className={inputCls} />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            To
            <input type="date" value={to} onChange={e => setTo(e.target.value)} className={inputCls} />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            Capital ($)
            <input type="number" value={initialCapital} onChange={e => setInitialCapital(e.target.value)} className={`${inputCls} w-28`} />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            Risk/trade ($)
            <input type="number" value={riskPerTrade} onChange={e => setRiskPerTrade(e.target.value)} className={`${inputCls} w-24`} />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            Max positions
            <input type="number" min={1} max={10} value={maxOpenPositions} onChange={e => setMaxOpenPositions(e.target.value)} className={`${inputCls} w-20`} />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            Entry block (min)
            <input type="number" value={entryBlock} onChange={e => setEntryBlock(e.target.value)} className={`${inputCls} w-24`} title="Minutes before close when no new entries are allowed" />
          </label>
          <button
            onClick={run}
            disabled={loading}
            className="px-4 py-1.5 rounded-md bg-primary text-primary-foreground text-sm hover:bg-primary/90 transition-colors disabled:opacity-50 self-end"
          >
            {loading ? 'Running…' : 'Run Backtest'}
          </button>
        </div>

        {/* Quality filters */}
        <div>
          <button
            type="button"
            onClick={() => setFiltersOpen(o => !o)}
            className="flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            <span className={`transition-transform ${filtersOpen ? 'rotate-90' : ''}`}>▶</span>
            Quality filters
          </button>
          {filtersOpen && (
            <div className="mt-3 flex flex-wrap gap-3 items-end border border-border rounded-lg p-4 bg-muted/20">
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                Skip first RTH min
                <input type="number" min={0} value={skipFirstRthMinutes} onChange={e => setSkipFirstRthMinutes(e.target.value)} className={`${inputCls} w-24`} title="Skip entries this many minutes after market open" />
              </label>
              <label className="flex items-center gap-2 text-xs text-muted-foreground self-end pb-1.5 cursor-pointer">
                <input
                  type="checkbox"
                  checked={requireNegativeSlope}
                  onChange={e => setRequireNegativeSlope(e.target.checked)}
                  className="w-3.5 h-3.5"
                />
                Require downward slope
              </label>
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                Min pole ATR×
                <input type="number" min={0} step={0.5} value={minPoleAtr} onChange={e => setMinPoleAtr(e.target.value)} className={`${inputCls} w-24`} title="Minimum flagpole height as ATR multiple" />
              </label>
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                Max pole ATR×
                <input type="number" min={0} step={0.5} value={maxPoleAtr} onChange={e => setMaxPoleAtr(e.target.value)} className={`${inputCls} w-24`} title="Maximum flagpole height as ATR multiple (filter over-extended moves)" />
              </label>
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                Min retracement
                <input type="number" min={0} max={0.5} step={0.05} value={minRetracement} onChange={e => setMinRetracement(e.target.value)} className={`${inputCls} w-24`} title="Minimum flag retracement as fraction (0.25 = 25%)" />
              </label>
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                Min flag bars
                <input type="number" min={1} value={minFlagBars} onChange={e => setMinFlagBars(e.target.value)} className={`${inputCls} w-20`} title="Minimum number of flag bars before entry" />
              </label>
            </div>
          )}
        </div>

        {error && (
          <p className="text-sm text-red-600 dark:text-red-400">Error: {error}</p>
        )}
      </section>

      {/* Results */}
      {result && (
        <div className="space-y-6">
          <div className="text-xs text-muted-foreground">
            {result.symbols.join(', ')} · {result.from} – {result.to}
          </div>

          <SummaryGrid r={result} />

          <section className="space-y-3">
            <h2 className="text-base font-medium">Trades ({result.trades.length})</h2>
            <TradesTable trades={result.trades} />
          </section>
        </div>
      )}
    </div>
  )
}
