import { useQuery } from '@tanstack/react-query'
import { getMarketDataHealthOptions } from '../../generated/health/@tanstack/react-query.gen'

/**
 * Live market-data flow indicator — distinct from the IBKR socket-connection badge. The socket can
 * be "connected" while data is fully starved (e.g. a competing session), so this reflects whether
 * the engine is actually receiving prices (backed by /health/market-data).
 */
export function DataFlowBadge({ compact = false }: { compact?: boolean }) {
  const { data, isLoading } = useQuery({
    ...getMarketDataHealthOptions(),
    refetchInterval: 10_000,
  })

  let color = 'bg-muted-foreground/40'
  let text = 'Data …'
  let tooltip: string | undefined

  if (!isLoading && data) {
    const { flowing, successes, failures, lastSuccessAgeSeconds, lastError, competingSession } = data
    const ageStr =
      lastSuccessAgeSeconds == null
        ? 'never'
        : lastSuccessAgeSeconds < 90
          ? `${lastSuccessAgeSeconds}s ago`
          : `${Math.round(lastSuccessAgeSeconds / 60)}m ago`

    if (competingSession) {
      // Actionable root cause: a TWS/app on the same account (another IP) is holding the market data.
      color = flowing ? 'bg-amber-400' : 'bg-red-500'
      text = 'Competing TWS session'
      tooltip = 'Another IBKR session (TWS desktop or mobile app) on this account is blocking the engine’s market data. Log out of it — even open charts hold the data.'
    } else if (!flowing) {
      color = 'bg-red-500'
      text = 'Data Down'
      tooltip = `No prices received (last success ${ageStr}). ${lastError ?? ''}`.trim()
    } else if (failures > 0) {
      color = 'bg-amber-400'
      text = 'Data Degraded'
      tooltip = `Flowing but ${failures} recent failures (${successes} ok). ${lastError ?? ''}`.trim()
    } else {
      color = 'bg-green-500'
      text = 'Data Live'
      tooltip = `${successes} price fetches in the last 10 min (last ${ageStr}).`
    }
  }

  return (
    <div className="flex items-center gap-2 text-sm" title={compact ? `${text} — ${tooltip ?? ''}`.trim() : tooltip}>
      <span className={`inline-block h-2 w-2 rounded-full ${color}`} />
      {!compact && <span className="text-muted-foreground">{text}</span>}
    </div>
  )
}
