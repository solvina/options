# Bull Flag Momentum Strategy — Implementation Summary

> Status: **IMPLEMENTED**. Last updated: 2026-06-05.
> This document reflects the delivered system, not the original spec.

---

## What Was Built

An intraday bull-flag momentum strategy engine running as a second independent strategy inside the same Spring Boot process as the bull put spread scanner. It subscribes to 5-second IBKR real-time bars, aggregates them into 5-minute candles, detects flag-and-pole breakouts, and executes bracket (OCA) orders with full trade journaling.

---

## Key Components

### Bar pipeline

```
reqRealTimeBars (5-sec bars, useRTH=true)
  → IbkrMarketDataRegistry.onRealtimeBar()
  → RealTimeBarsPort.streamBars(symbol) [callbackFlow]
  → FlagScannerService.subscribe() [per-symbol coroutine]
       ├── BarAggregator: accumulates 60 × 5-sec bars → FiveMinuteBar
       ├── BarBuffer: circular buffer of completed 5-min bars
       └── PatternDetector: stateful FSM per symbol
```

Historical bootstrap (on startup): `equityHistoricalBarsPort.fetch5MinBars(symbol, 3 days)` fetches 5-min bars via `reqHistoricalData("5 mins", "TRADES")` and replays them through the detector to prime the FSM before live bars arrive.

### Pattern FSM (`PatternDetector`)

Four states: `Idle → FlagpoleDetected → FlagForming → BreakoutReady`.

Transition logic:
- **Idle → FlagpoleDetected**: scan last `poleMaxBars` (10) candles for any start/end pair where `endBar.high − startBar.low ≥ atrMultiplier × ATR(14)` AND at least one bar has `volume > volumeSpikeMultiplier × VolumeMA(20)`.
- **FlagpoleDetected → FlagForming**: wait for ≥ `flagMinBars` (5) post-pole bars; compute linear regression on highs/lows; check retracement ≤ 50%; check channel slope ≤ 0.
- **FlagForming → BreakoutReady**: `newBar.close > upperResistance.valueAt(flagBarIndex)`. Also checked on every raw 5-sec bar (`checkBreakoutOnLiveBar`) for sub-candle precision. Breakout type stored: "FIVE_MIN" or "LIVE_BAR".

Reset conditions: retracement > 50%, flag exceeds 20 bars, upper slope rising, manual reset after entry.

### Entry quality filters (applied in `maybeEnter`)

| Filter | Condition |
|---|---|
| Scanner paused | `config.enabled == false` → reset detector |
| Entry block | within `entryBlockMinutesBeforeClose` (120 min) of session close → reset |
| First-bar skip | `minutesSinceOpen < skipFirstRthMinutes` (90) → reset |
| Channel slope | if `requireNegativeChannelSlope=true` and `slope ≥ 0` → reset |
| Pole/ATR ratio | not in `[minFlagpoleAtrMultiple, maxFlagpoleAtrMultiple]` = [2.0, 4.0] → reset |
| Retracement | `abs(retracement) < minFlagRetracementPct` (25%) → reset |
| Flag bars | `flag.bars.size < minFlagBarsForEntry` (7) → reset |
| Max open positions | `entryMutex` serialises read + write; `open + pending ≥ maxOpenPositions` → reset |

### Execution (`FlagExecutionService`)

```kotlin
shares = floor(riskPerTrade / (entryPrice - stopLossPrice)).coerceAtLeast(1)
profitTarget = entryPrice + 2 × (entryPrice - stopLossPrice)
```

Bracket order: stop-market parent (`DAY`), stop-market SL child + limit PT child (both `GTC`, OCA group).

After placing the bracket, the service launches a background coroutine that:
1. Awaits parent fill (up to 10h).
2. On fill: records `actualEntryPrice`, computes `entrySlippage`, updates status to OPEN.
3. Launches two parallel coroutines watching SL and PT fills. Whichever completes first wins; the other is cancelled by OCA.
4. On close: computes MAE, MFE, R-multiple, MFE-R, MAE-R, time-in-trade. Updates `FlagPosition`.

### EOD liquidation (`FlagMonitorScheduler` + `FlagManagementService`)

Every 60 seconds, `FlagMonitorScheduler` checks both EU (17:30 Berlin) and US (16:00 ET) close times. When `minuteOfDay ≥ closeMinute - eodLiqMinutesBeforeClose`:
1. Cancel SL and PT bracket children.
2. Market SELL open equity position (`submitMarketSell`).
3. Best-effort price capture for realized P&L.
4. Status → `CLOSED_EOD`.

### Watermark tracking

Every 5-sec bar from a subscribed symbol triggers `flagManagementService.updateWatermarksForSymbol(symbol, barClose)`. This updates `highestPriceSeen` / `lowestPriceSeen` on any currently OPEN position for that symbol — used for MAE/MFE computation at close.

---

## Watchlist Management

- Configured in `application.yml`: `flag.us-watchlist` / `flag.eu-watchlist`.
- Subscriptions start at `ApplicationReadyEvent`. Only markets currently open at startup subscribe immediately.
- Resubscription crons: `0 1 9 * * MON-FRI` (EU, Berlin TZ) and `0 31 9 * * MON-FRI` (US, ET).
- 5-minute watchdog `watchdogCheck()` detects symbols whose last bar is > 10 min old and resubscribes.
- Hot-subscribe API (`subscribeSymbol(symbol, session)`) for runtime additions.

---

## Trade Journal Fields Stored per Position

### At entry
`flagpoleHeight`, `flagRetracement`, `flagBarCount`, `flagpoleBarCount`, `flagpoleAvgVolume`, `flagAvgVolume`, `channelSlope`, `atrAtEntry`, `volumeMaAtEntry`, `flagpoleVolumeRatio`, `vwapAtEntry`, `dayOpenPrice`, `breakoutType`, `stopDistancePct`, `marketSession`, `minutesToClose`

### At execution
`actualEntryPrice`, `entrySlippage`

### During trade (updated on each 5-sec bar)
`highestPriceSeen`, `lowestPriceSeen`

### At close
`maxFavorableExcursion`, `maxAdverseExcursion`, `rMultiple`, `mfeR`, `maeR`, `timeInTradeSeconds`

---

## Database

Table: `flag_positions`. All columns above as `NUMERIC` / `INT` / `TIMESTAMP WITH TIME ZONE`.

Table: `flag_trading_config` (single row, id=1):
- `risk_per_trade`, `max_open_positions`, `entry_block_minutes_before_close`, `eod_liq_minutes_before_close`, `enabled`
- Managed by Liquibase; runtime editable via `PUT /options/flags/config`.

---

## REST API (flags)

| Endpoint | Description |
|---|---|
| `GET /options/flags` | Paginated list; `?status=OPEN\|PENDING\|CLOSED_*` |
| `GET /options/flags/{id}` | Single position |
| `GET /options/flags/analytics` | Win rate, avg R, MAE/MFE distribution |
| `GET /options/flags/config` | Runtime config |
| `PUT /options/flags/config` | Update config (riskPerTrade, maxOpenPositions, enabled, etc.) |
| `POST /options/flags/scanner/pause` | Set `enabled=false` in DB |
| `POST /options/flags/scanner/resume` | Set `enabled=true` in DB |
| `POST /options/flags/{id}/close` | Manual close |
| `GET /options/flags/scanner/status` | Per-symbol bar subscription + pattern state |

---

## IBKR Quirks for Flag Strategy

- `reqRealTimeBars` requires a live streaming market data subscription for the stock's primary exchange. EU symbols (ASML/SAP/SIE/ALV on XETRA/AEB) will fail with error 420 on a paper account without the relevant subscription — silently skipped, logged as WARN.
- `useRTH=true` means bars only arrive during regular trading hours; streams go quiet at session close and resume the next trading day.
- After IBKR reconnect, bar subscriptions should resume automatically via the `onEuMarketOpen`/`onUsMarketOpen` crons. If not, restart the engine.

---

## Original User Story Checklist vs Delivered

| Requirement | Delivered |
|---|---|
| 5-sec bar aggregation to 5-min candles | Yes — `BarAggregator` |
| Flagpole: ≥ 2×ATR, volume spike > 1.5×MA | Yes — `PatternDetector.detectPole()` |
| Flag: ≤ 50% retracement, ≤ 20 bars, volume drying | Yes — regression channel, retracement guard |
| Breakout entry on bar close above resistance | Yes — both 5-min and sub-candle 5-sec |
| Bracket/OCA order with stop-loss + profit target | Yes — `IbkrBracketOrderAdapter` |
| Position sizing from risk per trade | Yes — `shares = riskPerTrade / stopDistance` |
| Entry block 2h before close | Yes — `entryBlockMinutesBeforeClose` (configurable) |
| EOD liquidation 15 min before close | Yes — `eodLiqMinutesBeforeClose` (configurable) |
| Real-time UI | Yes — `/flags/positions` with scanner status panel |
| Kill switch persisted to DB | Yes — `flag_trading_config.enabled` |
| 1% capital risk cap | Yes — `riskPerTrade` in config (runtime-editable) |
| Historical bootstrap | Yes — 3-day 5-min bar replay at startup |
| Trade journaling: MAE/MFE, R-multiple, slippage | Yes — full `FlagPosition` journal |
