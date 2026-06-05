# Options Engine — Application Description

> **Living document.** Update this file whenever significant structural changes land.
> Last updated: 2026-06-05

---

## Purpose

An automated trading engine executing two independent strategies against Interactive Brokers (IBKR):

1. **Bull Put Spread** — options credit strategy; sells put verticals when IV rank is elevated.
2. **Bull Flag Momentum** — intraday equity strategy; detects flag-and-pole breakouts on 5-min bars.

Both strategies share the same IBKR connection infrastructure, persistence layer, and Spring Boot process.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.1.0 |
| Runtime | Spring Boot 3.5.0 / Spring WebFlux (Netty) |
| Async | Kotlin Coroutines + Reactor |
| Broker API | IBKR TWS API (EClientSocket/EWrapper, `TwsApi_debug.jar`) |
| Database | PostgreSQL 16 / JPA + Hibernate / Liquibase |
| Time-series | InfluxDB (bar storage for flag backtest) |
| Build | Gradle Kotlin DSL |
| API spec | OpenAPI 3 (springdoc, spec-first generation) |
| Frontend | React + TypeScript, Vite, TailwindCSS, TanStack Query, generated OpenAPI client |

---

## Architecture

Hexagonal (ports & adapters) with three concentric layers:

```
┌──────────────────────────────────────────────────────────────────────┐
│  Inbound Adapters                                                    │
│  REST API (WebFlux) · Scheduled Jobs · ApplicationReady listener    │
│                          │                                           │
│  ┌───────────────────────▼────────────────────────────────────────┐  │
│  │              Domain (business logic)                            │  │
│  │  ScannerService · SpreadManagementService · SpreadAnalytics    │  │
│  │  FlagScannerService · FlagExecutionService · FlagManagement    │  │
│  │  IvRankService · TradeExecutionService (combo orders)          │  │
│  │              │  (Port interfaces only — no adapter imports)    │  │
│  └──────────────┼──────────────────────────────────────────────── ┘  │
│                 │                                                     │
│  ┌──────────────▼─────────────────────────────────────────────────┐  │
│  │  Outbound Adapters                                              │  │
│  │  IBKR (connection, market data, orders, bars)                  │  │
│  │  PostgreSQL (spreads, flags, universe, flag config)            │  │
│  │  InfluxDB (5-min bar storage)                                  │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

**Key rule:** Domain never imports from adapters. All cross-boundary communication goes through port interfaces.

---

## Strategy 1 — Bull Put Spread (options)

### How it works

1. Sell an OTM put (collect premium) + buy a lower-strike OTM put (cap the risk).
2. Keep the net credit. Both legs expire worthless if the stock stays above the sold strike.
3. Target entry when IV rank ≥ 45% and ~45 DTE.
4. Exit at 50% profit, full-credit stop (1× credit), or ≤ 14 DTE.

### Key domain services

**`ScannerService`** — entry decision engine, runs on cron (every 15 min, Mon–Fri 03:00–15:00 ET).

1. Skip if `maxOpenSpreads` reached or IBKR not connected.
2. Get active symbols from `UniversePort` (DB-backed, exchange-hours-filtered).
3. Per symbol: delegate to `ScanCandidateSelector` (IV rank, expiry, option chain, delta, credit, risk checks).
4. Fire-and-forget `TradeExecutionPort.execute()` in a dedicated coroutine scope.

**`TradeExecutionService`** — async BAG/combo order execution. Places a BAG limit order (both legs in one IBKR order), manages it via a real-time event loop:
- Monitors underlying price drift (abort if > `driftProtectionPct`).
- Ladders limit credit price down by one tick every `priceAdjustIntervalSeconds` until floor reached or filled.
- `entryCooldownMinutes` (default 4h) prevents immediate re-entry after a failed attempt.
- On fill: persists `BullPutSpread` to PostgreSQL with full entry context.

**`SpreadManagementService`** — exit monitoring + manual close.

- `checkExits()` runs every 60 s (via `SpreadMonitorScheduler`).
- Filters to symbols whose exchange is currently open (config-driven, not hardcoded).
- **All automated exits fire MARKET orders** (`forceCloseSpread`) — no limit/chase/CLOSING intermediate state.
- CLOSING state is only reached when a manual `softClose` limit order fails to fill.
- Exit triggers (per-instrument overrides possible from `InstrumentConfig`):
  - Take-profit: spread value ≤ credit × (1 − takeProfitPercent) = 50% → `CLOSED_PROFIT`
  - Stop-loss: spread value ≥ credit + credit × stopLossPercent = 2× credit → `CLOSED_STOP`
  - Time exit: DTE ≤ 14 → `CLOSED_TIME`
- Manual close: `softClose` (limit at mid → `CLOSING` → `CLOSED_MANUAL`), `forceClose` (market → `CLOSED_MANUAL`).

**`SpreadMonitorScheduler`** — guards: kill-switch `monitorPaused`, IBKR connected, `isAnyExchangeOpen()` (iterates `ibkr.exchanges` config map, not hardcoded).

### Status flow

```
OPEN → CLOSING (soft manual only) → CLOSED_PROFIT / CLOSED_STOP / CLOSED_TIME / CLOSED_MANUAL
```
Automated exits skip CLOSING entirely.

### P&L

`(creditPerShare - closePricePerShare) × 100 × quantity`

---

## Strategy 2 — Bull Flag Momentum (equities, intraday)

### How it works

1. Subscribe to 5-second real-time bars (`reqRealTimeBars`) for watchlist symbols at startup.
2. Aggregate 60 × 5-sec bars into 5-min candles in-memory.
3. Pattern detection (per-symbol `PatternDetector`, stateful FSM):
   - **Flagpole**: strong up-move ≥ `atrMultiplier` × ATR(14), with at least one volume spike > `volumeSpikeMultiplier` × VolumeMA(20).
   - **Flag consolidation**: ≤ `maxRetracementPct` (50%) retracement of pole height, ≤ `flagMaxBars` (20) 5-min bars, linear regression channel flat/downward-sloping.
   - **Breakout**: live 5-sec bar close or completed 5-min bar close above the upper resistance regression line.
4. On breakout signal, quality filters applied (skip first 90 RTH minutes, channel slope, pole/ATR ratio bounds, minimum retracement, minimum flag bars).
5. Entry: bracket order — stop-market BUY at resistance + OCA children (stop-loss sell + profit-target sell).
6. Position sizing: `riskPerTrade / (entryPrice - stopLossPrice)` shares.
7. Profit target: `entryPrice + 2 × (entryPrice - stopLossPrice)` (2:1 reward/risk).
8. Stop-loss: just below the lowest low of the flag.

### Watchlists

- US: `[SPY, QQQ, AAPL, MSFT, NVDA]` — RTH 09:30–16:00 ET
- EU: `[SAP, ASML, SIE, ALV]` — RTH 09:00–17:30 Berlin

Subscriptions start at `ApplicationReadyEvent`, resubscribe at market open cron (EU: 09:01 Berlin, US: 09:31 ET), and a 5-minute watchdog detects silent IBKR drops.

Historical bootstrap: `historicalBootstrapDays` (3) of 5-min bars fetched via `reqHistoricalData` and replayed through `PatternDetector` before live bars arrive.

### Order mechanics

- Parent: stop-market `BUY` / `tif=DAY` (expires if session ends without entry).
- Children: stop-market `SELL` (stop-loss) + limit `SELL` (profit target) / `tif=GTC`, OCA group.
- Parent timeout: 10 hours (one trading session).
- Child timeout: 30 days (safety net for GTC orders).

### EOD liquidation

`FlagMonitorScheduler` runs every 60 seconds and triggers `checkEodLiquidation` once the clock enters the configured window (`eodLiqMinutesBeforeClose`, default 15 min) before each session's close. Separately tracked for EU and US sessions.

### Status flow

```
PENDING → OPEN → CLOSED_PROFIT / CLOSED_STOP / CLOSED_EOD / CLOSED_MANUAL / ENTRY_TIMEOUT
```

### Trade journaling fields

`FlagPosition` records at entry: `actualEntryPrice`, `entrySlippage`, `highestPriceSeen` / `lowestPriceSeen` (watermarks), `atrAtEntry`, `volumeMaAtEntry`, `flagpoleVolumeRatio`, `vwapAtEntry`, `dayOpenPrice`, `channelSlope`, `breakoutType` ("FIVE_MIN" or "LIVE_BAR"), `stopDistancePct`.

At close: `maxFavorableExcursion`, `maxAdverseExcursion`, `rMultiple`, `mfeR`, `maeR`, `timeInTradeSeconds`.

### Kill switch

`enabled` field in `flag_trading_config` DB table, toggled via `POST /options/flags/scanner/pause|resume`. Persists across restarts.

---

## IBKR Communication Layer

### Connection

- **`IbkrConnection`** — raw socket, `EClientSocket`, `EReader` message pump (coroutine loop).
- **`IbkrConnectionManager`** — mutex-guarded connect/disconnect; auto-reconnect watchdog every `reconnect-interval-ms` (10 s).
- **`IbkrEWrapper`** — implements `EWrapper`; pure callback dispatcher; routes ~100 IBKR callbacks to the correct registry. All TWS wire events are also logged to a dedicated `TWS_RAW` logger at DEBUG level.
- Port: 7497 (paper). SOCAT relay inside the gnzsnz container: host 7497 → container 4004 → IB Gateway 4002.
- Market data type: `reqMarketDataType(3)` for paper without live subscription; `reqMarketDataType(1)` for paper with live subscription or live account.

### Registries

Six focused registries under `adapters/outbound/ibkr/registry/`:

| Registry | Tracks |
|---|---|
| `IbkrHistoricalDataRegistry` | `reqHistoricalData` → `Flow<HistoricalBar>` callbacks |
| `IbkrContractRegistry` | `reqContractDetails` + `reqSecDefOptParams` → option params/conIds |
| `IbkrMarketDataRegistry` | Snapshot requests, continuous subscriptions, real-time bars, tick-by-tick bid/ask |
| `IbkrOrderRegistry` | Order fills → `CompletableDeferred<OrderStatus>`; order ID counter seeded from `nextValidId` |
| `IbkrAccountRegistry` | Account value callbacks (NetLiquidation, AvailableFunds etc.) → `StateFlow<AccountDetail?>` |
| `IbkrPositionsRegistry` | Accumulates positions from `position()` callbacks; completes `CompletableDeferred` on `positionEnd()` |

Additional: `IbkrOpenOrdersRegistry`, `IbkrPnlRegistry` (P&L subscription for unrealized P&L per spread).

**`IbkrOrderRegistry` error handling:**
- Code 399 (after-hours order queued) — fail-fast, complete deferred as CANCELLED.
- Code 201/202 (rejected/cancelled) — complete deferred as CANCELLED; distinguish self-cancel (repricing) vs external rejection.
- `selfCancelledOrders` set prevents spurious error logs for intentional repricing cancels.

**`MarketDataRegistry`** error handling: errors 354 (no live subscription) and 10197 (competing session) complete snapshot deferred immediately with whatever partial data arrived; caller falls back to Black-Scholes.

### Order adapters

- **`IbkrBracketOrderAdapter`** (flag strategy) — submits 3 linked orders (parent + 2 OCA children), tracks fills via `IbkrOrderRegistry`.
- **`IbkrOrderExecutionAdapter`** (spread strategy) — submits BAG combo limit orders.
- **`IbkrOrderAdapter`** (spread close legs) — single-leg limit/market orders.
- **`OrderChaseService`** — price-chase loop for spread close orders: cancel → lower price by `orderChasePriceStep` → resubmit, up to `orderChaseMaxRetries`.

---

## Universe / Watchlist

`UniversePort` is backed by a PostgreSQL table (`instrument_universe`). Instruments are managed via the UI (`/universe`) or REST API. Each instrument can override global scanner defaults (IV rank threshold, DTE range, delta range, spread width, min credit, exit percentages).

`UniversePersistenceAdapter`:
- `getActiveSymbols()` — returns enabled instruments whose exchange is currently in RTH.
- `isMarketOpen(symbol)` — looks up `ibkr.instruments[symbol].marketExchange`, finds the exchange hours from `ibkr.exchanges`, checks current time.
- In-memory cache (`ConcurrentHashMap`) keeps the hot path allocation-free.

---

## Configuration

### `ScannerConfig` (`@ConfigurationProperties("scanner")`)

| Group | Key parameters |
|---|---|
| Entry filters | `ivRankThreshold=45`, `minDte=30`, `maxDte=50`, `preferredDte=45`, `targetDelta=0.15`, `deltaMin=0.10`, `deltaMax=0.20` |
| Spread construction | `spreadWidthUsd=5.0`, `minCreditPerShare=0.35`, `maxRiskPercent=0.025`, `maxOpenSpreads=5` |
| Exit rules | `takeProfitPercent=0.50`, `stopLossPercent=1.00` (full credit = 2× credit loss), `timeProfitDte=14` |
| Execution | `driftProtectionPct=0.05`, `executionTimeoutMinutes=15`, `priceAdjustIntervalSeconds=30`, `maxLegBidAskSpreadPct=0.15`, `entryCooldownMinutes=240` |
| Order chasing | `orderChaseTimeoutMinutes=3`, `orderChaseMaxRetries=1`, `orderChasePriceStep=0.01` |
| Schedulers | `cron=0 */15 3-15 * * MON-FRI`, `monitorDelayMs=60000` |
| Kill switches | `tradingEnabled=true`, `scannerPaused=false`, `monitorPaused=false` |

### `FlagStrategyConfig` (`@ConfigurationProperties("flag")`)

| Key parameters |
|---|
| `usWatchlist=[SPY, QQQ, AAPL, MSFT, NVDA]`, `euWatchlist=[SAP, ASML, SIE, ALV]` |
| `atrPeriod=14`, `atrMultiplier=2.0`, `volumeMaPeriod=20`, `volumeSpikeMultiplier=1.5` |
| `poleMinBars=5`, `poleMaxBars=10`, `flagMinBars=5`, `flagMaxBars=20`, `maxRetracementPct=0.50` |
| `historicalBootstrapDays=3` |
| Quality gates: `skipFirstRthMinutes=90`, `requireNegativeChannelSlope=true`, `minFlagpoleAtrMultiple=2.0`, `maxFlagpoleAtrMultiple=4.0`, `minFlagRetracementPct=0.25`, `minFlagBarsForEntry=7` |

### `ibkr.exchanges` (exchange hours, config-driven, not hardcoded)

```yaml
ibkr:
  exchanges:
    US:
      timezone: America/New_York
      open: "09:30"
      close: "16:00"
    EU:
      timezone: Europe/Berlin
      open: "09:00"
      close: "17:30"
```

### Runtime flag config (`flag_trading_config` DB table)

Single-row table. Fields: `riskPerTrade`, `maxOpenPositions`, `entryBlockMinutesBeforeClose`, `eodLiqMinutesBeforeClose`, `enabled`. Editable at runtime via `PUT /options/flags/config`.

---

## Persistence

### Tables

| Table | Purpose |
|---|---|
| `spread_positions` | Bull put spread lifecycle |
| `flag_positions` | Bull flag position lifecycle |
| `flag_trading_config` | Single-row runtime config for flag strategy |
| `instrument_universe` | Spread watchlist + per-instrument parameter overrides |

Managed by Liquibase (`db.changelog-master.yaml`). Schema validated on startup (`ddl-auto: validate`).

### `spread_positions` notable columns

`id`, `symbol`, `status`, `sold_strike`, `bought_strike`, `expiry_date`, `credit_per_share`, `max_risk_per_share`, `quantity`, `sold_order_id`, `bought_order_id`, `iv_rank_at_entry`, `underlying_price_at_entry`, `opened_at`, `closed_at`, `close_reason`, `close_price_per_share`, `underlying_price_at_exit`, `iv_rank_at_exit`, `last_spread_value`.

### `flag_positions` notable columns

`id`, `symbol`, `status`, `entry_order_id`, `stop_loss_order_id`, `profit_target_order_id`, `entry_price`, `stop_loss_price`, `profit_target_price`, `shares`, `risk_amount`, `actual_entry_price`, `entry_slippage`, `highest_price_seen`, `lowest_price_seen`, `realized_pnl`, `r_multiple`, `mfe_r`, `mae_r`, `time_in_trade_seconds`, `flagpole_height`, `flag_retracement`, `channel_slope`, `atr_at_entry`, `vwap_at_entry`, `breakout_type`, `market_session`, `opened_at`, `closed_at`, `close_reason`.

---

## REST API

Server port 8081, base path `/options`. OpenAPI JSON at `GET /options/v3/api-docs`.

### Spread endpoints

| Endpoint | Description |
|---|---|
| `GET /options/spreads` | List spreads (optional `?status=OPEN`) |
| `GET /options/spreads/{id}` | Single spread by UUID |
| `GET /options/spreads/analytics` | Win rate, P&L breakdown |
| `POST /options/spreads/{id}/close` | Soft-close at mid (limit) |
| `POST /options/spreads/{id}/close-force` | Force-close at market |
| `POST /options/scanner/run` | Trigger scan immediately (202) |
| `GET /options/scanner/status` | IV ranks, pause state, cron |
| `POST /options/scanner/pause` / `resume` | Toggle new-entry scanner |
| `POST /options/monitor/pause` / `resume` | Toggle automatic exit monitor |

### Flag endpoints

| Endpoint | Description |
|---|---|
| `GET /options/flags` | List flag positions (optional `?status=`) |
| `GET /options/flags/{id}` | Single position by UUID |
| `GET /options/flags/analytics` | Win rate, P&L, R-multiple breakdown |
| `GET /options/flags/config` | Runtime trading config |
| `PUT /options/flags/config` | Update runtime config |
| `POST /options/flags/scanner/pause` / `resume` | Toggle flag scanner (persisted to DB) |
| `POST /options/flags/{id}/close` | Manual close (PENDING: cancel orders; OPEN: cancel + market sell) |
| `GET /options/flags/scanner/status` | Per-symbol: subscription active, buffered candles, pattern state |

### Other

| Endpoint | Description |
|---|---|
| `GET /options/health` | IBKR connection status |
| `GET /options/account` | Account overview (capital, P&L, positions) |
| `GET /options/universe` | Instrument universe list |
| `POST /options/universe` | Add/update instrument |
| `DELETE /options/universe/{symbol}` | Remove instrument |
| `POST /options/api/backtest/flag` | Run flag backtest |
| `POST /options/api/historical/fetch` | Fetch historical bars into InfluxDB |

---

## Frontend

React/TypeScript SPA served by nginx from `/home/solvina/options/frontend/`. OpenAPI clients auto-generated under `src/generated/` per strategy API. All generated clients must have `baseUrl: '/api'` set in `main.tsx`.

| Route | Description |
|---|---|
| `/spreads/positions` | Open/closed spread table with current price and unrealized P&L |
| `/spreads/analytics` | Spread P&L charts and win-rate breakdown |
| `/scanner` | Scanner config, IV rank snapshot, kill switches |
| `/flags/positions` | Flag positions table + scanner config panel |
| `/flags/analytics` | Flag P&L charts |
| `/universe` | Instrument universe management |
| `/account` | Account overview |
| `/diagnostic` | IBKR connection probe |
| `/grafana/` | Grafana log explorer (reverse-proxied) |

---

## Deployment

Runs on Raspberry Pi 4 at `192.168.0.107` / Tailscale IP `100.65.216.36`.

| Process | How it runs |
|---|---|
| PostgreSQL 16 | Docker Compose (`options-db-1`) |
| IB Gateway | Docker Compose (`options-ib-gateway-1`, `ghcr.io/gnzsnz/ib-gateway:stable`) |
| InfluxDB | Docker Compose |
| Loki / Grafana / Alloy | Docker Compose (log aggregation) |
| Spring Boot engine | systemd `options-engine` (fat JAR, `-Xmx512m`) |
| nginx | systemd — serves frontend, reverse-proxies `/api/` → engine |

- `application-rpi.yml` Spring profile: IBKR host = `localhost`, `use-live-market-data=false` for delayed data.
- IBKR credentials in `~/options/.env.rpi` (not in git). IB Gateway auto-restarts ~23:45 ET; engine reconnect watchdog handles it.
- Deploy: `./deploy.sh` from dev machine (build → upload → restart).
- Logs: `journalctl -fu options-engine`; structured trade events via named loggers `TRADES` (spreads) and `FLAG_TRADES` (flags).

---

## Test Strategy

| Type | Location | Notes |
|---|---|---|
| Unit (mock) | `src/test/kotlin/**/` | MockK; no Spring context |
| Domain-level | `src/test/kotlin/cz/solvina/options/spread/SpreadManagementServiceTest` | Tests exit logic against fixture data |
| Flag backtest smoke | `src/test/kotlin/cz/solvina/options/backtest/BacktestSmokeTest` | Replays historical bars through `PatternDetector`, simulates bracket fills |
| TWS fixture fetch | `src/test/kotlin/.../fixtures/FixtureFetchTest` | `@Tag("tws")`, requires live paper session, writes CSVs/JSON to `src/test/resources/fixtures/` |

```bash
./gradlew test          # all non-TWS tests
./gradlew test -Dtests.tags=tws  # TWS fixture fetch (requires paper session)
```
