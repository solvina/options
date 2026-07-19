import { useMemo, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getHistoricalCoverageOptions,
  getHistoricalSummaryOptions,
  listFetchJobsOptions,
  startHistoricalFetchMutation,
} from '../generated/historical/@tanstack/react-query.gen'
import type { FetchJobDto, SeriesSummaryDto, SymbolCoverageDto } from '../generated/historical/types.gen'

const TIMEFRAMES = ['5min', '4h', '1d'] as const
type Tf = (typeof TIMEFRAMES)[number]
// "Full day" bar count per timeframe: 78 five-min RTH bars (US; EU = 102), ~2 four-hour bars, 1 daily.
const FULL_EXPECTED: Record<Tf, number> = { '5min': 78, '4h': 2, '1d': 1 }

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}

function dateRange(from: string, to: string): string[] {
  const result: string[] = []
  const cur = new Date(from)
  const end = new Date(to)
  while (cur <= end) {
    result.push(cur.toISOString().slice(0, 10))
    cur.setDate(cur.getDate() + 1)
  }
  return result
}

function barCountColor(count: number, full: number): string {
  if (count === 0) return 'bg-muted text-muted-foreground'
  if (count >= full) return 'bg-green-600 text-white'
  if (count >= full * 0.5) return 'bg-yellow-500 text-white'
  return 'bg-orange-500 text-white'
}

function CoverageCell({ count, date, full }: { count: number; date: string; full: number }) {
  const isWeekend = [0, 6].includes(new Date(date).getDay())
  if (isWeekend) return <td className="w-8 h-7 text-center text-[10px] text-muted-foreground/30">·</td>
  return (
    <td
      className={`w-8 h-7 text-center text-[10px] font-mono rounded cursor-default transition-colors ${barCountColor(count, full)}`}
      title={`${date}: ${count} bars`}
    >
      {count > 0 ? count : '—'}
    </td>
  )
}

function CoverageTable({ coverage, dates, full }: { coverage: SymbolCoverageDto[]; dates: string[]; full: number }) {
  return (
    <div className="overflow-x-auto">
      <table className="text-xs border-separate border-spacing-0.5">
        <thead>
          <tr>
            <th className="text-left pr-3 font-medium text-muted-foreground w-16">Symbol</th>
            {dates.map((d, i) => {
              const mm = d.slice(5, 7)
              const dd = d.slice(8, 10)
              const isFirst = i === 0 || dates[i - 1].slice(5, 7) !== mm
              return (
                <th key={d} className="w-8 text-center font-normal text-muted-foreground/60 text-[10px]">
                  {isFirst ? mm : dd === '01' ? mm : dd}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {coverage.map(row => (
            <tr key={row.symbol}>
              <td className="pr-3 font-mono font-medium text-foreground/80">{row.symbol}</td>
              {dates.map(date => (
                <CoverageCell key={date} count={row.days[date] ?? 0} date={date} full={full} />
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <div className="flex gap-4 mt-3 text-xs text-muted-foreground">
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-3 h-3 rounded bg-green-600" /> Full ({full}+ bars/day)
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-3 h-3 rounded bg-yellow-500" /> Partial
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-3 h-3 rounded bg-orange-500" /> Sparse
        </span>
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-3 h-3 rounded bg-muted" /> No data
        </span>
      </div>
    </div>
  )
}

function StatusBadge({ status }: { status: FetchJobDto['status'] }) {
  const styles = {
    RUNNING: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
    DONE: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
    FAILED: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
  }
  return (
    <span className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${styles[status]}`}>
      {status}
    </span>
  )
}

function JobsTable({ jobs }: { jobs: FetchJobDto[] }) {
  if (jobs.length === 0) return <p className="text-sm text-muted-foreground">No fetch jobs yet.</p>
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="border-b border-border text-left text-xs text-muted-foreground">
          <th className="pb-2 pr-4">Status</th>
          <th className="pb-2 pr-4">Symbols</th>
          <th className="pb-2 pr-4">Range</th>
          <th className="pb-2 pr-4">Bars Written</th>
          <th className="pb-2 pr-4">Started</th>
          <th className="pb-2">Error</th>
        </tr>
      </thead>
      <tbody>
        {jobs.map(job => (
          <tr key={job.id} className="border-b border-border/50 last:border-0">
            <td className="py-2 pr-4"><StatusBadge status={job.status} /></td>
            <td className="py-2 pr-4 font-mono text-xs">{job.symbols.join(', ')}</td>
            <td className="py-2 pr-4 tabular-nums text-xs">{job.from} – {job.to}</td>
            <td className="py-2 pr-4 tabular-nums">{job.barsWritten.toLocaleString()}</td>
            <td className="py-2 pr-4 tabular-nums text-xs text-muted-foreground">
              {new Date(job.startedAt).toLocaleString()}
            </td>
            <td className="py-2 text-xs text-red-600 dark:text-red-400 max-w-xs truncate">
              {job.error ?? '—'}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** Per-symbol pivot of the series summaries: one row per symbol, one column group per timeframe. */
function DbSummary({ rows }: { rows: SeriesSummaryDto[] }) {
  const [filter, setFilter] = useState('')
  const bySymbol = useMemo(() => {
    const m = new Map<string, Partial<Record<string, SeriesSummaryDto>>>()
    for (const r of rows) {
      if (!m.has(r.symbol)) m.set(r.symbol, {})
      m.get(r.symbol)![r.interval] = r
    }
    return [...m.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [rows])
  const shown = bySymbol.filter(([sym]) => sym.toUpperCase().includes(filter.trim().toUpperCase()))
  const totals = useMemo(() => {
    const t: Record<string, number> = {}
    for (const r of rows) t[r.interval] = (t[r.interval] ?? 0) + r.barCount
    return t
  }, [rows])
  const d = (s?: string) => (s ? s.slice(0, 10) : '')
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-4">
        <input
          type="text" value={filter} onChange={e => setFilter(e.target.value)} placeholder="Filter symbol…"
          className="border border-border rounded px-2 py-1 text-sm bg-background text-foreground w-40"
        />
        <span className="text-xs text-muted-foreground">
          {bySymbol.length} symbols ·{' '}
          {TIMEFRAMES.filter(tf => totals[tf]).map(tf => `${tf}: ${(totals[tf] ?? 0).toLocaleString('en-US')} bars`).join(' · ')}
        </span>
      </div>
      <div className="overflow-x-auto max-h-[28rem] overflow-y-auto border border-border rounded-lg">
        <table className="w-full text-xs">
          <thead className="sticky top-0 bg-background">
            <tr className="border-b border-border text-left text-muted-foreground">
              <th className="px-3 py-2 font-medium">Symbol</th>
              {TIMEFRAMES.map(tf => (
                <th key={tf} className="px-3 py-2 font-medium" colSpan={2}>{tf}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {shown.map(([sym, per]) => (
              <tr key={sym} className="border-b border-border/40 last:border-0 hover:bg-muted/30">
                <td className="px-3 py-1.5 font-mono font-medium">{sym}</td>
                {TIMEFRAMES.map(tf => {
                  const r = per[tf]
                  return (
                    <td key={tf} colSpan={2} className="px-3 py-1.5 tabular-nums">
                      {r ? (
                        <span>{r.barCount.toLocaleString('en-US')} <span className="text-muted-foreground">({d(r.firstBar)} → {d(r.lastBar)})</span></span>
                      ) : (
                        <span className="text-muted-foreground/40">—</span>
                      )}
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export function HistoricalDataPage() {
  const qc = useQueryClient()

  const [coverageFrom, setCoverageFrom] = useState(daysAgo(30))
  const [coverageTo, setCoverageTo] = useState(today())
  const [coverageSymbols, setCoverageSymbols] = useState('')
  const [coverageTf, setCoverageTf] = useState<Tf>('1d')

  const [fetchFrom, setFetchFrom] = useState(daysAgo(30))
  const [fetchTo, setFetchTo] = useState(today())
  const [fetchSymbols, setFetchSymbols] = useState('')
  const [fetchTf, setFetchTf] = useState<Tf>('1d')
  const [fetchEnsure, setFetchEnsure] = useState(true)

  const summaryQuery = useQuery({
    ...getHistoricalSummaryOptions(),
    refetchInterval: 60_000,
  })

  const coverageQuery = useQuery({
    ...getHistoricalCoverageOptions({
      query: { from: coverageFrom, to: coverageTo, symbols: coverageSymbols || undefined, timeframe: coverageTf },
    }),
    refetchInterval: false,
  })

  const jobsQuery = useQuery({
    ...listFetchJobsOptions(),
    refetchInterval: 5000,
  })

  const startFetch = useMutation({
    ...startHistoricalFetchMutation(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['listFetchJobs'] }),
  })

  const dates = dateRange(coverageFrom, coverageTo)

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-lg font-semibold mb-1">Historical Data</h1>
        <p className="text-sm text-muted-foreground">What this instance's InfluxDB holds, per-day coverage, and on-demand fetch</p>
      </div>

      {/* Data in DB — everything stored on this instance */}
      <section className="space-y-4">
        <h2 className="text-base font-medium">Data in DB</h2>
        {summaryQuery.isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
        {summaryQuery.isError && (
          <p className="text-sm text-red-600">Failed to load summary: {String(summaryQuery.error)}</p>
        )}
        {summaryQuery.data && <DbSummary rows={summaryQuery.data} />}
      </section>

      {/* Coverage panel */}
      <section className="space-y-4">
        <h2 className="text-base font-medium">Coverage</h2>
        <div className="flex flex-wrap gap-3 items-end">
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            From
            <input
              type="date"
              value={coverageFrom}
              onChange={e => setCoverageFrom(e.target.value)}
              className="border border-border rounded px-2 py-1 text-sm bg-background text-foreground"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            To
            <input
              type="date"
              value={coverageTo}
              onChange={e => setCoverageTo(e.target.value)}
              className="border border-border rounded px-2 py-1 text-sm bg-background text-foreground"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            Symbols (blank = watchlist)
            <input
              type="text"
              value={coverageSymbols}
              onChange={e => setCoverageSymbols(e.target.value)}
              placeholder="e.g. SPY,QQQ"
              className="border border-border rounded px-2 py-1 text-sm bg-background text-foreground w-48"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            Timeframe
            <select
              value={coverageTf}
              onChange={e => setCoverageTf(e.target.value as Tf)}
              className="border border-border rounded px-2 py-1 text-sm bg-background text-foreground"
            >
              {TIMEFRAMES.map(tf => <option key={tf} value={tf}>{tf}</option>)}
            </select>
          </label>
          <button
            onClick={() => coverageQuery.refetch()}
            className="px-3 py-1.5 rounded-md bg-primary text-primary-foreground text-sm hover:bg-primary/90 transition-colors"
          >
            Refresh
          </button>
        </div>

        {coverageQuery.isLoading && <p className="text-sm text-muted-foreground">Loading coverage…</p>}
        {coverageQuery.isError && (
          <p className="text-sm text-red-600">Failed to load coverage: {String(coverageQuery.error)}</p>
        )}
        {coverageQuery.data && (
          <CoverageTable coverage={coverageQuery.data} dates={dates} full={FULL_EXPECTED[coverageTf]} />
        )}
      </section>

      {/* Fetch panel — collapsed by default; the summary above is the primary view */}
      <details className="group">
        <summary className="cursor-pointer text-base font-medium list-none flex items-center gap-2">
          <span className="text-muted-foreground transition-transform group-open:rotate-90">▸</span>
          Fetch Historical Bars
          <span className="text-xs text-muted-foreground font-normal">(manual download into this instance's InfluxDB)</span>
        </summary>
        <div className="flex flex-wrap gap-3 items-end mt-4">
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            From
            <input
              type="date"
              value={fetchFrom}
              onChange={e => setFetchFrom(e.target.value)}
              className="border border-border rounded px-2 py-1 text-sm bg-background text-foreground"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            To
            <input
              type="date"
              value={fetchTo}
              onChange={e => setFetchTo(e.target.value)}
              className="border border-border rounded px-2 py-1 text-sm bg-background text-foreground"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            Symbols (blank = watchlist)
            <input
              type="text"
              value={fetchSymbols}
              onChange={e => setFetchSymbols(e.target.value)}
              placeholder="e.g. SPY,QQQ"
              className="border border-border rounded px-2 py-1 text-sm bg-background text-foreground w-48"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            Timeframe
            <select
              value={fetchTf}
              onChange={e => setFetchTf(e.target.value as Tf)}
              className="border border-border rounded px-2 py-1 text-sm bg-background text-foreground"
            >
              {TIMEFRAMES.map(tf => <option key={tf} value={tf}>{tf}</option>)}
            </select>
          </label>
          <label className="flex items-center gap-2 text-xs text-muted-foreground pb-1.5">
            <input type="checkbox" checked={fetchEnsure} onChange={e => setFetchEnsure(e.target.checked)} />
            only missing (ensure)
          </label>
          <button
            onClick={() =>
              startFetch.mutate({
                body: {
                  from: fetchFrom,
                  to: fetchTo,
                  symbols: fetchSymbols ? fetchSymbols.split(',').map(s => s.trim().toUpperCase()) : undefined,
                  timeframe: fetchTf,
                  ensure: fetchEnsure,
                },
              })
            }
            disabled={startFetch.isPending}
            className="px-3 py-1.5 rounded-md bg-primary text-primary-foreground text-sm hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {startFetch.isPending ? 'Starting…' : 'Start Fetch'}
          </button>
        </div>
        {startFetch.isError && (
          <p className="text-sm text-red-600">Failed to start fetch: {String(startFetch.error)}</p>
        )}
      </details>

      {/* Jobs table */}
      <section className="space-y-4">
        <h2 className="text-base font-medium">Fetch Jobs</h2>
        {jobsQuery.isLoading && <p className="text-sm text-muted-foreground">Loading jobs…</p>}
        {jobsQuery.data && <JobsTable jobs={jobsQuery.data} />}
      </section>
    </div>
  )
}
