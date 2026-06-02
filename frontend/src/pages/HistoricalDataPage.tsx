import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getHistoricalCoverageOptions,
  listFetchJobsOptions,
  startHistoricalFetchMutation,
} from '../generated/historical/@tanstack/react-query.gen'
import type { FetchJobDto, SymbolCoverageDto } from '../generated/historical/types.gen'

const FULL_EXPECTED = 78 // conservative: at least US bars expected (EU = 102)

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

function barCountColor(count: number): string {
  if (count === 0) return 'bg-muted text-muted-foreground'
  if (count >= FULL_EXPECTED) return 'bg-green-600 text-white'
  if (count >= FULL_EXPECTED * 0.5) return 'bg-yellow-500 text-white'
  return 'bg-orange-500 text-white'
}

function CoverageCell({ count, date }: { count: number; date: string }) {
  const isWeekend = [0, 6].includes(new Date(date).getDay())
  if (isWeekend) return <td className="w-8 h-7 text-center text-[10px] text-muted-foreground/30">·</td>
  return (
    <td
      className={`w-8 h-7 text-center text-[10px] font-mono rounded cursor-default transition-colors ${barCountColor(count)}`}
      title={`${date}: ${count} bars`}
    >
      {count > 0 ? count : '—'}
    </td>
  )
}

function CoverageTable({ coverage, dates }: { coverage: SymbolCoverageDto[]; dates: string[] }) {
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
                <CoverageCell key={date} count={row.days[date] ?? 0} date={date} />
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <div className="flex gap-4 mt-3 text-xs text-muted-foreground">
        <span className="flex items-center gap-1.5">
          <span className="inline-block w-3 h-3 rounded bg-green-600" /> Full ({FULL_EXPECTED}+ bars)
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

export function HistoricalDataPage() {
  const qc = useQueryClient()

  const [coverageFrom, setCoverageFrom] = useState(daysAgo(30))
  const [coverageTo, setCoverageTo] = useState(today())
  const [coverageSymbols, setCoverageSymbols] = useState('')

  const [fetchFrom, setFetchFrom] = useState(daysAgo(30))
  const [fetchTo, setFetchTo] = useState(today())
  const [fetchSymbols, setFetchSymbols] = useState('')

  const coverageQuery = useQuery({
    ...getHistoricalCoverageOptions({
      query: { from: coverageFrom, to: coverageTo, symbols: coverageSymbols || undefined },
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
        <p className="text-sm text-muted-foreground">5-min bar coverage overview and on-demand fetch</p>
      </div>

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
          <CoverageTable coverage={coverageQuery.data} dates={dates} />
        )}
      </section>

      {/* Fetch panel */}
      <section className="space-y-4">
        <h2 className="text-base font-medium">Fetch Historical Bars</h2>
        <div className="flex flex-wrap gap-3 items-end">
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
          <button
            onClick={() =>
              startFetch.mutate({
                body: {
                  from: fetchFrom,
                  to: fetchTo,
                  symbols: fetchSymbols ? fetchSymbols.split(',').map(s => s.trim().toUpperCase()) : undefined,
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
      </section>

      {/* Jobs table */}
      <section className="space-y-4">
        <h2 className="text-base font-medium">Fetch Jobs</h2>
        {jobsQuery.isLoading && <p className="text-sm text-muted-foreground">Loading jobs…</p>}
        {jobsQuery.data && <JobsTable jobs={jobsQuery.data} />}
      </section>
    </div>
  )
}
