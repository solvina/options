# Flag backtest fidelity plan

**Created** 2026-07-29 · **Status** planned, not started · **Owner** next session

## The problem in one line

The flag backtest tests a trigger that produced **4 of 136** real trades.

## Evidence

`flag_positions.breakout_type`, closed trades with real exits (excludes the 10 pre-fix
`manual` rows whose P&L is fictional — see "Data hygiene" below):

| Trigger | Trades | Avg R |
|---|---|---|
| `LIVE_BAR` — 5-second sub-candle check | **131** | +0.009 |
| `FIVE_MIN` — completed 5-minute candle | 4 | -0.115 |

`FlagScannerService.subscribe()` runs two entry checks per streamed 5-second bar:

1. `aggregator.add(bar)` → on a completed 5-min candle, `detector.onNewBar()` → `FIVE_MIN`
2. every 5-second bar, `detector.checkBreakoutOnLiveBar()` → `LIVE_BAR`

`FlagBacktestStrategy.onBar()` only ever receives 5-minute candles and only enters via path 1.
So the backtest is **not a crude approximation of the live strategy — it is a different
strategy that shares a pattern detector**. This is exactly the divergence `StockStrategy`'s
KDoc warns about, and it names this trigger as the cautionary example.

Consequence: no live-vs-backtest comparison is currently meaningful, in either direction.
Neither "the backtest is too pessimistic" nor "live has an edge the backtest misses" can be
supported until the two run the same signal.

## What is NOT the problem

Measured, so we don't spend effort here:

- **Fill quality.** `entry_slippage` over 142 live entries averages **$0.14 (~4.6 bps)**.
  The backtest assuming a fill at the breakout level is off by roughly a rounding error.
  Modelling STP LMT mechanics in detail buys almost nothing.
- **Sizing.** Ported to stock-strategy parity at f497f2a (%-of-equity risk, ruin guard,
  ATR-based stop/target).

## Plan

### Phase 1 — Run the live trigger on 5-minute bars (cheap, do first)

Make `FlagBacktestStrategy.onBar()` also evaluate `checkBreakoutOnLiveBar(bar.close, ...)`
when the detector is in `FlagForming`, mirroring `FlagScannerService`. Tag the resulting
trade with its `breakoutType` so backtest output is comparable to `flag_positions`.

- Effort: hours.
- Deliverable: a backtest run reporting FIVE_MIN-only vs LIVE_BAR-enabled side by side.
- **This is the decision gate.** If the two are similar, Phase 2 is unnecessary and the
  5-minute backtest is trustworthy after all. If they diverge, Phase 2 is justified by
  evidence rather than by intuition.

Caveat to state in the output: a 5-minute close is only one of ~60 five-second closes in
that window, so this approximates the live trigger's *logic* but still misses ~59/60 of its
*opportunities*. It bounds the difference; it does not eliminate it.

### Phase 2 — 5-second bars (only if Phase 1 shows divergence)

Scope deliberately small: a handful of symbols, a short window, as a **fidelity check on the
5-minute approximation** — not as the standing backtest corpus.

Blockers to solve first, in order:

1. **No history exists.** `Timeframe` has only FIVE_MIN / FOUR_HOUR / DAILY, and the
   aggregator writes only completed 5-minute candles to Influx. A `FIVE_SEC` timeframe needs
   adding with its own `maxChunkDays` / `minBarsPerDay`.
2. **IBKR pacing.** ~4,680 five-second bars per symbol per RTH session. 30 symbols × 60 days
   ≈ 8.4M bars, many hours of fetching on the same historical pipe that has already caused
   starvation incidents. Run it on a settled engine (see `historical_interior_gap_fix`).
3. **Influx capacity.** influxd has OOM-killed the engine once already via shard sprawl
   (fixed by 1-year shards). Decide retention and shard duration for 5-second data
   *before* the first fetch, and consider a separate bucket with short retention.

### Phase 3 — Exit-side fidelity

Live places `STP LMT` entry with a `TRAIL` child, GTC — the trail moves on every broker tick.
The backtest walks the trail on 5-minute candles. Even with 5-second bars this stays
approximate: OHLC bars can't say whether the high or the low came first within the bar.

Worth doing only after Phases 1–2, and worth stating as a permanent known limitation rather
than pretending it can be made exact.

## Data hygiene (prerequisite for any comparison)

Ten `close_reason = 'manual'` rows dated 2026-06-26 → 2026-07-23 carry **fictional realized
P&L** — booked at quote-mid with no real fill, from before the cd58341 fix. They average
+7.88R (META +30.3R, AAPL +20.1R) against a strategy whose stop is 1R and trail is 2R, and
they account for **+$7,879 of the +$8,219 headline profit**.

Until they are quarantined or corrected, every expectancy, win-rate and R-multiple the UI
shows for the flag strategy is wrong. The 14 `manual` rows dated 2026-07-24 onward correctly
have NULL P&L — that is the fix working.

## Baseline to beat (real exits only, manual excluded)

| Metric | Value |
|---|---|
| Trades | 132 |
| Total | +$339.80 |
| Avg / trade | +$2.57 |
| Avg R | +0.02 |
| Win rate | 30.3% |
| t-statistic | **0.21** |

Flat. Detecting a July-sized edge (+$21/trade, sd $142) at t=2 needs ~180 trades, roughly two
months at the current rate. Detecting the full-sample +$2.57 would need ~11,500 trades — i.e.
it isn't there.

Known confounds in this baseline, both being addressed separately:

- 71 of 213 entries ended `ENTRY_TIMEOUT` (never filled) — a concurrency side effect,
  reported fixed 2026-07-29. Re-measure after a clean fortnight.
- EU: 0 wins in 13 trades, avg R -1.005. A 2–3 day old experiment, plus the IBKR 10311
  direct-routing rejection (fixed by `BYPASS_WARNING`, deployed 2026-07-29).
