# Backtest Framework

> Status: **IMPLEMENTED**. Last updated: 2026-06-05.

---

## Two Backtest Modes

### 1. Spread Strategy Backtest (smoke test + fixtures)

Runs the real domain services (`ScannerService`, `SpreadManagementService`, `IvRankService`, `TradeExecutionService`) against historical data with swapped-out port implementations. No IBKR needed.

### 2. Flag Strategy Backtest

Replays historical 5-min bars through `PatternDetector`, simulates bracket fills, computes trade-journal metrics. Entry point: `POST /options/api/backtest/flag` (REST API, stores results in DB) or `BacktestSmokeTest` (unit test, in-memory).

---

## Spread Backtest Architecture

```
ScannerService ──────────────────────────────────────────────────────┐
SpreadManagementService                                              │
IvRankService                                                        │  unchanged domain code
TradeExecutionService                                                │
      │                                                              │
      │ port interfaces                                              │
      ▼                                                              │
BacktestHistoricalDataAdapter  ← FixtureLoader ─ fixtures/iv/{SYM}.csv
BacktestMarketDataAdapter      ← FixtureLoader ─ fixtures/prices/{SYM}.csv
BacktestOptionChainAdapter     ← BlackScholes + IV fixture
BacktestAccountAdapter         ← in-memory StateFlow<AccountDetail?>
BacktestMarketTickAdapter      ← emits single credit tick per scan
BacktestOrderExecutionAdapter  ← immediate fills (BAG orders)
BacktestOrderAdapter           ← immediate fills (leg close orders)
BacktestSpreadAdapter          ← in-memory MutableList<BullPutSpread>
      │
      ▼
MutableClock  ─── advances one market day at a time
      │
BacktestEngine  ─── iterates dates, calls scan() + checkExits(), settles P&L
      │
BacktestResult  ─── P&L, win rate, max drawdown, per-trade log
```

---

## Components

### `MutableClock`

`src/test/kotlin/.../backtest/MutableClock.kt`

Extends `java.time.Clock`. `advanceTo(LocalDate)` moves the simulation forward one day. Domain services that call `LocalDate.now(clock)` or `Instant.now(clock)` pick up the simulated date transparently. Set to market open (09:30 ET) each day so DTE calculations match production.

### `FixtureLoader`

`src/test/kotlin/.../backtest/FixtureLoader.kt`

| Method | Source | Columns |
|---|---|---|
| `loadIvBars(symbol)` | `fixtures/iv/{SYMBOL}.csv` | `date,iv` |
| `loadPriceBars(symbol)` | `fixtures/prices/{SYMBOL}.csv` | `date,close` |

IV fixtures for SPY/QQQ/AAPL/MSFT/NVDA committed. Price fixtures synthetic for SPY (committed); fetch real ones via `./gradlew test -Dtests.tags=tws`.

### `BlackScholes`

`src/main/kotlin/.../domain/features/market/BlackScholes.kt` (production code, not test-only)

European put pricing — pure Kotlin. Functions: `putPrice`, `putDelta`, `gamma`, `putTheta`, `vega`, `impliedVol` (Newton-Raphson). Used by backtest adapters for option pricing and by production spread scanner as IV fallback.

### Backtest adapters

| Adapter | Notes |
|---|---|
| `BacktestHistoricalDataAdapter` | Filters IV bars to `date ≤ clock.currentDate()`, caches per symbol. |
| `BacktestMarketDataAdapter` | `getUnderlyingPrice`: last close on or before clock date. `getOptionMid`: Black-Scholes using flat IV. |
| `BacktestOptionChainAdapter` | Generates strike grid (OTM puts, $5 spacing), BS-prices each. Bid/ask = mid ± max(5%×mid, $0.05). |
| `BacktestAccountAdapter` | In-memory `StateFlow<AccountDetail?>`. `debit`/`credit` called by engine for capital checks. |
| `BacktestMarketTickAdapter` | Emits one `SpreadCreditTick` from `BacktestMarketDataAdapter.getOptionMid()`. |
| `BacktestOrderExecutionAdapter` | Atomic counter for orderIds; records fills; `awaitFill` → FILLED immediately. |
| `BacktestOrderAdapter` | Always FILLED; maintains `fills` log. `commissionPerLeg` default $0.65. |
| `BacktestSpreadAdapter` | `MutableList<BullPutSpread>`; UUID assigned on `save()`. |

### `BacktestEngine`

Each market day (Mon–Fri):
1. `clock.advanceTo(date)`
2. `scanner.scan()` — finds new entries
3. `spreadManager.checkExits()` — closes at TP / SL / DTE
4. Detect newly opened/closed spreads → `accountAdapter.debit/credit`
5. Update peak capital and max drawdown

### `BacktestResult` fields

| Field | Description |
|---|---|
| `totalPnl` | `finalCapital − initialCapital` |
| `tradeCount` | Closed trades |
| `winRate` | Fraction of trades with `pnlPerShare > 0` |
| `maxDrawdownPct` | Largest peak-to-trough % decline |
| `trades` | Per-trade log: open/close dates, credit, close price, P&L/contract, reason |

### `BacktestSmokeTest`

`src/test/kotlin/.../backtest/BacktestSmokeTest.kt` — no Spring context. Config overrides: `ivRankThreshold=20.0`, `deltaMin/Max=[0.10–0.35]`, `maxRiskPercent=0.10`, `maxOpenSpreads=3`. Runs 2025-01-02 → 2025-03-31 on SPY. Assertions: structural only (non-negative trade count, win rate 0–1, positive final capital).

---

## Flag Backtest (REST API)

`POST /options/api/backtest/flag` — fetches historical 5-min bars from InfluxDB, replays through `PatternDetector`, simulates fills.

Historical bar fetch: `POST /options/api/historical/fetch` — calls `reqHistoricalData` via IBKR and writes 5-min bars to InfluxDB for later backtest replay.

---

## Running

```bash
# Spread backtest smoke test (no IBKR, no Spring)
./gradlew test

# TWS fixture fetch (requires paper session)
./gradlew test -Dtests.tags=tws

# Flag backtest (requires running engine + InfluxDB with data)
curl -X POST http://localhost:8081/options/api/backtest/flag \
  -H "Content-Type: application/json" \
  -d '{"symbol":"SPY","from":"2025-01-02","to":"2025-03-31"}'
```

---

## Limitations

| Item | Note |
|---|---|
| Flat IV surface | Uses single daily IV bar for all strikes. No vol smile or skew. |
| No holiday calendar | Weekends skipped; US public holidays not excluded — fixture adapter uses last available bar. |
| Synthetic price fixture | SPY prices committed as deterministic random walk; fetch from TWS for realistic results. |
| Non-SPY price fixtures | Need fetching via `twsTest` for QQQ/AAPL/MSFT/NVDA. |
| Commission model | Flat $0.65/leg. No tiered pricing or assignment risk. |
| BAG fill model | `BacktestOrderExecutionAdapter` fills immediately at target credit. Laddering not simulated. |
