import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { ParamFields } from '../components/strategy/ParamFields'
import {
  FLAG_STRATEGY_ID,
  paramPrefill,
  strategyParamsApi,
  type StrategyParams,
  type SymbolParams,
} from '../components/strategy/params'
import {
  listUniverseOptions,
  toggleInstrumentMutation,
  saveInstrumentMutation,
  deleteInstrumentMutation,
} from '../generated/universe/@tanstack/react-query.gen'
import type { InstrumentConfigDto } from '../generated/universe/types.gen'
import { usePersistentSortable, sorted, SortTh } from '../lib/sort'

const DEFAULTS = {
  ivRankThreshold: 45,
  minDte: 30,
  maxDte: 50,
  preferredDte: 45,
  targetDelta: 0.15,
  deltaMin: 0.10,
  deltaMax: 0.20,
  spreadWidthUsd: 5.0,
  minCreditPerShare: 0.50,
  maxRiskPercent: 0.025,
  takeProfitPercent: 0.50,
  stopLossPercent: 1.00,
  timeProfitDte: 14,
}

function Cell({ value, defaultVal, decimals = 2 }: { value: number | null | undefined; defaultVal: number; decimals?: number }) {
  if (value != null) {
    return <span className="font-medium tabular-nums">{value.toFixed(decimals)}</span>
  }
  return <span className="text-muted-foreground tabular-nums text-xs">{defaultVal.toFixed(decimals)}</span>
}

type EditState = Partial<Omit<InstrumentConfigDto, 'symbol' | 'enabled'>>

function EditModal({
  instrument,
  onClose,
}: {
  instrument: InstrumentConfigDto
  onClose: () => void
}) {
  const qc = useQueryClient()
  const [form, setForm] = useState<EditState>({
    ivRankThreshold: instrument.ivRankThreshold ?? null,
    minDte: instrument.minDte ?? null,
    maxDte: instrument.maxDte ?? null,
    preferredDte: instrument.preferredDte ?? null,
    targetDelta: instrument.targetDelta ?? null,
    deltaMin: instrument.deltaMin ?? null,
    deltaMax: instrument.deltaMax ?? null,
    spreadWidthUsd: instrument.spreadWidthUsd ?? null,
    minCreditPerShare: instrument.minCreditPerShare ?? null,
    maxRiskPercent: instrument.maxRiskPercent ?? null,
    takeProfitPercent: instrument.takeProfitPercent ?? null,
    stopLossPercent: instrument.stopLossPercent ?? null,
    timeProfitDte: instrument.timeProfitDte ?? null,
    notes: instrument.notes ?? null,
  })

  const save = useMutation({
    ...saveInstrumentMutation(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: [{ _id: 'listUniverse' }] })
      onClose()
    },
  })

  function numField(key: keyof EditState, defaultVal: number, decimals = 2) {
    const val = form[key] as number | null | undefined
    return (
      <input
        type="text"
        inputMode="decimal"
        className="w-24 border border-border rounded px-2 py-1 text-sm bg-background tabular-nums"
        placeholder={`default: ${defaultVal.toFixed(decimals)}`}
        value={val ?? ''}
        onChange={(e) => {
          const v = e.target.value === '' ? null : parseFloat(e.target.value)
          setForm((f) => ({ ...f, [key]: v != null && isNaN(v) ? null : v }))
        }}
      />
    )
  }

  function handleSave() {
    save.mutate({
      path: { symbol: instrument.symbol },
      body: { symbol: instrument.symbol, enabled: instrument.enabled, ...form },
    })
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-background border border-border rounded-lg shadow-lg w-full max-w-lg p-6 space-y-4 overflow-y-auto max-h-[90vh]">
        <h2 className="text-lg font-semibold">{instrument.symbol} — Override Parameters</h2>
        <p className="text-xs text-muted-foreground">Leave blank to use global default (shown as placeholder).</p>
        <div className="grid grid-cols-2 gap-3 text-sm">
          {([
            ['ivRankThreshold', 'IV Rank threshold (%)', DEFAULTS.ivRankThreshold, 1],
            ['minDte', 'Min DTE', DEFAULTS.minDte, 0],
            ['maxDte', 'Max DTE', DEFAULTS.maxDte, 0],
            ['preferredDte', 'Preferred DTE', DEFAULTS.preferredDte, 0],
            ['targetDelta', 'Target delta', DEFAULTS.targetDelta, 2],
            ['deltaMin', 'Delta min', DEFAULTS.deltaMin, 2],
            ['deltaMax', 'Delta max', DEFAULTS.deltaMax, 2],
            ['spreadWidthUsd', 'Spread width ($)', DEFAULTS.spreadWidthUsd, 1],
            ['minCreditPerShare', 'Min credit/share ($)', DEFAULTS.minCreditPerShare, 2],
            ['maxRiskPercent', 'Max risk (%)', DEFAULTS.maxRiskPercent, 3],
            ['takeProfitPercent', 'Take profit (%)', DEFAULTS.takeProfitPercent, 2],
            ['stopLossPercent', 'Stop loss (%)', DEFAULTS.stopLossPercent, 2],
            ['timeProfitDte', 'Time profit DTE', DEFAULTS.timeProfitDte, 0],
          ] as const).map(([key, label, def, dec]) => (
            <label key={key} className="flex flex-col gap-1">
              <span className="text-xs text-muted-foreground">{label}</span>
              {numField(key as keyof EditState, def as number, dec as number)}
            </label>
          ))}
          <label className="col-span-2 flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">Notes</span>
            <input
              type="text"
              className="border border-border rounded px-2 py-1 text-sm bg-background"
              value={form.notes ?? ''}
              onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value || null }))}
            />
          </label>
        </div>
        <FlagParamsSection symbol={instrument.symbol} />

        <div className="flex justify-end gap-2 pt-2">
          <button className="px-4 py-1.5 text-sm rounded border border-border hover:bg-accent" onClick={onClose}>
            Cancel
          </button>
          <button
            className="px-4 py-1.5 text-sm rounded bg-primary text-primary-foreground hover:opacity-90 disabled:opacity-50"
            disabled={save.isPending}
            onClick={handleSave}
          >
            {save.isPending ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * This symbol's bull-flag parameters, layered over the global baseline.
 *
 * Saved and reset independently of the spread overrides above: they live in a different store
 * (strategy_symbol_params, not instrument_universe) and share only this dialog. Reset here deletes
 * the row so the symbol inherits the baseline again — the per-symbol twin of the strategy page's
 * Clear saved.
 */
function FlagParamsSection({ symbol }: { symbol: string }) {
  const [meta, setMeta] = useState<StrategyParams | null>(null)
  const [symbolValues, setSymbolValues] = useState<SymbolParams | null>(null)
  const [form, setForm] = useState<Record<string, unknown>>({})
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [open, setOpen] = useState(false)

  useEffect(() => {
    let cancelled = false
    Promise.all([strategyParamsApi.get(FLAG_STRATEGY_ID), strategyParamsApi.getSymbol(FLAG_STRATEGY_ID, symbol)])
      .then(([strategy, sym]) => {
        if (cancelled) return
        setMeta(strategy)
        setSymbolValues(sym)
        // A prefill handed over from the Candle Scanner wins over the stored values — that is the
        // whole point of the link — but it is only filled in, never saved.
        const prefill = paramPrefill.take(symbol)
        setForm(prefill ?? sym.values)
        if (prefill) {
          setDirty(true)
          setOpen(true)
          setNote('Prefilled from the strategy form — press Save to store them for this symbol.')
        }
      })
      .catch((e) => !cancelled && setError(String(e instanceof Error ? e.message : e)))
    return () => {
      cancelled = true
    }
  }, [symbol])

  const overrideCount = Object.keys(symbolValues?.overrides ?? {}).length

  async function save() {
    setBusy(true)
    setError(null)
    try {
      await strategyParamsApi.saveSymbol(FLAG_STRATEGY_ID, symbol, form)
      setSymbolValues(await strategyParamsApi.getSymbol(FLAG_STRATEGY_ID, symbol))
      setDirty(false)
      setNote('Saved and applied to the running scanner.')
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    } finally {
      setBusy(false)
    }
  }

  async function reset() {
    setBusy(true)
    setError(null)
    try {
      await strategyParamsApi.resetSymbol(FLAG_STRATEGY_ID, symbol)
      const refreshed = await strategyParamsApi.getSymbol(FLAG_STRATEGY_ID, symbol)
      setSymbolValues(refreshed)
      setForm(refreshed.values)
      setDirty(false)
      setNote('Custom values removed — this symbol follows the strategy defaults again.')
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="border-t border-border pt-3 space-y-3">
      <button
        onClick={() => setOpen((o) => !o)}
        className="w-full flex items-center justify-between text-left"
      >
        <span className="flex items-center gap-2 text-sm font-medium">
          Bull Flag parameters
          {overrideCount > 0 && (
            <span className="text-[11px] px-1.5 py-0.5 rounded bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300">
              {overrideCount} custom
            </span>
          )}
        </span>
        <span className="text-xs text-muted-foreground">{open ? 'hide ▲' : 'show ▼'}</span>
      </button>

      {open && (
        <>
          {error && <p className="text-sm text-destructive">{error}</p>}
          {!meta && !error && <p className="text-sm text-muted-foreground">Loading…</p>}
          {meta && (
            <>
              <ParamFields
                descriptors={meta.descriptors}
                values={form}
                onChange={(name, value) => {
                  setForm((f) => ({ ...f, [name]: value }))
                  setDirty(true)
                  setNote(null)
                }}
                dirtyAgainst={meta.values}
                disabled={busy}
                columns="grid-cols-2"
              />
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs text-muted-foreground">{note ?? ''}</span>
                <div className="flex gap-2">
                  <button
                    onClick={reset}
                    disabled={busy || overrideCount === 0}
                    title="Delete this symbol's custom values and follow the strategy defaults"
                    className="px-3 py-1 text-xs rounded border border-border hover:bg-accent disabled:opacity-40"
                  >
                    Reset to defaults
                  </button>
                  <button
                    onClick={save}
                    disabled={busy || !dirty}
                    className="px-3 py-1 text-xs rounded bg-primary text-primary-foreground hover:opacity-90 disabled:opacity-50"
                  >
                    {busy ? 'Saving…' : 'Save flag params'}
                  </button>
                </div>
              </div>
            </>
          )}
        </>
      )}
    </div>
  )
}

function InstrumentRow({
  inst,
  autoEdit = false,
  onEditorClosed,
}: {
  inst: InstrumentConfigDto
  autoEdit?: boolean
  onEditorClosed?: () => void
}) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState(autoEdit)
  const [confirmDelete, setConfirmDelete] = useState(false)

  const toggle = useMutation({
    ...toggleInstrumentMutation(),
    onSuccess: () => qc.invalidateQueries({ queryKey: [{ _id: 'listUniverse' }] }),
  })
  const del = useMutation({
    ...deleteInstrumentMutation(),
    onSuccess: () => {
      setConfirmDelete(false)
      qc.invalidateQueries({ queryKey: [{ _id: 'listUniverse' }] })
    },
  })

  return (
    <>
      {editing && (
        <EditModal
          instrument={inst}
          onClose={() => {
            setEditing(false)
            // Drop the deep-link parameter so a refresh does not reopen the dialog.
            onEditorClosed?.()
          }}
        />
      )}
      <tr className="border-b border-border hover:bg-muted/30 transition-colors text-sm">
        <td className="px-3 py-2 font-mono font-medium">{inst.symbol}</td>
        <td className="px-3 py-2">
          <button
            onClick={() => toggle.mutate({ path: { symbol: inst.symbol } })}
            disabled={toggle.isPending}
            className={`w-10 h-5 rounded-full transition-colors ${inst.enabled ? 'bg-green-500' : 'bg-muted'}`}
          >
            <span
              className={`block w-4 h-4 rounded-full bg-white shadow transition-transform mx-0.5 ${inst.enabled ? 'translate-x-5' : 'translate-x-0'}`}
            />
          </button>
        </td>
        <td className="px-3 py-2 tabular-nums">
          <Cell value={inst.ivRankThreshold} defaultVal={DEFAULTS.ivRankThreshold} decimals={1} />
        </td>
        <td className="px-3 py-2 tabular-nums">
          <span className="text-xs">
            <Cell value={inst.minDte} defaultVal={DEFAULTS.minDte} decimals={0} />
            {' – '}
            <Cell value={inst.maxDte} defaultVal={DEFAULTS.maxDte} decimals={0} />
          </span>
        </td>
        <td className="px-3 py-2 tabular-nums">
          <Cell value={inst.targetDelta} defaultVal={DEFAULTS.targetDelta} decimals={2} />
        </td>
        <td className="px-3 py-2 tabular-nums">
          <Cell value={inst.spreadWidthUsd} defaultVal={DEFAULTS.spreadWidthUsd} decimals={1} />
        </td>
        <td className="px-3 py-2 tabular-nums">
          <Cell value={inst.minCreditPerShare} defaultVal={DEFAULTS.minCreditPerShare} decimals={2} />
        </td>
        <td className="px-3 py-2 tabular-nums">
          <span className="text-xs">
            <Cell value={inst.takeProfitPercent} defaultVal={DEFAULTS.takeProfitPercent} decimals={2} />
            {' / '}
            <Cell value={inst.stopLossPercent} defaultVal={DEFAULTS.stopLossPercent} decimals={2} />
          </span>
        </td>
        <td className="px-3 py-2 text-muted-foreground text-xs max-w-32 truncate">{inst.notes ?? ''}</td>
        <td className="px-3 py-2">
          <div className="flex gap-1">
            <button
              onClick={() => setEditing(true)}
              className="px-2 py-1 text-xs rounded border border-border hover:bg-accent"
            >
              Edit
            </button>
            {confirmDelete ? (
              <>
                <button
                  onClick={() => del.mutate({ path: { symbol: inst.symbol } })}
                  disabled={del.isPending}
                  className="px-2 py-1 text-xs rounded bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
                >
                  {del.isPending ? '…' : 'Confirm'}
                </button>
                <button
                  onClick={() => setConfirmDelete(false)}
                  className="px-2 py-1 text-xs rounded border border-border hover:bg-accent"
                >
                  Cancel
                </button>
              </>
            ) : (
              <button
                onClick={() => setConfirmDelete(true)}
                className="px-2 py-1 text-xs rounded border border-border text-destructive hover:bg-destructive/10"
              >
                Delete
              </button>
            )}
          </div>
        </td>
      </tr>
    </>
  )
}

export function UniversePage() {
  const { data, isLoading, error } = useQuery(listUniverseOptions())
  // ?symbol=X opens that row's editor straight away — how the Candle Scanner's Tune link arrives.
  const [searchParams, setSearchParams] = useSearchParams()
  const deepLinkSymbol = searchParams.get('symbol')?.toUpperCase() ?? null
  const qc = useQueryClient()
  const [addSymbol, setAddSymbol] = useState('')
  const { sort, toggle } = usePersistentSortable('universe', 'symbol')
  const save = useMutation({
    ...saveInstrumentMutation(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: [{ _id: 'listUniverse' }] })
      setAddSymbol('')
    },
  })

  function handleAdd() {
    const sym = addSymbol.trim().toUpperCase()
    if (!sym) return
    save.mutate({ path: { symbol: sym }, body: { symbol: sym, enabled: true } })
  }

  const instruments: InstrumentConfigDto[] = (data as InstrumentConfigDto[] | undefined) ?? []
  const sortedInstruments = sorted(instruments, sort, (inst, k) => {
    if (k === 'enabled') return inst.enabled ? 1 : 0
    if (k === 'ivRankThreshold') return inst.ivRankThreshold ?? null
    return (inst as Record<string, unknown>)[k]
  })

  const thClass = 'px-3 py-2 text-left font-medium'

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Instrument Universe</h1>
        <div className="flex gap-2 items-center">
          <input
            type="text"
            className="border border-border rounded px-3 py-1.5 text-sm bg-background uppercase w-28"
            placeholder="Symbol"
            value={addSymbol}
            onChange={(e) => setAddSymbol(e.target.value.toUpperCase())}
            onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
          />
          <button
            onClick={handleAdd}
            disabled={!addSymbol.trim() || save.isPending}
            className="px-3 py-1.5 text-sm rounded bg-primary text-primary-foreground hover:opacity-90 disabled:opacity-50"
          >
            {save.isPending ? 'Adding…' : 'Add Symbol'}
          </button>
        </div>
      </div>

      <p className="text-xs text-muted-foreground">
        Greyed-out values are global defaults. Bold values are per-symbol overrides.
      </p>

      {isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
      {error && <p className="text-destructive text-sm">Error: {String(error)}</p>}

      {instruments.length > 0 && (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30 text-xs text-muted-foreground">
                <SortTh label="Symbol" col="symbol" sort={sort} onSort={toggle} className={thClass} />
                <SortTh label="Enabled" col="enabled" sort={sort} onSort={toggle} className={thClass} />
                <SortTh label="IV Rank %" col="ivRankThreshold" sort={sort} onSort={toggle} className={thClass} />
                <th className={thClass}>DTE (min–max)</th>
                <th className={thClass}>Delta</th>
                <th className={thClass}>Width $</th>
                <th className={thClass}>Min Credit</th>
                <th className={thClass}>TP / SL</th>
                <th className={thClass}>Notes</th>
                <th className={thClass}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {sortedInstruments.map((inst) => (
                <InstrumentRow
                  key={inst.symbol}
                  inst={inst}
                  autoEdit={inst.symbol === deepLinkSymbol}
                  onEditorClosed={() => deepLinkSymbol && setSearchParams({}, { replace: true })}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!isLoading && instruments.length === 0 && (
        <p className="text-muted-foreground text-sm text-center py-8">
          No instruments in universe. Add a symbol to get started.
        </p>
      )}
    </div>
  )
}
