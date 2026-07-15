import { useCallback, useState } from 'react'

/**
 * State backed by localStorage — survives reloads and navigation. Reads once on mount (falling back
 * to `initial` when absent or unparseable) and writes on every update. Writes are best-effort: a
 * quota/serialization failure is swallowed so the UI never breaks over a persistence hiccup.
 */
export function useLocalStorage<T>(
  key: string,
  initial: T,
): [T, (value: T | ((prev: T) => T)) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem(key)
      return raw != null ? (JSON.parse(raw) as T) : initial
    } catch {
      return initial
    }
  })

  const set = useCallback(
    (next: T | ((prev: T) => T)) => {
      setValue((prev) => {
        const resolved = typeof next === 'function' ? (next as (p: T) => T)(prev) : next
        try {
          localStorage.setItem(key, JSON.stringify(resolved))
        } catch {
          /* ignore quota / serialization errors — persistence is best-effort */
        }
        return resolved
      })
    },
    [key],
  )

  return [value, set]
}
