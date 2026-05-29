import { useQuery } from '@tanstack/react-query'
import { getIbkrConnectionStatusOptions } from '../../generated/health/@tanstack/react-query.gen'
import { getDataHealthOptions } from '../../generated/diagnostic/@tanstack/react-query.gen'

export function ConnectionBadge() {
  const { data: connectionData } = useQuery({
    ...getIbkrConnectionStatusOptions(),
    refetchInterval: 10_000,
  })

  const { data: healthData } = useQuery({
    ...getDataHealthOptions(),
    refetchInterval: 10_000,
  })

  const connected = connectionData?.connected ?? false
  const reconnecting = !connected && (connectionData?.autoReconnectThreadActive ?? false)

  // Check for market data errors across all symbols
  const symbolErrors = healthData?.symbols?.flatMap(s => s.errors ?? []) ?? []
  const hasMarketDataError = symbolErrors.some(err =>
    err?.includes('market data is not subscribed') ||
    err?.includes('354') ||
    err?.includes('10091') ||
    err?.includes('additional subscription')
  )
  const hasCompetingSession = symbolErrors.some(err =>
    err?.includes('competing live session') || err?.includes('10197')
  )
  const hasOtherErrors = symbolErrors.length > 0 && !hasMarketDataError && !hasCompetingSession

  // Determine status
  let status: 'offline' | 'reconnecting' | 'no-market-data' | 'competing-session' | 'other-error' | 'connected' = 'offline'
  let statusText = 'IBKR Offline'
  let statusColor = 'bg-red-500'

  if (reconnecting) {
    status = 'reconnecting'
    statusText = 'Reconnecting…'
    statusColor = 'bg-amber-400 animate-pulse'
  } else if (connected) {
    if (hasMarketDataError) {
      status = 'no-market-data'
      statusText = 'No Market Data'
      statusColor = 'bg-red-500'
    } else if (hasCompetingSession) {
      status = 'competing-session'
      statusText = 'Competing Session'
      statusColor = 'bg-amber-400'
    } else if (hasOtherErrors) {
      status = 'other-error'
      statusText = 'Connection Error'
      statusColor = 'bg-orange-500'
    } else {
      status = 'connected'
      statusText = 'IBKR Connected'
      statusColor = 'bg-green-500'
    }
  }

  const getTooltip = () => {
    if (status === 'no-market-data') {
      return 'Market data subscription not enabled. Log in to Interactive Brokers account to enable.'
    }
    if (status === 'competing-session') {
      return 'Another session is using the market data. Log out from browser to fix.'
    }
    if (status === 'other-error' && symbolErrors.length > 0) {
      return `Errors: ${symbolErrors.slice(0, 2).join(', ')}`
    }
    return undefined
  }

  const tooltip = getTooltip()

  return (
    <div className="flex items-center gap-2 text-sm" title={tooltip}>
      <span className={`inline-block h-2 w-2 rounded-full ${statusColor}`} />
      <span className="text-muted-foreground">{statusText}</span>
    </div>
  )
}
