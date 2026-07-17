# Stock Backtest — Plan & Progress

Building a UI-driven **stock** backtest by **extending** the existing backtest + historical-data
modules (not a parallel system). **Daily is the primary timeframe** (IBKR can't serve 5m/4h back
decades — intraday is recent-only, accepted). **IBKR is the primary data source**; a pluggable
bulk daily source (Stooq/Tiingo) is deferred.

The spread backtest is a **separate feature** (shares only the data ports) — see
`SpreadBacktestService` / `POST /api/backtest/spread`; currently unverified + deprioritized.

## Design principle — scriptable future
The parameter/rule strategy is only the *starter*. The future goal is a **scripted strategy object**
with lifecycle hooks (`onCandle`, `onTick`, scheduled e.g. "15 min after open") over a
`StrategyContext` (indicators, price, account, clock/session, schedule) + optional persistent memory.
So the engine binds to a **lifecycle strategy interface** (`BacktestableStrategy`), never to the rule
model. The rule strategy is one implementation; a scripted strategy graduates from a good param set.

## Done (backend, green, committed — UNDEPLOYED, blocked by open US session 2026-07-17)
- **Timeframe data layer** (`88954fb`): `Timeframe` enum (5min/4h/1d) threaded through `BarStorePort`
  + `EquityHistoricalBarsPort` as a **defaulted** param (FIVE_MIN → live code byte-for-byte unchanged);
  InfluxDB series tagged by timeframe; fetch adapter picks bar-size + chunk span per timeframe.
  Kept `FiveMinuteBar` (timeframe is a *series* property, not the candle); rename→`Bar` deferred
  (cosmetic; also collides with `com.ib.client.Bar`).
- **Data-on-demand**: `HistoricalDataService.ensureCoverage(symbols, from, to, timeframe)` — fetches
  only the missing head/tail (extend history backward/forward), no-op when covered. "Type AAPL
  1999-now → downloads once, then serves from store." Builds on the fetch timeout/per-chunk fix
  (`3f9a9c6`).
- **Rule strategy** (`2cb4616`): `RuleBacktestStrategy : BacktestableStrategy` — long support-bounce:
  SMA 50/200 (trend + support), RSI + RSI-slope (bounce). Params fully overrideable. No look-ahead
  (indicators from current close; engine fills next bar).
- **Engine**: `BacktestEngine` timeframe-aware (daily/4h read natively; 5-min still aggregated);
  `holdOvernight` for swing.
- **Endpoint**: `POST /api/backtest/stock` — ensures data (incl. SMA-warmup span) then runs.

## Remaining
1. **Deploy** — after US close / anytime Saturday (market closed = safe).
2. **Verify** end-to-end — AAPL daily; first run auto-downloads deep daily (IBKR serves it).
3. **UI** — extend `BacktestPage.tsx`: timeframe selector, indicator/rule builder, first-fetch
   progress, results (equity curve + drawdown). **Ship the train/test (in-sample/out-of-sample)
   guardrail WITH the UI** (anti-overfit — the friendly knobs are a curve-fitting machine otherwise).
4. **Named configs** — `backtest_config` table + save/recall/delete.
5. **Cost/slippage model** in the engine (daily/4h trades often enough that ignoring it = fantasy).
6. **VWAP** indicator (intraday only).

## Known issues / risks
- Data ceiling per timeframe (daily deep, intraday recent-only).
- Split/dividend adjustment consistency for multi-decade daily.
- Look-ahead bias — the #1 correctness risk (guarded, keep guarding).
- Overfitting — the UI makes it *easier*; hence the train/test guardrail is mandatory, not optional.
- First-fetch latency — bounded synchronous wait for now; UI makes it async with progress.
