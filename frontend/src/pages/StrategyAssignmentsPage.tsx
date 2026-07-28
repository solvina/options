import { useEffect, useState } from 'react'

/** Mirrors StrategyAssignmentApiController.AssignmentDto. */
type Assignment = {
  id: string
  strategyId: string
  symbol: string
  timeframe: string
  params: Record<string, unknown> | null
  enabled: boolean
  createdAt: string
  updatedAt: string
}

/** Mirrors StockBacktestApiController.StrategyDto — the same descriptors the backtest form renders. */
type ParamMeta = {
  name: string
  type: 'INT' | 'DOUBLE' | 'BOOLEAN'
  default: unknown
  min: number | null
  max: number | null
  group: string
  help: string | null
}
type StrategyMeta = {
  id: string
  displayName: string
  timeframes: string[]
  warmupBars: number
  requiresTicks: boolean
  params: ParamMeta[]
}

/**
 * Which strategy trades which symbol, live. Overrides are edited through the strategy's own
 * descriptors, so a new strategy needs no change here — the same payoff as the backtest form.
 */
export function StrategyAssignmentsPage() {
  const [assignments, setAssignments] = useState<Assignment[]>([])
  const [strategies, setStrategies] = useState<StrategyMeta[]>([])
  const [editing, setEditing] = useState<Assignment | 'new' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  async function reload() {
    const [a, s] = await Promise.all([
      fetch('/api/strategy-assignments').then((r) => r.json()),
      fetch('/api/backtest/strategies').then((r) => r.json()),
    ])
    setAssignments(a)
    setStrategies(s)
    setLoading(false)
  }

  useEffect(() => {
    reload().catch((e) => {
      setError(String(e))
      setLoading(false)
    })
  }, [])

  async function toggle(a: Assignment) {
    setError(null)
    const res = await fetch(`/api/strategy-assignments/${a.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...a, enabled: !a.enabled }),
    })
    if (!res.ok) setError((await res.json()).error ?? `${res.status}`)
    await reload()
  }

  async function remove(a: Assignment) {
    if (!confirm(`Remove ${a.strategyId} on ${a.symbol}?`)) return
    await fetch(`/api/strategy-assignments/${a.id}`, { method: 'DELETE' })
    await reload()
  }

  if (loading) return <div className="p-6 text-muted-foreground">Loading…</div>

  return (
    <div className="p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">Strategy Assignments</h1>
          <p className="text-sm text-muted-foreground">
            Which stock strategy runs on which symbol. Disabled assignments keep their history and stay backtestable.
          </p>
        </div>
        <button className="px-3 py-1.5 rounded bg-primary text-primary-foreground text-sm" onClick={() => setEditing('new')}>
          New assignment
        </button>
      </div>

      {error && <div className="rounded border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm">{error}</div>}

      {assignments.length === 0 ? (
        <div className="rounded border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
          No assignments yet. Nothing will trade until one is created and enabled.
        </div>
      ) : (
        <table className="w-full text-sm">
          <thead className="text-left text-muted-foreground border-b">
            <tr>
              <th className="py-2">Strategy</th>
              <th>Symbol</th>
              <th>Timeframe</th>
              <th>Overrides</th>
              <th>Enabled</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {assignments.map((a) => {
              const overrides = a.params ? Object.keys(a.params).length : 0
              return (
                <tr key={a.id} className="border-b last:border-0">
                  <td className="py-2 font-medium">{strategies.find((s) => s.id === a.strategyId)?.displayName ?? a.strategyId}</td>
                  <td className="tabular-nums">{a.symbol}</td>
                  <td className="tabular-nums">{a.timeframe}</td>
                  <td className="text-muted-foreground">
                    {overrides === 0 ? <span className="text-xs">strategy defaults</span> : `${overrides} overridden`}
                  </td>
                  <td>
                    <button
                      onClick={() => toggle(a)}
                      className={`px-2 py-0.5 rounded text-xs ${a.enabled ? 'bg-emerald-500/15 text-emerald-600' : 'bg-muted text-muted-foreground'}`}
                    >
                      {a.enabled ? 'enabled' : 'disabled'}
                    </button>
                  </td>
                  <td className="text-right">
                    <button className="text-xs text-muted-foreground hover:underline mr-3" onClick={() => setEditing(a)}>
                      edit
                    </button>
                    <button className="text-xs text-destructive hover:underline" onClick={() => remove(a)}>
                      remove
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}

      {editing && (
        <EditModal
          assignment={editing === 'new' ? null : editing}
          strategies={strategies}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null)
            await reload()
          }}
        />
      )}
    </div>
  )
}

function EditModal({
  assignment,
  strategies,
  onClose,
  onSaved,
}: {
  assignment: Assignment | null
  strategies: StrategyMeta[]
  onClose: () => void
  onSaved: () => void
}) {
  const [strategyId, setStrategyId] = useState(assignment?.strategyId ?? strategies[0]?.id ?? '')
  const [symbol, setSymbol] = useState(assignment?.symbol ?? '')
  const [timeframe, setTimeframe] = useState(assignment?.timeframe ?? '1d')
  const [params, setParams] = useState<Record<string, unknown>>(assignment?.params ?? {})
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const strategy = strategies.find((s) => s.id === strategyId)
  const groups = [...new Set(strategy?.params.map((p) => p.group) ?? [])]

  async function save() {
    setSaving(true)
    setError(null)
    const body = {
      strategyId,
      symbol,
      timeframe,
      // Only send what was actually overridden — an empty blob means "descriptor defaults".
      params: Object.keys(params).length ? params : null,
      enabled: assignment?.enabled ?? false,
    }
    const res = await fetch(assignment ? `/api/strategy-assignments/${assignment.id}` : '/api/strategy-assignments', {
      method: assignment ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    setSaving(false)
    if (!res.ok) {
      setError((await res.json()).error ?? `${res.status}`)
      return
    }
    onSaved()
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center p-4 z-50" onClick={onClose}>
      <div className="bg-background rounded-lg shadow-lg max-w-2xl w-full max-h-[85vh] overflow-auto p-5 space-y-4" onClick={(e) => e.stopPropagation()}>
        <h2 className="text-lg font-semibold">{assignment ? 'Edit assignment' : 'New assignment'}</h2>

        <div className="grid grid-cols-3 gap-3">
          <label className="text-sm space-y-1">
            <span className="text-muted-foreground">Strategy</span>
            <select
              className="w-full border rounded px-2 py-1"
              value={strategyId}
              onChange={(e) => {
                setStrategyId(e.target.value)
                setParams({})
                const s = strategies.find((x) => x.id === e.target.value)
                if (s && !s.timeframes.includes(timeframe)) setTimeframe(s.timeframes[0])
              }}
            >
              {strategies.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.displayName}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm space-y-1">
            <span className="text-muted-foreground">Symbol</span>
            <input className="w-full border rounded px-2 py-1 uppercase" value={symbol} onChange={(e) => setSymbol(e.target.value)} />
          </label>
          <label className="text-sm space-y-1">
            <span className="text-muted-foreground">Timeframe</span>
            <select className="w-full border rounded px-2 py-1" value={timeframe} onChange={(e) => setTimeframe(e.target.value)}>
              {(strategy?.timeframes ?? ['1d']).map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </label>
        </div>

        <p className="text-xs text-muted-foreground">
          Leave a field blank to use the strategy default. Only edited fields are stored as overrides.
        </p>

        {groups.map((g) => (
          <div key={g} className="space-y-2">
            <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{g}</div>
            <div className="grid grid-cols-3 gap-3">
              {strategy?.params
                .filter((p) => p.group === g)
                .map((p) => (
                  <label key={p.name} className="text-sm space-y-1" title={p.help ?? undefined}>
                    <span className="text-muted-foreground">{p.name}</span>
                    {p.type === 'BOOLEAN' ? (
                      <select
                        className="w-full border rounded px-2 py-1"
                        value={params[p.name] === undefined ? '' : String(params[p.name])}
                        onChange={(e) => {
                          const next = { ...params }
                          if (e.target.value === '') delete next[p.name]
                          else next[p.name] = e.target.value === 'true'
                          setParams(next)
                        }}
                      >
                        <option value="">default ({String(p.default)})</option>
                        <option value="true">true</option>
                        <option value="false">false</option>
                      </select>
                    ) : (
                      <input
                        type="number"
                        step={p.type === 'INT' ? 1 : 'any'}
                        min={p.min ?? undefined}
                        max={p.max ?? undefined}
                        placeholder={`default ${String(p.default)}`}
                        className="w-full border rounded px-2 py-1 tabular-nums"
                        value={params[p.name] === undefined ? '' : String(params[p.name])}
                        onChange={(e) => {
                          const next = { ...params }
                          if (e.target.value === '') delete next[p.name]
                          else next[p.name] = p.type === 'INT' ? parseInt(e.target.value, 10) : parseFloat(e.target.value)
                          setParams(next)
                        }}
                      />
                    )}
                  </label>
                ))}
            </div>
          </div>
        ))}

        {error && <div className="rounded border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm">{error}</div>}

        <div className="flex justify-end gap-2 pt-2">
          <button className="px-3 py-1.5 rounded border text-sm" onClick={onClose}>
            Cancel
          </button>
          <button className="px-3 py-1.5 rounded bg-primary text-primary-foreground text-sm disabled:opacity-50" disabled={saving || !symbol} onClick={save}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}
