# Strategy, Risk & Monitoring Plan — 2026-06-26

Consolidated plan covering (1) parameter/strategy changes, (2) the "no-live-quote"
monitoring + Black-Scholes risk-fallback design, and (3) a newly-surfaced P0
reconciliation bug with an orphaned-positions inventory.

Status legend: ✅ done · ✍️ edited not deployed · ⏸️ parked · 🔴 P0 · 📋 planned

---

## CURRENT STATUS (live, updated 2026-06-26 ~13:20 CEST)

- ✅ **Section 1 — strategy params DEPLOYED & verified live** (delta 0.30 / band 0.25–0.30,
  21 DTE, 200% stop). Committed `f614367`, pushed to master.
- ✅ **Section 3 Increment 1 — orphan-detection + Telegram alerting DEPLOYED & validated**:
  reconciliation detected the 5 orphans and the Telegram alert sent successfully. (One
  concurrency bug found+fixed — ReentrantLock→Mutex; see Follow-up bugs.) Orphans are being
  closed manually (scheduled at market open).
- ⏳ **Section 3 Increment 2 — recovery adopt-if-held**: coded, building/redeploying together
  with the Mutex fix.
- 📋 **Phase 1 (Section 2A) & Phase 2 (Section 2B)** — not started yet.

The original "priority order" below is kept for reference but has been overtaken by the
above. Note: the engine HAS been restarted/deployed multiple times today (the earlier
"do not restart" caution is no longer in force).

---

## 1. Strategy parameter changes  ✅ DEPLOYED & verified live (commit f614367)

All 112 universe instruments have NULL per-instrument overrides → these globals apply
universally. Config is bundled in the jar → requires rebuild + redeploy + restart.

| Param | Old (live) | New | Rationale |
|---|---|---|---|
| `target-delta` | 0.15 | **0.30** | Short put ~30-delta → ~$1.50 credit on $5 width; caps max loss ≈ $3.50 |
| `delta-min` | 0.10 | **0.25** | Accept 0.25–0.30 short-put band |
| `delta-max` | 0.20 | **0.30** | " |
| `take-profit-percent` | 0.50 | **0.50** (unchanged) | Capture fast Θ decay; on $1.50 credit → +$0.75 |
| `stop-loss-percent` | 1.00 | **2.00** | Loss = 200% of credit; on $1.50 credit → buyback $4.50 (−$3.00). Was briefly set 3.00, revised to 2.00. |
| `time-profit-dte` | 14 | **21** | Exit ~3 wks out, before late-cycle Γ spike / whipsaw |

Math profile (per user): ~72–76% win rate, ~4 wins to erase 1 max loss → sustainable.

⚠️ Interaction note: higher delta (more credit, max loss ≈ $3.50 on $5 width) + 200%
stop ($3.00 loss = buyback $4.50) means the stop sits only ~$0.50 below max width — i.e.
the stop gives almost the full spread of room before triggering. Intentional ("breathing
room") but worth monitoring once live.

Deploy flow when greenlit:
```
cd /home/solvina/projects/options/engine && ./gradlew build          # green incl. ktlint
cp /home/solvina/options/engine.jar /home/solvina/options/engine.jar.bak-$(date +%F)
cp build/libs/engine-0.0.1-SNAPSHOT.jar /home/solvina/options/engine.jar
sudo systemctl restart options-engine.service
# verify health + confirm new 0.30 / 21 / 2.00 values in logs
```

---

## 2. "No-Live-Quote" blindspot — monitoring + risk fallback  📋

### The gap (confirmed in code)
`checkSpreadExit` (`SpreadManagementService.kt:356-362`) pulls `getOptionMidLive()` for
both legs; if either is `null` it **skips TP/SL entirely** and only the DTE time-exit can
fire. No timestamp, no escalation, no fallback. A position can sit unmanaged through a fast
move until quotes return or DTE≤21. The underlying stock quote is usually still live.
The widened 200% stop amplifies this (bigger loss can accrue while blind).

### Phase 1 — Heartbeat & Age circuit breaker (ship first, NO automated trading action)
1. **Timestamp the data:** add `asOf: Instant` to `MarketDataSnapshot`; set on every tick
   in `IbkrMarketDataRegistry.onTickPrice/onTickOptionComputation`. Expose age via
   `getOptionMidLive` (e.g. a `QuoteWithAge`).
2. **Per-spread data-health state** (only during that exchange's market hours):
   - `LIVE`  age < 60s → normal logic.
   - `STALE` 60s ≤ age < 300s → log WARN, mark "Safe Mode", ONE Telegram alert.
   - `BLIND` age ≥ 300s → log CRITICAL, Telegram alert repeated every N min until cleared.
3. **Alerting:** reuse the Telegram bot via a new outbound `AlertPort` (faster than
   email/Slack; Slack/email can be added later as another adapter).
4. **Observability:** metrics `spread_quote_age_seconds{symbol}` gauge, `blind_cycles_total`
   counter; surface worst quote-age in `/health`.

Phase 1 alone removes the *silent* failure mode — known within 60s instead of never.

### Phase 2 — Black-Scholes RISK-ONLY fallback (feature-flagged, dry-run first)
When a spread is STALE/BLIND but the **underlying quote is still fresh** (age < 60s):
1. **Synthetic valuation:** BS-price each put leg from live spot, strikes, T = DTE/365,
   configured risk-free rate, IV estimate (seed = last-known streamed IV at entry; fallback
   IV-Rank-derived). `spreadValueBS = soldBS − boughtBS`.
2. **Asymmetric, protective-only:** fire an exit **only on the stop-loss side**
   (`spreadValueBS ≥ slThreshold`). NEVER take-profit, NEVER enter on synthetic data.
   Close via existing **passive chase** (limit, not market — order entry doesn't depend on
   the frozen quote stream). Tag `CLOSED_STOP_SYNTHETIC`, apply cooldown.
3. **Guardrails:** require fresh underlying (else escalate to manual); **hysteresis** — 2
   consecutive blind cycles before firing; clamp IV; require DTE > 0.
4. **Rollout:** ship in **dry-run** (log "would fire synthetic STOP …") for several
   sessions, compare to reality, then flip `synthetic-risk-exit-enabled: true`.

### HARD CONSTRAINT
BS must NOT leak back into entry/pricing (deliberately removed in the zero-fill fix —
see memory `eliminate_bs_plan` / `streaming_completion_fix`). Keep BS strictly: exit-side,
stop-loss-only, flagged, dry-run gated. It's a risk circuit-breaker, not a pricing model.

### Config to add
`quote-stale-seconds: 60`, `quote-blind-seconds: 300`, `synthetic-risk-exit-enabled: false`,
`synthetic-risk-free-rate`, `synthetic-iv-source`, `blind-cycles-before-exit: 2`.

---

## 3. 🔴 P0 — Phantom closes & orphaned, unmanaged positions

### Symptom (observed 2026-06-26 ~11:09 CEST, paper DU7875979)
Engine-tracked OPEN spreads = **0**, but IBKR account holds **6** positions:

| Qty | Type | Position | avgCost | uPnL |
|----:|---|---|---:|---:|
| −1 | OPT EUR | ASML 1340 P (07-17) | 2778.90 | +1,169.58 |
| −147 | STK USD | NOW short stock | 96.03 | +754.26 |
| −140 | STK USD | HOOD short stock | 108.03 | +2,220.46 |
| −18 | OPT USD | AMD 420 P ×18 (07-17) | 1959.54 | +22,299.80 |
| −1 | OPT USD | INTC 80 P (07-17) | 117.95 | +66.95 |
| +1 | OPT USD | INTC 85 P (07-17) | 182.05 | −111.47 |

Total unrealized PnL ≈ **+$26,400** (paper). Engine spread DB statuses:
288 CLOSED_TIMEOUT, 137 CLOSED_REJECTED, 14 CLOSED_MANUAL, 5 CLOSED_STOP, 4 CLOSED_PROFIT.

### Why this is bad
- Exit logic (TP/SL/DTE) runs ONLY on engine-tracked OPEN spreads → **none of these 6 are
  managed.** They sit until expiry/assignment unless manually closed in TWS.
- Several aren't even the strategy: **AMD 18 naked short puts** (no long leg, ~$756k
  notional undefined risk), ASML/INTC naked short puts, **NOW/HOOD short stock**
  (assignment/partial-fill artifacts), and INTC 80/85 is an **inverted** pair (long 85 /
  short 80 = bear put debit, opposite of bull put) — a stranded/mis-paired leg.

### Smoking gun / root-cause hypothesis
Closed spreads carry `closeReason: "no_market_data"` + status `CLOSED_TIMEOUT`. The engine
**marked spreads CLOSED in its own DB when option quotes went stale, without confirming the
broker legs were actually flattened.** Tracking abandoned → broker positions persist and
accumulate. Leg-by-leg stranded-leg failures explain the naked AMD/ASML shorts. This is the
same "no-live-quote blindspot" (Section 2) already biting in production.

### Fix direction (to design)
1. **Never mark CLOSED without broker confirmation.** A timeout/no-data condition must NOT
   set a terminal CLOSED status; introduce a distinct non-terminal state (e.g.
   `CLOSE_UNCONFIRMED` / keep monitoring) until `verifyPositionClosed` confirms flat.
2. **Startup + periodic reconciliation that ADOPTS orphans.** Compare IBKR account positions
   vs engine OPEN spreads; surface/alert on any broker position with no managing spread;
   optionally re-attach (adopt) matchable spreads so they get managed again.
3. **Naked-leg / inverted-pair detector** → CRITICAL alert (these should never exist under
   a defined-risk bull-put strategy).
4. **Cleanup decision for the current 6 orphans** — manual flatten in TWS vs engine-adopt.
   Hold engine restart until decided (restart re-runs reconciliation).

### CONFIRMED root cause (code-level, 2026-06-26)
1. **`StartupRecoveryService.kt:131-139`** — for a PENDING spread whose order is no longer
   in IBKR open-orders, it marks the spread `CLOSED_MANUAL` (`recovery_unknown`) **without
   querying account positions**. If the order actually FILLED while the engine was down, a
   real position is now orphaned. The log literally says *"check positions manually"*. It
   checks `openOrdersAdapter` only, never `PositionsPort.getPositions()`.
2. **Stale SELL orders filling after a close → position reversal** — documented in the same
   file's comment (lines 46-48) as "the AMD bug: +18 LONG closed but -18 SHORT opened". This
   matches the AMD −18 orphan exactly. Recovery cancels stale BUY+SELL orders, but only for
   `CLOSING` spreads, and clearly still leaks.
3. **No full-account orphan sweep.** `PositionReconciliationService.verifyBothLegsFilled`
   only checks *specific* legs at *entry* time. Nothing ever compares the **complete** set of
   IBKR account positions vs engine-tracked OPEN spreads, so orphans from prior runs are
   never re-detected or managed. Restart on 2026-06-26 11:18 re-confirmed: 0 tracked, 6 held.
4. Infra already exists to fix it: `PositionsPort.getPositions()` + `checkPosition()` in
   `PositionReconciliationService`. `StartupRecoveryService` just doesn't use them.

### Fix increments (proposed; DECISION NEEDED before coding)
- **Increment 1 (safe, low-risk):** add an orphan-DETECTION reconciliation pass (startup +
  periodic) → CRITICAL alert listing every IBKR position with no managing OPEN spread. No
  auto-adopt, no auto-close. Surfaces the 6 now and any future ones. Needs the `AlertPort`
  (shared with Phase 1 monitoring).
- **Increment 2:** `StartupRecoveryService` queries `PositionsPort` before closing a
  vanished-order spread; adopt → OPEN only for a clean matching bull-put pair, else alert.
- **DECISION FORK:** alert-only vs auto-adopt (auto-adopt makes the engine start
  managing/closing positions it didn't cleanly create — risky).
- **Current 6 orphans:** malformed for a bull-put engine (short stock NOW/HOOD, naked AMD×18
  / ASML / INTC, inverted INTC 80/85). Recommend **manual flatten in TWS** — engine can't
  safely adopt these. Needs user decision.

---

## Follow-up bugs found while building (2026-06-26)
- **`SpreadMonitorScheduler` (and `FlagMonitorScheduler`) use a thread-bound `ReentrantLock`
  across coroutine suspension** — same bug just hit in `PositionReconciliationScheduler`
  (IllegalMonitorStateException on unlock from a different dispatcher thread, which then
  leaves the lock permanently held → job silently stops). Reconciliation was fixed to use a
  coroutine `Mutex`. **Audit/fix the other schedulers the same way** — if it ever trips,
  exit monitoring (TP/SL/DTE) would silently die. Has not obviously tripped in prod, but the
  pattern is unsafe. P1.

## Cross-references (memory)
`streaming_completion_fix` (zero-fill root cause), `eliminate_bs_plan` (BS removed from
trading path), `no_fill_diagnosis_2026-06-16`, `leg_by_leg_wip_plan` (stranded-leg refactor),
`deploy_rpi_validation_policy`.
