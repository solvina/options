# IBKR Admission Control (plan v3 — implemented)

One choke point that makes it impossible to breach either IBKR ceiling — ~100 concurrent
market-data **lines** and ~50 outbound **messages/sec** — while guaranteeing that flags, execution
and the exit monitor are never starved by the entry scanner. Payoff: the scanner can run
bounded-parallel (~18 min → target ~3–4 min).

## What changed vs. plan v2 (after reading the code)

1. **Phase 0 shrank dramatically.** A `GuardedEClientSocket` (extends `EClientSocket`) is already
   the one `EClientSocket` bean every adapter injects (`IbkrBeanConfig`). No `PacedClient`
   injection swap across adapters — the message-rate gate is added by overriding the outbound
   methods in the existing guard. Every outbound method in the TWS API is `synchronized`, so the
   token must be taken *before* delegating to `super` (never while holding the client monitor).
2. **Priority travels as a `CoroutineContext` element**, not as new parameters on every domain
   port. `MarketDataPriority` (FLAG / EXEC / EXIT / SCANNER) lives in the domain; services tag
   their scope once (`withContext(MarketDataPriority.SCANNER) { … }`) and adapters read it from
   `coroutineContext`, defaulting to EXEC so an untagged caller is never accidentally starved.
3. **Observability is an explicit workstream** (was the gap in v2): the admission controller is
   the single door, so it records per-class wait/held/timeout stats, exposes them on
   `/actuator/health` (component `ibkrAdmission`), and raises Telegram alerts when a
   high-priority class is starved — our own version of TWS's pacing warning, firing *before*
   IBKR ever complains.
4. The message-rate floor for the scanner is enforced **upstream** (scanner paths wait for bucket
   headroom before issuing a request), keeping the socket-level bucket priority-blind and simple.

## The two resources

| Resource | Kind | Mechanism |
|---|---|---|
| Market-data lines (~100/account) | slot held for a subscription's lifetime | partitioned budget with per-class reserves |
| Message rate (~50/s, counts everything) | flow over time | token bucket in `GuardedEClientSocket` |

## Components

### `IbkrAdmissionController` (evolved from `IbkrRateLimiter`)
Keeps historical-data pacing + contract-details serialisation unchanged. Adds:

**(a) Message token bucket** — refill `messages-per-second` (45, headroom under 50), capacity =
one second's refill. `paceMessage()` is a *blocking* take (called from the synchronous socket
methods); waits are ~22 ms at saturation. Scanner paths additionally call
`awaitScannerHeadroom()` (suspends until bucket level > `scanner-token-floor`) before issuing a
request, so a scan burst always leaves headroom for exits/orders.

**(b) Partitioned line budget** — total `market-data-lines` (90). Classes: EXIT / FLAG / EXEC
(reserved) and SCANNER (leftover only). Class *p* may take a line when
`available − 1 ≥ Σ unmet reserves of the other high classes`; SCANNER additionally must stay
under `scanner-line-concurrency` and its takes must leave *every* unmet high reserve intact.
Reserved-headroom check under one mutex — simple and race-free; waiters are woken
high-priority-first on every release.

### Priority classes

| Class | What | Line reserve |
|---|---|---|
| EXIT | exit monitor: open-position quotes/greeks, TP/SL/DTE | 40 (`maxOpenSpreads` × 2) |
| FLAG | constant 5-sec real-time bars | 15 (flag universe) |
| EXEC | entry/reprice/close quote streams during execution | 8 |
| SCANNER | entry-scan greeks snapshots, diagnostics, warmup, dividends | 0 — leftover, ≤ 5 concurrent |

Tagging: `ScannerService`, `UniverseWarmupService`, `DiagnosticService`, dividend refresh →
SCANNER; `SpreadManagementService` monitor/refresh → EXIT; real-time bars adapter → FLAG
(hardcoded); everything untagged → EXEC.

### Single door for lines (bypasses closed)
- `reqMktDataSnapshot(…)` acquires/releases a line itself (covers scanner greeks, one-shot
  prices, diagnostics). SCANNER acquires with a timeout and *skips the strike* on expiry —
  a choke signal, not a hang.
- `IbkrDividendTickAdapter` wrapped the same way.
- Streams (`IbkrMarketTickAdapter`, `IbkrRealTimeBarsAdapter`) already acquired lines; they now
  pass their class.

### Observability (the "our own TWS warning" layer)
- **Stats in the controller** (per class): acquires, current held, total/max wait, timeouts;
  bucket level, tokens taken, total/max token wait; count of broker limit errors
  (100 msg-rate, 101 line-cap, 162/420 historical pacing — must stay 0 once this ships).
- **`/actuator/health` component `ibkrAdmission`** exposes the full snapshot (lines per class,
  bucket level, waits) — the live "how close to the ceiling" view.
- **Starvation alerts**: a FLAG/EXEC/EXIT line-wait ≥ `starvation-alert-ms`, or any message
  token wait that long, sends a Telegram WARNING (per-class cooldown). Scanner waits are *by
  design* and only counted, never alerted.
- IBKR error 100/101 additionally alerts CRITICAL — the invariant failed.

## Config (`ibkr.admission.*`, all tunable without rebuild)
```yaml
ibkr:
  admission:
    market-data-lines: 90            # verify against the account's real allowance
    exit-reserve: 40
    flag-reserve: 15
    exec-reserve: 8
    scanner-line-concurrency: 5
    scanner-line-timeout-ms: 30000   # scanner skips the strike when no line frees in time
    messages-per-second: 45
    scanner-token-floor: 10
    greeks-snapshot-timeout-ms: 5000 # was hardcoded in reqMktDataSnapshot
    starvation-alert-ms: 2000
    starvation-alert-cooldown-ms: 900000
    # migrated unchanged from ibkr.rate-limit.*:
    historical-max-per10-min: 55
    historical-max-in-flight: 5
    historical-min-spacing-ms: 200
    pacing-backoff-ms: 15000
    contract-details-max-in-flight: 1
```

## Phases (each independently shippable; deploy separately for attributability)
- **Phase 0** — message-rate gate: token bucket + `GuardedEClientSocket` overrides for every
  outbound call the app makes; broker limit-error counting. The 50/s ceiling becomes untrippable.
- **Phase 1** — line invariant completed: `reqMktDataSnapshot` + dividend ticks acquire lines.
- **Phase 2** — priorities, reserves, scanner floor/sub-cap, stats, health endpoint, starvation
  alerts, snapshot-timeout config.
- **Phase 3** — scanner greeks parallelized (`mapNotNull` → bounded `async`), safe by
  construction under the SCANNER gate. This is where the scan-time win lands.

## Validation
- Unit: bucket never exceeds rate; budget never exceeds N; SCANNER blocked below reserves/floor
  and sub-cap; high classes never wait while their reserve has room; waiter wakeup order;
  scanner timeout counted; stats correct.
- `./gradlew build` per phase; RPi deploy per phase. Watch health `ibkrAdmission`, scan
  duration, and **zero** IBKR 100/101/162 errors; confirm flags/exits/entries uninterrupted.
- Rollback = config (`scanner-line-concurrency: 1`, `scanner-token-floor: 45`) or jar backup.

## Open items
- Confirm the account's real line allowance before trusting 90 (base 100 varies with
  subscriptions/boosters).
- Reserve sizing is judgement; tune on paper via the health snapshot (held-per-class highs).
