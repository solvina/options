import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useLocalStorage } from '../lib/useLocalStorage'

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
  atrPeriod: number
  stopAtrMultiple: number
  targetAtrMultiple: number
  riskPerTrade: number
  riskPerTradePct: number
  maxOpenPositions: number
  maxLeverage: number
}

const STORAGE_KEY = 'stockBacktestForm'

/** Minimum valid value per numeric field — the single source for both sanitizing and input `min`. */
const NUM_MIN: Partial<Record<keyof StockForm, number>> = {
  initialCapital: 1,
  rsiPeriod: 1,
  rsiOversold: 1,
  smaFastPeriod: 1,
  smaSlowPeriod: 1,
  supportProximityPct: 0,
  stopLossPct: 0.1,
  targetPct: 0.1,
  atrPeriod: 1,
  stopAtrMultiple: 0,
  targetAtrMultiple: 0,
  riskPerTrade: 1,
  riskPerTradePct: 0,
  maxOpenPositions: 1,
  maxLeverage: 0,
}

/** Coerces an untrusted form (localStorage blob, DB preset payload, mid-typing NaN state) into a
 *  runnable one: unknown shapes fall back to DEFAULTS, numeric fields below their minimum (or
 *  NaN/null) revert to the default value. */
function sanitizeForm(raw: unknown): StockForm {
  const src = raw !== null && typeof raw === 'object' ? (raw as Partial<StockForm>) : {}
  const f: StockForm = { ...DEFAULTS, ...src }
  for (const key of Object.keys(NUM_MIN) as (keyof StockForm)[]) {
    const v = f[key]
    if (typeof v !== 'number' || !isFinite(v) || v < NUM_MIN[key]!) {
      (f[key] as number) = DEFAULTS[key] as number
    }
  }
  if (f.riskPerTradePct > 100) f.riskPerTradePct = 100
  return f
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
  atrPeriod: 14,
  stopAtrMultiple: 0,
  targetAtrMultiple: 0,
  riskPerTrade: 200,
  riskPerTradePct: 0,
  maxOpenPositions: 1,
  maxLeverage: 0,
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
  avgRMultiple: number | null
  avgWinR: number | null
  avgLossR: number | null
  profitFactor: number | null
  maxDrawdownPct: number
  annualizedReturnPct: number | null
  buyHoldFinalCapital: number | null
  buyHoldPnl: number | null
  buyHoldPnlPct: number | null
  buyHoldAnnualizedPct: number | null
  benchmarks: { symbol: string; pnlPct: number; annualizedPct: number | null }[]
}
interface CurvePoint {
  date: string
  value: number
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
  buyHoldCurve: CurvePoint[]
}
interface Preset {
  id: string
  name: string
  payload: StockForm
  createdAt: string
}

const inputCls = 'border border-border rounded px-2 py-1 text-sm bg-background text-foreground w-full'
const fmt = (v: number | null | undefined, d = 2) =>
  v == null ? '—' : v.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d })
const money = (v: number) => `${v >= 0 ? '+' : '−'}$${Math.abs(v).toLocaleString('en-US', { maximumFractionDigits: 0 })}`

/** What each parameter does, what values mean, with examples — shown on the ⓘ hover. */
const HELP: Partial<Record<keyof StockForm, string>> = {
  symbols: 'Comma-separated tickers, e.g. "AAPL, MSFT, SPY". Buy & hold benchmark splits capital equally across them.',
  from: 'Backtest window start. Indicator warm-up bars before this date are fetched automatically.',
  to: 'Backtest window end. Positions still open here are marked-to-market at the last bar.',
  timeframe: '1d = one decision per day, deep history available (recommended). 4h = intraday swing, recent data only.',
  initialCapital: 'Starting account size. All % results (total, CAGR, drawdown) are measured against it.',
  rsiPeriod: 'Bars used for RSI. 14 = standard. 7 reacts faster but noisier; 21 smoother with fewer signals.',
  rsiOversold: 'Entry requires RSI below this. 30–40 = classic beaten-down pullback; 50–55 = looser, catches shallow dips, many more trades.',
  requireRsiRising: 'Also require RSI turning up vs the previous bar — "the bounce has started". Off ≈ twice the signals, earlier but weaker entries.',
  smaFastPeriod: 'The support line: entries only when price sits just above this SMA. 50 = classic swing support; 20 = shorter cycle, more signals.',
  smaSlowPeriod: 'The trend line used by "Require uptrend". 200 = long-term bull/bear divider.',
  requireUptrend: 'Only enter when price is above the slow SMA (established uptrend). Off = also trades bear markets — more trades, lower win rate.',
  supportProximityPct: 'How far above the fast SMA price may be and still count as "at support". 3 = within a 3% band. Wider (5–8) = more entries, weaker signal.',
  stopLossPct: 'Exit if price drops this % below entry. 3 on a $100 entry = stop at $97. Tighter stop = more shares per $ risked, but more shake-outs. Ignored when Stop ATR × > 0.',
  atrPeriod: 'Lookback (bars) for the Average True Range used by the ATR-based stop/target. 14 is the Wilder default.',
  stopAtrMultiple: 'When > 0, stop = entry − ATR × this, replacing Stop-loss %. Volatility-scaled: same multiple gives wider stops in wild markets, tighter in calm ones. 2–3 is a common range.',
  targetAtrMultiple: 'When > 0, target = entry + ATR × this, replacing Target %. Pair with Stop ATR × to keep a fixed reward:risk ratio across volatility regimes.',
  targetPct: 'Exit if price rises this % above entry. 6 with a 3 stop = 2:1 reward-to-risk; break-even win rate ≈ 33%.',
  riskPerTrade: 'Fixed $ lost if the stop is hit; shares = risk ÷ (entry − stop). $200 risk, 3% stop, $150 stock ≈ 44 shares. Ignored when % of capital is set.',
  riskPerTradePct: 'Risk this % of CURRENT capital per trade — compounds as the account grows (1% = $200 on $20k, $300 on $30k). 0 = use the fixed $ instead.',
  maxOpenPositions: 'Max simultaneous positions.',
  maxLeverage: 'Optional buying-power ceiling: cap a position\'s notional at capital × this. 0 = uncapped (pure risk sizing). 1 = cash account, 4 = Reg-T intraday. Leave 0 unless you want to constrain leverage — a low cap silently masks the risk lever on high-priced, tight-stop names.',
}

function Help({ text }: { text?: string }) {
  if (!text) return null
  return (
    <span
      title={text}
      className="ml-1 inline-flex items-center justify-center w-3.5 h-3.5 rounded-full border border-muted-foreground/40 text-muted-foreground/70 text-[9px] font-semibold cursor-help select-none align-middle"
    >
      i
    </span>
  )
}

function Stat({ label, value, sub, color, help }: { label: string; value: string; sub?: string; color?: string; help?: string }) {
  return (
    <div className="border border-border rounded-lg p-3 bg-card" title={help}>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={`text-lg font-semibold tabular-nums ${color ?? ''}`}>{value}</p>
      {sub && <p className="text-xs text-muted-foreground mt-0.5">{sub}</p>}
    </div>
  )
}

const money0 = (v: number) => '$' + Math.round(v).toLocaleString('en-US')

/** Account-value curve over closed trades, annotated with start / end / max / min (no chart lib). */
function EquityCurve({ trades, initialCapital, buyHoldCurve }: { trades: RuleTrade[]; initialCapital: number; buyHoldCurve: CurvePoint[] }) {
  const [hoverI, setHoverI] = useState<number | null>(null)
  // Match the viewBox width to the rendered width (1 unit = 1 CSS px) so the drawing fills the
  // card instead of being letterboxed, and text keeps its aspect.
  const wrapRef = useRef<HTMLDivElement>(null)
  const [chartW, setChartW] = useState(600)
  useEffect(() => {
    const el = wrapRef.current
    if (!el) return
    const ro = new ResizeObserver(() => setChartW(Math.max(300, el.clientWidth)))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  const sorted = [...trades].sort((a, b) => new Date(a.exitAt).getTime() - new Date(b.exitAt).getTime())
  if (sorted.length < 2) return null
  let cum = initialCapital
  const points = [initialCapital, ...sorted.map((t) => (cum += t.pnl))]
  // Buy&hold value at each strategy point's date (last curve value ≤ that date), so both lines
  // share the trade-index x-axis honestly.
  const holdAt = (dateStr: string): number | null => {
    let v: number | null = null
    for (const p of buyHoldCurve) {
      if (p.date > dateStr) break
      v = p.value
    }
    return v
  }
  const holdPoints = buyHoldCurve.length > 0
    ? [initialCapital, ...sorted.map((t) => holdAt(t.exitAt.slice(0, 10)) ?? initialCapital)]
    : null
  const min = Math.min(...points, ...(holdPoints ?? []))
  const max = Math.max(...points, ...(holdPoints ?? []))
  const range = max - min || 1
  const W = chartW
  const H = 150
  const padX = 8
  const padY = 18
  const x = (i: number) => padX + (i / (points.length - 1)) * (W - 2 * padX)
  const y = (v: number) => padY + (1 - (v - min) / range) * (H - 2 * padY)
  const path = points.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')
  const maxI = points.indexOf(max)
  const minI = points.indexOf(min)
  const last = points[points.length - 1]
  // Anchor labels away from the chart edge they're closest to.
  const anchor = (i: number) => (x(i) < W / 3 ? 'start' : x(i) > (2 * W) / 3 ? 'end' : 'middle')
  // Crosshair: snap the mouse to the nearest closed-trade point (point 0 is the starting capital).
  // getScreenCTM maps client px → viewBox units, correctly handling the letterbox margins that
  // preserveAspectRatio adds when the container's aspect ratio differs from the viewBox's.
  const onMove = (e: React.MouseEvent<SVGSVGElement>) => {
    const ctm = e.currentTarget.getScreenCTM()
    if (!ctm) return
    const vx = new DOMPoint(e.clientX, e.clientY).matrixTransform(ctm.inverse()).x
    const i = Math.round(((vx - padX) / (W - 2 * padX)) * (points.length - 1))
    setHoverI(Math.min(points.length - 1, Math.max(0, i)))
  }
  const hoverLabel = (i: number) =>
    (i === 0 ? 'start' : sorted[i - 1].exitAt.slice(0, 10)) + ' · ' + money0(points[i]) +
    (holdPoints ? ` · hold ${money0(holdPoints[i])}` : '')
  return (
    <div className="border border-border rounded-lg bg-card p-2">
      <p className="text-xs text-muted-foreground px-1">
        Account value over closed trades
        {holdPoints && <span className="text-muted-foreground/60"> · dashed = buy&nbsp;&amp;&nbsp;hold</span>}
      </p>
      <div ref={wrapRef} className="w-full">
        <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} className="block" onMouseMove={onMove} onMouseLeave={() => setHoverI(null)}>
        <line x1={padX} y1={y(initialCapital)} x2={W - padX} y2={y(initialCapital)}
          strokeDasharray="4 3" strokeWidth="1" stroke="currentColor" className="text-muted-foreground/40" />
        {holdPoints && (
          <polyline points={holdPoints.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')}
            fill="none" strokeWidth="1.2" strokeDasharray="5 4" stroke="currentColor" className="text-sky-500/70" />
        )}
        <polyline points={path} fill="none" strokeWidth="1.5" stroke="currentColor"
          className={last >= initialCapital ? 'text-green-500' : 'text-red-500'} />
        <circle cx={x(maxI)} cy={y(max)} r="3" fill="currentColor" className="text-green-500" />
        <circle cx={x(minI)} cy={y(min)} r="3" fill="currentColor" className="text-red-500" />
        {maxI !== points.length - 1 && (
          <text x={x(maxI)} y={y(max) - 6} textAnchor={anchor(maxI)} fontSize="11" fill="currentColor" className="text-muted-foreground">
            max {money0(max)}
          </text>
        )}
        {minI !== 0 && (
          <text x={x(minI)} y={y(min) + 14} textAnchor={anchor(minI)} fontSize="11" fill="currentColor" className="text-muted-foreground">
            min {money0(min)}
          </text>
        )}
        <text x={padX + 2} y={y(initialCapital) - 6} fontSize="11" fill="currentColor" className="text-muted-foreground/70">
          start {money0(initialCapital)}
        </text>
        <text x={W - padX - 2} y={y(last) + (last >= initialCapital ? -8 : 16)} textAnchor="end" fontSize="11"
          fill="currentColor" className="font-medium text-foreground">
          end {money0(last)}
        </text>
        {hoverI != null && (
          <g pointerEvents="none">
            <line x1={x(hoverI)} y1={padY - 4} x2={x(hoverI)} y2={H - padY + 4}
              strokeWidth="1" stroke="currentColor" className="text-muted-foreground/60" />
            <circle cx={x(hoverI)} cy={y(points[hoverI])} r="3" fill="currentColor" className="text-foreground" />
            <text x={x(hoverI)} y={padY - 6} textAnchor={anchor(hoverI)} fontSize="11"
              fill="currentColor" className="font-medium text-foreground">
              {hoverLabel(hoverI)}
            </text>
          </g>
        )}
        </svg>
      </div>
    </div>
  )
}

export function StockBacktestPage() {
  // All form mutations go through setForm (the hook persists every update), so presets, resets,
  // and keystrokes all survive a reload. Rendering is lenient (a cleared field may hold NaN while
  // the user types); sanitizeForm runs at the boundaries — Run and preset load — so what executes
  // is always valid and the inputs snap to the values actually used.
  const navigate = useNavigate()
  const [savedForm, setForm] = useLocalStorage<StockForm>(STORAGE_KEY, DEFAULTS)
  const form: StockForm = { ...DEFAULTS, ...(savedForm !== null && typeof savedForm === 'object' ? savedForm : {}) }
  const [presets, setPresets] = useState<Preset[]>([])
  const [presetName, setPresetName] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<Result | null>(null)
  const [paramsOpen, setParamsOpen] = useState(true)

  const set = <K extends keyof StockForm>(k: K, v: StockForm[K]) => setForm({ ...savedForm, [k]: v })

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
    // Through sanitize + setForm so the preset both survives a reload and can't carry bad values.
    if (p) { setForm(sanitizeForm({ ...DEFAULTS, ...p.payload })); setPresetName(p.name) }
  }

  /** Downloads a scripts/param-sweep.py config seeded from the current form: every numeric rule
   *  parameter appears under "sweep" with min = max = its current value (a single-value sweep) —
   *  widen the ranges you actually want to explore, leave the rest pinned. */
  async function run() {
    // Sanitize before running and write the result back, so the inputs show exactly the params
    // the backtest executed (a cleared field reverts to its default visibly, not silently).
    const f = sanitizeForm(savedForm)
    setForm(f)
    setLoading(true); setError(null); setResult(null)
    try {
      const res = await fetch('/api/backtest/stock', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          symbols: f.symbols.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean),
          from: f.from,
          to: f.to,
          timeframe: f.timeframe,
          initialCapital: f.initialCapital,
          rsiPeriod: f.rsiPeriod,
          rsiOversold: f.rsiOversold,
          requireRsiRising: f.requireRsiRising,
          smaFastPeriod: f.smaFastPeriod,
          smaSlowPeriod: f.smaSlowPeriod,
          requireUptrend: f.requireUptrend,
          supportProximityPct: f.supportProximityPct,
          stopLossPct: f.stopLossPct,
          targetPct: f.targetPct,
          atrPeriod: f.atrPeriod,
          stopAtrMultiple: f.stopAtrMultiple,
          targetAtrMultiple: f.targetAtrMultiple,
          riskPerTrade: f.riskPerTrade,
          riskPerTradePct: f.riskPerTradePct,
          maxOpenPositions: f.maxOpenPositions,
          maxLeverage: f.maxLeverage,
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

  const num = (k: keyof StockForm, label: string, step = 'any', disabled = false) => (
    <label className="block">
      <span className="text-xs text-muted-foreground">{label}<Help text={HELP[k]} /></span>
      <input type="number" step={step} min={NUM_MIN[k]} className={inputCls + (disabled ? ' opacity-40' : '')} disabled={disabled}
        value={Number.isFinite(form[k] as number) ? (form[k] as number) : ''}
        onChange={(e) => set(k, parseFloat(e.target.value) as never)} />
    </label>
  )
  const bool = (k: keyof StockForm, label: string) => (
    <label className="flex items-center gap-2 text-sm mt-4">
      <input type="checkbox" checked={form[k] as boolean} onChange={(e) => set(k, e.target.checked as never)} />
      {label}<Help text={HELP[k]} />
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

      {/* Universe + window: symbols / timeframe / capital · from / to · year / period */}
      <section className="border border-border rounded-lg p-4 bg-card grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        <label className="block col-span-2 lg:col-span-3">
          <span className="text-xs text-muted-foreground">Symbols (comma-sep)<Help text={HELP.symbols} /></span>
          <input className={inputCls} value={form.symbols} onChange={(e) => set('symbols', e.target.value)} />
        </label>
        <label className="block"><span className="text-xs text-muted-foreground">Timeframe<Help text={HELP.timeframe} /></span>
          <select className={inputCls} value={form.timeframe} onChange={(e) => set('timeframe', e.target.value as Timeframe)}>
            <option value="1d">1 day</option>
            <option value="4h">4 hours</option>
          </select></label>
        {num('initialCapital', 'Initial capital')}
        <label className="block"><span className="text-xs text-muted-foreground">From<Help text={HELP.from} /></span>
          <input type="date" className={inputCls} value={form.from} onChange={(e) => set('from', e.target.value)} /></label>
        <label className="block"><span className="text-xs text-muted-foreground">To<Help text={HELP.to} /></span>
          <input type="date" className={inputCls} value={form.to} onChange={(e) => set('to', e.target.value)} /></label>
        <label className="block sm:col-start-1"><span className="text-xs text-muted-foreground">Year<Help text="Quick-pick: sets From to Jan 1 of the year and To to Jan 1 of the next year." /></span>
          <select className={inputCls} value="" onChange={(e) => {
            const y = parseInt(e.target.value)
            if (y) setForm({ ...form, from: `${y}-01-01`, to: `${y + 1}-01-01` })
          }}>
            <option value="">Pick…</option>
            {Array.from({ length: new Date().getFullYear() - 1999 }, (_, i) => new Date().getFullYear() - i).map((y) =>
              <option key={y} value={y}>{y}</option>)}
          </select></label>
        <label className="block"><span className="text-xs text-muted-foreground">Period<Help text="Sets To = From + the chosen period. Pick From first (or use Year), then stretch the window." /></span>
          <select className={inputCls} value="" onChange={(e) => {
            const months = parseInt(e.target.value)
            if (!months) return
            const [y, m, d] = form.from.split('-').map(Number)
            if (!y || !m || !d) return
            setForm({ ...form, to: new Date(Date.UTC(y, m - 1 + months, d)).toISOString().slice(0, 10) })
          }}>
            <option value="">Pick…</option>
            {[[1, '1 month'], [2, '2 months'], [3, '3 months'], [6, 'half a year'], [12, '1 year'], [24, '2 years'], [36, '3 years'], [60, '5 years'], [120, '10 years']].map(([v, l]) =>
              <option key={v} value={v}>{l}</option>)}
          </select></label>
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
            {num('targetPct', 'Target %', 'any', form.targetAtrMultiple > 0)}
            {num('stopLossPct', 'Stop-loss %', 'any', form.stopAtrMultiple > 0)}
            {num('atrPeriod', 'ATR period', '1')}
            {num('stopAtrMultiple', 'Stop ATR × (0 = use %)')}
            {num('targetAtrMultiple', 'Target ATR × (0 = use %)')}
            <label className="block">
              <span className="text-xs text-muted-foreground">Risk / trade $ (fixed)<Help text={HELP.riskPerTrade} /></span>
              <input type="number" step="any" min={NUM_MIN.riskPerTrade} className={inputCls + (form.riskPerTradePct > 0 ? ' opacity-40' : '')}
                value={Number.isFinite(form.riskPerTrade) ? form.riskPerTrade : ''} disabled={form.riskPerTradePct > 0}
                onChange={(e) => set('riskPerTrade', parseFloat(e.target.value))} />
            </label>
            <label className="block">
              <span className="text-xs text-muted-foreground">Risk / trade % of capital<Help text={HELP.riskPerTradePct} /></span>
              <input type="number" step="0.1" min="0" max="100" className={inputCls}
                value={Number.isFinite(form.riskPerTradePct) ? form.riskPerTradePct : ''}
                onChange={(e) => set('riskPerTradePct', parseFloat(e.target.value))} />
            </label>
            {num('maxOpenPositions', 'Max open positions', '1')}
            {num('maxLeverage', 'Max leverage (0 = uncapped)', '0.5')}
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
        <button onClick={() => navigate('/backtest/sweeps/new', { state: { seed: sanitizeForm(savedForm) } })}
          title="Open the sweep form seeded with these parameters — pick which ones to sweep, then start it as an engine job."
          className="text-sm text-muted-foreground hover:text-foreground border border-border rounded px-3 py-1.5">
          New sweep from these params
        </button>
      </div>

      {error && <p className="text-destructive text-sm break-all">{error}</p>}

      {/* Results */}
      {s && (() => {
        const years = Math.max((new Date(s.to).getTime() - new Date(s.from).getTime()) / (365.25 * 86400000), 1 / 365.25)
        const posColor = 'text-green-600 dark:text-green-400'
        return (
        <div className="space-y-4">
          {/* Group 1 — what the strategy made */}
          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted-foreground/70 mb-1.5">Result</p>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <Stat label="Total P&L" value={money(s.totalPnl)} sub={`${fmt(s.totalPnlPct)}% of start`}
                color={s.totalPnl >= 0 ? posColor : 'text-red-500'}
                help="Sum of all trade P&L in dollars, and as % of initial capital." />
              <Stat label="Final capital" value={money0(s.finalCapital)} sub={`from ${money0(s.initialCapital)}`}
                help="Account value at the end of the window vs the starting value." />
              <Stat label="Avg / year" value={fmt(s.annualizedReturnPct) + '%/yr'} sub="strategy CAGR"
                color={(s.annualizedReturnPct ?? 0) >= 0 ? posColor : 'text-red-500'}
                help="Compound annual growth rate: the constant yearly % that turns initial into final capital over this window. Directly comparable across different period lengths." />
              <Stat label="Max drawdown" value={'−' + fmt(s.maxDrawdownPct) + '%'} color="text-red-500" sub="peak-to-trough equity"
                help="Worst decline from an equity high before recovering. The pain metric: how much you'd have watched disappear at the worst moment." />
            </div>
          </div>

          {/* Group 2 — how it traded */}
          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted-foreground/70 mb-1.5">Trades</p>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <Stat label="Trades" value={String(s.tradeCount)}
                sub={`${s.winCount}W / ${s.lossCount}L${s.eodCount > 0 ? ` / ${s.eodCount} open-end` : ''} · ${(s.tradeCount / years).toFixed(1)}/yr`}
                help="Closed trades in the window. W = profit target hit, L = stop hit, open-end = still open at window end (marked-to-market)." />
              <Stat label="Win rate" value={fmt(s.winRate * 100, 0) + '%'} sub="of target/stop exits"
                help="Winners ÷ (winners + losers). Trades still open at the end are excluded. Compare against the break-even rate for your target:stop ratio — 2:1 needs ≈33%." />
              <Stat label="Profit factor" value={fmt(s.profitFactor)} sub="gross wins ÷ gross losses"
                help="Total win dollars divided by total loss dollars. >1 = profitable; 1.5–2 is solid; below 1.2 the edge is thin after costs." />
              <Stat label="Avg R" value={s.avgRMultiple != null ? `${s.avgRMultiple >= 0 ? '+' : ''}${fmt(s.avgRMultiple)}R` : '—'}
                sub={`win ${fmt(s.avgWinR)}R · loss ${fmt(s.avgLossR)}R`}
                color={(s.avgRMultiple ?? 0) >= 0 ? posColor : 'text-red-500'}
                help="Average result per unit of risk (R = the $ risked at the stop). +0.25R means the average trade made a quarter of what it risked. Positive = edge." />
            </div>
          </div>

          {/* Group 3 — was it worth it vs doing nothing */}
          <div>
            <p className="text-[11px] uppercase tracking-wide text-muted-foreground/70 mb-1.5">vs Buy & Hold</p>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <Stat label="Hold P&L" value={s.buyHoldPnl != null ? money(s.buyHoldPnl) : '—'}
                sub={s.buyHoldPnlPct != null ? `${fmt(s.buyHoldPnlPct)}% of start` : undefined}
                color={(s.buyHoldPnl ?? 0) >= 0 ? posColor : 'text-red-500'}
                help="Absolute gain of the benchmark: initial capital split equally across the symbols, bought at the first bar, held to the last." />
              <Stat label="Hold final" value={s.buyHoldFinalCapital != null ? money0(s.buyHoldFinalCapital) : '—'}
                sub={`from ${money0(s.initialCapital)}`}
                help="What the account would be worth at the end by just holding — compare directly with Final capital above." />
              <Stat label="Hold / year" value={fmt(s.buyHoldAnnualizedPct) + '%/yr'} sub="hold CAGR"
                color={(s.buyHoldAnnualizedPct ?? 0) >= 0 ? posColor : 'text-red-500'}
                help="Buy & hold compound annual growth rate over the same window — the bar the strategy has to clear." />
              <Stat label="Edge vs hold"
                value={s.annualizedReturnPct != null && s.buyHoldAnnualizedPct != null
                  ? `${s.annualizedReturnPct - s.buyHoldAnnualizedPct >= 0 ? '+' : '−'}${Math.abs(s.annualizedReturnPct - s.buyHoldAnnualizedPct).toFixed(2)} pp/yr`
                  : '—'}
                sub="strategy − hold, per year"
                color={s.annualizedReturnPct != null && s.buyHoldAnnualizedPct != null && s.annualizedReturnPct >= s.buyHoldAnnualizedPct
                  ? posColor : 'text-red-500'}
                help="Strategy CAGR minus buy & hold CAGR in percentage points per year. Green = the strategy beat simply holding the stock(s)." />
            </div>
          </div>

          {/* Group 4 — vs sector ETF / broad market (buy & hold of each benchmark over the window) */}
          {(s.benchmarks?.length ?? 0) > 0 && (
            <div>
              <p className="text-[11px] uppercase tracking-wide text-muted-foreground/70 mb-1.5">vs Sector / Market ETF</p>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                {s.benchmarks.map((b) => (
                  <Stat key={b.symbol} label={`Hold ${b.symbol}`}
                    value={b.annualizedPct != null ? `${fmt(b.annualizedPct)}%/yr` : `${fmt(b.pnlPct)}%`}
                    sub={`${fmt(b.pnlPct)}% total`}
                    color={s.annualizedReturnPct != null && b.annualizedPct != null && s.annualizedReturnPct >= b.annualizedPct
                      ? posColor : 'text-red-500'}
                    help={`Buy & hold of ${b.symbol} (sector/market ETF) over the same window. Green = the strategy's CAGR beat it.`} />
                ))}
              </div>
            </div>
          )}

          <EquityCurve trades={result.trades} initialCapital={s.initialCapital} buyHoldCurve={result.buyHoldCurve ?? []} />

          <div className="overflow-x-auto border border-border rounded-lg">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-left text-muted-foreground bg-muted/40">
                  {['Symbol', 'Entry', 'Exit', 'Days', 'Open in', 'Open out', 'Reason', 'Entry $', 'Exit $', 'Shares', 'P&L', 'P&L %', 'Cum P&L', 'Cum %', 'Account'].map((h) =>
                    <th key={h} className="px-3 py-2 font-medium whitespace-nowrap"
                      title={h === 'Open in' ? 'Other positions already open when this trade was entered (first trade = 0)'
                        : h === 'Open out' ? 'Other positions still open when this trade was closed' : undefined}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {(() => {
                  const sorted = [...result.trades].sort((a, b) => new Date(a.exitAt).getTime() - new Date(b.exitAt).getTime())
                  // Other positions whose [entry, exit) interval covers the given moment.
                  const openAt = (self: RuleTrade, at: number) =>
                    sorted.filter((u) => u !== self && new Date(u.entryAt).getTime() <= at && new Date(u.exitAt).getTime() > at).length
                  let cum = 0
                  return sorted.map((t, i) => {
                    cum += t.pnl
                    const openIn = openAt(t, new Date(t.entryAt).getTime())
                    const openOut = openAt(t, new Date(t.exitAt).getTime())
                    const days = Math.max(1, Math.round((new Date(t.exitAt).getTime() - new Date(t.entryAt).getTime()) / 86400000))
                    const pnlPct = (t.exitPrice / t.entryPrice - 1) * 100
                    const cumPct = (cum / s.initialCapital) * 100
                    const pos = 'text-green-600 dark:text-green-400'
                    return (
                      <tr key={i} className="border-b border-border/40 last:border-0 hover:bg-muted/30">
                        <td className="px-3 py-1.5 font-mono font-medium">{t.symbol}</td>
                        <td className="px-3 py-1.5 tabular-nums text-muted-foreground whitespace-nowrap">{t.entryAt.slice(0, 10)}</td>
                        <td className="px-3 py-1.5 tabular-nums text-muted-foreground whitespace-nowrap">{t.exitAt.slice(0, 10)}</td>
                        <td className="px-3 py-1.5 tabular-nums text-muted-foreground">{days}</td>
                        <td className="px-3 py-1.5 tabular-nums text-muted-foreground">{openIn}</td>
                        <td className="px-3 py-1.5 tabular-nums text-muted-foreground">{openOut}</td>
                        <td className="px-3 py-1.5">{t.closeReason.replace('_', ' ')}</td>
                        <td className="px-3 py-1.5 tabular-nums">{fmt(t.entryPrice)}</td>
                        <td className="px-3 py-1.5 tabular-nums">{fmt(t.exitPrice)}</td>
                        <td className="px-3 py-1.5 tabular-nums">{t.shares}</td>
                        <td className={`px-3 py-1.5 tabular-nums font-medium ${t.pnl >= 0 ? pos : 'text-red-500'}`}>{money(t.pnl)}</td>
                        <td className={`px-3 py-1.5 tabular-nums ${pnlPct >= 0 ? pos : 'text-red-500'}`}>{pnlPct >= 0 ? '+' : ''}{pnlPct.toFixed(1)}%</td>
                        <td className={`px-3 py-1.5 tabular-nums font-medium ${cum >= 0 ? pos : 'text-red-500'}`}>{money(cum)}</td>
                        <td className={`px-3 py-1.5 tabular-nums ${cum >= 0 ? pos : 'text-red-500'}`}>{cum >= 0 ? '+' : ''}{cumPct.toFixed(1)}%</td>
                        <td className="px-3 py-1.5 tabular-nums font-medium">{money0(s.initialCapital + cum)}</td>
                      </tr>
                    )
                  })
                })()}
              </tbody>
            </table>
          </div>
        </div>
        )
      })()}
    </div>
  )
}
