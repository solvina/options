import { useQuery } from '@tanstack/react-query'
import { AlertOctagon } from 'lucide-react'
import { getFatalStatusOptions } from '../../generated/health/@tanstack/react-query.gen'

/**
 * Engine fatal-lockout banner. When the engine has latched a FATAL condition (e.g. the gateway is
 * logged into a different account than the engine is configured to trade), order placement is
 * blocked engine-side — this banner makes that state impossible to miss: full-width, red, on every
 * page, with the exact cause and remedy. Renders nothing while healthy.
 */
export function FatalBanner() {
  const { data } = useQuery({
    ...getFatalStatusOptions(),
    refetchInterval: 10_000,
  })

  if (!data?.fatal) return null

  return (
    <div className="sticky top-0 z-50 border-b-2 border-red-700 bg-red-600 px-4 py-3 text-white">
      <div className="flex items-start gap-3">
        <AlertOctagon className="mt-0.5 h-5 w-5 shrink-0 animate-pulse" />
        <div className="min-w-0">
          <div className="font-bold uppercase tracking-wide">
            Fatal — order placement disabled
          </div>
          {data.reasons.map((r) => (
            <div key={r.title} className="mt-1 text-sm">
              <span className="font-semibold">{r.title}</span>
              <span className="opacity-90"> — {r.detail}</span>
              <span className="ml-2 text-xs opacity-75">
                (since {new Date(r.at).toLocaleTimeString('en-GB')})
              </span>
            </div>
          ))}
          <div className="mt-1 text-xs opacity-90">
            The engine refuses to send any order to the broker. Fix the cause and restart the engine.
          </div>
        </div>
      </div>
    </div>
  )
}
