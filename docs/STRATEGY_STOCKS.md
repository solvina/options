# Stock Strategy: Bull-Flag Breakout

Intraday momentum strategy on stocks (`flag.*` config, `FlagScannerService` +
`FlagExecutionService`). Watchlist is DB-driven via `instrument_universe.flag_enabled`. Values
below are as configured 2026-07-06; `application.yml` inline comments track tuning history.

## Pattern

A **flagpole** (sharp advance) followed by a **flag** (shallow consolidation channel), entered on
the breakout above flag/resistance:

- Bars: 5-second real-time bars aggregated per symbol (**requires live market data — real-time
  bars are not available on a delayed-data deployment; the strategy is inert there**).
- Flagpole: 5–10 bars, height 1.5–5.0 × ATR(14) (`min/max-flagpole-atr-multiple`).
- Flag: 5–20 bars, retracement 15–50% of the pole (`min-flag-retracement-pct`,
  `max-retracement-pct`), channel slope currently allowed flat/slightly rising
  (`require-negative-channel-slope: false`).
- Volume: breakout volume ≥ 1.5 × 20-bar MA (`volume-spike-multiplier`).
- Session filter: first 90 minutes after the US open are skipped (`skip-first-rth-minutes`).

## Execution

- Entry: bracket order — market/limit entry with attached **stop-loss** and **profit-target**
  children (ATR-based sizing: risk amount → share count, so odd lots are normal).
- Exits: stop, target, or **end-of-day liquidation** (no overnight positions). EOD windows are
  currently fixed ET times — half-days close early and are missed (see CURRENT.md).
- Journaling: every position records pattern metrics (pole height, retracement, channel slope,
  MFE/MAE, volume profile) in `flag_positions` for later analysis; the backtest framework
  (`/backtest`, `backtest_run` table) replays the same detector on historical bars.

## Status

Runs on paper. Entry-quality filters were deliberately loosened 2026-06-17 (every live breakout
that day was a near-miss on one filter) — pending backtest validation before any tightening or
live promotion; go-live will most likely start **without** the flag strategy enabled.

## Known operational hazards

- Fill watchers are in-memory: a restart/disconnect used to strand PENDING/OPEN rows while their
  GTC orders lived on at the broker, and a later blind close double-sold (the 2026-07 orphan
  shorts). Since 2026-07-07 closes verify broker holdings first (zero held → CLOSED_EXTERNAL,
  unverifiable → abort) and `FlagRecoveryService` re-arms watchers / re-protects shares at
  startup and every 5 minutes.
- A rejected real-time-bars subscription now logs ERROR + CRITICAL Telegram alert (previously it
  was silent and the strategy just produced nothing).
