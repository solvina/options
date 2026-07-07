# Current Work & Open Problems

*Living document — update as things change. Last update: **2026-07-06**.*

## Current task: move to real production

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
7. **max-open-spreads sizing** — currently 500 (experiment; 21 spreads opened 2026-07-06).
   Resource ceiling with the sequential 60s exit monitor is ~15–20 open spreads; the canary is
   the "Spread monitor skipped: previous run still in progress" log. Dial down or parallelize
   the monitor sweep. (decision)
8. **Consolidate deploy env files** — `.env` vs `.env.rpi` duplication caused a real outage
   (2026-07-06); deploy.sh should use one file. (cleanup)

## Open positions to clean up (paper, TWS)

- 4 orphan short stock positions (AAPL −135, GOOGL −96, TSLA −58, META −49) — flag manual closes
  that blind-sold: the close marked the row CLOSED_MANUAL even when the broker calls failed or the
  exit had already filled, so the protective sell (or the manual sell) executed a second time.
  Three date from 2026-07-01; GOOGL/AAPL grew on 2026-07-06 when closes were clicked during the
  gateway outage. **Cancel any resting sell orders on these symbols first**, then flatten. The
  root cause is fixed in code (2026-07-07, see below) — closes now verify broker holdings first.

## Recently completed (context for the above)

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
