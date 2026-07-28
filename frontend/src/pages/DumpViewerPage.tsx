import { useCallback, useEffect, useState } from 'react'
import {
  analyseThreadDump,
  STATE_GLOSSARY,
  THREAD_GLOSSARY,
  type Finding,
  type ThreadDump,
  type ThreadInfo,
} from '../lib/dumpAnalysis'

/** Hover explanation. Plain title attribute — no dependency, works everywhere, copy-pasteable. */
function Hint({ text, children }: { text: string; children: React.ReactNode }) {
  return (
    <span title={text} className="underline decoration-dotted decoration-muted-foreground/60 underline-offset-2 cursor-help">
      {children}
    </span>
  )
}

const SEVERITY_STYLE = {
  critical: 'border-red-500/50 bg-red-500/10',
  warning: 'border-amber-500/50 bg-amber-500/10',
  info: 'border-blue-500/40 bg-blue-500/10',
} as const

const STATE_STYLE: Record<string, string> = {
  RUNNABLE: 'bg-emerald-500/15 text-emerald-600',
  WAITING: 'bg-amber-500/15 text-amber-600',
  TIMED_WAITING: 'bg-muted text-muted-foreground',
  BLOCKED: 'bg-red-500/15 text-red-600',
}

function glossaryFor(name: string) {
  return THREAD_GLOSSARY.find((g) => g.match.test(name))
}

/** Our own frames are the only ones that say anything about *our* bug. */
function ownFrames(t: ThreadInfo) {
  return t.stackTrace.filter((f) => f.className.startsWith('cz.solvina'))
}

function FindingCard({ f }: { f: Finding }) {
  return (
    <div className={`rounded border px-3 py-2 ${SEVERITY_STYLE[f.severity]}`}>
      <div className="flex items-baseline gap-2">
        <span className="text-xs uppercase font-semibold tracking-wide">{f.severity}</span>
        <Hint text={f.meaning}>
          <span className="font-medium">{f.title}</span>
        </Hint>
      </div>
      <div className="text-sm text-muted-foreground mt-1">{f.detail}</div>
      <div className="text-xs mt-2 leading-relaxed">{f.meaning}</div>
      {f.threads.length > 0 && (
        <div className="text-xs text-muted-foreground mt-2 font-mono">{f.threads.join(', ')}</div>
      )}
    </div>
  )
}

function ThreadRow({ t }: { t: ThreadInfo }) {
  const [open, setOpen] = useState(false)
  const g = glossaryFor(t.threadName)
  const mine = ownFrames(t)
  const top = mine[0] ?? t.stackTrace[0]
  return (
    <>
      <tr className="border-b last:border-0 hover:bg-muted/40 cursor-pointer" onClick={() => setOpen(!open)}>
        <td className="py-1.5 font-mono text-xs">
          {g ? <Hint text={g.meaning}>{t.threadName}</Hint> : t.threadName}
        </td>
        <td className="text-xs text-muted-foreground">{g?.label ?? '—'}</td>
        <td>
          <Hint text={STATE_GLOSSARY[t.threadState] ?? t.threadState}>
            <span className={`px-2 py-0.5 rounded text-xs ${STATE_STYLE[t.threadState] ?? 'bg-muted'}`}>
              {t.threadState}
            </span>
          </Hint>
        </td>
        <td className="font-mono text-xs text-muted-foreground truncate max-w-md">
          {top ? `${top.className.split('.').pop()}.${top.methodName}(${top.lineNumber})` : '—'}
        </td>
        <td className="text-xs text-muted-foreground">{mine.length > 0 ? `${mine.length} own` : ''}</td>
      </tr>
      {open && (
        <tr className="bg-muted/30">
          <td colSpan={5} className="px-3 py-2">
            <pre className="text-[11px] leading-relaxed overflow-x-auto font-mono">
              {t.stackTrace.length === 0
                ? '(no stack — thread is native or just started)'
                : t.stackTrace
                    .map((f) => {
                      const own = f.className.startsWith('cz.solvina')
                      return `${own ? '>> ' : '   '}${f.className}.${f.methodName}(${f.fileName ?? '?'}:${f.lineNumber})`
                    })
                    .join('\n')}
            </pre>
            {t.lockName && (
              <div className="text-xs text-muted-foreground mt-1">
                waiting on <span className="font-mono">{t.lockName}</span>
                {t.lockOwnerName ? ` held by ${t.lockOwnerName}` : ' (no owner — a timed park, not contention)'}
              </div>
            )}
          </td>
        </tr>
      )}
    </>
  )
}

export function DumpViewerPage() {
  const [dump, setDump] = useState<ThreadDump | null>(null)
  const [coroutines, setCoroutines] = useState<string>('')
  const [findings, setFindings] = useState<Finding[]>([])
  const [takenAt, setTakenAt] = useState<string>('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [onlyInteresting, setOnlyInteresting] = useState(true)

  const capture = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [td, cd] = await Promise.all([
        fetch('/api/actuator/threaddump').then((r) => r.json()),
        fetch('/api/actuator/coroutinedump').then((r) => r.text()),
      ])
      setDump(td)
      setCoroutines(cd)
      setFindings(analyseThreadDump(td))
      setTakenAt(new Date().toLocaleTimeString())
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    capture()
  }, [capture])

  const threads = dump?.threads ?? []
  const byState = threads.reduce<Record<string, number>>((acc, t) => {
    acc[t.threadState] = (acc[t.threadState] ?? 0) + 1
    return acc
  }, {})

  const visible = threads
    .filter((t) => (onlyInteresting ? ownFrames(t).length > 0 || t.threadState === 'BLOCKED' : true))
    .filter((t) => (filter ? t.threadName.toLowerCase().includes(filter.toLowerCase()) : true))
    .sort((a, b) => ownFrames(b).length - ownFrames(a).length || a.threadName.localeCompare(b.threadName))

  // The coroutine dump prints a header even when it reports nothing, so "has content" means more
  // than that header. An empty body is itself worth flagging — see the note rendered below.
  const coroutineBody = coroutines.split('---- Coroutine dump ----')[1]?.split('---- Thread dump ----')[0]?.trim() ?? ''
  const coroutineEmpty = coroutineBody.split('\n').filter((l) => l.trim() && !l.startsWith('Coroutines dump')).length === 0

  return (
    <div className="p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">Hang Diagnostics</h1>
          <p className="text-sm text-muted-foreground">
            Thread and coroutine dumps, with the failure patterns we have actually hit called out. Hover any
            dotted item for an explanation; click a row for its full stack.
          </p>
        </div>
        <button
          className="px-3 py-1.5 rounded bg-primary text-primary-foreground text-sm disabled:opacity-50"
          onClick={capture}
          disabled={loading}
        >
          {loading ? 'Capturing…' : 'Capture now'}
        </button>
      </div>

      {error && <div className="rounded border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm">{error}</div>}

      {takenAt && (
        <div className="flex flex-wrap gap-2 items-center text-sm">
          <span className="text-muted-foreground">Captured {takenAt} —</span>
          <span className="font-medium">{threads.length} threads</span>
          {Object.entries(byState).map(([s, n]) => (
            <Hint key={s} text={STATE_GLOSSARY[s] ?? s}>
              <span className={`px-2 py-0.5 rounded text-xs ${STATE_STYLE[s] ?? 'bg-muted'}`}>
                {n} {s}
              </span>
            </Hint>
          ))}
        </div>
      )}

      <section className="space-y-2">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
          What looks wrong
        </h2>
        {findings.length === 0 ? (
          <div className="rounded border border-dashed px-4 py-6 text-center text-sm text-muted-foreground">
            No known failure pattern detected. That is not a clean bill of health — it only means none of the
            specific problems this page knows about are present.
          </div>
        ) : (
          findings.map((f, i) => <FindingCard key={i} f={f} />)
        )}
      </section>

      <section className="space-y-2">
        <div className="flex items-center gap-3">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">Threads</h2>
          <input
            className="border rounded px-2 py-1 text-sm"
            placeholder="filter by name…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <label className="text-sm flex items-center gap-1.5 text-muted-foreground">
            <input type="checkbox" checked={onlyInteresting} onChange={(e) => setOnlyInteresting(e.target.checked)} />
            <Hint text="Hides JVM and framework threads that have none of our code on their stack. Those are almost never the problem.">
              only threads running our code
            </Hint>
          </label>
        </div>
        <table className="w-full text-sm">
          <thead className="text-left text-muted-foreground border-b">
            <tr>
              <th className="py-2">Thread</th>
              <th>Role</th>
              <th>State</th>
              <th>Top frame</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {visible.map((t) => (
              <ThreadRow key={`${t.threadName}-${t.threadId}`} t={t} />
            ))}
          </tbody>
        </table>
        {visible.length === 0 && (
          <div className="text-sm text-muted-foreground py-4 text-center">
            No threads match. Untick the filter to see framework threads.
          </div>
        )}
      </section>

      <section className="space-y-2">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">Coroutines</h2>
        {coroutineEmpty ? (
          <div className="rounded border border-amber-500/50 bg-amber-500/10 px-3 py-2 text-sm">
            <strong>The coroutine dump reported nothing — do not read this as healthy.</strong> As of
            2026-07-28 it comes back empty even when a coroutine is provably suspended, so it currently
            proves nothing either way. Trust the thread dump above until this is fixed.
          </div>
        ) : (
          <pre className="text-[11px] leading-relaxed overflow-x-auto font-mono border rounded p-3 max-h-96">
            {coroutineBody}
          </pre>
        )}
      </section>
    </div>
  )
}
