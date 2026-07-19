import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

// Seed shape = StockBacktestPage's form (passed via router state, falls back to its localStorage).
interface Seed {
  symbols: string
  from: string
  to: string
  timeframe: string
  initialCapital: number
  requireRsiRising: boolean
  requireUptrend: boolean
  riskPerTrade: number
  [k: string]: string | number | boolean
}

type Mode = 'fixed' | 'range' | 'list'

interface Axis {
  mode: Mode
  value: number // fixed
  min: number
  max: number
  step: number
  list: string // comma-separated
}

interface Preview {
  totalCombos: number
  redundantCombos: number
  toRun: number
  axisSizes: Record<string, number>
}

const INT_PARAMS = new Set(['rsiPeriod', 'smaFastPeriod', 'smaSlowPeriod', 'atrPeriod', 'maxOpenPositions'])
const PARAMS: { key: string; label: string; hint?: string }[] = [
  { key: 'rsiPeriod', label: 'RSI period' },
  { key: 'rsiOversold', label: 'RSI oversold' },
  { key: 'smaFastPeriod', label: 'SMA fast' },
  { key: 'smaSlowPeriod', label: 'SMA slow' },
  { key: 'supportProximityPct', label: 'Support proximity %' },
  { key: 'stopLossPct', label: 'Stop-loss %', hint: 'ignored in combos where Stop ATR × > 0' },
  { key: 'stopAtrMultiple', label: 'Stop ATR ×' },
  { key: 'targetPct', label: 'Target %', hint: 'ignored in combos where Target ATR × > 0' },
  { key: 'targetAtrMultiple', label: 'Target ATR ×' },
  { key: 'atrPeriod', label: 'ATR period' },
  { key: 'riskPerTradePct', label: 'Risk per trade %' },
  { key: 'maxOpenPositions', label: 'Max open positions' },
]

const DEFAULT_SEED: Seed = {
  symbols: 'AAPL', from: '2015-01-01', to: '2025-01-01', timeframe: '1d', initialCapital: 20000,
  requireRsiRising: true, requireUptrend: true, riskPerTrade: 200,
  rsiPeriod: 14, rsiOversold: 40, smaFastPeriod: 50, smaSlowPeriod: 200, supportProximityPct: 3,
  stopLossPct: 3, targetPct: 6, atrPeriod: 14, stopAtrMultiple: 0, targetAtrMultiple: 0,
  riskPerTradePct: 0, maxOpenPositions: 1,
}

const inputCls = 'border border-border rounded px-2 py-1 text-sm bg-background text-foreground w-full'
const nfmt = (v: number) => v.toLocaleString('en-US')

export function SweepNewPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const seed: Seed = useMemo(() => {
    const fromState = (location.state as { seed?: Seed } | null)?.seed
    if (fromState) return { ...DEFAULT_SEED, ...fromState }
    try {
      const ls = localStorage.getItem('stockBacktestForm')
      if (ls) return { ...DEFAULT_SEED, ...JSON.parse(ls) }
    } catch { /* fall through */ }
    return DEFAULT_SEED
  }, [location.state])

  const [symbols, setSymbols] = useState(String(seed.symbols))
  const [from, setFrom] = useState(String(seed.from))
  const [to, setTo] = useState(String(seed.to))
  const [timeframe, setTimeframe] = useState(String(seed.timeframe))
  const [initialCapital, setInitialCapital] = useState(Number(seed.initialCapital))
  const [name, setName] = useState(
    `sweep-${String(seed.symbols).split(',')[0].trim().toUpperCase()}-${seed.timeframe}-${new Date().toISOString().slice(0, 10).replaceAll('-', '')}`,
  )
  const [parallelism, setParallelism] = useState(0) // 0 = server default (CPU cores)
  const [axes, setAxes] = useState<Record<string, Axis>>(() => {
    const a: Record<string, Axis> = {}
    for (const p of PARAMS) {
      const v = Number(seed[p.key] ?? 0)
      a[p.key] = { mode: 'fixed', value: v, min: v, max: v, step: INT_PARAMS.has(p.key) ? 1 : 0.1, list: String(v) }
    }
    return a
  })
  const [preview, setPreview] = useState<Preview | null>(null)
  const [previewErr, setPreviewErr] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [submitErr, setSubmitErr] = useState<string | null>(null)

  function buildBody() {
    const request: Record<string, unknown> = {
      symbols: symbols.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean),
      from, to, timeframe, initialCapital,
      requireRsiRising: seed.requireRsiRising,
      requireUptrend: seed.requireUptrend,
      riskPerTrade: seed.riskPerTrade,
    }
    const sweep: Record<string, unknown> = {}
    for (const p of PARAMS) {
      const a = axes[p.key]
      if (a.mode === 'fixed') request[p.key] = a.value
      else if (a.mode === 'range') sweep[p.key] = { min: a.min, max: a.max, step: a.step }
      else sweep[p.key] = a.list.split(',').map((s) => Number(s.trim())).filter((n) => isFinite(n))
    }
    return { name: name.trim(), parallelism: parallelism > 0 ? parallelism : undefined, request, sweep }
  }

  const sweptCount = Object.values(axes).filter((a) => a.mode !== 'fixed').length

  // Live variant count while editing (debounced).
  useEffect(() => {
    if (sweptCount === 0) { setPreview(null); setPreviewErr(null); return }
    const t = setTimeout(async () => {
      try {
        const res = await fetch('/api/backtest/sweeps/preview', {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(buildBody()),
        })
        const body = await res.json()
        if (res.ok) { setPreview(body); setPreviewErr(null) } else { setPreview(null); setPreviewErr(body.error ?? 'invalid sweep') }
      } catch (e) { setPreview(null); setPreviewErr(String(e)) }
    }, 400)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [axes, symbols, from, to, timeframe, initialCapital, name, parallelism])

  async function start() {
    setSubmitting(true)
    setSubmitErr(null)
    try {
      const res = await fetch('/api/backtest/sweeps', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(buildBody()),
      })
      if (res.ok) { navigate('/backtest/sweeps'); return }
      const body = await res.json().catch(() => ({}))
      setSubmitErr(body.error ?? `HTTP ${res.status}`)
    } catch (e) {
      setSubmitErr(String(e))
    } finally {
      setSubmitting(false)
    }
  }

  function setAxis(key: string, patch: Partial<Axis>) {
    setAxes((prev) => ({ ...prev, [key]: { ...prev[key], ...patch } }))
  }

  return (
    <div className="space-y-4 max-w-5xl">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">New parameter sweep</h1>
        <button className="text-sm underline" onClick={() => navigate('/backtest/sweeps')}>← sweep jobs</button>
      </div>

      <div className="border border-border rounded p-4 space-y-3">
        <div className="grid grid-cols-2 md:grid-cols-6 gap-3">
          <label className="text-sm col-span-2">Symbols
            <input className={inputCls} value={symbols} onChange={(e) => setSymbols(e.target.value)} />
          </label>
          <label className="text-sm">From
            <input type="date" className={inputCls} value={from} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="text-sm">To
            <input type="date" className={inputCls} value={to} onChange={(e) => setTo(e.target.value)} />
          </label>
          <label className="text-sm">Timeframe
            <select className={inputCls} value={timeframe} onChange={(e) => setTimeframe(e.target.value)}>
              <option value="1d">1d</option>
              <option value="4h">4h</option>
            </select>
          </label>
          <label className="text-sm">Initial capital
            <input type="number" className={inputCls} value={initialCapital} onChange={(e) => setInitialCapital(Number(e.target.value))} />
          </label>
        </div>
        <div className="grid grid-cols-2 md:grid-cols-6 gap-3">
          <label className="text-sm col-span-2">Sweep name <span className="text-muted-foreground">(= output dir; same name resumes)</span>
            <input className={inputCls} value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="text-sm">Parallelism
            <input type="number" min={0} max={64} className={inputCls} value={parallelism}
              onChange={(e) => setParallelism(Number(e.target.value))} title="0 = CPU cores" />
          </label>
        </div>
      </div>

      <div className="border border-border rounded p-4">
        <div className="text-sm font-medium mb-2">Parameters — fixed, range, or explicit list</div>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-muted-foreground">
              <th className="py-1 pr-2">Parameter</th>
              <th className="py-1 pr-2 w-24">Mode</th>
              <th className="py-1">Values</th>
              <th className="py-1 w-16 text-right">#</th>
            </tr>
          </thead>
          <tbody>
            {PARAMS.map((p) => {
              const a = axes[p.key]
              const n = preview?.axisSizes?.[p.key]
              return (
                <tr key={p.key} className="border-t border-border">
                  <td className="py-1 pr-2">
                    {p.label}
                    {p.hint && <span className="text-xs text-muted-foreground block">{p.hint}</span>}
                  </td>
                  <td className="py-1 pr-2">
                    <select className={inputCls} value={a.mode} onChange={(e) => setAxis(p.key, { mode: e.target.value as Mode })}>
                      <option value="fixed">fixed</option>
                      <option value="range">range</option>
                      <option value="list">list</option>
                    </select>
                  </td>
                  <td className="py-1">
                    {a.mode === 'fixed' && (
                      <input type="number" className={inputCls} value={a.value}
                        onChange={(e) => setAxis(p.key, { value: Number(e.target.value) })} />
                    )}
                    {a.mode === 'range' && (
                      <div className="flex gap-2 items-center">
                        <input type="number" className={inputCls} value={a.min} title="min"
                          onChange={(e) => setAxis(p.key, { min: Number(e.target.value) })} />
                        <span className="text-muted-foreground">→</span>
                        <input type="number" className={inputCls} value={a.max} title="max"
                          onChange={(e) => setAxis(p.key, { max: Number(e.target.value) })} />
                        <span className="text-muted-foreground">step</span>
                        <input type="number" className={inputCls} value={a.step} title="step"
                          onChange={(e) => setAxis(p.key, { step: Number(e.target.value) })} />
                      </div>
                    )}
                    {a.mode === 'list' && (
                      <input className={inputCls} value={a.list} placeholder="e.g. 0, 2, 2.5, 3"
                        onChange={(e) => setAxis(p.key, { list: e.target.value })} />
                    )}
                  </td>
                  <td className="py-1 text-right text-muted-foreground">{a.mode !== 'fixed' && n != null ? nfmt(n) : ''}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <div className="border border-border rounded p-4 flex items-center gap-4 flex-wrap">
        <div className="text-sm">
          {sweptCount === 0 && <span className="text-muted-foreground">Switch at least one parameter to range/list.</span>}
          {previewErr && <span className="text-red-500">{previewErr}</span>}
          {preview && (
            <span>
              <b>{nfmt(preview.toRun)}</b> variants to run
              {preview.redundantCombos > 0 && (
                <span className="text-muted-foreground"> ({nfmt(preview.totalCombos)} raw, {nfmt(preview.redundantCombos)} redundant ATR-override combos pruned)</span>
              )}
            </span>
          )}
        </div>
        <div className="grow" />
        {submitErr && <span className="text-sm text-red-500">{submitErr}</span>}
        <button
          className="bg-primary text-primary-foreground rounded px-4 py-2 text-sm disabled:opacity-40"
          disabled={submitting || sweptCount === 0 || !preview || preview.toRun < 1}
          onClick={start}
        >
          {submitting ? 'Starting…' : 'Start sweep'}
        </button>
      </div>
    </div>
  )
}
