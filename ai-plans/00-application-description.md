# Options Engine — Application Description

> **Living document.** Update this file whenever significant structural changes land.
> Last updated: 2026-05-12 (session 2)

---

## Purpose

An automated options trading engine that executes a **Bull Put Spread** (short put vertical / put credit spread) strategy against a watchlist of US equities using Interactive Brokers (IBKR) TWS API.

### Strategy in plain English

1. Sell an OTM put (collect premium) + Buy a lower-strike OTM put (cap the risk).
2. Keep the net credit. Both legs expire worthless if the stock stays above the sold strike.
3. Target entry when IV rank is elevated (options overpriced) and ~45 DTE.
4. Exit early at 50% profit, 50% loss, or when ≤14 DTE.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.1.0 |
| Runtime | Spring Boot 3.5.0 / Spring WebFlux (Netty) |
| Async | Kotlin Coroutines + Reactor |
| Broker API | IBKR TWS API (local socket, `TwsApi_debug.jar`) |
| Database | PostgreSQL 16 / JPA + Hibernate / Liquibase |
| Build | Gradle Kotlin DSL |
| API spec | OpenAPI 3 (springdoc, spec-first generation) |

---

## Architecture

Hexagonal (ports & adapters) with three concentric layers:

```
┌──────────────────────────────────────────────────────────┐
│  Inbound Adapters                                        │
│  REST API · Scheduled Jobs · Lifecycle hooks             │
│                        │                                 │
│  ┌─────────────────────▼──────────────────────────────┐  │
│  │              Domain (business logic)                │  │
│  │  ScannerService · TradeExecutionService             │  │
│  │  SpreadManagementService · IvRankService            │  │
│  │              │  (Port interfaces)  │                │  │
│  └──────────────┼─────────────────────┼───────────────┘  │
│                 │                     │                   │
│  ┌──────────────▼─────────────────────▼───────────────┐  │
│  │  Outbound Adapters                                  │  │
│  │  IBKR TWS · PostgreSQL · In-memory watchlist        │  │
│  └─────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

**Key rule:** Domain never imports from adapters. All cross-boundary communication goes through port interfaces.

---

## Main Business Classes

### Domain Models (`domain/models/`)

| Class | What it is |
|---|---|
| `Symbol` | Inline value class wrapping a ticker string (`SPY`, `AAPL`…) |
| `Money` | Value object for USD amounts — wraps `BigDecimal`, supports arithmetic |
| `OptionContract` | An option identified by (symbol, expiry date, strike, PUT/CALL) |
| `OptionGreeks` | delta, gamma, theta, vega, implied volatility from IBKR market data |
| `OptionType` | Enum `PUT` / `CALL` with IBKR wire codes |
| `IvRank` | Percentile rank (0–100) + `currentIv: Double` (raw IV value used for BS fallback) + calculation timestamp |
| `HistoricalBar` | One daily bar: date, closing price, IV value |
| `ConnectionStatus` | Live IBKR connection state snapshot |

### Account Models (`domain/features/account/`)

| Class | What it is |
|---|---|
| `AccountDetail` | Live account snapshot: totalCapital, availableFunds, unrealizedPnL, excessLiquidity |
| `AccountPosition` | Raw IBKR position: account ID, symbol, secType, currency, expiry, strike, optionRight, quantity, avgCost |
| `AccountPort` | Port for the live `StateFlow<AccountDetail?>` feed |
| `PositionsPort` | Port: `suspend fun getPositions(): List<AccountPosition>` — one-shot request/response |

### Strategy Models (`domain/features/spread/`)

| Class | What it is |
|---|---|
| `BullPutSpread` | The core position: sold leg + bought leg, net credit, max risk, status, timestamps, entry context (IV rank, underlying price) |
| `SpreadLeg` | One leg of the spread: contract, BUY/SELL action, premium, fill order ID |
| `SpreadStatus` | `OPEN` → `CLOSED_PROFIT` / `CLOSED_STOP` / `CLOSED_TIME` / `CLOSED_MANUAL` |

### Domain Services

#### `ScannerService` — Entry decision engine
Runs on a cron (every 15 min, 10 am–3 pm ET, Mon–Fri). For each watchlist symbol:

1. Skip if max open spreads reached or symbol already in-flight / open.
2. Fetch IV rank — reject if below threshold (default 30%).
3. Find expiry closest to preferredDte (45 days) within [minDte=30, maxDte=50].
4. Fetch option chain with Greeks — filter to delta range [0.10, 0.20], pick strike closest to targetDelta (0.15).
5. Find the bought strike: highest available strike that is ≥ spread-width (5 USD) below the sold strike.
6. Validate net credit ≥ minCreditPerShare (0.30 USD) and risk ≤ 2.5% of capital.
7. Fire-and-forget `TradeExecutionService.execute()` in a dedicated coroutine scope.

#### `TradeExecutionService` — Async combo order execution
Places a BAG (combo) limit order and manages it in a real-time event loop until filled or timed out (15 min):

- Merges three async streams via a `Channel<ExecutionEvent>`:
  - **Underlying price ticks** → drift guard: abort if underlying moves > driftProtectionPct (1%) from entry.
  - **Spread credit ticks** → price ladder: every N ticks (default 5) with no fill, lower the limit price by one tick. Abort if price would fall below floor credit.
  - **Order fill events** → break the loop on FILLED.
- On fill: persist the new `BullPutSpread` to PostgreSQL with entry metadata.
- Pre-trade guards: checks in-flight exposure, available capital, and leg bid-ask spread liquidity.

#### `SpreadManagementService` — Exit monitoring + manual close
Runs every 60 seconds. For each open spread:

1. Fetch current mid price of both legs via snapshot market data.
2. Calculate current spread value = sold leg mid − bought leg mid.
3. Check exit triggers:
   - **Take-profit**: spread value ≤ credit × (1 − 50%) → buy back sold leg, sell back bought leg → `CLOSED_PROFIT`
   - **Stop-loss**: spread value ≥ credit + maxRisk × 50% → same close sequence → `CLOSED_STOP`
   - **Time exit**: DTE ≤ 14 → close at market → `CLOSED_TIME`

Also handles manual close requests (via API):
- **`softClose(id)`** — places limit close orders at mid price → `CLOSED_MANUAL`
- **`forceClose(id)`** — places market close orders → `CLOSED_MANUAL`
- Returns `ManualCloseResult` sealed class: `Closed(spread)`, `NotFound`, `AlreadyClosed`

#### `TradingKillSwitch` — Runtime pause flags
Spring component (initialized from `ScannerConfig`) with two `@Volatile` booleans:
- `scannerPaused` — when `true`, `ScannerScheduler` skips cron ticks (no new entries opened)
- `monitorPaused` — when `true`, `SpreadMonitorScheduler` skips cron ticks (no automatic exits)

#### `IvRankService` — IV percentile calculation
Fetches 365 days of daily IV bars for a symbol, computes:

```
ivRank = (currentIV − minIV(365d)) / (maxIV(365d) − minIV(365d)) × 100
```

Result (rank + raw `currentIv`) cached 60 minutes (configurable). The `currentIv` value is used by `IbkrOptionChainAdapter` as the volatility input for the Black-Scholes fallback.

### Execution Results (`domain/features/execution/`)

| Class | What it is |
|---|---|
| `TradeExecutionRequest` | Input to the execution engine: both contracts, target credit, floor credit, risk per share, initial bid/ask snapshots |
| `TradeExecutionResult` | Output: `ExecutionOutcome` (FILLED / DRIFTED / FLOOR_REACHED / TIMED_OUT / PRE_TRADE_REJECTED) + achieved credit + order ID |

---

## Configuration (`ScannerConfig`)

All strategy parameters live in `application.yml` under the `scanner:` key and are bound to `ScannerConfig`. Nothing is hardcoded in business logic.

| Parameter group | Key settings |
|---|---|
| Entry filters | `ivRankThreshold=30`, `minDte=30`, `maxDte=50`, `preferredDte=45`, `targetDelta=0.15`, `deltaMin=0.10`, `deltaMax=0.20` |
| Spread construction | `spreadWidthUsd=5.0`, `minCreditPerShare=0.30`, `maxRiskPercent=0.025`, `maxOpenSpreads=5` |
| Exit rules | `takeProfitPercent=0.50`, `stopLossPercent=0.50`, `timeProfitDte=14` |
| Execution | `driftProtectionPct=0.01`, `floorCreditBuffer=0.50`, `executionTimeoutMinutes=15`, `ticksBeforePriceAdjust=5` |
| Leg liquidity | `maxLegBidAskSpreadPct=0.30` |
| Order chasing | `orderChaseTimeoutMinutes=5`, `orderChaseMaxRetries=3`, `orderChasePriceStep=0.03` |
| Kill-switch defaults | `scannerPaused=false`, `monitorPaused=false` |
| Watchlist | `[SPY, QQQ, AAPL, MSFT, AMZN]` |
| Cron | `0 */15 10-15 * * MON-FRI` |
| Cache TTLs | `ivHistoryDays=365`, `ivCacheTtlMinutes=60`, `optionParamsCacheTtlHours=24` |

---

## IBKR Adapter Layer

The broker adapter is split into focused components under `adapters/outbound/ibkr/`:

### Connection
- **`IbkrConnection`** — Raw socket connect/disconnect, message reader thread.
- **`IbkrConnectionManager`** — Implements `ConnectionPort`; mutex-guarded connect; runs watchdog coroutine for auto-reconnect (10 s interval).
- **`IbkrEWrapper`** — Implements `EWrapper`; pure callback dispatcher — routes each of 100+ IBKR callbacks to the correct registry. No business logic.

### Registries (async request tracking)
Six focused registries, all injecting the shared **`IbkrIdCounter`** for request IDs:

| Registry | Tracks |
|---|---|
| `IbkrHistoricalDataRegistry` | Pending `reqHistoricalData` requests → `Flow<HistoricalBar>` callbacks |
| `IbkrContractRegistry` | Pending `reqContractDetails` + `reqSecDefOptParams` → expirations/strikes |
| `IbkrMarketDataRegistry` | Pending snapshot requests, continuous subscriptions, tick-by-tick bid/ask streams |
| `IbkrOrderRegistry` | Pending order fills → `CompletableDeferred<OrderStatus>`; owns the order ID counter (seeded from `nextValidId`) |
| `IbkrAccountRegistry` | Account value callback dispatch (no pending map) |
| `IbkrPositionsRegistry` | Accumulates `AccountPosition` list from `position()` callbacks; completes `CompletableDeferred` on `positionEnd()` |

`IbkrMarketDataRegistry` error handling: IBKR errors **354** (no live subscription) and **10197** (competing live session) never send `tickSnapshotEnd` for options, so the registry completes the pending deferred immediately with whatever snapshot arrived. The caller receives an empty snapshot (`delta=NaN`) and the option chain adapter triggers the BS fallback.

`MarketDataSnapshot` is an immutable `data class` (all `val`). Each tick produces a new instance via `copy()`, held in a `@Volatile var` reference — safe for the IBKR reader thread writing, coroutine threads reading.

### Account adapters
- **`IbkrAccountAdapter`** — Implements `AccountPort`; exposes live `StateFlow<AccountDetail?>` populated from IBKR account value callbacks.
- **`IbkrPositionsAdapter`** — Implements `PositionsPort`; calls `reqPositions()` under a mutex with a 10 s timeout; cancels the subscription on completion or error.

### Market data adapters
- **`IbkrMarketDataAdapter`** — Snapshot queries: underlying price + option mid.
- **`IbkrMarketTickAdapter`** — Streaming: `Flow<Double>` underlying price, `Flow<SpreadCreditTick>` for both legs simultaneously (bid/ask via `reqTickByTick` + Greeks via continuous `reqMktData`).
- **`IbkrOptionChainAdapter`** — Fetches OTM put chain with Greeks for candidate strikes. Fetches `candidateStrikeCount` strikes near the target delta, plus the corresponding bought-leg strike for each, so `ScannerService` always finds both spread legs without a second round-trip. When IBKR returns no Greeks (paper account, error 354 or 10197), falls back to **Black-Scholes** pricing using `IvRankService.currentIv`. `BlackScholes` object lives in `domain/features/market/` and provides `putPrice`, `putDelta`, `gamma`, `putTheta`, `vega`, `impliedVol`.
- **`IbkrHistoricalDataAdapter`** — Fetches daily IV or price bars as a `Flow`.

### Order adapters
- **`IbkrOrderExecutionAdapter`** — Implements `OrderExecutionPort`: places BAG (combo) limit orders, awaits fill, cancels and re-submits on price improvement.
- **`IbkrOrderAdapter`** — Implements `OrderPort`: places single-leg limit orders for spread close sequences.
- **`OrderChaseService`** — Price-chasing loop: on timeout, lowers limit price and resubmits up to `maxRetries` times.

### Caches
- **`IbkrContractCache`** — Contract IDs (conId) per option key; evicts expired entries lazily.
- **`IbkrOptionParamsCache`** — Expirations + strikes per symbol; 24-hour TTL to avoid IBKR pacing limits.

---

## Persistence

Single table: `spread_positions`

| Column | Notes |
|---|---|
| `id` (UUID) | Auto-generated primary key |
| `symbol`, `status` | Ticker + `SpreadStatus` string |
| `sold_strike`, `bought_strike` | `DECIMAL(10,2)` |
| `expiry_date` | `DATE` |
| `credit_per_share`, `max_risk_per_share` | `DECIMAL(10,4)` |
| `quantity` | Number of contracts |
| `sold_order_id`, `bought_order_id` | IBKR order IDs |
| `iv_rank_at_entry`, `underlying_price_at_entry` | Entry context |
| `opened_at`, `closed_at` | Timestamps |
| `close_reason`, `close_price_per_share` | Exit context |

Managed by Liquibase (`db.changelog-master.yaml`). Schema validated on startup (`ddl-auto: validate`).

---

## REST API

Server on port 8081, context path `/options`. OpenAPI spec auto-generated from YAML specs in `openapi/` directory.

| Endpoint | Description |
|---|---|
| `GET /options/health` | IBKR connection status |
| `GET /options/account` | Account overview: capital, P&L, engine-tracked spreads, all IBKR positions |
| `POST /options/scanner/run` | Fire-and-forget scan (returns 202) |
| `GET /options/scanner/status` | IV rank snapshot, open spread count, pause state |
| `POST /options/scanner/pause` | Pause new-entry scanner (no new trades opened) |
| `POST /options/scanner/resume` | Resume new-entry scanner |
| `POST /options/monitor/pause` | Pause spread exit monitor (no automatic closes) |
| `POST /options/monitor/resume` | Resume spread exit monitor |
| `GET /options/spreads?status=OPEN` | Stream open/all spreads |
| `GET /options/spreads/{id}` | Single spread by UUID |
| `POST /options/spreads/{id}/close` | Soft-close at mid price (limit orders) → `CLOSED_MANUAL` |
| `POST /options/spreads/{id}/close-force` | Force-close at market price → `CLOSED_MANUAL` |

OpenAPI docs (JSON) available at `GET /options/v3/api-docs` — Swagger UI is disabled due to a springdoc + Spring Boot 3.5.0 incompatibility (issue: pattern `/swagger-ui/**/*swagger-initializer.js` rejected by Spring Framework 6.2's stricter `PathPatternParser`).

---

## Test Strategy

| Test type | Where | Notes |
|---|---|---|
| Unit tests with mocks | `src/test/kotlin/**/` | MockK for mocking; no Spring context |
| Integration / backtest | `src/test/kotlin/cz/solvina/options/backtest/` | Full domain run against synthetic Black-Scholes priced option chains; no IBKR needed |
| Live fixture fetch | `src/test/kotlin/cz/solvina/options/fixtures/FixtureFetchTest.kt` | `@Tag("tws")` — requires live TWS paper session; saves JSON/CSV fixtures for offline use |

Run all non-TWS tests: `./gradlew test` (TWS tag excluded by default in `build.gradle.kts`).

---

## Deployment

Runs on a Raspberry Pi at `192.168.0.107`. Three processes:

| Process | How it runs |
|---|---|
| PostgreSQL 16 | Docker Compose (`docker-compose.rpi.yml`) |
| IB Gateway | Docker Compose (`ghcr.io/gnzsnz/ib-gateway:stable`), paper account, port 7497 |
| Spring Boot engine | systemd service `options-engine`, JAR at `~/options/engine.jar` |
| React frontend | nginx, static files at `~/options/frontend/`, proxies `/api/` → `localhost:8081/options/` |

- `application-rpi.yml` Spring profile overrides IBKR host to `localhost` (gateway is co-located in Docker).
- IBKR credentials in `~/options/.env.rpi`, loaded by `docker compose --env-file .env.rpi`.
- Auto-reconnect watchdog handles IB Gateway daily restart (~23:45 ET).
- Deploy with `./deploy.sh` from the dev machine (builds locally, uploads, restarts services).
- App logs: `journalctl -fu options-engine`
