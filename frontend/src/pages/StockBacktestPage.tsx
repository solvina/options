import { useEffect, useState } from 'react'

// ---- types ----
type Timeframe = '1d' | '4h'

interface StockForm {
  symbols: string
  from: string
  to: string
  timeframe: Timeframe
  initialCapital: number
  rsiPeriod: number
  rsiOversold: number
  requireRsiRising: boolean
  smaFastPeriod: number
  smaSlowPeriod: number
  requireUptrend: boolean
  supportProximityPct: number
  stopLossPct: number
  targetPct: number
  riskPerTrade: number
  maxOpenPositions: number
}

const DEFAULTS: StockForm = {
  symbols: 'AAPL',
  from: '2015-01-01',
  to: '2025-01-01',
  timeframe: '1d',
  initialCapital: 20000,
  rsiPeriod: 14,
  rsiOversold: 40,
  requireRsiRising: true,
  smaFastPeriod: 50,
  smaSlowPeriod: 200,
  requireUptrend: true,
  supportProximityPct: 3,
  stopLossPct: 3,
  targetPct: 6,
  riskPerTrade: 200,
  maxOpenPositions: 1,
}

interface Summary {
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
  profitFactor: number | null
  maxDrawdownPct: number
}
interface RuleTrade {
  symbol: string
  entryAt: string
  entryPrice: number
  exitAt: string
  exitPrice: number
  closeReason: string
  shares: number
  pnl: number
}
interface Result {
  summary: Summary
  trades: RuleTrade[]
}
interface Preset {
  id: string
  name: string
  payload: StockForm
  createdAt: string
}

const inputCls = 'border border-border rounded px-2 py-1 text-sm bg-background text-foreground w-full'
const fmt = (v: number | null | undefined, d = 2) => (v == null ? '—' : v.toFixed(d))
const money = (v: number) => `${v >= 0 ? '+' : '−'}$${Math.abs(v).toFixed(0)}`

function Stat({ label, value, sub, color }: { label: string; value: string; sub?: string; color?: string }) {
  return (
    <div className="border border-border rounded-lg p-3 bg-card">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={`text-lg font-semibold tabular-nums ${color ?? ''}`}>{value}</p>
      {sub && <p className="text-xs text-muted-foreground mt-0.5">{sub}</p>}
    </div>
  )
}

/** Cumulative-P&L equity curve computed from the trade list (no chart lib). */
function EquityCurve({ trades }: { trades: RuleTrade[] }) {
  const sorted = [...trades].sort((a, b) => new Date(a.exitAt).getTime() - new Date(b.exitAt).getTime())
  if (sorted.length < 2) return null
  // pnl is already total (per-trade) dollars in the backend → cumulative equity curve.
  let cum = 0
  const equity = sorted.map((t) => (cum += t.pnl))
  const min = Math.min(0, ...equity)
  const max = Math.max(0, ...equity)
  const range = max - min || 1
  const w = 100
  const h = 40
  const path = equity
    .map((e, i) => `${(i / (equity.length - 1)) * w},${h - ((e - min) / range) * h}`)
    .join(' ')
  const zeroY = h - ((0 - min) / range) * h
  return (
    <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="w-full h-24 border border-border rounded-lg bg-card">
      <line x1="0" y1={zeroY} x2={w} y2={zeroY} stroke="currentColor" strokeWidth="0.3" className="text-muted-foreground/40" />
      <polyline
        points={path}
        fill="none"
        strokeWidth="0.8"
        vectorEffect="non-scaling-stroke"
        className={equity[equity.length - 1] >= 0 ? 'text-green-500' : 'text-red-500'}
        stroke="currentColor"
      />
    </svg>
  )
}

export function StockBacktestPage() {
  const [form, setForm] = useState<StockForm>(DEFAULTS)
  const [presets, setPresets] = useState<Preset[]>([])
  const [presetName, setPresetName] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<Result | null>(null)
  const [paramsOpen, setParamsOpen] = useState(true)

  const set = <K extends keyof StockForm>(k: K, v: StockForm[K]) => setForm((f) => ({ ...f, [k]: v }))

  async function loadPresets() {
    try {
      const res = await fetch('/api/backtest/configs')
      if (res.ok) setPresets(await res.json())
    } catch { /* ignore */ }
  }
  useEffect(() => { loadPresets() }, [])

  async function savePreset() {
    const name = presetName.trim()
    if (!name) return
    await fetch('/api/backtest/configs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, payload: form }),
    })
    setPresetName('')
    loadPresets()
  }
  async function deletePreset(id: string) {
    await fetch(`/api/backtest/configs/${id}`, { method: 'DELETE' })
    loadPresets()
  }
  function loadPreset(name: string) {
    const p = presets.find((x) => x.name === name)
    if (p) { setForm({ ...DEFAULTS, ...p.payload }); setPresetName(p.name) }
  }

  async function run() {
    setLoading(true); setError(null); setResult(null)
    try {
      const res = await fetch('/api/backtest/stock', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          symbols: form.symbols.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean),
          from: form.from,
          to: form.to,
          timeframe: form.timeframe,
          initialCapital: form.initialCapital,
          rsiPeriod: form.rsiPeriod,
          rsiOversold: form.rsiOversold,
          requireRsiRising: form.requireRsiRising,
          smaFastPeriod: form.smaFastPeriod,
          smaSlowPeriod: form.smaSlowPeriod,
          requireUptrend: form.requireUptrend,
          supportProximityPct: form.supportProximityPct,
          stopLossPct: form.stopLossPct,
          targetPct: form.targetPct,
          riskPerTrade: form.riskPerTrade,
          maxOpenPositions: form.maxOpenPositions,
        }),
      })
      if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`)
      setResult(await res.json())
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }

  const num = (k: keyof StockForm, label: string, step = 'any') => (
    <label className="block">
      <span className="text-xs text-muted-foreground">{label}</span>
      <input type="number" step={step} className={inputCls} value={form[k] as number}
        onChange={(e) => set(k, parseFloat(e.target.value) as never)} />
    </label>
  )
  const bool = (k: keyof StockForm, label: string) => (
    <label className="flex items-center gap-2 text-sm mt-4">
      <input type="checkbox" checked={form[k] as boolean} onChange={(e) => set(k, e.target.checked as never)} />
      {label}
    </label>
  )

  const s = result?.summary
  return (
    <div className="space-y-6 max-w-5xl">
      <div>
        <h1 className="text-xl font-semibold">Stock Backtest</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Rule strategy (support bounce). Data downloads automatically on first use, then serves from the store.
          Daily is the reliable deep-history timeframe; 4h is recent-only.
        </p>
      </div>

      {/* Presets */}
      <section className="border border-border rounded-lg p-4 bg-card space-y-3">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium">Presets:</span>
          <select className={inputCls + ' w-auto'} defaultValue="" onChange={(e) => e.target.value && loadPreset(e.target.value)}>
            <option value="">Load…</option>
            {presets.map((p) => <option key={p.id} value={p.name}>{p.name}</option>)}
          </select>
          <input className={inputCls + ' w-40'} placeholder="preset name" value={presetName} onChange={(e) => setPresetName(e.target.value)} />
          <button onClick={savePreset} disabled={!presetName.trim()}
            className="px-3 py-1 text-sm rounded bg-primary text-primary-foreground disabled:opacity-40">Save</button>
          {presets.map((p) => p.name === presetName.trim() && (
            <button key={p.id} onClick={() => deletePreset(p.id)}
              className="px-3 py-1 text-sm rounded bg-destructive/20 text-destructive">Delete “{p.name}”</button>
          ))}
        </div>
      </section>

      {/* Universe + window */}
      <section className="border border-border rounded-lg p-4 bg-card grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        <label className="block col-span-2">
          <span className="text-xs text-muted-foreground">Symbols (comma-sep)</span>
          <input className={inputCls} value={form.symbols} onChange={(e) => set('symbols', e.target.value)} />
        </label>
        <label className="block"><span className="text-xs text-muted-foreground">From</span>
          <input type="date" className={inputCls} value={form.from} onChange={(e) => set('from', e.target.value)} /></label>
        <label className="block"><span className="text-xs text-muted-foreground">To</span>
          <input type="date" className={inputCls} value={form.to} onChange={(e) => set('to', e.target.value)} /></label>
        <label className="block"><span className="text-xs text-muted-foreground">Timeframe</span>
          <select className={inputCls} value={form.timeframe} onChange={(e) => set('timeframe', e.target.value as Timeframe)}>
            <option value="1d">1 day</option>
            <option value="4h">4 hours</option>
          </select></label>
        {num('initialCapital', 'Initial capital')}
      </section>

      {/* Strategy params */}
      <section className="border border-border rounded-lg bg-card">
        <button onClick={() => setParamsOpen((o) => !o)} className="w-full flex justify-between items-center px-4 py-3 text-sm font-medium">
          <span>Strategy parameters</span><span className="text-muted-foreground">{paramsOpen ? '▲' : '▼'}</span>
        </button>
        {paramsOpen && (
          <div className="px-4 pb-4 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
            {num('rsiPeriod', 'RSI period', '1')}
            {num('rsiOversold', 'RSI oversold <')}
            {bool('requireRsiRising', 'RSI rising')}
            {num('smaFastPeriod', 'SMA fast (support)', '1')}
            {num('smaSlowPeriod', 'SMA slow (trend)', '1')}
            {bool('requireUptrend', 'Require uptrend (>slow SMA)')}
            {num('supportProximityPct', 'Support proximity %')}
            {num('stopLossPct', 'Stop-loss %')}
            {num('targetPct', 'Target %')}
            {num('riskPerTrade', 'Risk / trade $')}
            {num('maxOpenPositions', 'Max open positions', '1')}
          </div>
        )}
      </section>

      <div className="flex items-center gap-3">
        <button onClick={run} disabled={loading}
          className="px-5 py-2 rounded bg-primary text-primary-foreground font-medium disabled:opacity-50">
          {loading ? 'Running…' : 'Run backtest'}
        </button>
        {loading && <span className="text-xs text-muted-foreground">First run for a symbol/period downloads history — can take a few minutes.</span>}
        <button onClick={() => setForm(DEFAULTS)} className="text-sm text-muted-foreground hover:text-foreground">Reset</button>
      </div>

      {error && <p className="text-destructive text-sm break-all">{error}</p>}

      {/* Results */}
      {s && (
        <div className="space-y-4">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
            <Stat label="Trades" value={String(s.tradeCount)} sub={`${s.winCount}W / ${s.lossCount}L`} />
            <Stat label="Win rate" value={fmt(s.winRate * 100, 0) + '%'} />
            <Stat label="Total P&L" value={money(s.totalPnl)} sub={fmt(s.totalPnlPct) + '%'}
              color={s.totalPnl >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-500'} />
            <Stat label="Profit factor" value={fmt(s.profitFactor)} />
            <Stat label="Max drawdown" value={fmt(s.maxDrawdownPct) + '%'} color="text-red-500" />
            <Stat label="Final capital" value={'$' + s.finalCapital.toFixed(0)} />
          </div>

          <EquityCurve trades={result.trades} />

          <div className="overflow-x-auto border border-border rounded-lg">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground bg-muted/40">
                  {['Symbol', 'Entry', 'Exit', 'Reason', 'Entry $', 'Exit $', 'Shares', 'P&L'].map((h) =>
                    <th key={h} className="px-3 py-2 font-medium whitespace-nowrap">{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {result.trades.map((t, i) => (
                  <tr key={i} className="border-b border-border/40 last:border-0 hover:bg-muted/30">
                    <td className="px-3 py-1.5 font-mono font-medium">{t.symbol}</td>
                    <td className="px-3 py-1.5 tabular-nums text-muted-foreground whitespace-nowrap">{t.entryAt.slice(0, 10)}</td>
                    <td className="px-3 py-1.5 tabular-nums text-muted-foreground whitespace-nowrap">{t.exitAt.slice(0, 10)}</td>
                    <td className="px-3 py-1.5">{t.closeReason}</td>
                    <td className="px-3 py-1.5 tabular-nums">{fmt(t.entryPrice)}</td>
                    <td className="px-3 py-1.5 tabular-nums">{fmt(t.exitPrice)}</td>
                    <td className="px-3 py-1.5 tabular-nums">{t.shares}</td>
                    <td className={`px-3 py-1.5 tabular-nums font-medium ${t.pnl >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-500'}`}>{money(t.pnl)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}
