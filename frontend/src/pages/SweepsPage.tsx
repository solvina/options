import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

interface SweepJob {
  id: string
  name: string
  status: 'RUNNING' | 'DONE' | 'CANCELLED' | 'FAILED' | 'STOPPED'
  totalCombos: number
  redundantCombos: number
  toRun: number
  done: number
  failed: number
  resumedRows: number
  startedAt: string | null
  finishedAt: string | null
  error: string | null
  sweptParams: string[]
  symbols: string[]
  timeframe: string
  hasResults: boolean
}

const nfmt = (v: number) => v.toLocaleString('en-US')

const STATUS_CLS: Record<SweepJob['status'], string> = {
  RUNNING: 'bg-blue-500/15 text-blue-500',
  DONE: 'bg-green-500/15 text-green-600',
  CANCELLED: 'bg-yellow-500/15 text-yellow-600',
  FAILED: 'bg-red-500/15 text-red-500',
  STOPPED: 'bg-muted text-muted-foreground',
}

/** ETA from observed rate since job start; returns e.g. "4m 20s". */
function eta(job: SweepJob): string {
  if (job.status !== 'RUNNING' || !job.startedAt || job.done === 0) return '—'
  const elapsedS = (Date.now() - new Date(job.startedAt).getTime()) / 1000
  const remaining = Math.max(job.toRun - job.done, 0)
  const s = Math.round((elapsedS / job.done) * remaining)
  if (s < 60) return `${s}s`
  if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`
  return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`
}

export function SweepsPage() {
  const navigate = useNavigate()
  const [jobs, setJobs] = useState<SweepJob[]>([])
  const [err, setErr] = useState<string | null>(null)

  async function load() {
    try {
      const res = await fetch('/api/backtest/sweeps')
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      setJobs(await res.json())
      setErr(null)
    } catch (e) {
      setErr(String(e))
    }
  }

  useEffect(() => {
    load()
    const t = setInterval(load, 3000)
    return () => clearInterval(t)
  }, [])

  async function terminate(id: string) {
    if (!confirm(`Terminate sweep "${id}"? Rows written so far are kept; starting the same name later resumes.`)) return
    await fetch(`/api/backtest/sweeps/${encodeURIComponent(id)}`, { method: 'DELETE' })
    load()
  }

  async function purge(id: string) {
    if (!confirm(`Delete sweep "${id}" INCLUDING its results directory?`)) return
    await fetch(`/api/backtest/sweeps/${encodeURIComponent(id)}?purge=true`, { method: 'DELETE' })
    load()
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Parameter sweeps</h1>
        <button className="bg-primary text-primary-foreground rounded px-4 py-2 text-sm" onClick={() => navigate('/backtest/sweeps/new')}>
          New sweep
        </button>
      </div>
      {err && <div className="text-sm text-red-500">{err}</div>}
      <div className="border border-border rounded overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-muted-foreground border-b border-border">
              <th className="px-3 py-2">Name</th>
              <th className="px-3 py-2">Status</th>
              <th className="px-3 py-2">Symbols</th>
              <th className="px-3 py-2">Swept params</th>
              <th className="px-3 py-2 w-56">Progress</th>
              <th className="px-3 py-2 text-right">ETA</th>
              <th className="px-3 py-2 text-right">Failed</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {jobs.length === 0 && (
              <tr><td colSpan={8} className="px-3 py-6 text-center text-muted-foreground">No sweeps yet — start one from here or from the Stock Backtest page.</td></tr>
            )}
            {jobs.map((j) => {
              const denom = j.status === 'RUNNING' || j.toRun > 0 ? j.toRun : j.done
              const pct = denom > 0 ? Math.min((j.done / denom) * 100, 100) : j.status === 'DONE' ? 100 : 0
              return (
                <tr key={j.id} className="border-b border-border last:border-0">
                  <td className="px-3 py-2 font-medium whitespace-nowrap">{j.name}</td>
                  <td className="px-3 py-2">
                    <span className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${STATUS_CLS[j.status]}`}>{j.status}</span>
                    {j.error && <span className="block text-xs text-red-500 max-w-48 truncate" title={j.error}>{j.error}</span>}
                  </td>
                  <td className="px-3 py-2 whitespace-nowrap">{j.symbols.join(', ') || '—'}{j.timeframe && ` · ${j.timeframe}`}</td>
                  <td className="px-3 py-2 text-xs text-muted-foreground max-w-52 truncate" title={j.sweptParams.join(', ')}>
                    {j.sweptParams.join(', ') || '—'}
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex items-center gap-2">
                      <div className="h-2 grow rounded bg-muted overflow-hidden">
                        <div className="h-full bg-primary transition-all" style={{ width: `${pct}%` }} />
                      </div>
                      <span className="text-xs whitespace-nowrap text-muted-foreground">
                        {nfmt(j.done)}{j.toRun > 0 && `/${nfmt(j.toRun)}`}
                      </span>
                    </div>
                    {j.resumedRows > 0 && <div className="text-xs text-muted-foreground">+{nfmt(j.resumedRows)} resumed</div>}
                  </td>
                  <td className="px-3 py-2 text-right whitespace-nowrap">{eta(j)}</td>
                  <td className={`px-3 py-2 text-right ${j.failed > 0 ? 'text-red-500' : 'text-muted-foreground'}`}>{nfmt(j.failed)}</td>
                  <td className="px-3 py-2 text-right whitespace-nowrap space-x-2">
                    {j.hasResults && (
                      <Link className="underline" to={`/backtest/sweeps/${encodeURIComponent(j.id)}/results`}>results</Link>
                    )}
                    {j.status === 'RUNNING' ? (
                      <button className="text-red-500 underline" onClick={() => terminate(j.id)}>terminate</button>
                    ) : (
                      <button className="text-red-500/70 underline" onClick={() => purge(j.id)}>purge</button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-muted-foreground">
        Sweeps write results.csv compatible with scripts/param-sweep.py — CLI runs appear here too. Terminating keeps
        completed rows; starting a sweep with the same name resumes where it stopped.
      </p>
    </div>
  )
}
