# Changelog

Notable strategy, engine and operations changes. Newest first. Rendered in the frontend under
**Maintenance › Changelog**.

## 2026-10-30 — Would-be trades: what paper can't fill, followed as if it had

No spread order has filled on paper since August. Paper's simulator fills a limit order only
when the visible bid reaches it, while the user's own live orders on the same candidates (ORCL,
NUE, WELL) filled at 99–100% of mid. Paper therefore can't answer the real question: does the
strategy make money if the fills happen?

- **Recording.** Every spread attempt that ends unfilled (`FLOOR_REACHED`, `TIMED_OUT`), or is
  rejected only because a leg's bid/ask looked too wide on screen (`LIQUIDITY_REJECTED`, never
  ordered), is saved as a would-be position assumed filled at mid. Mid, achievable floor and the
  natural price are kept for comparison. Repeated attempts on the same spread are recorded once.
  Recording can never change an execution result.
- **Daily revaluation**, 30 minutes before each market's close (in that market's timezone), from
  real option quotes — two quotes per position, a few dozen requests a day. The same rules as a
  real spread close it: take-profit at 50% of the mid credit, the stock-price stop at the short
  strike, the 21-DTE time exit, and settlement at intrinsic value at expiry. With no live quote it
  waits for the next day. Checking once a day means an intraday-only touch is caught at the next
  check.
- **Overview › Would-be Trades** page: win rate, average win and loss, total P&L at mid and at 95%
  of mid (how much the fill assumption matters), open positions marked to their last value, and
  every row with its reason. Per 1 contract, before commissions. Back-filled history, once added,
  is shown separately as approximate.

## 2026-10-29 — Engine watchdog stops making outages worse

`options-engine-monitor.timer` runs `monitor-engine.sh` every 5 minutes. It used to probe
`/health/ibkr` with a 5-second timeout and `kill -9` the engine on any miss. That killed every
engine that was still booting: both "out of memory" signal-9 kills during the 2026-10-28 deploys
were this script, not memory. And when IB Gateway itself hung (2026-10-28, Gateway Java heap
`OutOfMemoryError`), it killed the engine 24 times in two hours, which can't fix a hung Gateway.

The rewrite (now versioned as `deploy/monitor-engine.sh`, installed to
`/home/solvina/options/monitor-engine.sh`):

- **Boot grace:** no check in the engine's first 5 minutes.
- **Liveness only:** probes `/actuator/health` (3 tries, 10 s each). IBKR connection state is the
  engine's own job and has its own alerts.
- **If the engine is unresponsive, check Gateway first** with the real IBKR API handshake (a TCP
  accept proves nothing — the 2026-07-31 wedge accepted TCP). If Gateway doesn't answer, send a
  Telegram alert (mentioning a Gateway `OutOfMemoryError` if its log shows one) and **do not**
  kill the engine — recovering Gateway is a human decision.
- **Only if Gateway answers and the engine doesn't** is the engine killed (with an alert);
  systemd's `Restart=on-failure` brings it back.
- Alerts repeat at most hourly and reset once the engine is healthy. The bot token is never logged.

## 2026-10-28 — Opportunity emails: fair value, suggested limit, the real stop

For placing engine candidates by hand on the live account, and for testing whether live fills
near mid while paper does not.

- **Fair value.** Each email now shows the spread's model value: Black-Scholes on each leg's own
  volatility (IBKR's, or estimated from neighbours). In a wide market this is a better reference
  than the quoted mid. CRM 265/280 on 2026-10-21: fair $2.45 against a mid of $2.61.
- **Suggested resting limit:** the better of fair value and mid, rounded up to $0.05. This is the
  price to ask for and leave working — the price we would be glad to get, not the one the engine
  chases down to. A limit that never fills costs nothing.
- **The stop line shows what actually runs:** e.g. "Stop: underlying ≥ $265.00 (short strike);
  option-price stop off". It used to print "Stop-loss 0.0% credit", which read as "no stop".
- Estimated deltas are marked "(est.)"; missing values show "n/a" instead of NaN.

## 2026-10-28 — Spread selection: real widths, real markets, expiries that miss earnings

Emails kept proposing spreads far wider than the $5 target (CRM 265/280, QCOM 145/130, ORCL
118/110). The long leg was always chosen by width, as designed. Two things went wrong under that
rule.

- **Strikes with a price but no delta were thrown away**, even as the long leg, which needs no
  delta at all. IBKR sometimes sends a strike's bid/ask without its greeks (ORCL 115 today:
  bid 3.00 / ask 3.30, no delta). The engine then skipped to the next strike out, so a $5 spread
  became $8 or $15. Such strikes are now kept. Their greeks are **estimated with Black-Scholes**
  from the volatility of live neighbouring strikes; the strike's own price is trusted only when
  it agrees with them within 25%. An estimated strike may also be the short leg. With no live
  neighbour nothing is estimated, and the strike can only be a long leg. Quote requests are
  unchanged — the estimate uses data the scan already has.
- **Width cap: 2× target** (`max-width-multiple`). The long leg is still the first listed strike
  at least one width away. If that is more than $10 on a $5 target the symbol is skipped
  (`WIDTH_EXCEEDS_CAP`). Names quoting only $10 strikes that far out (MU, AMD, DE) still trade.
- **Market-quality gate** (`min-combo-bid-pct-of-mid: 0.60`). If the market would pay less than
  60% of the mid credit, the mid is not a real price and the order could only rest unfilled
  (CRM: 1.20 vs 2.61 = 46%). Skipped as `MARKET_TOO_WIDE`.
- **Risk budget checked at the worst accepted credit**, not at mid, so a below-mid fill can't
  exceed the per-trade risk limit.
- **Earnings-aware expiry.** The engine used to pick the expiry nearest 45 days and reject the
  symbol if earnings fell inside it — even when an earlier expiry inside the 30–50 day band
  would have finished first (CRM today: Dec 4 rejected for Dec 2 earnings; Nov 27 was never
  considered). It now takes the in-band expiry nearest 45 days that ends before earnings.
- Bear calls now honour a per-symbol width override, as bull puts already did.

The new limits are on the Trading Rules page; the new skip reasons show in the scanner table.

## 2026-10-28 — Spreads get a stop again: the underlying through the short strike

Since 2026-07-18 a spread had no stop at all. The option-value stop was switched off
(`stop-loss-percent: 0`) and the config said "occasional full-width losses are accepted by
design"; the only exits were take-profit at 50% and the 21-DTE time exit. HON 230/220 shows what
that means: it broke its 230 short strike on 2026-08-11 with 38 days left, was at 217 when the
21-DTE exit came due on 2026-08-28, and would have closed for about −$750 of a possible −$800.
Nothing in the rules reacted for those 17 days.

- **New underlying stop, on for bull put and bear call.** A spread closes once the stock trades
  through the short strike: at or below it for a short put, at or above it for a short call.
  `underlying-stop-buffer-pct` (default 0) moves the trigger that fraction of the strike to the
  safe side, if we want out before the strike is touched.
- **It is not the stop that was switched off.** That one watched the option price, which jumps on
  volatility and wide quotes — TXN was stopped on 2026-07-17 with the stock still above its short
  strike. The stock price has no such noise, so the TXN case holds and the HON case closes
  (roughly −$200 to −$250 on 2026-08-11–12 instead of ~−$750).
- **Same safety gates as before:** two consecutive breaching monitor checks (~1 min apart), none in
  the first 15 minutes after entry or the first 30 minutes of the session, and a 24-hour re-entry
  block afterwards. It closes with marketable limits, escalating to market if they don't fill.
- **Spot comes from the option legs' own data** (no extra market-data line). Only if that is
  missing does the engine take a one-off snapshot of the stock.
- The option-value stop stays off. Take-profit 50% and the 21-DTE exit are unchanged. Both new
  settings show on the Trading Rules page. There is no per-symbol override for them yet.

## 2026-10-28 — Trading Rules page

A one-screen summary of how the spread strategies trade, under **Overview › Trading Rules**:
the principles, then the entry and exit rules side by side for bull put and bear call, the
portfolio caps, the stop gates and any per-symbol overrides.

- **Every number is live.** The page reads a new read-only endpoint, `GET /scanner/rules`, which
  serves the values the engine actually bound at startup — `application.yml` plus profile
  overrides, resolved per strategy through `StrategyParamsRegistry` — and the overrides stored on
  universe rows. Nothing is re-typed into the frontend, so the page cannot drift the way
  hand-written parameter docs always have.
- **Disabled rules show as "Off"**, highlighted. Today that includes the option-value stop on both
  strategies.

Also: `StockStrategyRunner` read its bar window and stamped signals with `Instant.now()` instead
of its injected clock. Production was unaffected (that clock is the system clock), but the
runner's tests pin the clock to 2026-08-07 and began failing once the wall clock moved more than
a warmup window past that date.

## 2026-08-07 — MFI oversold joins the daily paper session

The stock-strategy host shipped dark in Phase 7 and had never actually run. Arming it for the
MFI mean-reversion strategy — daily, alongside the flag strategy — surfaced four gaps, all of
which had to close before a paper session could mean anything.

- **The runner never warmed its strategy.** It read a full warmup window of bars and then passed
  exactly one of them to `decide`. Every strategy in the library carries rolling state (MFI, RSI,
  ATR) that reports "not ready" until it has its whole period, so `decide` returned null on every
  symbol on every pass: **the live host could not have produced a single entry.** It now warms on
  `bars.dropLast(1)` and decides on the last bar — the same two steps `StrategyBacktestAdapter`
  performs, so both hosts feed identical state.
- **Positions had no lifecycle.** `StockStrategyRunner` was the only writer of `stock_position`:
  nothing moved a row past PENDING, so fill rate (the one number a backtest cannot produce) was
  unmeasurable, an expired day limit left a PENDING row forever, and since the runner refuses a
  second entry while one is live, the first signal on a symbol blocked that symbol permanently.
  New `StockPositionLifecycleService` (broker-callback driven, like `FlagExecutionService`) books
  entry fills, entry misses as ENTRY_UNFILLED, and stop/target fills with realized P&L from the
  actual fill prices. New `StockRecoveryService` re-attaches after a restart or disconnect, with
  the flag strategy's decision table and its 10-minute re-protection acknowledgement guard (the
  2026-08-05 "32 stops in 72 minutes" lesson). Neither ever decides anything from an unavailable
  position feed — that check is now shared as `BrokerPositionsSnapshotService`.
- **Daily bars were stale and nothing refreshed them.** Bars were only ever written on demand by
  a backtest or the Historical Data page; on the paper host the newest daily bars were 2.5 weeks
  old. New `DailyBarRefreshService` + a 23:30 Berlin job tops up every symbol an enabled
  assignment names, paced at ~10s/symbol for IBKR's ~60-requests-per-10-minutes limit. The runner
  independently **refuses a stale bar**: its only data check was bar *count*, which a series that
  stopped updating three weeks ago passes happily.
- **Live entries did not match the backtest.** The host added `limit-tolerance-atr-multiple`
  (0.25 ATR) on top of the strategy's own entry price, while the stop and target stay anchored to
  the signal price — the exact anchoring that commit cc3a7b2 measured as a 25% → 15% win-rate
  loss. Now 0 by default, and the ATR-unavailable fallback no longer smuggles in 0.25% either.

Also: stock positions now appear in the daily audit (open counts, zombie PENDING rows,
positions with no protective stop, cumulative fill rate) and each stock strategy gets its own row
on the Reports/Overview page next to `bull_flag`, rather than being invisible.

Armed on the paper box only, via `STOCK_STRATEGIES_ENABLED=true` in its `engine.env`; the
committed default stays `false`, so a prod deploy of this build changes nothing.

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
- Viewer follow-ups (app page + standalone sweep-viewer.html): header shows **what you're
  looking at** (run name · symbols · timeframe · window · rows); the app page adds a
  **data-coverage warning** when local bars don't span the sweep window (a 4h sweep before the
  4h universe download produced "strange" results — MSFT 4h only covers 2021-09→2024-12);
  **global top-10** across all slider positions with click-to-jump (snaps sliders to the
  combo's slice); buy & hold tile shows **absolute dollars** (initial capital derived from
  finalCapital − totalPnl).

### RPi stability (three spontaneous reboots on 2026-07-19)
- Not thermal: 67°C under full download load, `throttled=0x0` (never throttled since boot).
  The signature — hard freeze, zero log evidence, watchdog reboot — matches **memory
  exhaustion**: 4 GB RAM with two JVMs + InfluxDB + full observability stack left ~30 MB free
  and 1.3 GB deep in SD-card swap.
- Mitigations: persistent journald enabled (next crash leaves `journalctl -b -1` evidence);
  portainer/alloy/loki stopped for the download period (`docker start` re-enables);
  downloader now supports `PAUSE_SECONDS` between symbols (running with 20s + nice 10) so
  Influx compactions drain instead of piling up.

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
