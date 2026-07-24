import React, { useEffect, useState } from 'react'

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

interface RunOverview {
  id: string
  createdAt: string
  label: string | null
  strategy: string
  from: string
  to: string
  symbols: string[]
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
  profitFactor: number | null
  maxDrawdownPct: number
}

interface RunDetail {
  overview: RunOverview
  params: Record<string, unknown>
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
  // Full date incl. year, in ET (matches the data's trading session).
  const dateOpts: Intl.DateTimeFormatOptions = {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'America/New_York',
  }
  // Running (cumulative) realized P&L down the trade list.
  let running = 0
  const rows = trades.map(t => {
    running += t.realizedPnl ?? 0
    return { t, cum: running }
  })
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-b border-border text-left text-muted-foreground">
            {['Symbol', 'Opened', 'Closed', 'Result', 'Entry', 'Stop', 'PT', 'Close', 'Shares', 'P&L', 'Cum P&L', 'R', 'MFE-R', 'MAE-R', 'Hold'].map(h => (
              <th key={h} className="pb-2 pr-3 font-medium whitespace-nowrap">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(({ t, cum }) => {
            const pnlColor = (t.realizedPnl ?? 0) >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
            const cumColor = cum >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
            return (
              <tr key={t.id} className="border-b border-border/40 last:border-0 hover:bg-muted/30 transition-colors">
                <td className="py-1.5 pr-3 font-mono font-medium">{t.symbol}</td>
                <td className="py-1.5 pr-3 tabular-nums text-muted-foreground whitespace-nowrap">
                  {t.openedAt ? new Date(t.openedAt).toLocaleString('en-GB', dateOpts) : '—'}
                </td>
                <td className="py-1.5 pr-3 tabular-nums text-muted-foreground whitespace-nowrap">
                  {t.closedAt ? new Date(t.closedAt).toLocaleString('en-GB', dateOpts) : '—'}
                </td>
                <td className="py-1.5 pr-3">{closeReasonBadge(t.closeReason)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{fmt(t.actualEntryPrice ?? t.entryPrice)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{fmt(t.stopLossPrice)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{fmt(t.profitTargetPrice)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{fmt(t.closePriceActual)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{t.shares}</td>
                <td className={`py-1.5 pr-3 tabular-nums font-medium ${pnlColor}`}>{fmtMoney(t.realizedPnl)}</td>
                <td className={`py-1.5 pr-3 tabular-nums font-medium ${cumColor}`}>{fmtMoney(cum)}</td>
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

/** Rebuilds a BacktestResult from a persisted run so the existing SummaryGrid/TradesTable can render
 *  it. avgWinR/avgLossR aren't in the overview, so derive them from the per-trade R-multiples. */
function overviewToResult(d: RunDetail): BacktestResult {
  const wins = d.trades.map(t => t.rMultiple).filter((r): r is number => r != null && r > 0)
  const losses = d.trades.map(t => t.rMultiple).filter((r): r is number => r != null && r < 0)
  const avg = (a: number[]): number | null => (a.length ? a.reduce((x, y) => x + y, 0) / a.length : null)
  const o = d.overview
  return {
    symbols: o.symbols,
    from: o.from,
    to: o.to,
    initialCapital: o.initialCapital,
    finalCapital: o.finalCapital,
    totalPnl: o.totalPnl,
    totalPnlPct: o.totalPnlPct,
    tradeCount: o.tradeCount,
    winCount: o.winCount,
    lossCount: o.lossCount,
    eodCount: o.eodCount,
    winRate: o.winRate,
    avgRMultiple: o.avgRMultiple,
    avgWinR: avg(wins),
    avgLossR: avg(losses),
    profitFactor: o.profitFactor,
    maxDrawdownPct: o.maxDrawdownPct,
    trades: d.trades,
  }
}

/** Shows the exact parameters a run used (so they can be read off and re-run). */
function ParamsBlock({ params }: { params: Record<string, unknown> }) {
  const entries = Object.entries(params).filter(([, v]) => v != null)
  if (entries.length === 0) return null
  return (
    <div className="flex flex-wrap gap-2">
      {entries.map(([k, v]) => (
        <span key={k} className="inline-flex items-center rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground">
          <span className="font-medium text-foreground/80">{k}:</span>
          &nbsp;{Array.isArray(v) ? v.join(',') : String(v)}
        </span>
      ))}
    </div>
  )
}

const HISTORY_PAGE_SIZE = 100

/** Browse persisted backtest runs and open one to view its params + summary + trades. */
function BacktestHistory({ reloadSignal, onApplyParams }: { reloadSignal: number; onApplyParams: (p: Record<string, unknown>) => void }) {
  const [runs, setRuns] = useState<RunOverview[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<RunDetail | null>(null)
  const [selectedLoading, setSelectedLoading] = useState(false)
  const [page, setPage] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetch('/api/backtest/runs')
      .then(r => {
        if (!r.ok) throw new Error(`${r.status}`)
        return r.json()
      })
      .then((d: RunOverview[]) => {
        if (!cancelled) {
          setRuns(d)
          setPage(0)
        }
      })
      .catch(e => {
        if (!cancelled) setError(String(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [reloadSignal])

  async function openRun(id: string) {
    setSelectedLoading(true)
    setError(null)
    try {
      const res = await fetch(`/api/backtest/runs/${id}`)
      if (!res.ok) throw new Error(`${res.status}`)
      const data: RunDetail = await res.json()
      setSelected(data)
      // Recall this run's exact parameters into the form so it can be tweaked and re-run.
      onApplyParams(data.params)
    } catch (e) {
      setError(String(e))
    } finally {
      setSelectedLoading(false)
    }
  }

  const totalPages = Math.max(1, Math.ceil(runs.length / HISTORY_PAGE_SIZE))
  const clampedPage = Math.min(page, totalPages - 1)
  const pageRuns = runs.slice(clampedPage * HISTORY_PAGE_SIZE, (clampedPage + 1) * HISTORY_PAGE_SIZE)

  return (
    <section className="space-y-3">
      <h2 className="text-base font-medium">History ({runs.length})</h2>
      {error && <p className="text-sm text-red-600 dark:text-red-400">Error: {error}</p>}
      {loading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {!loading && runs.length === 0 && <p className="text-sm text-muted-foreground">No saved runs yet.</p>}
      {runs.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-border text-left text-muted-foreground">
                {['When', 'Label', 'Period', 'Symbols', 'Trades', 'Win%', 'P&L', 'PF', 'Max DD', ''].map(h => (
                  <th key={h} className="pb-2 pr-3 font-medium whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {pageRuns.map(r => {
                const pnlColor = r.totalPnl >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'
                return (
                  <tr
                    key={r.id}
                    onClick={() => openRun(r.id)}
                    className={`border-b border-border/40 last:border-0 hover:bg-muted/30 transition-colors cursor-pointer ${selected?.overview.id === r.id ? 'bg-muted/40' : ''}`}
                  >
                    <td className="py-1.5 pr-3 tabular-nums text-muted-foreground whitespace-nowrap">
                      {new Date(r.createdAt).toLocaleString('en-GB', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                    </td>
                    <td className="py-1.5 pr-3 max-w-[12rem] truncate">{r.label ?? '—'}</td>
                    <td className="py-1.5 pr-3 whitespace-nowrap">{r.from} – {r.to}</td>
                    <td className="py-1.5 pr-3 font-mono max-w-[10rem] truncate">{r.symbols.join(', ')}</td>
                    <td className="py-1.5 pr-3 tabular-nums">{r.tradeCount}</td>
                    <td className="py-1.5 pr-3 tabular-nums">{fmtPct(r.winRate * 100)}</td>
                    <td className={`py-1.5 pr-3 tabular-nums font-medium ${pnlColor}`}>{fmtMoney(r.totalPnl)}</td>
                    <td className="py-1.5 pr-3 tabular-nums">{fmt(r.profitFactor)}</td>
                    <td className="py-1.5 pr-3 tabular-nums">{fmtPct(r.maxDrawdownPct)}</td>
                    <td className="py-1.5 pr-3 text-muted-foreground whitespace-nowrap">load →</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {runs.length > HISTORY_PAGE_SIZE && (
        <div className="flex items-center gap-3 text-xs text-muted-foreground">
          <button
            type="button"
            onClick={() => setPage(p => Math.max(0, p - 1))}
            disabled={clampedPage === 0}
            className="px-2 py-1 rounded border border-border hover:bg-muted/40 disabled:opacity-40"
          >
            ← Prev
          </button>
          <span>
            Page {clampedPage + 1} of {totalPages} · showing {clampedPage * HISTORY_PAGE_SIZE + 1}–{Math.min((clampedPage + 1) * HISTORY_PAGE_SIZE, runs.length)} of {runs.length}
          </span>
          <button
            type="button"
            onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
            disabled={clampedPage >= totalPages - 1}
            className="px-2 py-1 rounded border border-border hover:bg-muted/40 disabled:opacity-40"
          >
            Next →
          </button>
        </div>
      )}

      {selectedLoading && <p className="text-sm text-muted-foreground">Loading run…</p>}
      {selected && (
        <div className="border border-border rounded-lg p-4 space-y-4 bg-muted/10">
          <div className="flex items-center justify-between gap-3">
            <div className="text-sm font-medium">
              {selected.overview.label ?? selected.overview.id.slice(0, 8)}
              <span className="ml-2 text-xs font-normal text-muted-foreground">
                {selected.overview.symbols.join(', ')} · {selected.overview.from} – {selected.overview.to}
              </span>
            </div>
            <button onClick={() => setSelected(null)} className="text-xs text-muted-foreground hover:text-foreground">close ✕</button>
          </div>
          <ParamsBlock params={selected.params} />
          <SummaryGrid r={overviewToResult(selected)} />
          <div>
            <h3 className="text-sm font-medium mb-2">Trades ({selected.trades.length})</h3>
            <TradesTable trades={selected.trades} />
          </div>
        </div>
      )}
    </section>
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

  // Money management — blank = server defaults (fixed $ risk, flag-low stop, 2R target, ATR period 14)
  const [riskPerTradePct, setRiskPerTradePct] = useState('')
  const [stopAtrPct, setStopAtrPct] = useState('')
  const [targetAtrPct, setTargetAtrPct] = useState('')
  const [atrPeriod, setAtrPeriod] = useState('')
  const [moneyOpen, setMoneyOpen] = useState(false)

  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<BacktestResult | null>(null)
  const [reloadSignal, setReloadSignal] = useState(0)
  const [tradesOpen, setTradesOpen] = useState(false)

  /** Loads a stored run's parameters back into the form (called when a history row is clicked). */
  function applyParams(p: Record<string, unknown>) {
    const str = (v: unknown) => (v == null ? '' : String(v))
    if (Array.isArray(p.symbols)) setSymbols((p.symbols as unknown[]).join(','))
    if (p.from != null) setFrom(String(p.from))
    if (p.to != null) setTo(String(p.to))
    if (p.initialCapital != null) setInitialCapital(String(p.initialCapital))
    if (p.riskPerTrade != null) setRiskPerTrade(String(p.riskPerTrade))
    if (p.maxOpenPositions != null) setMaxOpenPositions(String(p.maxOpenPositions))
    if (p.entryBlockMinutesBeforeClose != null) setEntryBlock(String(p.entryBlockMinutesBeforeClose))
    if (p.skipFirstRthMinutes != null) setSkipFirstRthMinutes(String(p.skipFirstRthMinutes))
    if (typeof p.requireNegativeChannelSlope === 'boolean') setRequireNegativeSlope(p.requireNegativeChannelSlope)
    if (p.minFlagpoleAtrMultiple != null) setMinPoleAtr(String(p.minFlagpoleAtrMultiple))
    if (p.maxFlagpoleAtrMultiple != null) setMaxPoleAtr(String(p.maxFlagpoleAtrMultiple))
    if (p.minFlagRetracementPct != null) setMinRetracement(String(p.minFlagRetracementPct))
    if (p.minFlagBarsForEntry != null) setMinFlagBars(String(p.minFlagBarsForEntry))
    // Money management — set to the run's value, or clear (server default) when the run didn't use it.
    setRiskPerTradePct(str(p.riskPerTradePct))
    setStopAtrPct(str(p.stopAtrPct))
    setTargetAtrPct(str(p.targetAtrPct))
    setAtrPeriod(str(p.atrPeriod))
    if (p.riskPerTradePct != null || p.stopAtrPct != null || p.targetAtrPct != null || p.atrPeriod != null) setMoneyOpen(true)
    setFiltersOpen(true)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

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
          // Money management — sent only when set, so a blank field falls back to the server default.
          ...(riskPerTradePct.trim() ? { riskPerTradePct: parseFloat(riskPerTradePct) } : {}),
          ...(stopAtrPct.trim() ? { stopAtrPct: parseFloat(stopAtrPct) } : {}),
          ...(targetAtrPct.trim() ? { targetAtrPct: parseFloat(targetAtrPct) } : {}),
          ...(atrPeriod.trim() ? { atrPeriod: parseInt(atrPeriod) } : {}),
        }),
      })
      if (!res.ok) {
        const text = await res.text()
        throw new Error(`${res.status}: ${text}`)
      }
      setResult(await res.json())
      setReloadSignal(s => s + 1) // refresh the history list with the newly-saved run
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
          <label className={`flex flex-col gap-1 text-xs text-muted-foreground ${riskPerTradePct.trim() ? 'opacity-40' : ''}`}>
            Risk/trade ($)
            <input
              type="number"
              value={riskPerTrade}
              onChange={e => setRiskPerTrade(e.target.value)}
              disabled={!!riskPerTradePct.trim()}
              title={riskPerTradePct.trim() ? 'Overridden by Risk/trade (% equity)' : undefined}
              className={`${inputCls} w-24 disabled:cursor-not-allowed`}
            />
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

        {/* Money management */}
        <div>
          <button
            type="button"
            onClick={() => setMoneyOpen(o => !o)}
            className="flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            <span className={`transition-transform ${moneyOpen ? 'rotate-90' : ''}`}>▶</span>
            Money management
          </button>
          {moneyOpen && (
            <div className="mt-3 flex flex-wrap gap-3 items-end border border-border rounded-lg p-4 bg-muted/20">
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                Risk/trade (% equity)
                <input type="number" min={0} max={100} step={0.25} value={riskPerTradePct} onChange={e => setRiskPerTradePct(e.target.value)} placeholder="fixed $" className={`${inputCls} w-28`} title="Risk per trade as % of current account equity. Overrides Risk/trade ($) when set; blank = use the fixed dollar amount." />
              </label>
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                Stop (% of ATR)
                <input type="number" min={0} step={10} value={stopAtrPct} onChange={e => setStopAtrPct(e.target.value)} placeholder="flag low" className={`${inputCls} w-28`} title="Stop distance as % of ATR (150 = 1.5×ATR). Blank = flag-low stop." />
              </label>
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                Target (% of ATR)
                <input type="number" min={0} step={10} value={targetAtrPct} onChange={e => setTargetAtrPct(e.target.value)} placeholder="2R" className={`${inputCls} w-28`} title="Target distance as % of ATR (150 = 1.5×ATR). Blank = 2R of the stop distance." />
              </label>
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                ATR period
                <input type="number" min={1} value={atrPeriod} onChange={e => setAtrPeriod(e.target.value)} placeholder="14" className={`${inputCls} w-20`} title="ATR lookback in bars. Blank = 14." />
              </label>
            </div>
          )}
        </div>

        {error && (
          <p className="text-sm text-red-600 dark:text-red-400">Error: {error}</p>
        )}
      </section>

      {/* Current-run summary (kept up top for immediate feedback) */}
      {result && (
        <div className="space-y-4">
          <div className="text-xs text-muted-foreground">
            {result.symbols.join(', ')} · {result.from} – {result.to}
          </div>
          <SummaryGrid r={result} />
        </div>
      )}

      {/* Saved-run history — shown before the per-trade detail (click a row to load its params) */}
      <BacktestHistory reloadSignal={reloadSignal} onApplyParams={applyParams} />

      {/* Current-run trades — collapsed by default, expand on demand */}
      {result && (
        <section className="space-y-3">
          <button
            type="button"
            onClick={() => setTradesOpen(o => !o)}
            className="flex items-center gap-1.5 text-base font-medium hover:text-foreground/80 transition-colors"
          >
            <span className={`text-xs transition-transform ${tradesOpen ? 'rotate-90' : ''}`}>▶</span>
            Trades ({result.trades.length})
          </button>
          {tradesOpen && <TradesTable trades={result.trades} />}
        </section>
      )}
    </div>
  )
}
