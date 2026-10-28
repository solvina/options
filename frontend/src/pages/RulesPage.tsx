import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getSpreadRulesOptions } from '../generated/spreads/@tanstack/react-query.gen'
import type { StrategyRulesDto } from '../generated/spreads/types.gen'

// Every number on this page comes from GET /scanner/rules (the engine's bound config + universe
// overrides), so the page cannot drift from what the engine is actually running.

const pct = (x: number) => `${+(x * 100).toFixed(1)}%`
const usd = (x: number) => `$${x.toFixed(2)}`

const LABEL: Record<string, string> = { BULL_PUT: 'Bull put', BEAR_CALL: 'Bear call' }

function RuleTable({
  title,
  strategies,
  rows,
}: {
  title: string
  strategies: StrategyRulesDto[]
  rows: [string, (s: StrategyRulesDto) => ReactNode][]
}) {
  return (
    <section className="rounded-lg border border-border bg-card p-4">
      <h2 className="text-sm font-semibold mb-2">{title}</h2>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-xs text-muted-foreground">
            <th className="text-left font-normal py-1" />
            {strategies.map((s) => (
              <th key={s.strategyId} className="text-right font-normal py-1">
                {LABEL[s.strategyId] ?? s.strategyId}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(([label, value]) => (
            <tr key={label} className="border-t border-border">
              <td className="py-1.5 text-muted-foreground">{label}</td>
              {strategies.map((s) => (
                <td key={s.strategyId} className="py-1.5 text-right tabular-nums">
                  {value(s)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

function Off() {
  return <span className="text-amber-600 dark:text-amber-400">Off</span>
}

export function RulesPage() {
  const { data, isLoading, isError } = useQuery(getSpreadRulesOptions())

  if (isLoading) return <p className="text-muted-foreground text-sm">Loading…</p>
  if (isError || !data) return <p className="text-destructive text-sm">Failed to load trading rules.</p>

  const s = data.strategies
  const p = data.portfolio
  const bp = s.find((x) => x.strategyId === 'BULL_PUT') ?? s[0]

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h1 className="text-lg font-semibold mb-1">Trading Rules</h1>
        <p className="text-sm text-muted-foreground">
          Live from the engine configuration. Trading is{' '}
          {p.tradingEnabled ? (
            <span className="text-green-600 dark:text-green-400">enabled</span>
          ) : (
            <span className="text-amber-600 dark:text-amber-400">disabled</span>
          )}
          .
        </p>
      </div>

      <section className="rounded-lg border border-border bg-card p-4">
        <h2 className="text-sm font-semibold mb-2">Principles</h2>
        <ul className="list-disc pl-5 space-y-1 text-sm text-muted-foreground">
          <li>
            Sell defined-risk credit spreads only when options are expensive (IV rank ≥ {bp.ivRankThreshold}),{' '}
            {bp.minDte}–{bp.maxDte} days out.
          </li>
          <li>
            Direction per symbol:{' '}
            {p.directionalGateEnabled
              ? 'trend + RSI pick bull put (bullish) or bear call (bearish); the other side is suppressed.'
              : 'directional gate is off — both sides may be scanned.'}
          </li>
          <li>Never hold through earnings: skip any symbol whose earnings date falls before expiry.</li>
          <li>
            A full loss on one spread is sized to ≤ {pct(bp.maxRiskPercent)} of the account; at most {p.maxOpenSpreads}{' '}
            open, {p.maxOpenSpreadsPerSector} per sector, {p.maxNewEntriesPerDay} new per day.
          </li>
          <li>
            Don't chase fills: never sell below {pct(p.entryMinFillPctOfMid)} of mid. No fill means retry next scan
            ({p.entryCooldownMinutes} min cooldown).
          </li>
          <li>
            Take profit at {pct(bp.takeProfitPercent)} of the credit; close everything at {bp.timeProfitDte} days to
            expiry.
          </li>
        </ul>
      </section>

      <RuleTable
        title="Entry"
        strategies={s}
        rows={[
          ['Enabled', (x) => (x.enabled ? 'Yes' : <Off />)],
          ['IV rank ≥', (x) => x.ivRankThreshold],
          ['Days to expiry', (x) => `${x.minDte}–${x.maxDte} (prefer ${x.preferredDte})`],
          ['Short-leg delta', (x) => `${x.deltaMin}–${x.deltaMax} (target ${x.targetDelta})`],
          ['Width', (x) => usd(x.spreadWidthUsd)],
          ['Min credit / share', (x) => usd(x.minCreditPerShare)],
          ['Max credit, % of width', (x) => pct(x.maxCreditPctOfWidth)],
          ['Max risk / spread, % of account', (x) => pct(x.maxRiskPercent)],
          ['Abort if underlying drifts', (x) => pct(x.driftProtectionPct)],
        ]}
      />

      <RuleTable
        title="Exit"
        strategies={s}
        rows={[
          ['Take profit at', (x) => `${pct(x.takeProfitPercent)} of credit`],
          ['Value stop', (x) => (x.stopLossPercent > 0 ? `${+(1 + x.stopLossPercent).toFixed(2)}× entry mid` : <Off />)],
          ['Time exit', (x) => `≤ ${x.timeProfitDte} DTE`],
        ]}
      />

      <section className="rounded-lg border border-border bg-card p-4">
        <h2 className="text-sm font-semibold mb-2">Stop gates & cadence</h2>
        <ul className="text-sm text-muted-foreground space-y-1">
          <li>
            Stop needs {p.stopLossConfirmCycles} consecutive breaching checks; none in the first{' '}
            {p.stopLossGraceMinutesAfterEntry} min after entry or {p.stopLossSkipFirstRthMinutes} min of the session.
          </li>
          <li>No re-entry on a stopped symbol for {p.stopLossCooldownHours} h.</li>
          <li>
            Scan schedule <code className="font-mono text-xs bg-muted px-1 rounded">{p.scanCron}</code> (server time).
          </li>
        </ul>
      </section>

      <section className="rounded-lg border border-border bg-card p-4">
        <h2 className="text-sm font-semibold mb-2">Per-symbol overrides</h2>
        {data.symbolOverrides.length === 0 ? (
          <p className="text-sm text-muted-foreground">None — every symbol uses the defaults above.</p>
        ) : (
          <ul className="text-sm space-y-1">
            {data.symbolOverrides.map((o) => (
              <li key={o.symbol}>
                <span className="font-medium">{o.symbol}</span>{' '}
                <span className="text-muted-foreground">
                  {Object.entries(o.overrides)
                    .map(([k, v]) => `${k}=${v}`)
                    .join(', ')}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
