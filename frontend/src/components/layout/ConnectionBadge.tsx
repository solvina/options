import { useQuery } from '@tanstack/react-query'
import { getIbkrConnectionStatusOptions } from '../../generated/health/@tanstack/react-query.gen'

export function ConnectionBadge() {
  const { data } = useQuery({
    ...getIbkrConnectionStatusOptions(),
    refetchInterval: 10_000,
  })

  const connected = data?.connected ?? false
  const reconnecting = !connected && (data?.autoReconnectThreadActive ?? false)

  return (
    <div className="flex items-center gap-2 text-sm">
      <span
        className={`inline-block h-2 w-2 rounded-full ${
          connected ? 'bg-green-500' : reconnecting ? 'bg-amber-400 animate-pulse' : 'bg-red-500'
        }`}
      />
      <span className="text-muted-foreground">
        {connected ? 'IBKR Connected' : reconnecting ? 'Reconnecting…' : 'IBKR Offline'}
      </span>
    </div>
  )
}
