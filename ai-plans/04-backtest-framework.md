# Backtest Framework — Implementation Plan

## Status

**IMPLEMENTED** — `./gradlew build` passes. Smoke test runs a full Q1 2025 SPY simulation
and produces 3 closed trades. Price fixtures for non-SPY symbols must still be fetched from TWS.

---

## Goal

Run the real domain services (`ScannerService`, `SpreadManagementService`, `IvRankService`)
against historical data without any live IBKR connection. The same strategy logic that runs
in production executes unchanged — only the port implementations are swapped.

---

## Architecture

```
ScannerService ──────────────────────────────────────────┐
SpreadManagementService                                  │
IvRankService                                            │  unchanged domain code
      │                                                  │
      │ port interfaces                                  │
      ▼                                                  │
BacktestHistoricalDataAdapter  ← FixtureLoader ─ fixtures/iv/{SYMBOL}.csv
BacktestMarketDataAdapter      ← FixtureLoader ─ fixtures/prices/{SYMBOL}.csv
BacktestOptionChainAdapter     ← BlackScholes + IV fixture
BacktestAccountAdapter         ← in-memory StateFlow
BacktestOrderAdapter           ← immediate fills
BacktestSpreadAdapter          ← in-memory list
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

### Clock abstraction

**`MutableClock`** (`src/test/kotlin/.../backtest/MutableClock.kt`)

Extends `java.time.Clock`. Represents market open (09:30 ET) so DTE calculations
work the same as in production. `advanceTo(LocalDate)` steps the simulation forward.
Domain services that call `LocalDate.now(clock)` or `Instant.now(clock)` transparently
pick up the simulated date.

```kotlin
val clock = MutableClock(LocalDate.of(2025, 1, 2))
clock.advanceTo(LocalDate.of(2025, 2, 14))
```

Production wiring: `AppConfig` provides `Clock.systemDefaultZone()` as a Spring `@Bean`.

---

### Fixture loading

**`FixtureLoader`** (`src/test/kotlin/.../backtest/FixtureLoader.kt`)

Reads pre-fetched CSVs from the test classpath:

| Method | Source path | Columns |
|--------|-------------|---------|
| `loadIvBars(symbol)` | `fixtures/iv/{SYMBOL}.csv` | `date,iv` |
| `loadPriceBars(symbol)` | `fixtures/prices/{SYMBOL}.csv` | `date,close` |

Returns bars sorted by date ascending. Blows up with a clear error message if the fixture
file is missing ("run `./gradlew twsTest` to fetch it from TWS").

IV fixtures for SPY/QQQ/AAPL/MSFT/AMZN are committed (fetched Nov 2024 – Apr 2026).
Price fixtures must be fetched per-symbol via `FixtureFetchTest`.
A synthetic SPY price fixture covering the same date range is committed for the smoke test.

---

### Black-Scholes engine

**`BlackScholes`** (`src/test/kotlin/.../backtest/BlackScholes.kt`)

European put pricing — pure Kotlin, no external dependencies.

| Function | Returns |
|----------|---------|
| `putPrice(spot, strike, t, r, sigma)` | Fair value |
| `putDelta(spot, strike, t, r, sigma)` | Delta (≤ 0 for puts) |
| `gamma(spot, strike, t, r, sigma)` | Gamma |
| `putTheta(spot, strike, t, r, sigma)` | Theta per calendar day |
| `vega(spot, strike, t, r, sigma)` | Vega per 1-point vol move |
| `impliedVol(marketPrice, spot, strike, t, r)` | Newton-Raphson IV solver |

CDF via Hart's rational approximation (~7.5 significant digits for |x| ≤ 7.65).

**`BlackScholesTest`** — 19 unit tests:
- OTM put price against reference value (5.16 for SPY-like scenario)
- Delta sign invariants, ATM ≈ −0.45, deep ITM → −1, deep OTM → 0
- Gamma positive, peaks ATM
- Theta negative for all DTE
- Vega positive, per-1%-vol value in plausible range
- IV round-trip through `putPrice` (tolerance 1e-5)
- `impliedVol` returns null for sub-intrinsic price and at expiry
- Put-call parity holds

---

### Backtest adapters

**`BacktestHistoricalDataAdapter`** — implements `HistoricalDataPort`

Loads all IV bars from fixture once, caches per symbol. On each call filters to
`date <= clock.currentDate()` and takes the last N — matching what IBKR would
return for a `reqHistoricalData` call on the simulated day. The `IvRankService`
cache TTL (60 min) naturally misses on every simulated day (clock advances 24h).

**`BacktestMarketDataAdapter`** — implements `MarketDataPort`

- `getUnderlyingPrice`: most recent close on or before the clock date (handles weekends/holidays)
- `getOptionMid`: Black-Scholes pricing using flat IV surface + spot from price fixture.
  Calls at expiry return intrinsic value. Put-call parity used for calls.

**`BacktestOptionChainAdapter`** — implements `OptionChainPort`

- `getAvailableExpirations`: next 12 standard monthly expirations (3rd Friday of each month)
- `getOptionChain`: generates a strike grid (`candidateStrikeCount` OTM puts, $5 spacing),
  prices each via Black-Scholes using today's flat IV fixture. Bid/ask modelled as
  mid ± max(5% × mid, $0.05).

**`BacktestAccountAdapter`** — implements `AccountPort`

In-memory `MutableStateFlow<AccountDetail?>` initialised with a fixed capital amount.
`debit(amount)` / `credit(amount)` called by `BacktestEngine` after each trade so
`ScannerService`'s `maxRiskPercent` capital guard runs exactly as in production.

**`BacktestOrderAdapter`** — implements `OrderPort`

Always returns `FILLED` immediately. Maintains a `fills: List<FillRecord>` log.
`commissionPerLeg` (default $0.65) is read by the engine for P&L accounting.

**`BacktestSpreadAdapter`** — implements `SpreadPort`

Plain `MutableList<BullPutSpread>`. Assigns a `UUID` on `save()` if none provided.
`findOpen`, `findAll`, `countByStatus`, `findByStatus` query the list directly.

---

### Engine and result

**`BacktestEngine`** (`src/test/kotlin/.../backtest/BacktestEngine.kt`)

```kotlin
val result = engine.run(startDate, endDate)
```

Each market day (Mon–Fri):
1. `clock.advanceTo(date)`
2. `scanner.scan()` — finds new entries
3. `spreadManager.checkExits()` — closes at TP / SL / DTE
4. Detect newly opened spreads → `accountAdapter.debit(maxRiskPerContract)`
5. Detect newly closed spreads → `accountAdapter.credit(collateral + netPnl)`
6. Update peak capital and max drawdown

Commission model: 4 × `commissionPerLeg` per spread (open + close, 2 legs each).

**`BacktestResult`** / **`ClosedTrade`**

| Field | Description |
|-------|-------------|
| `totalPnl` | `finalCapital − initialCapital` |
| `tradeCount` | closed trades |
| `winRate` | fraction of trades where `pnlPerShare > 0` |
| `maxDrawdownPct` | largest peak-to-trough % decline during the run |
| `trades` | per-trade log with open/close dates, credit, close price, P&L/contract, reason |

`result.summary()` prints a one-block human-readable summary.

---

### Smoke test

**`BacktestSmokeTest`** (`src/test/kotlin/.../backtest/BacktestSmokeTest.kt`)

No Spring context. Wires all adapters and domain services manually and runs a
3-month simulation (2025-01-02 → 2025-03-31) on SPY.

Config overrides for the test:
- `ivRankThreshold = 20.0` (lower to guarantee entries with synthetic prices)
- `deltaMin/Max = 0.10–0.35` (wider band)
- `maxRiskPercent = 0.10`, `minCreditPerShare = $0.10`
- `maxOpenSpreads = 3`

Sample output from one run:
```
Backtest 2025-01-02 → 2025-03-31
Capital:   $50000 → $50072.10  (P&L: $72.10)
Trades:    3  Wins: 2  Win rate: 66.7 %
Max DD:    0.81 %

  SPY 2025-01-02→2025-02-28  CLOSED_TIME   credit=$0.9906 close=$0.5237  P&L=$47.69
  SPY 2025-02-21→2025-03-17  CLOSED_TIME   credit=$0.8012 close=$1.1455  P&L=$-34.43
  SPY 2025-03-17→2025-03-31  CLOSED_PROFIT credit=$0.9141 close=$0.2477  P&L=$66.64
```

Assertions verify structural correctness: result dates match, `winRate` in 0–1,
`maxDrawdownPct ≥ 0`, `finalCapital > 0`. P&L figures are not asserted (depend on fixture).

---

## Running

```bash
# Unit tests only (no TWS, no Spring)
./gradlew test

# Fetch real price fixtures from TWS (requires paper-trading session)
./gradlew twsTest
```

After fetching prices, re-run the smoke test for a realistic simulation. Replace the
synthetic `fixtures/prices/SPY.csv` with the fetched file.

---

## Limitations and next steps

| Item | Note |
|------|------|
| Flat IV surface | Uses the single daily IV bar as σ for all strikes and expirations. A term structure or skew model would be more realistic but requires more fixture data. |
| No holiday calendar | Weekends are skipped; US public holidays are not. The fixture adapter falls back to the last available bar, so this rarely matters. |
| Synthetic price fixture | SPY prices are a deterministic random walk, not real historical prices. Fetch with `twsTest` for realistic results. |
| Non-SPY price fixtures | `fixtures/prices/{SYMBOL}.csv` does not yet exist for QQQ/AAPL/MSFT/AMZN. |
| Commission model | Flat $0.65/leg. Does not model IBKR tiered pricing or assignment risk. |
| Pending: unit tests for domain services | `IvRankServiceTest`, `ScannerServiceTest`, `SpreadManagementServiceTest` against fixtures (noted in `02-fixture-fetch-tests.md`) are not yet written. |
