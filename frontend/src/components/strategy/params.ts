/**
 * Client side of the strategy tuning layer (`/api/strategy-params`).
 *
 * Deliberately descriptor-driven: nothing here names a single parameter. Adding a parameter to a
 * strategy — or adding a whole strategy — changes no frontend code, which is the entire reason the
 * backend stores a params blob rather than columns.
 */

export type ParamType = 'INT' | 'DOUBLE' | 'BOOLEAN' | 'STRING'

export type ParamDescriptor = {
  name: string
  type: ParamType
  default: unknown
  min: number | null
  max: number | null
  group: string
  help: string | null
}

export type StrategyParams = {
  strategyId: string
  displayName: string
  descriptors: ParamDescriptor[]
  /** Descriptor defaults — what Reset restores. */
  defaults: Record<string, unknown>
  /** Saved global baseline (defaults with the stored row applied). */
  values: Record<string, unknown>
  /** True when a saved baseline exists, i.e. Reset would change something. */
  customised: boolean
}

export type SymbolParams = {
  strategyId: string
  symbol: string
  values: Record<string, unknown>
  overrides: Record<string, unknown>
}

const base = '/api/strategy-params'

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.error ?? `${res.status} ${res.statusText}`)
  }
  return res.json() as Promise<T>
}

const send = (url: string, method: string, body?: unknown) =>
  fetch(url, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })

export const strategyParamsApi = {
  get: (strategyId: string) => fetch(`${base}/${strategyId}`).then(json<StrategyParams>),
  save: (strategyId: string, values: Record<string, unknown>) =>
    send(`${base}/${strategyId}`, 'PUT', values).then(json<StrategyParams>),
  /** Reset is a DELETE: the absolute defaults live only in the descriptors, never in a stored row. */
  reset: (strategyId: string) => send(`${base}/${strategyId}`, 'DELETE').then(json<StrategyParams>),
  getSymbol: (strategyId: string, symbol: string) =>
    fetch(`${base}/${strategyId}/symbols/${symbol}`).then(json<SymbolParams>),
  saveSymbol: (strategyId: string, symbol: string, values: Record<string, unknown>) =>
    send(`${base}/${strategyId}/symbols/${symbol}`, 'PUT', values).then(json<unknown>),
  resetSymbol: (strategyId: string, symbol: string) =>
    send(`${base}/${strategyId}/symbols/${symbol}`, 'DELETE').then(json<unknown>),
}

/** The flag strategy's persisted id — matches FLAG_STRATEGY_ID on the server. */
export const FLAG_STRATEGY_ID = 'bull_flag'

/**
 * Values a Candle Scanner row hands to the Universe page when you follow its link.
 *
 * Passed through sessionStorage rather than the query string: sixteen parameters would make an
 * unreadable URL, and these are a one-shot handoff, not a shareable address.
 */
const PREFILL_KEY = 'flagParamPrefill'

export type ParamPrefill = { symbol: string; values: Record<string, unknown> }

export const paramPrefill = {
  set: (prefill: ParamPrefill) => sessionStorage.setItem(PREFILL_KEY, JSON.stringify(prefill)),
  /** Reads and clears — a prefill must not survive into the next, unrelated edit. */
  take: (symbol: string): Record<string, unknown> | null => {
    const raw = sessionStorage.getItem(PREFILL_KEY)
    if (!raw) return null
    sessionStorage.removeItem(PREFILL_KEY)
    try {
      const parsed = JSON.parse(raw) as ParamPrefill
      return parsed.symbol === symbol ? parsed.values : null
    } catch {
      return null
    }
  },
}

/** Groups in declaration order, so the form follows the strategy's own grouping. */
export function paramGroups(descriptors: ParamDescriptor[]): string[] {
  return [...new Set(descriptors.map((d) => d.group))]
}

/** Coerces a form string back to the descriptor's declared type. */
export function coerce(descriptor: ParamDescriptor, raw: string): unknown {
  if (descriptor.type === 'BOOLEAN') return raw === 'true'
  if (descriptor.type === 'STRING') return raw
  const n = Number(raw)
  return Number.isNaN(n) ? null : descriptor.type === 'INT' ? Math.round(n) : n
}
