import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listShadowSpreadsOptions } from '../generated/spreads/@tanstack/react-query.gen'
import type { ShadowSpreadDto } from '../generated/spreads/types.gen'
import { getAiReviewReportOptions } from '../generated/aireview/@tanstack/react-query.gen'
import type { AiReviewReport, ReportCohort } from '../generated/aireview/types.gen'

// Would-be spreads: everything paper didn't fill, followed as if filled at mid (live market makers
// fill near mid; paper's simulator never does). Estimates only — nothing here places orders.

const usd = (x: number | null | undefined) => (x == null ? '—' : `${x < 0 ? '−' : ''}$${Math.abs(x).toFixed(2)}`)
const pnlClass = (x: number | null | undefined) =>
  x == null ? '' : x >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'

function Summary({ title, rows }: { title: string; rows: ShadowSpreadDto[] }) {
  const closed = rows.filter((r) => r.status !== 'OPEN' && r.pnlAtMid != null)
  const open = rows.filter((r) => r.status === 'OPEN')
  const wins = closed.filter((r) => (r.pnlAtMid ?? 0) > 0)
  const losses = closed.filter((r) => (r.pnlAtMid ?? 0) <= 0)
  const sum = (xs: ShadowSpreadDto[], k: 'pnlAtMid' | 'pnlAt95PctMid') => xs.reduce((a, r) => a + (r[k] ?? 0), 0)
  const avg = (xs: ShadowSpreadDto[]) => (xs.length ? sum(xs, 'pnlAtMid') / xs.length : null)
  const cell = (label: string, value: React.ReactNode) => (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-sm font-medium tabular-nums">{value}</p>
    </div>
  )
  return (
    <section className="rounded-lg border border-border bg-card p-4">
      <h2 className="text-sm font-semibold mb-3">{title}</h2>
      <div className="grid grid-cols-3 sm:grid-cols-6 gap-4">
        {cell('Closed', `${closed.length} (${wins.length} won)`)}
        {cell('Win rate', closed.length ? `${Math.round((wins.length / closed.length) * 100)}%` : '—')}
        {cell('Avg win / loss', `${usd(avg(wins))} / ${usd(avg(losses))}`)}
        {cell('P&L at mid', <span className={pnlClass(sum(closed, 'pnlAtMid'))}>{usd(sum(closed, 'pnlAtMid'))}</span>)}
        {cell('P&L at 95% mid', <span className={pnlClass(sum(closed, 'pnlAt95PctMid'))}>{usd(sum(closed, 'pnlAt95PctMid'))}</span>)}
        {cell('Open (marked)', <span className={pnlClass(sum(open, 'pnlAtMid'))}>{`${open.length} · ${usd(sum(open, 'pnlAtMid'))}`}</span>)}
      </div>
    </section>
  )
}

const verdictClass = (v: string | null | undefined) =>
  v === 'VETO' ? 'text-red-600 dark:text-red-400' : v === 'APPROVE' ? 'text-green-600 dark:text-green-400' : 'text-muted-foreground'

const COHORT_LABELS: Record<string, string> = {
  APPROVE: 'AI approved',
  VETO: 'AI vetoed',
  ABSTAIN: 'AI abstained',
  NO_VERDICT_IN_TIME: 'Reviewed, no verdict before the decision',
  NOT_REVIEWED: 'Not reviewed',
}

function CohortRow({ c, label }: { c: ReportCohort; label?: string }) {
  return (
    <tr className="border-t border-border">
      <td className="py-1.5 pr-3">{label ?? COHORT_LABELS[c.label] ?? c.label}</td>
      <td className="py-1.5 pr-3 tabular-nums">{c.count}</td>
      <td className="py-1.5 pr-3 tabular-nums">
        {c.closed} ({c.wins} won{c.winRate != null ? `, ${Math.round(c.winRate * 100)}%` : ''})
      </td>
      <td className={`py-1.5 pr-3 tabular-nums ${pnlClass(c.realizedPnl)}`}>{usd(c.realizedPnl)}</td>
      <td className="py-1.5 pr-3 tabular-nums">{usd(c.avgPnl)}</td>
      <td className={`py-1.5 pr-3 tabular-nums ${pnlClass(c.markedPnl)}`}>
        {c.open} · {usd(c.markedPnl)}
      </td>
      <td className="py-1.5 pr-3 tabular-nums">{c.tradingDays}</td>
    </tr>
  )
}

function AiPanel({ report, rulesVersion, setRulesVersion }: { report: AiReviewReport; rulesVersion: string; setRulesVersion: (v: string) => void }) {
  const d = report.approveMinusVeto
  const head = ['', 'Trades', 'Closed', 'P&L at mid', 'Avg / trade', 'Open (marked)', 'Days']
  return (
    <section className="rounded-lg border border-border bg-card p-4 space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold">AI reviewer ({report.reviewerId}) on the would-be trades — advisory, nothing is blocked</h2>
        {report.rulesVersions.length > 0 && (
          <label className="text-xs text-muted-foreground">
            Rules version{' '}
            <select className="ml-1 rounded border border-border bg-background px-1 py-0.5 text-xs" value={rulesVersion} onChange={(e) => setRulesVersion(e.target.value)}>
              <option value="">all</option>
              {report.rulesVersions.map((v) => (
                <option key={v.version} value={v.version}>
                  {v.version.slice(0, 10)} · since {new Date(v.firstSeenAt).toLocaleDateString()} · {v.tasks} reviews
                </option>
              ))}
            </select>
          </label>
        )}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-xs text-muted-foreground text-left">
              {head.map((h) => (
                <th key={h} className="font-normal py-1 pr-3">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {report.cohorts.map((c) => (
              <CohortRow key={c.label} c={c} />
            ))}
            <tr>
              <td colSpan={7} className="pt-3 text-xs text-muted-foreground">
                The two simulated portfolios (same limits; the AI one skips vetoed titles and takes the next instead)
              </td>
            </tr>
            <CohortRow c={report.rulesOnly} label="Rules only" />
            <CohortRow c={report.withAi} label="Rules + AI" />
            <CohortRow c={report.removedByAi} label="Removed by the AI" />
            <CohortRow c={report.addedByAi} label="Let in instead" />
            {(report.candidates ?? []).length > 0 && (
              <tr>
                <td colSpan={7} className="pt-3 text-xs text-muted-foreground">
                  Every reviewed title, followed from the moment it first qualified, no veto applied (did the verdict
                  call the trade right?)
                  {report.candidateLossesAvoidedMinusWinsForgone != null &&
                    ` · vetoes: losses avoided − wins forgone ${usd(report.candidateLossesAvoidedMinusWinsForgone)}`}
                </td>
              </tr>
            )}
            {(report.candidates ?? []).map((c) => (
              <CohortRow key={`cand-${c.label}`} c={c} />
            ))}
          </tbody>
        </table>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 text-sm">
        <div>
          <p className="text-xs text-muted-foreground">Vetoes: losses avoided − wins forgone</p>
          <p className={`font-medium tabular-nums ${pnlClass(report.lossesAvoidedMinusWinsForgone)}`}>{usd(report.lossesAvoidedMinusWinsForgone)}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">Approved − vetoed, avg per closed trade</p>
          <p className="font-medium tabular-nums">
            {d ? (
              <>
                <span className={pnlClass(d.mean)}>{usd(d.mean)}</span>
                <span className="text-xs text-muted-foreground">
                  {' '}
                  {d.low != null && d.high != null ? `90%: ${usd(d.low)} … ${usd(d.high)}` : 'too few days for an interval'} · n={d.approveClosed}/{d.vetoClosed}
                </span>
              </>
            ) : (
              <span className="text-muted-foreground">needs closed approved and vetoed trades</span>
            )}
          </p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">Reviewed titles at the decision</p>
          <p className="text-xs tabular-nums">
            {Object.entries(report.decisions)
              .map(([k, v]) => `${k.toLowerCase().replace('_', ' ')} ${v}`)
              .join(' · ') || '—'}
            {report.wouldGate != null && ` · gating would block ${report.wouldGate}`}
          </p>
        </div>
      </div>
      <div>
        <p className="text-xs text-muted-foreground mb-1">Calibration — is a confident verdict right more often? (approve right = won, veto right = lost)</p>
        <div className="flex flex-wrap gap-4 text-xs tabular-nums">
          {report.calibration.map((b) => (
            <span key={b.low}>
              {b.low.toFixed(2)}–{b.high.toFixed(2)}: {b.closed ? `${b.right}/${b.closed} (${Math.round((b.right / b.closed) * 100)}%)` : '—'}
            </span>
          ))}
        </div>
      </div>
      <p className="text-xs text-muted-foreground">
        Grouped by the verdict the decision saw; a verdict that came later counts as "no verdict". Small samples swing a lot —
        read the trade counts and days before the P&amp;L.
      </p>
    </section>
  )
}

export function ShadowSpreadsPage() {
  const [rulesVersion, setRulesVersion] = useState('')
  const report = useQuery({
    ...getAiReviewReportOptions({ query: rulesVersion ? { rulesVersion } : undefined }),
    refetchInterval: 300_000,
  })
  const { data, isLoading, isError } = useQuery({ ...listShadowSpreadsOptions(), refetchInterval: 60_000 })
  if (isLoading) return <p className="text-muted-foreground text-sm">Loading…</p>
  if (isError || !data) return <p className="text-destructive text-sm">Failed to load would-be trades.</p>
  const rows = (data as ShadowSpreadDto[]).filter((r) => !rulesVersion || r.aiRulesVersion === rulesVersion)
  // REVIEWED_CANDIDATE rows score the AI verdicts (see the AI panel); they are not decision rows.
  const live = rows.filter((r) => !r.backfilled && r.sourceOutcome !== 'REVIEWED_CANDIDATE')
  const backfilled = rows.filter((r) => r.backfilled)

  return (
    <div className="space-y-6 max-w-6xl">
      <div>
        <h1 className="text-lg font-semibold mb-1">Would-be Trades</h1>
        <p className="text-sm text-muted-foreground">
          Spreads paper didn't fill, followed as if filled at mid, revalued daily from real quotes and closed by the
          same rules as real spreads (take-profit, stock-price stop, time exit, expiry). An estimate of live results —
          nothing here places orders. P&amp;L per 1 contract, before commissions.
        </p>
      </div>
      <Summary title="All titles, one position per title (does the signal make money?)" rows={live} />
      <Summary
        title="Passed the filters (no falling/rising trend, IV rank below the review level)"
        rows={live.filter((r) => r.score != null && r.flags.length === 0)}
      />
      <Summary
        title="Selected portfolio (what the daily ranking would really have taken within your limits)"
        rows={live.filter((r) => r.selected)}
      />
      <Summary
        title="Selected with the AI layer (same limits, primary reviewer's vetoes skipped)"
        rows={live.filter((r) => r.aiSelected)}
      />
      {report.data && <AiPanel report={report.data as AiReviewReport} rulesVersion={rulesVersion} setRulesVersion={setRulesVersion} />}
      {backfilled.length > 0 && <Summary title="Back-filled history (approximate: daily closes + model prices)" rows={backfilled} />}
      <section className="rounded-lg border border-border bg-card p-4 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-xs text-muted-foreground text-left">
              {['Opened', 'Symbol', 'Spread', 'Expiry', 'Mid', 'Natural', 'Score', 'Rank', 'AI', 'Warnings', 'Source', 'Status', 'Value', 'P&L at mid', 'Reason'].map((h) => (
                <th key={h} className="font-normal py-1 pr-3">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className="border-t border-border">
                <td className="py-1.5 pr-3 whitespace-nowrap">
                  {new Date(r.openedAt).toLocaleDateString()}
                  {r.backfilled && <span className="ml-1 text-xs text-muted-foreground">(bf)</span>}
                </td>
                <td className="py-1.5 pr-3 font-medium">{r.symbol}</td>
                <td className="py-1.5 pr-3 whitespace-nowrap">
                  {r.soldStrike}/{r.boughtStrike}
                  {r.optionType === 'PUT' ? 'P' : 'C'}
                </td>
                <td className="py-1.5 pr-3 whitespace-nowrap">{r.expiry}</td>
                <td className="py-1.5 pr-3 tabular-nums">{usd(r.midCredit)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{usd(r.naturalCredit)}</td>
                <td className="py-1.5 pr-3 tabular-nums">{r.score == null ? '—' : r.score.toFixed(2)}</td>
                <td className="py-1.5 pr-3 tabular-nums">
                  {r.decisionRank ?? '—'}
                  {r.selected && <span className="ml-1 text-xs text-green-600 dark:text-green-400">selected</span>}
                  {r.aiSelected && <span className="ml-1 text-xs text-sky-600 dark:text-sky-400">ai</span>}
                </td>
                <td className={`py-1.5 pr-3 text-xs whitespace-nowrap ${verdictClass(r.aiVerdict)}`} title={r.aiReason ?? undefined}>
                  {r.aiVerdict
                    ? `${r.aiVerdict.toLowerCase()}${r.aiConfidence != null ? ` ${r.aiConfidence.toFixed(2)}` : ''}`
                    : r.aiReviewed
                      ? r.aiReason
                        ? 'late'
                        : 'none in time'
                      : ''}
                </td>
                <td className="py-1.5 pr-3 text-xs text-amber-600 dark:text-amber-400">
                  {r.flags.map((f) => f.toLowerCase().replace('_', ' ')).join(', ')}
                </td>
                <td className="py-1.5 pr-3 text-xs">{r.sourceOutcome}</td>
                <td className="py-1.5 pr-3 text-xs">{r.status}</td>
                <td className="py-1.5 pr-3 tabular-nums">{usd(r.exitValue ?? r.lastValue)}</td>
                <td className={`py-1.5 pr-3 tabular-nums ${pnlClass(r.pnlAtMid)}`}>{usd(r.pnlAtMid)}</td>
                <td className="py-1.5 pr-3 text-xs text-muted-foreground">{r.closeReason ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <p className="text-sm text-muted-foreground">Nothing recorded yet.</p>}
      </section>
    </div>
  )
}
