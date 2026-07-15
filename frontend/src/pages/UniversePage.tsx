import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
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

function InstrumentRow({ inst }: { inst: InstrumentConfigDto }) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState(false)
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
      {editing && <EditModal instrument={inst} onClose={() => setEditing(false)} />}
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
                <InstrumentRow key={inst.symbol} inst={inst} />
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
