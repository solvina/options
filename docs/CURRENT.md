# Current Work & Open Problems

*Living document — update as things change. Last update: **2026-07-07**.*

## Current task: spread P&L overhaul — deploy tonight, observe tomorrow

2026-07-07 P&L review found the spread strategy structurally losing (−$1.4k realized, −$1.9k
open): entries filled at the natural cross (avg 68% of mid ≈ $84/spread donated), stops keyed to
those depressed fill credits fired on single wide-quote observations (LITE stopped 29 s after
entry) and exited with raw market orders, and the cap-30 experiment opened 21 correlated tech
bull puts into the Jul 6 vol spike. Full reconstruction: `POSTMORTEM_2026-07-06_SPREADS.md`
(including the "What actually changed" section with the new parameters and expectations).

**State right now**: fixes implemented + tested; the spread scanner is **paused via API**
(`POST /options/scanner/pause`, done 2026-07-07 ~17:00) so no new entries fire at the old
settings; the exit monitor still protects the open book (19 bull puts + 2 bear calls).

### Deploy checklist (after US close tonight, 22:00 CEST)
1. Deploy the new engine build (Liquibase v24 adds `entry_mid_per_share` to both spread tables).
2. Run `~/options/backfill_entry_mid_2026-07-07.sql` — sets scan-mid entry values for the open
   Jul 6/7 positions. **Skipping this makes their stops fall back to 2× fill credit (tighter
   than the old 3×) and several would stop out on the first sweep.**
3. Scanner pause clears with the restart; verify `scanner/status` shows maxOpenSpreads=5.
4. Tomorrow: expect fewer fills (FLOOR_REACHED/LIQUIDITY_REJECTED aborts are the system working);
   watch `entryMid=$…` on FILLED lines and `SL breach 1/2` confirmations in the journal.

## Previous task: move to real production

Live engine + live IB Gateway move to a new machine (VPS, not yet provisioned); this RPi keeps
the paper engine on **free delayed market data** (no second data subscription, no relay of
market data). Decided 2026-07-06; delayed-tick support, the prod→paper dividend relay, and the
fatal-lockout safety layer are implemented and deployed.

### Cutover checklist (this box → delayed paper)
1. `ibkr.connection.use-live-market-data: false` (application-rpi.yml)
2. `dividends.remote.enabled: true` + prod Tailscale IP in `jdbc-url` + password env var;
   prod compose must expose Postgres on the tailnet; `dividends.refresh-cron: "0 0 7 * * *"`
3. Verify on first delayed session: EUREX/FTA delayed permission for EU names; delayed daily
   historical bars (IV rank, regime); use the diagnostic probes only after they are made
   delayed-aware (below)
4. Prod machine: `TRADING_MODE=live`, live account code in `ibkr.connection.account`, its own
   Spring profile (do NOT reuse application-rpi.yml with `paper-account: true`)

### Gateway login (changed 2026-07-06)
IBKR now requires direct paper-username login (`svzxsu299`) — live username + paper mode is
rejected ("multiple Paper Trading users"). Credentials must be correct in **both**
`/home/solvina/options/.env` and `/home/solvina/options/.env.rpi` (deploy.sh uses the latter).

## Problems to solve

1. **ExecutionLogPort has no callers** — `execution_log` stays empty; no audit trail for live
   money. Wire it through the order flow. (priority: before live)
2. **Diagnostic tick-stream probe not delayed-aware** — uses tick-by-tick + generic tick 100 and
   hardcodes `error=null`; false-fails on healthy delayed sessions. Fix before cutover
   verification. (before cutover)
3. **Security pass before live** — prod Postgres binds 0.0.0.0 with a committed password; needs
   tailnet-only binding, env-var password, and a read-only DB user for the paper dividend relay.
   (when VPS exists)
4. **Delayed-tick timestamp semantics** — delayed ticks carry no timestamp; engine stamps
   `asOf` at arrival, so quote-age STALE/BLIND monitoring treats 15-min-old prices as fresh, and
   a live session downgraded to delayed would too. Open design decision: gate normalization on
   config vs carry per-request `marketDataType` into snapshots vs `asOf − delay`. (discussion)
5. **EU dividend payers excluded** — `DividendRefreshService` filters to US session, so SAP/SIE/
   ALV have no ex-div data for bear-call protection. One-line change if wanted. (decision)
6. **Flag EOD liquidation uses fixed ET windows** — misses half-day 13:00 closes (Nov 27,
   Dec 24). Calendar infra (liquidHours) now exists to fix it. (before those dates)
7. **max-open-spreads sizing** — RESOLVED 2026-07-07: the intraday canary DID fire (skips every
   minute through the 07-07 session with 21 open spreads), confirming the sweep can't keep up
   past ~15–20. Cap set back to 5 + `max-new-entries-per-day: 4` as part of the P&L overhaul.
   Parallelizing the sweep is only needed if the cap is ever raised again.
8. **Consolidate deploy env files** — `.env` vs `.env.rpi` duplication caused a real outage
   (2026-07-06); deploy.sh should use one file. (cleanup)
9. **Scanner pause doesn't stop an in-flight scan** — `POST /scanner/pause` at ~17:00 on
   2026-07-07 returned 204, yet entries kept filling until 20:05 (PG, BKNG, COHR, INTC, PANW,
   AMD, ADBE). The kill switch gates new scan runs but a sweep already in progress keeps
   launching executions for hours. Check the flag per candidate, not just per scan. (bug)
10. **Stop-loss cooldown failed to block same-day re-entry** — PANW stopped 16:05 CEST
    2026-07-07 (24 h `blockEntry`), re-entered 20:03 CEST the same day. `cooldownUntil` is
    in-memory; either the checking path (scanner `isCoolingDown`) isn't consulted on this route
    or the entry raced the stop. Investigate; consider persisting cooldowns. (bug)

## Open positions to clean up (paper, TWS)

- 4 orphan short stock positions (AAPL −135, GOOGL −96, TSLA −58, META −49) — flag manual closes
  that blind-sold: the close marked the row CLOSED_MANUAL even when the broker calls failed or the
  exit had already filled, so the protective sell (or the manual sell) executed a second time.
  Three date from 2026-07-01; GOOGL/AAPL grew on 2026-07-06 when closes were clicked during the
  gateway outage. **Cancel any resting sell orders on these symbols first**, then flatten. The
  root cause is fixed in code (2026-07-07, see below) — closes now verify broker holdings first.

## Recently completed (context for the above)

- 2026-07-07 (evening): spread P&L overhaul — mid-anchored entry ladder with 85%-of-mid fill
  floor + fresh-tick width re-check; `entry_mid_per_share` persisted (v24) and stop-loss re-keyed
  to it (SL 1.00 = exit at 2× entry mid, TP unchanged at 50% of fill credit); SL gated by 2-cycle
  confirmation + 15-min entry grace + 30-min opening-rotation skip; stop exits via marketable
  limits with retryClose market escalation; 40% credit/width crash-pricing guard on candidates;
  delta target 0.30→0.25; cap 30→5 + 4-fills/day throttle; retryClose PROFIT/STOP relabeling by
  realized sign. See `POSTMORTEM_2026-07-06_SPREADS.md`.
- 2026-07-07: short-stock-orphan fix — flag closes verify broker holdings before selling (sell
  capped at held quantity; zero held → CLOSED_EXTERNAL, nothing sold; unverifiable → close aborts
  with 503 and the position stays OPEN/protected); PENDING closes also cancel the entry order;
  new `FlagRecoveryService` re-arms lost fill watchers after restart/disconnect, re-places
  vanished trailing stops, and adopts/administratively closes positions whose orders changed
  while unwatched; scanner now enforces one PENDING/OPEN flag position per symbol.
- 2026-07-06: delayed-data support (tick normalization, delayed-aware spread streams), dividend
  relay + `dividend_sync_status`, fatal lockout (account mismatch → orders blocked + UI banner),
  loud real-time-bars failures, error 10168 handling, spread-stream permit-leak fix, dollar P&L
  in positions tables, dashboard cap from backend, `engine.env` created (engine-side Telegram
  alerts were silently unconfigured before this).
- 2026-07-03: EU options error-200 root cause fixed (conId + per-strike venue routing);
  startup-recovery false-close fixed.
- 2026-07-02: execution safety overhaul; first automated fill (APH).
