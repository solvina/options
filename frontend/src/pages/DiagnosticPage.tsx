import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getDataHealthOptions,
  probeSymbolMutation,
  probeAccountMutation,
} from '../generated/diagnostic/@tanstack/react-query.gen'
import type {
  SymbolHealthDto,
  AccountHealthDto,
  OptionMidSampleDto,
} from '../generated/diagnostic/types.gen'
import { usePersistentSortable, sorted, SortTh } from '../lib/sort'

type DataSource = 'LIVE' | 'HISTORICAL_FALLBACK' | 'BS_FALLBACK' | 'UNAVAILABLE'

function SourceBadge({ source }: { source: DataSource | undefined }) {
  if (!source) return <span className="text-muted-foreground">—</span>
  const styles: Record<DataSource, string> = {
    LIVE: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
    HISTORICAL_FALLBACK: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400',
    BS_FALLBACK: 'bg-orange-100 text-orange-800 dark:bg-orange-900/30 dark:text-orange-400',
    UNAVAILABLE: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
  }
  const labels: Record<DataSource, string> = {
    LIVE: 'LIVE',
    HISTORICAL_FALLBACK: 'HIST',
    BS_FALLBACK: 'BS',
    UNAVAILABLE: 'NONE',
  }
  return (
    <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${styles[source]}`}>
      {labels[source]}
    </span>
  )
}

function StatusIcon({ ok, error }: { ok: boolean; error?: string | null }) {
  if (ok) return <span className="text-green-600 dark:text-green-400 font-bold">✓</span>
  return <span className="text-red-500 font-bold" title={error ?? undefined}>✗</span>
}

function fmt(val: number | null | undefined, d = 2) {
  return val == null ? '—' : val.toFixed(d)
}

function OptionSamplesTable({ samples }: { samples: OptionMidSampleDto[] }) {
  if (samples.length === 0) return <p className="text-muted-foreground text-xs">No option samples.</p>
  return (
    <table className="text-xs w-full">
      <thead>
        <tr className="text-muted-foreground border-b border-border">
          <th className="text-left py-1 pr-3">Strike</th>
          <th className="text-left py-1 pr-3">Expiry</th>
          <th className="text-left py-1 pr-3">Bid</th>
          <th className="text-left py-1 pr-3">Ask</th>
          <th className="text-left py-1 pr-3">Mid</th>
          <th className="text-left py-1 pr-3">Delta</th>
          <th className="text-left py-1 pr-3">IV</th>
          <th className="text-left py-1 pr-3">Source</th>
          <th className="text-left py-1">ms</th>
        </tr>
      </thead>
      <tbody>
        {samples.map((s, i) => (
          <tr key={i} className="border-b border-border/50">
            <td className="py-1 pr-3 tabular-nums">{s.strike}</td>
            <td className="py-1 pr-3">{s.expiry}</td>
            <td className="py-1 pr-3 tabular-nums">{fmt(s.bid, 4)}</td>
            <td className="py-1 pr-3 tabular-nums">{fmt(s.ask, 4)}</td>
            <td className="py-1 pr-3 tabular-nums font-medium">{fmt(s.mid, 4)}</td>
            <td className="py-1 pr-3 tabular-nums">{fmt(s.delta, 3)}</td>
            <td className="py-1 pr-3 tabular-nums">{s.impliedVol != null ? (s.impliedVol * 100).toFixed(1) + '%' : '—'}</td>
            <td className="py-1 pr-3"><SourceBadge source={s.source} /></td>
            <td className="py-1 tabular-nums text-muted-foreground">{s.durationMs}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function SymbolRow({ report, onProbe }: { report: SymbolHealthDto; onProbe: () => void }) {
  const [expanded, setExpanded] = useState(false)

  const contractOk = report.contractResolution?.stockConId != null && !report.contractResolution?.error
  const optionParamsOk = (report.optionParams?.strikeCount ?? 0) > 0
  const histOk = (report.historicalData?.barCount ?? 0) >= 200
  const spotSource = report.spot?.source
  const spotOk = spotSource === 'LIVE'
  const sampleSources = report.optionSamples?.map(s => s.source) ?? []
  const allLive = sampleSources.length > 0 && sampleSources.every(s => s === 'LIVE')
  const tickOk = (report.tickStream?.ticksReceived ?? 0) > 0
  const errorCount = report.errors?.length ?? 0

  return (
    <>
      <tr
        className="border-b border-border hover:bg-muted/30 transition-colors cursor-pointer"
        onClick={() => setExpanded(e => !e)}
      >
        <td className="px-3 py-2 font-medium">{report.symbol}</td>
        <td className="px-3 py-2 text-center">
          <StatusIcon ok={contractOk} error={report.contractResolution?.error} />
        </td>
        <td className="px-3 py-2 tabular-nums">
          {optionParamsOk
            ? <span className="text-green-600 dark:text-green-400">{report.optionParams?.strikeCount} strikes / {report.optionParams?.availableExpiries?.length} exp</span>
            : <span className="text-red-500">{report.optionParams?.error ?? 'none'}</span>}
        </td>
        <td className="px-3 py-2 tabular-nums">
          <span className={histOk ? 'text-green-600 dark:text-green-400' : 'text-yellow-600'}>
            {report.historicalData?.barCount ?? 0} bars
          </span>
          {report.historicalData?.ivRank != null && (
            <span className="ml-2 text-muted-foreground text-xs">
              IVR {report.historicalData.ivRank.toFixed(1)}%
            </span>
          )}
        </td>
        <td className="px-3 py-2">
          <div className="flex items-center gap-1.5">
            <SourceBadge source={spotSource} />
            {!spotOk && <span className="text-xs text-muted-foreground">{fmt(report.spot?.price)}</span>}
            {spotOk && <span className="text-xs tabular-nums">{fmt(report.spot?.price)}</span>}
          </div>
        </td>
        <td className="px-3 py-2">
          {sampleSources.length === 0
            ? <span className="text-muted-foreground text-xs">—</span>
            : allLive
              ? <span className="text-green-600 dark:text-green-400 text-xs">{sampleSources.length}× LIVE</span>
              : <span className="text-orange-500 text-xs">{sampleSources.filter(s => s !== 'LIVE').length} fallback</span>}
        </td>
        <td className="px-3 py-2">
          {report.tickStream
            ? <span className={tickOk ? 'text-green-600 dark:text-green-400 text-xs' : 'text-red-500 text-xs'}>
                {report.tickStream.ticksReceived} ticks
              </span>
            : <span className="text-muted-foreground text-xs">—</span>}
        </td>
        <td className="px-3 py-2">
          {errorCount > 0
            ? <span className="text-red-500 text-xs">{errorCount} error{errorCount > 1 ? 's' : ''}</span>
            : <span className="text-green-600 dark:text-green-400 text-xs">OK</span>}
        </td>
        <td className="px-3 py-2 text-muted-foreground text-xs">
          {report.probedAt ? new Date(report.probedAt).toLocaleTimeString() : '—'}
        </td>
        <td className="px-3 py-2" onClick={e => e.stopPropagation()}>
          <button
            onClick={onProbe}
            className="px-2 py-1 text-xs rounded bg-secondary text-secondary-foreground hover:bg-secondary/80"
          >
            Probe
          </button>
        </td>
      </tr>
      {expanded && (
        <tr className="bg-muted/20">
          <td colSpan={10} className="px-6 py-4">
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4 text-xs">
                <div>
                  <p className="font-medium mb-1 text-muted-foreground uppercase tracking-wide">Contract Resolution</p>
                  <p>conId: <span className="font-mono">{report.contractResolution?.stockConId ?? '—'}</span></p>
                  <p>duration: {report.contractResolution?.durationMs}ms</p>
                  {report.contractResolution?.error && <p className="text-red-500">{report.contractResolution.error}</p>}
                </div>
                <div>
                  <p className="font-medium mb-1 text-muted-foreground uppercase tracking-wide">Tick Stream</p>
                  {report.tickStream ? (
                    <>
                      <p>ticks: <span className="font-medium">{report.tickStream.ticksReceived}</span> in {report.tickStream.windowMs}ms</p>
                      <p>last bid: {fmt(report.tickStream.lastBid, 4)} / ask: {fmt(report.tickStream.lastAsk, 4)}</p>
                      <p>last delta: {fmt(report.tickStream.lastDelta, 4)}</p>
                      {report.tickStream.error && <p className="text-red-500">{report.tickStream.error}</p>}
                    </>
                  ) : <p className="text-muted-foreground">not probed</p>}
                </div>
              </div>
              <div>
                <p className="font-medium mb-2 text-muted-foreground uppercase tracking-wide text-xs">Option Samples</p>
                <OptionSamplesTable samples={report.optionSamples ?? []} />
              </div>
              {(report.errors?.length ?? 0) > 0 && (
                <div>
                  <p className="font-medium mb-1 text-red-500 uppercase tracking-wide text-xs">Errors</p>
                  {report.errors!.map((e, i) => (
                    <p key={i} className="text-xs text-red-500 font-mono">{e}</p>
                  ))}
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  )
}

function AccountPanel({ account, onProbe, probing }: {
  account: AccountHealthDto | undefined
  onProbe: () => void
  probing: boolean
}) {
  const netLiqOk = account?.netLiquidation != null && !account?.accountError
  const posOk = !account?.positionsError
  const ordersOk = !account?.openOrdersError

  return (
    <div className="border border-border rounded-lg p-4 mb-6">
      <div className="flex items-center justify-between mb-3">
        <h2 className="font-semibold text-sm">Account</h2>
        <div className="flex items-center gap-2">
          {account?.probedAt && (
            <span className="text-xs text-muted-foreground">
              {new Date(account.probedAt).toLocaleTimeString()}
            </span>
          )}
          <button
            onClick={onProbe}
            disabled={probing}
            className="px-2 py-1 text-xs rounded bg-secondary text-secondary-foreground hover:bg-secondary/80 disabled:opacity-50"
          >
            {probing ? '…' : 'Probe Account'}
          </button>
        </div>
      </div>
      {!account ? (
        <p className="text-muted-foreground text-sm">Not yet probed.</p>
      ) : (
        <div className="grid grid-cols-4 gap-4 text-sm">
          <div>
            <p className="text-xs text-muted-foreground mb-0.5">Net Liquidation</p>
            <p className={netLiqOk ? 'font-medium' : 'text-red-500'}>
              {account.netLiquidation != null ? '$' + account.netLiquidation.toLocaleString() : '—'}
            </p>
            {account.accountError && <p className="text-xs text-red-500">{account.accountError}</p>}
          </div>
          <div>
            <p className="text-xs text-muted-foreground mb-0.5">Available Funds</p>
            <p className="font-medium">
              {account.availableFunds != null ? '$' + account.availableFunds.toLocaleString() : '—'}
            </p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground mb-0.5">Positions</p>
            <div className="flex items-center gap-1">
              <StatusIcon ok={posOk} />
              <span className="font-medium">{account.positionCount}</span>
            </div>
            {account.positionsError && <p className="text-xs text-red-500">{account.positionsError}</p>}
          </div>
          <div>
            <p className="text-xs text-muted-foreground mb-0.5">Open Orders</p>
            <div className="flex items-center gap-1">
              <StatusIcon ok={ordersOk} />
              <span className="font-medium">{account.openOrderCount}</span>
            </div>
            {account.openOrdersError && <p className="text-xs text-red-500">{account.openOrdersError}</p>}
          </div>
        </div>
      )}
    </div>
  )
}

export function DiagnosticPage() {
  const qc = useQueryClient()
  const [probingSymbol, setProbingSymbol] = useState<string | null>(null)
  const { sort, toggle } = usePersistentSortable('diagnostic', 'symbol', 'asc')

  const { data, isLoading, isError, dataUpdatedAt } = useQuery({
    ...getDataHealthOptions(),
    refetchInterval: 30_000,
  })

  const probeSymbol = useMutation({
    ...probeSymbolMutation(),
    onMutate: (vars) => setProbingSymbol(vars.path?.symbol ?? null),
    onSettled: () => {
      setProbingSymbol(null)
      qc.invalidateQueries({ queryKey: [{ _id: 'getDataHealth' }] })
    },
  })

  const probeAccount = useMutation({
    ...probeAccountMutation(),
    onSettled: () => qc.invalidateQueries({ queryKey: [{ _id: 'getDataHealth' }] }),
  })

  const symbols = data?.symbols ?? []
  const sortedSymbols = sorted(symbols, sort, (r, k) => {
    if (k === 'spot') return r.spot?.price ?? null
    if (k === 'errors') return r.errors?.length ?? 0
    if (k === 'probedAt') return r.probedAt ? new Date(r.probedAt).getTime() : null
    return (r as Record<string, unknown>)[k]
  })

  function probeAll() {
    const targets = (data?.watchlist ?? []).length > 0 ? (data?.watchlist ?? []) : symbols.map(s => s.symbol).filter(Boolean) as string[]
    targets.forEach(symbol => probeSymbol.mutate({ path: { symbol } }))
    probeAccount.mutate({})
  }

  const thClass = 'px-3 py-2 text-left'

  return (
    <div>
      <div className="flex items-center justify-between mb-5">
        <h1 className="text-xl font-semibold">Diagnostics</h1>
        <div className="flex items-center gap-3">
          {dataUpdatedAt > 0 && (
            <span className="text-xs text-muted-foreground">
              Updated {new Date(dataUpdatedAt).toLocaleTimeString()}
            </span>
          )}
          <button
            onClick={probeAll}
            disabled={probeSymbol.isPending || probeAccount.isPending}
            className="px-3 py-1.5 text-sm rounded bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {probeSymbol.isPending || probeAccount.isPending ? 'Probing…' : 'Probe All'}
          </button>
        </div>
      </div>

      <AccountPanel
        account={data?.account}
        onProbe={() => probeAccount.mutate({})}
        probing={probeAccount.isPending}
      />

      {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
      {isError && <p className="text-destructive text-sm">Failed to load diagnostic data.</p>}

      {symbols.length === 0 && !isLoading && (
        <p className="text-muted-foreground text-sm">No symbol reports yet. Click "Probe All" to run diagnostics.</p>
      )}

      {symbols.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/50 text-muted-foreground text-xs uppercase tracking-wide">
                <SortTh label="Symbol" col="symbol" sort={sort} onSort={toggle} className={thClass} />
                <th className="px-3 py-2 text-center">Contract</th>
                <th className={thClass}>Params</th>
                <th className={thClass}>Hist Data</th>
                <SortTh label="Spot" col="spot" sort={sort} onSort={toggle} className={thClass} />
                <th className={thClass}>Options</th>
                <th className={thClass}>Tick Stream</th>
                <SortTh label="Errors" col="errors" sort={sort} onSort={toggle} className={thClass} />
                <SortTh label="Probed At" col="probedAt" sort={sort} onSort={toggle} className={thClass} />
                <th className={thClass}></th>
              </tr>
            </thead>
            <tbody>
              {sortedSymbols.map(report => (
                <SymbolRow
                  key={report.symbol}
                  report={probingSymbol === report.symbol
                    ? { ...report, symbol: report.symbol }
                    : report}
                  onProbe={() => report.symbol && probeSymbol.mutate({ path: { symbol: report.symbol } })}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="text-xs text-muted-foreground mt-3">Click a row to expand details. Auto-refreshes every 30s.</p>
    </div>
  )
}
