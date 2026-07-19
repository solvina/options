# Changelog

Notable strategy, engine and operations changes. Newest first. Rendered in the frontend under
**Maintenance › Changelog**.

## 2026-07-19 — Sweeps in the web UI as terminable engine jobs

The parameter sweeper is now a first-class engine feature (Backtest › Sweeps) instead of a
side-car Python script. Full loop: Stock Backtest → "New sweep from these params" → adjust
ranges → run as a job → open results → "use" any row to load its params back into the
backtest form.

- **`SweepService`** (domain): the param-sweep.py semantics ported in-process — Decimal-exact
  range expansion, ATR-override pruning, arithmetic variant counting, lazy combo streaming
  through a bounded worker pool, `results.csv`/`failures.csv`/`config.json` in the same format
  and directory as the script (CLI and UI runs list and RESUME interchangeably). Combos run
  against `BacktestEngine` directly — no HTTP per combo, coverage checked once per sweep —
  measuring **~1,700 combos/s** on the workstation (the 29k-combo smoke grid took 17s).
  Terminate = coroutine cancel: rows stay, same name resumes. Unit tests pin the count math
  (24→12, 290,400→330).
- **API** `/backtest/sweeps`: preview (live variant count for the form), create/start, list,
  detail, DELETE (terminate; `?purge=true` deletes the run dir), results/config passthrough
  for the viewer. Sweep names are validated (they become directories).
- **UI**: Sweeps job list (status, progress bar, ETA countdown, terminate/purge/results),
  New Sweep form (per-param fixed/range/list with live "N variants (M pruned)" preview),
  and the results viewer ported from `sweep-viewer.html` into the app (same heatmap, robust
  3×3-neighborhood winners, slicing sliders) with a **"use in backtest"** button on every row.
- The standalone `scripts/sweep-viewer.html` and `scripts/param-sweep.py` remain for
  offline/headless use; sweeps are disabled on the RPi profile (`sweep.enabled=false`) so a
  mis-click can't starve the trading engine.
- Startup scans the sweep output dir, so pre-existing runs (including python ones) appear in
  the job list as STOPPED with openable results.

## 2026-07-19 — Sweep hot path + Historical Data page rework

Follow-up to the workstation setup: with 5,000+ requests per sweep, the per-request fixed costs
dominated — and InfluxDB was being hammered with identical queries.

### Sweep request hot path (backtest, performance)
- **Bar read cache** in `InfluxDbBarStoreAdapter`: exact-key LRU over (symbol, range, timeframe),
  capped at 10 entries / 1.5M bars total, 10-min TTL (external EDR writes can land in a cached
  range), evicted immediately on local writes. A sweep now reads each series from InfluxDB once
  instead of once per combo.
- **Coverage memo** in `HistoricalDataService`: `ensureCoverage` re-verified coverage with a
  full-range `coverageByDay` query per symbol *and* per benchmark ETF on every request — after
  bar reads were cached, this was the remaining InfluxDB load. Verified spans are now remembered
  for 10 min, **including spans with a gap that yielded nothing** (backtest profile's no-op
  fetcher, delisted tails) — without that, one stale benchmark ETF (XLK ends 2024-12-31 locally)
  kept the re-verification firing on every single combo. A fetch that actually writes bars is
  never memoized, so real downloads still re-verify.
- **Adaptive job poll** in the stock backtest controller: the fixed 2s poll tick was the floor on
  warm requests; the first 2s now poll at 100ms.
- Net effect: warm sweep request 2.6s → **0.13s**, InfluxDB CPU per request ~0.16s → **~0.02s**.
  Remaining influxd load in htop during a sweep is the EDR ingest stream from the RPi download
  plus compactions, not the sweep.
- Fetch-job records are now pruned (oldest finished beyond 2,000) — a long sweep used to grow the
  in-memory job map without bound.

### ensureCoverage stops asking IBKR for weekends (data)
- A head/tail gap consisting only of Saturday/Sunday can never be filled, but each rerun asked
  IBKR for it anyway — one silent 45s chunk timeout per covered symbol (≈3h wasted per universe
  re-pass on the RPi). Weekend-only gaps are now skipped.

### Historical Data page rework (frontend)
- New **`GET /historical/summary`**: per (symbol, interval) series in InfluxDB — bar count, first
  and last bar. Backed by three grouped Flux aggregates over the whole bucket.
- The page now leads with a **Data in DB** table (per-symbol pivot over 5min/4h/1d with counts and
  first→last dates, filter box, per-interval totals, 60s refresh) — it shows what the instance you
  are on can actually serve, which on the workstation is the replica, not IBKR.
- The coverage grid gained a timeframe selector (it was hardcoded to 5-min expectations; a full
  day is 78 five-min bars, 2 four-hour bars, 1 daily bar).
- The **Fetch historical bars** form is collapsed into a details panel and gained timeframe +
  "only missing (ensure)" controls. On the backtest profile fetching is a deliberate no-op —
  data arrives via EDR replication from the RPi.
- Known data holes surfaced by the new table: XLK/XLI 1d end 2024-12-31 locally, and
  XLB/XLU/XLY/XLP/XLRE/XLC have no 1d series — sector-ETF benchmarks for those sectors run on
  stale/missing data until they're fetched on the RPi (they're not in the universe, so the bulk
  download skips them).

### Sweep script
- ETA/elapsed now print as `H:MM:SS` (they printed raw float seconds).

### Fixes after the first big run (290k combos, AAPL 1d)
- **Sweep viewer froze on the 39 MB results.csv**: `distinct()` rescanned all rows on every call
  and was itself called from per-row filter loops — O(n²·cols), invisible at 3k rows, ~10¹² ops at
  290k. Distinct values are now memoized per column; the full file loads in ~2s.
- Sweeping percent exits AND ATR-multiple exits in one grid multiplies in dead combos: whenever
  both ATR multiples are > 0, every stopLossPct × targetPct cell is byte-identical (ATR overrides
  percent). Sweep them in separate configs, or include 0 in the ATR lists and pin the percent
  params when ATR is active.
- **InfluxDB client read timeout 10s → 120s**: `/historical/summary`'s full-bucket scans time out
  on the RPi's SD-card storage (fine on the workstation's NVMe).
- **`SpreadMonitorSchedulerConcurrentTest` failed every weekend**: the mutex-release test pinned
  exchange hours to 00:00–23:59 but not the day — the scheduler's weekday gate short-circuits on
  Sat/Sun, so `checkExits` was never invoked. The scheduler now takes an overridable `Clock`
  (production default unchanged) and the test pins a Wednesday.

## 2026-07-19 — Universe-wide history download + dedicated backtest workstation

Two infrastructure steps toward serious backtesting: download **all** available history for the
whole universe into the RPi's InfluxDB, and split backtesting off onto a more powerful machine
that mirrors that data.

### Bulk historical download for the whole universe (data)
- `/historical/fetch` extended with `timeframe` (`5min` | `4h` | `1d`) and `ensure: true` —
  ensure mode routes to `ensureCoverage`, fetching only the missing head/tail of the range, so
  jobs are idempotent and resumable. `/historical/coverage` takes `timeframe` too; fetch jobs
  report theirs.
- New `scripts/download-universe-history.sh`: pulls the universe (188 symbols), submits one fetch
  job per symbol per timeframe sequentially and polls to completion. Defaults: 1d from 1999,
  4h from 2004 (IBKR intraday history rarely reaches further back). Rerun-safe — covered ranges
  are skipped.
- **Operational finding:** during IBKR's Saturday reset window the historical farm (HMDS) accepts
  connections but answers nothing — every request dies at the adapter's 45s chunk timeout with no
  error code, even after a gateway restart, while contract details keep working. Signature in the
  log: `Historical chunk timed out after 45000ms — skipping` with 0 bars written. A probe loop on
  the RPi (`scripts/probe-and-relaunch.sh`) retries every 15 min and relaunches the download
  automatically once bars flow again.

### Sector-ETF / market benchmarks in backtest results (backtest)
- Backtest summaries now include buy&hold of each symbol's **sector ETF** (GICS sector from the
  universe row → SPDR ETF via `SectorEtf`, e.g. Information Technology → XLK) plus **SPY** as the
  broad market, over the same window and timeframe (first open → last close, same convention as
  the symbols' own buy&hold). Rendered as a "vs Sector / Market ETF" stat row in the UI.
- Benchmark ETF history is fetched on demand together with the backtest symbols (RPi); the
  backtest profile computes from stored bars only and skips a benchmark whose series isn't
  synced yet (logged). Unit-tested (computation + skip path).

### ATR-scaled stops/targets in the rule strategy (backtest)
- `RuleBacktestStrategy` gains `atrPeriod` (default 14), `stopAtrMultiple` and `targetAtrMultiple`:
  when a multiple is > 0 the exit is entry ∓ ATR × multiple (Wilder ATR over bars of the backtest
  timeframe), replacing the fixed-percent stop/target — the same multiple gives wider stops in
  volatile regimes and tighter ones in calm markets, so one setting generalizes across regimes
  instead of being tuned to one volatility level. Signals before the ATR window fills are skipped
  (never silently mixed with percent exits). Exposed in the API, the UI form, and therefore
  sweepable via param-sweep configs. Sanity check (AAPL 1d, loose entries): 3%/6% percent exits
  → 22 trades, +0.2%, 7.8% max DD; ATR 2×/4× → 13 trades, +1.7%, 3.8% max DD.

### Dedicated backtest instance on the workstation (backtest)
- New Spring profile **`backtest`** (`application-backtest.yml`): port 8082, IBKR fully off
  (no connect attempts, no trading/scanners/alerts), boots in ~8s. A `@Profile("backtest")`
  no-op `EquityHistoricalBarsPort` replaces the IBKR fetch chain, so a backtest over a local data
  gap returns instantly with a warning instead of stalling 45s per chunk.
- Local InfluxDB is a **replica of the RPi master**, fed two ways:
  - **InfluxDB Edge Data Replication** (`scripts/setup-influx-replication.sh`): the RPi pushes
    every `market_data` write here, with a 2 GiB durable queue riding out laptop sleep. Rerun the
    script when the workstation's LAN IP changes.
  - `scripts/sync-influx-from-rpi.py`: full/incremental pull over an ssh tunnel for the initial
    backfill and for catch-up after long offline stretches (EDR only carries writes made while
    the replication exists).
- New `scripts/param-sweep.py` (JSON-config driven, see `scripts/sweep-example.json`): any
  request field can be fixed or swept (range spec or explicit value list, cartesian product over
  all swept params — e.g. stopLossPct 2–10 × targetPct 3–10 by 0.1 = 5,751 combos). Runs N
  backtests in parallel and writes a per-run output directory: `results.csv` (one row per combo:
  swept params + summary metrics), `failures.csv`, and a `config.json` copy for provenance.
  Re-running the same config RESUMES — combos already in results.csv are skipped. 18-combo smoke
  test: 6s at 6 parallel.
- Fixed for any fresh database: `execution_log` and `engine_trade_entries` existed only
  out-of-band on the original deployment (their changesets never made the master changelog), so
  Hibernate schema validation failed on every new environment. `v32__fresh_db_missing_tables.yaml`
  creates them where missing and MARK_RANs where they already exist.
- Gotcha worth remembering: under a comma-decimal locale (`cs_CZ`) awk parses a `0.1` step as 0 —
  the sweep's range expansion looped forever until forced to `LC_ALL=C`.
- `scripts/sweep-viewer.html`: standalone results explorer (open in a browser, drop in a
  `results.csv` + `config.json`, or serve the dir and use `?csv=…&config=…`). Fixed params as
  chips, X/Y axis pickers with sliders for the remaining swept dims, metric-colored heatmap with
  hover details, "relative to buy & hold" toggle, and two winner sets: top-10 absolute (solid
  ring / blue rows) and top-10 **robust** — best 3×3 neighborhood average, i.e. plateaus rather
  than lucky spikes (dashed gold ring) — plus a sortable full table.

## 2026-07-18 — Stock backtest actually works (three stacked bugs)

The new Stock Backtest UI failed on first use. Review found three independent bugs, any one of
which made the feature unusable:

- **Routing (the visible crash):** all four backtest controllers mapped `/api/backtest…`, but the
  app serves under base-path `/options` and both proxies (nginx, Vite dev) rewrite `/api/X` →
  `/options/X` — every backtest endpoint (stock run, flag run, spread run, presets) 404'd from the
  UI. Controllers now map without the `/api` prefix. This also un-breaks the older flag Backtest
  page.
- **Daily bars never filled an entry:** the engine expired pending entries at the end of every
  trading day, but a signal from a daily bar can only fill on the *next* day's bar — daily
  backtests structurally produced zero trades. With `holdOvernight`, an entry now stays working
  through the next session, then expires (good-for-next-session). Unit-tested.
- **Daily history landed in 1970:** IBKR returns daily bars as `yyyyMMdd` date strings; the parser
  read them as epoch seconds, so downloaded daily bars were stored at Aug-1970 timestamps where no
  query ever found them — every run re-downloaded the same span and backtested over 0 bars. Daily
  bars are now stamped at their 16:00 ET close (the 1970 garbage was purged from InfluxDB).
- Also: nginx API read-timeout 60s → 900s (a cold run legitimately blocks while history
  downloads; it used to 504), and the UI's `maxOpenPositions` now reaches the engine (it was
  silently capped at 3).

Verified end-to-end: cold run downloads 4y of AAPL daily bars (~30s), warm run is instant, looser
params produce a plausible 25-trade result with next-day fills and stop/target exits; preset
save/list/delete round-trips. Note: the **default** rule params (RSI<40 + above SMA200 + within 3%
of SMA50 + RSI rising) are genuinely strict — 0 trades on trending AAPL 2023–24 is the strategy
being selective, not a bug.

## 2026-07-18 — Spread edge overhaul (post-week-review)

Follow-up to the 2026-07-13..17 week review: −$753 realized, entire open book underwater after a
broad tech selloff. The win/loss record itself was statistically inconclusive (4W/4L post-epoch),
but three structural leaks were confirmed and fixed:

### Value stop-loss disabled — manage by TP + 21 DTE (strategy)
- `stop-loss-percent: 0` for bull put and bear call; `stopLossPercent <= 0` now means **disabled**
  in `SpreadManagementService` (also honoured per-symbol via the universe row).
- Rationale: spreads are defined-risk — max loss is capped by the width and already sized by
  `max-risk-percent` (2.5% of capital). The 2×-entry-mid value stop fired on vol expansion and
  converted recoverable drawdowns into realized losses (TXN was stopped 2026-07-17 with the
  underlying still **above** the short strike). Managed exits are now take-profit at 50% of credit
  and the 21-DTE time exit; an occasional full-width loss is an accepted, bounded outcome.
  Expected effect: realized win rate moves toward the ~75% theoretical POP of a 25-delta entry
  instead of sitting at the ~67% breakeven.
- **Applies to the existing open book immediately** (thresholds are read from config on every
  monitor cycle): the ten open Jul/Aug bull puts will no longer stop out on spread value; they run
  to TP or their 21-DTE date (Jul 31 for the Aug 21 cohort).

### Earnings-date entry filter (strategy)
- New daily `EarningsRefreshService` stores `next_earnings_date` per US instrument
  (public Nasdaq earnings-date API, Zacks data; DB migration v31).
- Both selectors reject any entry whose chosen expiry spans the earnings date
  (`EARNINGS_BEFORE_EXPIRY` in the scanner funnel). Holding a short spread through a scheduled
  binary event is uncompensated risk at 20–30 delta pricing — MRVL −22% and IONQ −25% during the
  review week were earnings-sized gaps.
- Note: late July / early August is peak earnings season — expect this filter to visibly throttle
  entries. That is the filter working, not a scanner problem.

### Credit floor raised to ≈18% of width (strategy)
- `min-credit-per-share: 0.35 → 0.90` (bull put and bear call, $5 width).
- A $0.35-credit spread's best case is a ~$17 win before commissions (PG closed +$13 for 4 days of
  risk). A fair 25-delta $5-wide spread prices ~$0.90–1.50, so genuine candidates still pass.

### Patient entry execution (execution)
- `entry-min-fill-pct-of-mid: 0.85 → 0.95` — rest near mid; if it doesn't fill, skip and let the
  15-minute scan retry. No more walking the order down toward the natural cross.
- `max-leg-bid-ask-spread-pct: 0.15 → 0.10` — only tight books are worth crossing; the worst
  slippage came from wide-legged quotes (COHR −$36, MRVL −$35 vs entry mid).
- Context: all 10 post-epoch fills printed below entry mid (avg −$17/spread ≈ 15–30% of the
  expected TP win) — a systematic drag on top of the breakeven math. Expect fewer but better fills.

### Frontend
- This changelog is now rendered at **Maintenance › Changelog** (`/changelog`).
