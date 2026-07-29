import { useCallback, useEffect, useState } from 'react'
import { ParamFields } from './ParamFields'
import { strategyParamsApi, type StrategyParams } from './params'

/**
 * Global tuning for one strategy: edit, Apply, and Reset to the absolute defaults.
 *
 * Collapsed by default and remembered per strategy — this sits above the position tables on a page
 * that is mostly read, and scrolling past sixteen fields to reach them every time is the reason it
 * collapses at all.
 *
 * Reset only fills the form; nothing is stored until Apply. That keeps one rule for the whole panel
 * — the DB changes when you press Apply, never before — so "reset" cannot silently become a write.
 */
export function StrategyParamsPanel({ strategyId }: { strategyId: string }) {
  const storageKey = `strategyParamsPanel.open.${strategyId}`
  const [open, setOpen] = useState(() => localStorage.getItem(storageKey) === 'true')
  const [meta, setMeta] = useState<StrategyParams | null>(null)
  const [form, setForm] = useState<Record<string, unknown>>({})
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const next = await strategyParamsApi.get(strategyId)
      setMeta(next)
      setForm(next.values)
      setDirty(false)
      setError(null)
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    }
  }, [strategyId])

  useEffect(() => {
    if (open && meta == null) void load()
  }, [open, meta, load])

  function toggle() {
    const next = !open
    setOpen(next)
    localStorage.setItem(storageKey, String(next))
  }

  function change(name: string, value: unknown) {
    setForm((f) => ({ ...f, [name]: value }))
    setDirty(true)
    setNote(null)
  }

  async function apply() {
    setBusy(true)
    setError(null)
    try {
      const saved = await strategyParamsApi.save(strategyId, form)
      setMeta(saved)
      setForm(saved.values)
      setDirty(false)
      setNote('Applied to every symbol without its own override.')
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    } finally {
      setBusy(false)
    }
  }

  /** Clears the saved baseline so the strategy falls back to its descriptor defaults. */
  async function resetSaved() {
    setBusy(true)
    setError(null)
    try {
      const reset = await strategyParamsApi.reset(strategyId)
      setMeta(reset)
      setForm(reset.values)
      setDirty(false)
      setNote('Saved defaults cleared — back to the strategy defaults.')
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="rounded-lg border border-border bg-card">
      <button
        onClick={toggle}
        className="w-full flex items-center justify-between px-5 py-3 text-left hover:bg-muted/30 transition-colors"
      >
        <span className="flex items-center gap-2">
          <span className="text-base font-semibold">Strategy Parameters</span>
          {meta?.customised && (
            <span className="text-[11px] px-1.5 py-0.5 rounded bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300">
              customised
            </span>
          )}
        </span>
        <span className="text-xs text-muted-foreground">{open ? 'hide ▲' : 'show ▼'}</span>
      </button>

      {open && (
        <div className="px-5 pb-5 space-y-4 border-t border-border pt-4">
          {error && <p className="text-sm text-destructive">{error}</p>}
          {!meta && !error && <p className="text-sm text-muted-foreground">Loading…</p>}

          {meta && (
            <>
              <p className="text-xs text-muted-foreground">
                Applies to every symbol that has no override of its own. A symbol's own values are edited
                on the Universe page.
              </p>

              <ParamFields
                descriptors={meta.descriptors}
                values={form}
                onChange={change}
                dirtyAgainst={meta.values}
                disabled={busy}
              />

              <div className="flex items-center justify-between gap-2 pt-1">
                <span className="text-xs text-muted-foreground">
                  {note ?? (dirty ? 'Unsaved changes — press Apply to store and use them.' : '')}
                </span>
                <div className="flex gap-2">
                  <button
                    onClick={() => {
                      setForm(meta.defaults)
                      setDirty(true)
                      setNote('Form filled with the strategy defaults — press Apply to store them.')
                    }}
                    disabled={busy}
                    className="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-accent transition-colors disabled:opacity-40"
                  >
                    Reset to defaults
                  </button>
                  <button
                    onClick={resetSaved}
                    disabled={busy || !meta.customised}
                    title="Delete the saved baseline so the strategy defaults apply again"
                    className="px-3 py-1.5 text-sm rounded-md border border-border hover:bg-accent transition-colors disabled:opacity-40"
                  >
                    Clear saved
                  </button>
                  <button
                    onClick={apply}
                    disabled={busy || !dirty}
                    className="px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:opacity-90 disabled:opacity-50"
                  >
                    {busy ? 'Applying…' : 'Apply'}
                  </button>
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}
