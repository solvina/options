/**
 * Turns a raw JVM thread dump into "what is wrong, in order of severity".
 *
 * Written against the failures we have actually hit, not generic JVM advice. Every rule below
 * corresponds to an incident: the 2026-07-28 scanner wedge (a cron @Scheduled blocked in
 * runBlocking, which ends that schedule for the process lifetime), the option-chain hang, and the
 * IBKR reader thread dying silently.
 */

export type ThreadInfo = {
  threadName: string
  threadId: number
  threadState: string
  lockName?: string | null
  lockOwnerName?: string | null
  stackTrace: { className: string; methodName: string; fileName?: string | null; lineNumber: number }[]
}

export type ThreadDump = { threads: ThreadInfo[] }

export type Severity = 'critical' | 'warning' | 'info'

export type Finding = {
  severity: Severity
  title: string
  detail: string
  /** Why this matters, in plain terms — shown on hover. */
  meaning: string
  threads: string[]
}

const ownFrame = (t: ThreadInfo) => t.stackTrace.find((f) => f.className.startsWith('cz.solvina'))

const frameLabel = (f: { className: string; methodName: string; lineNumber: number }) =>
  `${f.className.split('.').pop()}.${f.methodName}(${f.lineNumber})`

/** A scheduler thread parked inside runBlocking — the signature of the wedge that cost a session. */
function blockedSchedulers(threads: ThreadInfo[]): Finding[] {
  const stuck = threads.filter(
    (t) =>
      /^(scheduling|pool)/.test(t.threadName) &&
      t.stackTrace.some((f) => f.methodName === 'joinBlocking' || f.className.includes('BlockingCoroutine')),
  )
  return stuck.map((t) => {
    const own = ownFrame(t)
    return {
      severity: 'critical' as const,
      title: `Scheduled task blocked in runBlocking: ${own ? frameLabel(own) : t.threadName}`,
      detail: `${t.threadName} is parked waiting for a coroutine that has not completed.`,
      meaning:
        'Spring computes a cron task\'s next fire time only AFTER the current run returns. A scheduled ' +
        'method blocked here is never re-triggered — you lose that task for the whole process lifetime, ' +
        'silently, with no warning in the log. This is exactly how the scanner went quiet for 2h20m on ' +
        '2026-07-28. Fix: wrap the work in runScheduled() so it has a deadline.',
      threads: [t.threadName],
    }
  })
}

/** runBlocking waiting while every coroutine worker is idle = a suspension that will never resume. */
function suspendedForever(threads: ThreadInfo[]): Finding[] {
  const anyBlocking = threads.some((t) => t.stackTrace.some((f) => f.methodName === 'joinBlocking'))
  if (!anyBlocking) return []
  const workers = threads.filter((t) => t.threadName.startsWith('DefaultDispatcher-worker'))
  const busy = workers.filter((t) => !t.stackTrace.some((f) => f.methodName === 'tryPark' || f.methodName === 'park'))
  if (workers.length === 0 || busy.length > 1) return []
  return [
    {
      severity: 'critical',
      title: 'A coroutine is suspended and will never resume',
      detail: `Something is blocked in runBlocking, but ${busy.length} of ${workers.length} coroutine workers are doing any work.`,
      meaning:
        'If work were merely slow, a worker thread would be running it. Every worker being parked while ' +
        'something waits means the coroutine is suspended on a result that is never going to arrive — an ' +
        'await with no timeout, usually on a broker response. It will wait for ever unless something ' +
        'cancels it.',
      threads: busy.map((t) => t.threadName),
    },
  ]
}

/** The IBKR reader thread is the only path for fills, positions and ticks. */
function ibkrReader(threads: ThreadInfo[]): Finding[] {
  const reader = threads.find((t) => t.stackTrace.some((f) => f.className.includes('EJavaSignal') || f.className.includes('IbkrConnection')))
  if (!reader) {
    return [
      {
        severity: 'critical',
        title: 'IBKR reader thread not found',
        detail: 'No thread is sitting in the IBKR message reader.',
        meaning:
          'Every fill, position update and tick from TWS arrives on this one thread. If it is gone, the ' +
          'engine still looks connected but will never learn that an order filled. Restart required.',
        threads: [],
      },
    ]
  }
  const healthy = reader.stackTrace.some((f) => f.methodName === 'waitForSignal' || f.methodName === 'wait' || f.methodName === 'wait0')
  if (healthy) return []
  return [
    {
      severity: 'warning',
      title: 'IBKR reader thread is not waiting for messages',
      detail: `${reader.threadName} is in ${reader.threadState} rather than parked on the socket signal.`,
      meaning:
        'Normally this thread sits in waitForSignal doing nothing until TWS sends something. Finding it ' +
        'elsewhere means it is either busy processing (fine, transient) or stuck inside a callback ' +
        '(serious — it would stall every subsequent message).',
      threads: [reader.threadName],
    },
  ]
}

/** Real lock contention — rare here, but unmistakable when it happens. */
function blockedThreads(threads: ThreadInfo[]): Finding[] {
  const blocked = threads.filter((t) => t.threadState === 'BLOCKED')
  if (blocked.length === 0) return []
  return [
    {
      severity: 'critical',
      title: `${blocked.length} thread(s) BLOCKED on a lock`,
      detail: blocked.map((t) => `${t.threadName} waiting for ${t.lockOwnerName ?? 'unknown owner'}`).join('; '),
      meaning:
        'BLOCKED means the thread is waiting to enter a synchronized block another thread holds. A few ' +
        'for a moment is normal; several at once, or the same one across two dumps a minute apart, means ' +
        'a lock is held too long or a deadlock. Compare two dumps before concluding.',
      threads: blocked.map((t) => t.threadName),
    },
  ]
}

/** Thread-count growth is how leaks announce themselves. */
function threadPressure(threads: ThreadInfo[]): Finding[] {
  if (threads.length < 120) return []
  return [
    {
      severity: 'warning',
      title: `High thread count (${threads.length})`,
      detail: 'The engine normally runs 30-60 threads.',
      meaning:
        'A climbing thread count usually means something creates threads per request and never releases ' +
        'them. On a 4GB Pi each thread also costs stack memory, so this ends in an OOM kill.',
      threads: [],
    },
  ]
}

export function analyseThreadDump(dump: ThreadDump): Finding[] {
  const t = dump.threads ?? []
  const findings = [
    ...blockedSchedulers(t),
    ...suspendedForever(t),
    ...blockedThreads(t),
    ...ibkrReader(t),
    ...threadPressure(t),
  ]
  const rank: Record<Severity, number> = { critical: 0, warning: 1, info: 2 }
  return findings.sort((a, b) => rank[a.severity] - rank[b.severity])
}

/**
 * What each thread is for, in plain terms. Keyed by name prefix — shown on hover so you do not have
 * to remember which pool is which.
 */
export const THREAD_GLOSSARY: { match: RegExp; label: string; meaning: string }[] = [
  {
    match: /^scheduling-/,
    label: 'Spring scheduler',
    meaning:
      'Runs @Scheduled tasks: the 15-minute scanner, the spread monitor, reconciliation, warmups. If one ' +
      'of these is stuck inside runBlocking, that particular schedule is dead until restart.',
  },
  {
    match: /^DefaultDispatcher-worker/,
    label: 'Kotlin coroutine worker',
    meaning:
      'Runs coroutine work. Parked in tryPark means idle and available — that is the normal state. All of ' +
      'them idle while something waits is the classic "suspended for ever" signature.',
  },
  {
    match: /^reactor-http-nio/,
    label: 'HTTP server I/O',
    meaning: 'Serves the REST API and the UI. Parked here means simply no request in flight.',
  },
  {
    match: /^boundedElastic/,
    label: 'Reactor blocking pool',
    meaning: 'Where blocking calls (JDBC, file I/O) are offloaded so they do not stall the HTTP event loop.',
  },
  {
    match: /^HikariPool/,
    label: 'Database connection pool',
    meaning: 'PostgreSQL connections. The housekeeper thread just prunes idle ones; it is always present.',
  },
  {
    match: /^(Reference Handler|Finalizer|Common-Cleaner|Cleaner-|Notification Thread|Signal Dispatcher|DestroyJavaVM)/,
    label: 'JVM internal',
    meaning: 'Part of the JVM itself — garbage collection bookkeeping and signal handling. Always ignore.',
  },
  {
    match: /^Thread-/,
    label: 'IBKR socket reader (usually)',
    meaning:
      'The TWS API reader. Every fill, position and tick arrives here. It should be parked in waitForSignal.',
  },
]

export const STATE_GLOSSARY: Record<string, string> = {
  RUNNABLE:
    'Executing, or ready to. Note the JVM also reports a thread blocked on a network socket as RUNNABLE, ' +
    'so this does not always mean it is burning CPU.',
  WAITING:
    'Waiting indefinitely for another thread — no timeout. This is the dangerous one: if whatever it is ' +
    'waiting for never happens, it waits for ever.',
  TIMED_WAITING:
    'Waiting with a deadline (sleep, poll, await-with-timeout). Normal and self-healing — it will wake up ' +
    'regardless.',
  BLOCKED: 'Waiting to acquire a lock another thread holds. Several at once suggests contention or deadlock.',
  NEW: 'Created but not started yet.',
  TERMINATED: 'Finished.',
}
