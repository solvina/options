# Trade Execution — Current Architecture

> Last updated: 2026-06-05. This document reflects the implemented state.

---

## Spread Strategy — Combo Order Execution

### Overview

`TradeExecutionService` (domain) replaces sequential leg orders with a single atomic BAG/combo order. This eliminates legging risk — both legs fill simultaneously or not at all.

### Flow

```
ScannerService
  └─ scope.launch { TradeExecutionPort.execute(request) }
        │
        TradeExecutionService
          ├── Pre-trade guards (exposure, capital, liquidity)
          ├── submitComboLimitOrder(soldContract, boughtContract, netCredit)
          │       → BAG contract with SELL + BUY ComboLegs, LMT order
          ├── Start event loop (timeout = executionTimeoutMinutes = 15 min):
          │     Underlying price stream → drift check (abort if > 5%)
          │     Credit tick stream → price ladder (every 30s: lower by 1 tick)
          │     Fill event → break loop FILLED
          ├── On fill: persist BullPutSpread (status=OPEN)
          └── On abort/timeout: record cooldown (4h per symbol)
```

### Key behaviours

- **Drift guard**: if underlying moves > `driftProtectionPct` (5%) from entry reference, cancel and abort. Cooldown applied.
- **Price ladder**: every `priceAdjustIntervalSeconds` (30 s) with no fill, lower net-credit limit by one tick. If price would fall below floor credit, abort (`FLOOR_REACHED`).
- **Floor credit**: `targetCredit × (1 − floorCreditBuffer)`.
- **Entry cooldown**: `entryCooldownMinutes` (240) prevents re-scanning a recently-failed symbol.
- **TOCTOU guard**: `inFlightSymbols` set prevents duplicate executions if scanner fires twice quickly.

### ScannerConfig parameters for execution

```kotlin
val driftProtectionPct: Double = 0.05        // 5% underlying movement → abort
val executionTimeoutMinutes: Long = 15
val priceAdjustIntervalSeconds: Int = 30     // ladder cadence
val maxLegBidAskSpreadPct: Double = 0.15    // liquidity gate
val entryCooldownMinutes: Long = 240         // 4h per-symbol cooldown after failed entry
```

---

## Spread Strategy — Close Execution

### Automated exits (all market orders)

`SpreadManagementService.forceCloseSpread()` is called directly for TP, SL, and DTE exits — no limit order, no CLOSING state, no chase. Market sell on both legs immediately.

### Manual soft-close (limit orders)

`softClose()` → `closeSpread()`:
1. Mark spread as CLOSING in DB immediately (prevents re-entry).
2. Place limit BUY on sold put at mid price (chase via `OrderChaseService`, up to `orderChaseMaxRetries`=1 retry).
3. If sold put fills: place limit SELL on bought put at mid price.
4. If either leg fails: spread remains CLOSING. `SpreadMonitorScheduler` retries `CLOSING` spreads on next tick via `retryClose()` (market order).

### Manual force-close (market orders)

`forceClose()` → `forceCloseSpread()`: fires both legs as market orders immediately. No fill-wait timeout check — orders are already submitted at IBKR.

### Special case: worthless sold leg

If `soldMid ≤ $0.00`, buy-back would never fill at limit $0.00. `closeSpread()` skips the buy-back and marks the spread closed directly.

---

## Flag Strategy — Bracket Order Execution

### Order structure

Three IBKR orders submitted in a single group:

```
Parent:  action=BUY, orderType=STP, auxPrice=entryPrice, tif=DAY, transmit=false
Child 1: action=SELL, orderType=STP, auxPrice=stopLossPrice, tif=GTC,
         parentId=entryId, ocaGroup=FLAG_{symbol}_{ts}, ocaType=1, transmit=false
Child 2: action=SELL, orderType=LMT, lmtPrice=profitTargetPrice, tif=GTC,
         parentId=entryId, ocaGroup=FLAG_{symbol}_{ts}, ocaType=1, transmit=true
```

`transmit=true` on Child 2 triggers all three orders simultaneously. OCA group ensures whichever child fills first cancels the other.

### Timeouts

- Parent: 10 hours (`PARENT_TIMEOUT_MS`). If not filled, status → `ENTRY_TIMEOUT`.
- Children: 30 days (`CHILD_TIMEOUT_MS`). Safety-net for GTC orders stuck in IBKR after a reconnect.

### Fill tracking

`IbkrOrderRegistry.consumeFillPrice(orderId)` captures `avgFillPrice` from the `orderStatus` callback and exposes it for slippage calculation: `actualEntryPrice - entryPrice`.

### Manual close

`FlagManagementService.manualClose(id)`:
- For PENDING: cancel all 3 bracket orders → `CLOSED_MANUAL`.
- For OPEN: cancel SL + PT orders → market SELL → `CLOSED_MANUAL` (best-effort price capture for P&L).

---

## `IbkrOrderRegistry` — Error Handling Details

| IBKR code | Handling |
|---|---|
| 399 (after-hours queue) | Fail-fast → CANCELLED; caller cancels queued order before repricing |
| 201 (rejected) | CANCELLED; distinguish self-cancel (repricing) vs unexpected rejection (ERROR log) |
| 202 (cancelled) | CANCELLED; same self-cancel vs external distinguish |
| Other (permissions, risk limits) | `completeExceptionally` → propagates as RuntimeException |

The `selfCancelledOrders` set tracks order IDs that were cancelled internally for repricing, suppressing spurious error logs.

---

## IBKR Streaming Infrastructure

### Real-time bars (flag strategy)

```kotlin
realTimeBarsPort.streamBars(symbol)  // → reqRealTimeBars(contract, 5, "TRADES", useRTH=true)
```

Bars arrive as 5-second `realtimeBar` callbacks → `IbkrMarketDataRegistry.onRealtimeBar()` → emitted to a per-symbol `Channel` → collected by `FlagScannerService.subscribe()`.

### Tick-by-tick (spread execution)

```kotlin
marketTickPort.streamSpreadCredit(soldContract, boughtContract)  // → reqTickByTickData("BidAsk") × 2 legs
```

Combined with `reqMktData(snapshot=false, "100")` × 2 legs for delta ticks. Emits `SpreadCreditTick` on every bid/ask update.

### Snapshot (spread exit monitor)

```kotlin
marketDataPort.getOptionMid(contract)  // → reqMktData(snapshot=true)
```

Awaits `tickSnapshotEnd` or completes early on errors 354/10197 → Black-Scholes fallback.

---

## Backtest Adapters (flag strategy)

`BacktestEngine` in `src/test/kotlin/.../backtest/` replays historical 5-min bars through `PatternDetector`, simulates bracket fills, and computes P&L.

Port implementations:
- `BacktestRealTimeBarsPort` — emits pre-fetched historical bars as a Flow.
- `BacktestBracketOrderAdapter` — immediately "fills" the parent; resolves SL or PT based on simulated future price.
- `BacktestFlagPort` — in-memory list.

```bash
./gradlew test  # runs BacktestSmokeTest (no IBKR needed)
```
