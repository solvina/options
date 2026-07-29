import { coerce, paramGroups, type ParamDescriptor } from './params'

/**
 * Renders one field per descriptor, grouped as the strategy declares.
 *
 * Shared by the Flag Strategy Parameters panel and the Universe per-symbol modal so the two can
 * never drift into showing different fields for the same strategy.
 *
 * [dirtyAgainst] marks a field that differs from the values it is layered over — the saved baseline
 * on the global form, the inherited baseline on the per-symbol one.
 */
export function ParamFields({
  descriptors,
  values,
  onChange,
  dirtyAgainst,
  disabled = false,
  columns = 'grid-cols-2 sm:grid-cols-3',
}: {
  descriptors: ParamDescriptor[]
  values: Record<string, unknown>
  onChange: (name: string, value: unknown) => void
  dirtyAgainst?: Record<string, unknown>
  disabled?: boolean
  columns?: string
}) {
  const differs = (name: string) =>
    dirtyAgainst != null && String(values[name] ?? '') !== String(dirtyAgainst[name] ?? '')

  return (
    <div className="space-y-4">
      {paramGroups(descriptors).map((group) => (
        <div key={group} className="space-y-2">
          <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{group}</div>
          <div className={`grid ${columns} gap-3`}>
            {descriptors
              .filter((d) => d.group === group)
              .map((d) => (
                <label key={d.name} className="text-sm space-y-1" title={d.help ?? undefined}>
                  <span className={`text-xs ${differs(d.name) ? 'text-amber-600 dark:text-amber-400 font-medium' : 'text-muted-foreground'}`}>
                    {d.name}
                    {differs(d.name) && ' •'}
                  </span>
                  {d.type === 'BOOLEAN' ? (
                    <select
                      className="w-full rounded-md border border-border bg-background px-2 py-1.5 text-sm"
                      value={String(values[d.name] ?? d.default)}
                      disabled={disabled}
                      onChange={(e) => onChange(d.name, e.target.value === 'true')}
                    >
                      <option value="true">true</option>
                      <option value="false">false</option>
                    </select>
                  ) : (
                    <input
                      type="number"
                      step={d.type === 'INT' ? 1 : 'any'}
                      min={d.min ?? undefined}
                      max={d.max ?? undefined}
                      disabled={disabled}
                      className="w-full rounded-md border border-border bg-background px-2 py-1.5 text-sm tabular-nums"
                      value={values[d.name] === undefined || values[d.name] === null ? '' : String(values[d.name])}
                      onChange={(e) => onChange(d.name, coerce(d, e.target.value))}
                    />
                  )}
                  {d.help && <span className="block text-[11px] leading-tight text-muted-foreground">{d.help}</span>}
                </label>
              ))}
          </div>
        </div>
      ))}
    </div>
  )
}
