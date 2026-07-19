import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

// React port of scripts/sweep-viewer.html (same slicing/winner/heatmap logic), fed from the sweep
// API, plus "use in backtest": any result row can be sent back to the Stock Backtest form.

type Row = Record<string, number | string | null>

interface MetricDef {
  label: string
  kind: 'div' | 'seq'
  mid?: number
  higher: boolean
  holdCol?: string
}

const METRICS: Record<string, MetricDef> = {
  totalPnlPct: { label: 'Total PnL %', kind: 'div', mid: 0, higher: true, holdCol: 'buyHoldPnlPct' },
  annualizedReturnPct: { label: 'CAGR %/yr', kind: 'div', mid: 0, higher: true, holdCol: 'buyHoldAnnualizedPct' },
  profitFactor: { label: 'Profit factor', kind: 'div', mid: 1, higher: true },
  winRate: { label: 'Win rate', kind: 'seq', higher: true },
  maxDrawdownPct: { label: 'Max drawdown %', kind: 'seq', higher: false },
  avgRMultiple: { label: 'Avg R', kind: 'div', mid: 0, higher: true },
  tradeCount: { label: 'Trades', kind: 'seq', higher: true },
  finalCapital: { label: 'Final capital', kind: 'seq', higher: true },
}

const SEQ_STEPS = ['#cde2fb', '#b7d3f6', '#9ec5f4', '#86b6ef', '#6da7ec', '#5598e7', '#3987e5', '#2a78d6', '#256abf', '#1c5cab', '#184f95', '#104281', '#0d366b']

// ---------- color helpers (linear-RGB mix, same as the standalone viewer) ----------
const hex2rgb = (h: string) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255)
const lin = (c: number) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
const unlin = (c: number) => (c <= 0.0031308 ? c * 12.92 : 1.055 * c ** (1 / 2.4) - 0.055)
function mix(h1: string, h2: string, t: number): string {
  const a = hex2rgb(h1).map(lin)
  const b = hex2rgb(h2).map(lin)
  const m = a.map((v, i) => unlin(v + (b[i] - v) * t))
  return `rgb(${m.map((v) => Math.round(v * 255)).join(',')})`
}
const dark = () => document.documentElement.classList.contains('dark') || matchMedia('(prefers-color-scheme: dark)').matches
const PAL = () => (dark()
  ? { mid: '#383835', blueDeep: '#86b6ef', red: '#e66767', gold: '#c98500', ink: '#ffffff', ink2: '#c3c2b7', muted: '#898781' }
  : { mid: '#f0efec', blueDeep: '#0d366b', red: '#e34948', gold: '#eda100', ink: '#0b0b0b', ink2: '#52514e', muted: '#898781' })
function seqColor(t: number): string {
  const p = Math.min(0.9999, Math.max(0, t)) * (SEQ_STEPS.length - 1)
  return mix(SEQ_STEPS[Math.floor(p)], SEQ_STEPS[Math.floor(p) + 1], p - Math.floor(p))
}
function divColor(t: number): string {
  const c = PAL()
  return t >= 0 ? mix(c.mid, c.blueDeep, Math.min(1, t)) : mix(c.mid, c.red, Math.min(1, -t))
}

function parseCsv(text: string): { header: string[]; sweepCols: string[]; rows: Row[] } {
  const lines = text.trim().split(/\r?\n/)
  const header = lines[0].split(',')
  const metricStart = header.indexOf('tradeCount')
  const sweepCols = metricStart > 0 ? header.slice(0, metricStart) : []
  const rows = lines.slice(1).map((l) => {
    const p = l.split(',')
    const o: Row = {}
    header.forEach((h, i) => {
      const raw = p[i]
      if (raw === '' || raw == null) { o[h] = null; return }
      const n = parseFloat(raw)
      o[h] = Number.isFinite(n) && /^[-+.\d][\d.eE+-]*$/.test(raw) ? n : raw
    })
    return o
  })
  return { header, sweepCols, rows }
}

const fmt = (v: number | string | null | undefined, d = 2) =>
  v == null ? '—' : typeof v === 'number' ? v.toLocaleString('en-US', { maximumFractionDigits: d }) : String(v)

interface SweepConfig {
  request?: Record<string, unknown>
}

interface SeriesSummary {
  symbol: string
  interval: string
  firstBar: string
  lastBar: string
  barCount: number
}

/** Per-symbol store coverage vs the sweep window — the usual cause of "strange" sweep results. */
function coverageWarnings(config: SweepConfig | null, summary: SeriesSummary[]): string[] {
  const req = config?.request
  if (!req) return []
  const symbols = Array.isArray(req.symbols) ? (req.symbols as string[]) : []
  const timeframe = String(req.timeframe ?? '1d')
  const from = typeof req.from === 'string' ? req.from : null
  const to = typeof req.to === 'string' ? req.to : null
  const out: string[] = []
  for (const sym of symbols) {
    const s = summary.find((x) => x.symbol === sym && x.interval === timeframe)
    if (!s) {
      out.push(`${sym}: no ${timeframe} data in this instance's store at all — every combo backtested on nothing.`)
      continue
    }
    const first = s.firstBar.slice(0, 10)
    const last = s.lastBar.slice(0, 10)
    const missesStart = from != null && first > from
    const missesEnd = to != null && last < to
    if (missesStart || missesEnd) {
      out.push(
        `${sym}: local ${timeframe} bars span ${first} → ${last}, but the sweep window is ${from} → ${to} — ` +
          'results only reflect the overlap (and CAGR is annualized over the requested window).',
      )
    }
  }
  return out
}

export function SweepResultsPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [data, setData] = useState<{ header: string[]; sweepCols: string[]; rows: Row[] } | null>(null)
  const [config, setConfig] = useState<SweepConfig | null>(null)
  const [summary, setSummary] = useState<SeriesSummary[]>([])
  const [err, setErr] = useState<string | null>(null)

  const [xCol, setXCol] = useState<string | null>(null)
  const [yCol, setYCol] = useState<string | null>(null)
  const [metric, setMetric] = useState('totalPnlPct')
  const [relHold, setRelHold] = useState(false)
  const [sliceVals, setSliceVals] = useState<Record<string, number | string | null>>({})
  const [sortState, setSortState] = useState<{ col: string | null; dir: number }>({ col: null, dir: -1 })
  const [tip, setTip] = useState<{ x: number; y: number; row: Row } | null>(null)

  const canvasRef = useRef<HTMLCanvasElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!id) return
    ;(async () => {
      try {
        const [csvRes, cfgRes] = await Promise.all([
          fetch(`/api/backtest/sweeps/${encodeURIComponent(id)}/results`),
          fetch(`/api/backtest/sweeps/${encodeURIComponent(id)}/config`),
        ])
        if (!csvRes.ok) throw new Error(`results: HTTP ${csvRes.status}`)
        const parsed = parseCsv(await csvRes.text())
        if (cfgRes.ok) setConfig(await cfgRes.json())
        fetch('/api/historical/summary').then(async (r) => { if (r.ok) setSummary(await r.json()) }).catch(() => {})
        setData(parsed)
        const distinctCount = (col: string) => new Set(parsed.rows.map((r) => r[col])).size
        const movable = parsed.sweepCols.filter((c) => distinctCount(c) > 1)
        setXCol(movable[0] ?? null)
        setYCol(movable[1] ?? null)
        const sv: Record<string, number | string | null> = {}
        for (const c of movable) sv[c] = [...new Set(parsed.rows.map((r) => r[c]))].sort((a, b) => (Number(a) - Number(b)))[0] ?? null
        setSliceVals(sv)
      } catch (e) {
        setErr(String(e))
      }
    })()
  }, [id])

  // Distinct values per column, memoized once per dataset (the standalone viewer's perf lesson).
  const distinct = useMemo(() => {
    const cache: Record<string, (number | string | null)[]> = {}
    if (!data) return cache
    for (const c of data.sweepCols) {
      cache[c] = [...new Set(data.rows.map((r) => r[c]))].sort((a, b) =>
        typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b)))
    }
    return cache
  }, [data])

  const movable = useMemo(() => (data ? data.sweepCols.filter((c) => (distinct[c]?.length ?? 0) > 1) : []), [data, distinct])
  const frozen = useMemo(() => (data ? data.sweepCols.filter((c) => (distinct[c]?.length ?? 0) <= 1) : []), [data, distinct])

  const yVals = useCallback(() => (yCol ? distinct[yCol] ?? [] : ['—']), [yCol, distinct])
  const yOf = useCallback((r: Row) => (yCol ? r[yCol] : '—'), [yCol])

  const slice = useMemo(() => {
    if (!data || !xCol) return []
    const pinned = data.sweepCols.filter((c) => c !== xCol && c !== yCol && (distinct[c]?.length ?? 0) > 1)
    return data.rows.filter((r) => pinned.every((c) => r[c] === sliceVals[c]))
  }, [data, xCol, yCol, sliceVals, distinct])

  const val = useCallback((r: Row): number | null => {
    let v = r[metric]
    if (v == null || typeof v !== 'number') return null
    const hc = METRICS[metric].holdCol
    if (relHold && hc && typeof r[hc] === 'number') v -= r[hc] as number
    return v
  }, [metric, relHold])

  const { winners, robust } = useMemo(() => {
    if (!xCol || slice.length === 0) return { winners: [] as Row[], robust: [] as { r: Row; score: number | null }[] }
    const m = METRICS[metric]
    const dir = m.higher ? -1 : 1
    const scored = slice.filter((r) => val(r) != null).slice().sort((a, b) => dir * ((val(a) ?? 0) - (val(b) ?? 0)))
    const winners = scored.slice(0, 10)
    const xs = distinct[xCol] ?? []
    const ys = yVals()
    const ix = new Map(xs.map((v, i) => [v, i]))
    const iy = new Map(ys.map((v, i) => [v, i]))
    const grid = new Map(slice.map((r) => [ix.get(r[xCol]) + ',' + iy.get(yOf(r)), val(r)]))
    const nb = (r: Row) => {
      const cx = ix.get(r[xCol]) ?? 0
      const cy = iy.get(yOf(r)) ?? 0
      let s = 0
      let n = 0
      for (let dx = -1; dx <= 1; dx++) {
        for (let dy = -1; dy <= 1; dy++) {
          const v = grid.get(cx + dx + ',' + (cy + dy))
          if (v != null) { s += v; n++ }
        }
      }
      return n ? s / n : null
    }
    const robust = slice.filter((r) => val(r) != null).map((r) => ({ r, score: nb(r) }))
      .sort((a, b) => dir * ((a.score ?? 0) - (b.score ?? 0))).slice(0, 10)
    return { winners, robust }
  }, [slice, metric, xCol, distinct, yVals, yOf, val])

  // Global top-10: best rows across ALL slider positions (the slice tables only rank what's
  // visible). Clicking one snaps the sliders to its combo so the heatmap shows its neighborhood.
  const globalWinners = useMemo(() => {
    if (!data) return []
    const dir = METRICS[metric].higher ? -1 : 1
    const scored: { r: Row; v: number }[] = []
    for (const r of data.rows) {
      const v = val(r)
      if (v != null) scored.push({ r, v })
    }
    scored.sort((a, b) => dir * (a.v - b.v))
    return scored.slice(0, 10).map((s) => s.r)
  }, [data, metric, val])

  function showCombo(r: Row) {
    setSliceVals((prev) => {
      const next = { ...prev }
      for (const c of movable) if (c !== xCol && c !== yCol) next[c] = r[c]
      return next
    })
    boxRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }

  // ---------- heatmap ----------
  useEffect(() => {
    const cv = canvasRef.current
    const box = boxRef.current
    if (!cv || !box || !xCol || !data) return
    const xs = distinct[xCol] ?? []
    const ys = yVals()
    const padL = 56; const padB = 34; const padT = 6; const padR = 6
    const wCss = box.clientWidth || 1100
    const cell = Math.max(4, Math.floor((wCss - padL - padR) / Math.max(xs.length, 1)))
    const cellH = Math.max(10, Math.min(14, cell))
    const w = padL + cell * xs.length + padR
    const h = padT + cellH * ys.length + padB
    const dpr = window.devicePixelRatio || 1
    cv.width = w * dpr; cv.height = h * dpr
    cv.style.width = w + 'px'; cv.style.height = h + 'px'
    const g = cv.getContext('2d')!
    g.setTransform(dpr, 0, 0, dpr, 0, 0)
    g.clearRect(0, 0, w, h)
    const pal = PAL()

    const vals = slice.map(val).filter((v): v is number => v != null)
    if (vals.length === 0) return
    const m = METRICS[metric]
    const lo = Math.min(...vals); const hi = Math.max(...vals)
    const mid = relHold && m.holdCol ? 0 : (m.mid ?? 0)
    const useDiv = m.kind === 'div' || (relHold && !!m.holdCol)
    const extPos = Math.max(hi - mid, 0) || 1; const extNeg = Math.max(mid - lo, 0) || 1
    const divT = (v: number) => (v >= mid ? (v - mid) / extPos : (v - mid) / extNeg)

    const ix = new Map(xs.map((v, i) => [v, i]))
    const iy = new Map(ys.map((v, i) => [v, i]))
    const cellOf = (r: Row) => ({ x: padL + (ix.get(r[xCol]) ?? 0) * cell, y: padT + (ys.length - 1 - (iy.get(yOf(r)) ?? 0)) * cellH })
    for (const r of slice) {
      const v = val(r); if (v == null) continue
      const { x, y } = cellOf(r)
      g.fillStyle = useDiv ? divColor(divT(v)) : seqColor((v - lo) / ((hi - lo) || 1))
      g.fillRect(x, y, cell - 1, cellH - 1)
    }
    g.lineWidth = 2
    for (const { r } of robust) { const { x, y } = cellOf(r); g.strokeStyle = pal.gold; g.setLineDash([3, 2]); g.strokeRect(x + 0.5, y + 0.5, cell - 2, cellH - 2) }
    g.setLineDash([])
    for (const r of winners) { const { x, y } = cellOf(r); g.strokeStyle = pal.ink; g.strokeRect(x + 0.5, y + 0.5, cell - 2, cellH - 2) }

    g.fillStyle = pal.muted; g.font = '11px system-ui'; g.textAlign = 'center'
    const xEvery = Math.ceil(xs.length / 12); const yEvery = Math.ceil(ys.length / 8)
    xs.forEach((v, i) => { if (i % xEvery === 0) g.fillText(String(v), padL + i * cell + cell / 2, h - padB + 14) })
    g.textAlign = 'right'
    ys.forEach((v, i) => { if (i % yEvery === 0) g.fillText(String(v), padL - 6, padT + (ys.length - 1 - i) * cellH + cellH / 2 + 4) })
    g.textAlign = 'center'; g.fillStyle = pal.ink2
    g.fillText(xCol, padL + (xs.length * cell) / 2, h - 6)
    if (yCol) { g.save(); g.translate(12, padT + (ys.length * cellH) / 2); g.rotate(-Math.PI / 2); g.fillText(yCol, 0, 0); g.restore() }

    // hover
    const bySlot = new Map(slice.map((r) => [(ix.get(r[xCol]) ?? -9) + ',' + (iy.get(yOf(r)) ?? -9), r]))
    cv.onmousemove = (e) => {
      const rect = cv.getBoundingClientRect()
      const px = (e.clientX - rect.left) * (w / rect.width); const py = (e.clientY - rect.top) * (h / rect.height)
      const cxi = Math.floor((px - padL) / cell); const cyi = ys.length - 1 - Math.floor((py - padT) / cellH)
      const r = cxi >= 0 && cyi >= 0 ? bySlot.get(cxi + ',' + cyi) : undefined
      if (!r) { setTip(null); return }
      setTip({ x: Math.min(e.clientX + 14, innerWidth - 300), y: e.clientY + 14, row: r })
    }
    cv.onmouseleave = () => setTip(null)
  }, [slice, metric, relHold, xCol, yCol, winners, robust, distinct, data, val, yVals, yOf])

  // ---------- use in backtest ----------
  function useInBacktest(r: Row) {
    const req = (config?.request ?? {}) as Record<string, unknown>
    const form: Record<string, unknown> = {}
    // request fields first (symbols array → comma string), then the row's swept values on top
    for (const [k, v] of Object.entries(req)) form[k] = k === 'symbols' && Array.isArray(v) ? v.join(', ') : v
    for (const c of data?.sweepCols ?? []) if (r[c] != null) form[c] = r[c]
    try {
      const prev = JSON.parse(localStorage.getItem('stockBacktestForm') ?? '{}')
      localStorage.setItem('stockBacktestForm', JSON.stringify({ ...prev, ...form }))
    } catch {
      localStorage.setItem('stockBacktestForm', JSON.stringify(form))
    }
    navigate('/backtest/stock')
  }

  const metricCols = Object.keys(METRICS).filter((k) => data?.header.includes(k))
  const tableCols = [...movable, ...metricCols]

  function WinnerTable({ list, extra, onRowClick }: {
    list: Row[] | { r: Row; score: number | null }[]
    extra?: string
    onRowClick?: (r: Row) => void
  }) {
    return (
      <div className="tablebox">
        <table className="data">
          <thead>
            <tr>
              {extra && <th>{extra}</th>}
              {tableCols.map((c) => <th key={c}>{METRICS[c]?.label ?? c}</th>)}
              <th />
            </tr>
          </thead>
          <tbody>
            {list.map((item, i) => {
              const r = 'r' in (item as object) ? (item as { r: Row }).r : (item as Row)
              const score = 'score' in (item as object) ? (item as { score: number | null }).score : null
              return (
                <tr key={i}
                  className={onRowClick ? 'clickable' : ''}
                  title={onRowClick ? 'Show this combo: sets the sliders to its values' : undefined}
                  onClick={onRowClick ? () => onRowClick(r) : undefined}>
                  {extra && <td>{fmt(score)}</td>}
                  {tableCols.map((c) => <td key={c}>{fmt(r[c])}</td>)}
                  <td><button className="usebtn" onClick={(e) => { e.stopPropagation(); useInBacktest(r) }}>use</button></td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    )
  }

  if (err) return <div className="text-sm text-red-500">Failed to load sweep: {err}</div>
  if (!data || !xCol) return <div className="text-sm text-muted-foreground">Loading results…</div>

  const m = METRICS[metric]
  const sortCol = sortState.col ?? metric
  const sorted = slice.slice().sort((a, b) => sortState.dir * (((a[sortCol] as number) ?? -Infinity) - ((b[sortCol] as number) ?? -Infinity)))
  const winnerSet = new Set(winners)
  const robustSet = new Set(robust.map((q) => q.r))
  const hold = data.rows[0]?.buyHoldPnlPct
  // Absolute money for buy & hold: initial capital falls out of any row (finalCapital − totalPnl).
  const r0 = data.rows[0]
  const initialCap = typeof r0?.finalCapital === 'number' && typeof r0?.totalPnl === 'number'
    ? (r0.finalCapital as number) - (r0.totalPnl as number) : null
  const holdMoney = initialCap != null && typeof hold === 'number' ? initialCap * (1 + hold / 100) : null

  return (
    <div className="space-y-3 sweepviewer">
      <style>{`
        .sweepviewer .chip { display:inline-block; border:1px solid var(--border,#d4d4d4); border-radius:999px; padding:1px 9px; font-size:11px; margin:2px; opacity:.85 }
        .sweepviewer .chip b { font-weight:600 }
        .sweepviewer table.data { border-collapse:collapse; width:100%; font-size:12px }
        .sweepviewer table.data th, .sweepviewer table.data td { padding:4px 8px; text-align:right; font-variant-numeric:tabular-nums; border-bottom:1px solid rgba(128,128,128,.25); white-space:nowrap }
        .sweepviewer table.data th { font-weight:600; cursor:pointer; position:sticky; top:0; background:inherit; user-select:none; opacity:.75 }
        .sweepviewer table.data tr.win td { background: rgba(42,120,214,.13) }
        .sweepviewer table.data tr.robust td { background: rgba(237,161,0,.13) }
        .sweepviewer .tablebox { max-height:420px; overflow:auto; border:1px solid rgba(128,128,128,.25); border-radius:8px; background:inherit }
        .sweepviewer .usebtn { font-size:11px; border:1px solid rgba(128,128,128,.4); border-radius:5px; padding:0 6px; cursor:pointer; background:transparent }
        .sweepviewer tr.clickable { cursor:pointer }
        .sweepviewer tr.clickable:hover td { background: rgba(42,120,214,.08) }
        .sweepviewer .usebtn:hover { border-color:#2a78d6; color:#2a78d6 }
        .sweepviewer .legendbar { height:10px; width:220px; border-radius:5px; border:1px solid rgba(128,128,128,.25) }
      `}</style>

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">Sweep results — {id}</h1>
          <div className="text-sm text-muted-foreground">
            {[
              Array.isArray(config?.request?.symbols) ? (config!.request!.symbols as string[]).join(', ') : null,
              config?.request?.timeframe ? String(config.request.timeframe) : null,
              config?.request?.from || config?.request?.to ? `${config?.request?.from ?? '…'} → ${config?.request?.to ?? '…'}` : null,
              `${data.rows.length.toLocaleString('en-US')} rows`,
            ].filter(Boolean).join('  ·  ')}
          </div>
        </div>
        <button className="text-sm underline" onClick={() => navigate('/backtest/sweeps')}>← sweep jobs</button>
      </div>

      {coverageWarnings(config, summary).map((w) => (
        <div key={w} className="border border-yellow-500/50 bg-yellow-500/10 text-yellow-700 dark:text-yellow-400 rounded p-3 text-sm">
          ⚠ Data coverage: {w}
        </div>
      ))}

      <div className="border border-border rounded p-3 text-sm">
        {config?.request && Object.entries(config.request).map(([k, v]) => (
          <span key={k} className="chip">{k} <b>{Array.isArray(v) ? v.join(', ') : String(v)}</b></span>
        ))}
        {frozen.map((c) => <span key={c} className="chip">{c} <b>{fmt(distinct[c]?.[0])}</b> (swept, single value)</span>)}
        {hold != null && <span className="chip">buy &amp; hold <b>{fmt(hold)}%</b> ({fmt(data.rows[0]?.buyHoldAnnualizedPct)}%/yr)</span>}
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="border border-border rounded p-3"><div className="text-xs text-muted-foreground uppercase">Combos in slice</div>
          <div className="text-lg font-semibold">{slice.length.toLocaleString('en-US')}</div>
          <div className="text-xs text-muted-foreground">{data.rows.length.toLocaleString('en-US')} loaded</div></div>
        <div className="border border-border rounded p-3"><div className="text-xs text-muted-foreground uppercase">Best (absolute)</div>
          <div className="text-lg font-semibold">{winners[0] ? fmt(val(winners[0])) : '—'}</div>
          <div className="text-xs text-muted-foreground">{winners[0] ? `${xCol}=${winners[0][xCol]}${yCol ? ` ${yCol}=${winners[0][yCol]}` : ''}` : ''}</div></div>
        <div className="border border-border rounded p-3"><div className="text-xs text-muted-foreground uppercase">Best (robust)</div>
          <div className="text-lg font-semibold">{robust[0] ? fmt(val(robust[0].r)) : '—'}</div>
          <div className="text-xs text-muted-foreground">{robust[0] ? `nbhd ${fmt(robust[0].score)}` : ''}</div></div>
        <div className="border border-border rounded p-3"><div className="text-xs text-muted-foreground uppercase">Buy &amp; hold</div>
          <div className="text-lg font-semibold">{holdMoney != null ? `$${fmt(holdMoney, 0)}` : `${fmt(hold)}%`}</div>
          <div className="text-xs text-muted-foreground">
            {holdMoney != null && initialCap != null ? `from $${fmt(initialCap, 0)} · ${fmt(hold)}% · ` : ''}
            {fmt(data.rows[0]?.buyHoldAnnualizedPct)}%/yr — the bar to clear
          </div></div>
      </div>

      <div className="border border-border rounded p-3 flex flex-wrap gap-4 items-center text-sm">
        <label>X{' '}
          <select className="border border-border rounded px-1 py-0.5 bg-background" value={xCol} onChange={(e) => {
            const v = e.target.value
            setXCol(v)
            if (yCol === v) setYCol(movable.find((c) => c !== v) ?? null)
          }}>
            {movable.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </label>
        {movable.length > 1 && (
          <label>Y{' '}
            <select className="border border-border rounded px-1 py-0.5 bg-background" value={yCol ?? ''} onChange={(e) => {
              const v = e.target.value
              setYCol(v)
              if (xCol === v) setXCol(movable.find((c) => c !== v) ?? v)
            }}>
              {movable.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </label>
        )}
        <label>Metric{' '}
          <select className="border border-border rounded px-1 py-0.5 bg-background" value={metric} onChange={(e) => setMetric(e.target.value)}>
            {metricCols.map((k) => <option key={k} value={k}>{METRICS[k].label}</option>)}
          </select>
        </label>
        {m.holdCol && (
          <label className="flex items-center gap-1">
            <input type="checkbox" checked={relHold} onChange={(e) => setRelHold(e.target.checked)} /> relative to buy &amp; hold
          </label>
        )}
        {movable.filter((c) => c !== xCol && c !== yCol).map((c) => {
          const vals = distinct[c] ?? []
          const idx = Math.max(vals.indexOf(sliceVals[c] as never), 0)
          return (
            <label key={c} className="flex items-center gap-1 text-muted-foreground">
              {c} = <b className="text-foreground">{fmt(sliceVals[c])}</b>
              <input type="range" min={0} max={vals.length - 1} step={1} value={idx}
                onChange={(e) => setSliceVals((prev) => ({ ...prev, [c]: vals[+e.target.value] }))} />
            </label>
          )
        })}
      </div>

      <div className="border border-border rounded p-3">
        <h2 className="text-xs uppercase text-muted-foreground mb-2">
          {m.label}{relHold && m.holdCol ? ' − buy & hold' : ''} over {xCol}{yCol ? ` × ${yCol}` : ''}
        </h2>
        <div ref={boxRef}><canvas ref={canvasRef} style={{ display: 'block' }} /></div>
        <div className="text-xs text-muted-foreground mt-2">
          solid ring / blue rows = top-10 absolute · dashed gold ring / gold rows = top-10 robust (best 3×3 neighborhood — prefer these)
        </div>
      </div>

      {movable.length > 2 && (
        <div className="border border-border rounded p-3">
          <h2 className="text-xs uppercase text-muted-foreground mb-2">Top 10 — global (best across all slider positions)</h2>
          <WinnerTable list={globalWinners} onRowClick={showCombo} />
          <div className="text-xs text-muted-foreground mt-2">Click a row to snap the sliders to that combo — the heatmap jumps to its slice.</div>
        </div>
      )}

      <div className="grid md:grid-cols-2 gap-3">
        <div className="border border-border rounded p-3">
          <h2 className="text-xs uppercase text-muted-foreground mb-2">Top 10 — absolute (current slice)</h2>
          <WinnerTable list={winners} />
        </div>
        <div className="border border-border rounded p-3">
          <h2 className="text-xs uppercase text-muted-foreground mb-2">Top 10 — robust (plateau, current slice)</h2>
          <WinnerTable list={robust} extra="nbhd avg" />
        </div>
      </div>

      <div className="border border-border rounded p-3">
        <h2 className="text-xs uppercase text-muted-foreground mb-2">
          All rows in current slice — {slice.length.toLocaleString('en-US')} rows{slice.length > 3000 ? ', showing 3,000' : ''}
        </h2>
        <div className="tablebox">
          <table className="data">
            <thead>
              <tr>
                {tableCols.map((c) => (
                  <th key={c} onClick={() => setSortState((s) => ({ col: c, dir: s.col === c ? -s.dir : -1 }))}>
                    {METRICS[c]?.label ?? c}{sortCol === c ? (sortState.dir < 0 ? ' ↓' : ' ↑') : ''}
                  </th>
                ))}
                <th />
              </tr>
            </thead>
            <tbody>
              {sorted.slice(0, 3000).map((r, i) => (
                <tr key={i} className={winnerSet.has(r) ? 'win' : robustSet.has(r) ? 'robust' : ''}>
                  {tableCols.map((c) => <td key={c}>{fmt(r[c])}</td>)}
                  <td><button className="usebtn" onClick={() => useInBacktest(r)}>use</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {tip && (
        <div style={{ position: 'fixed', left: tip.x, top: tip.y, zIndex: 10, pointerEvents: 'none' }}
          className="border border-border rounded bg-background p-2 text-xs shadow-lg">
          <div className="font-semibold">{xCol}={String(tip.row[xCol])}{yCol ? ` · ${yCol}=${String(tip.row[yCol])}` : ''}</div>
          <table><tbody>
            {metricCols.map((k) => (
              <tr key={k}><td className="text-muted-foreground pr-2 text-left">{METRICS[k].label}</td><td className="text-right">{fmt(tip.row[k])}</td></tr>
            ))}
          </tbody></table>
        </div>
      )}
    </div>
  )
}
