# Trading Engine

The engine is a Kotlin/Spring Boot application (hexagonal ports-and-adapters) that trades
automatically through Interactive Brokers (IB Gateway). It runs three strategies — bull put
spreads and bear call spreads on options, and a bull-flag breakout strategy on stocks — on top of
the shared infrastructure documented here: market data, order execution, risk monitoring,
recovery, alerting, and a React frontend.

**Source**: `/home/solvina/projects/options/engine/` (Gradle-built)  
**Binary**: `/home/solvina/options/engine.jar` (deployed via `deploy.sh`)  
**Service**: `options-engine.service` (systemd, Spring profile `rpi`)  
**Frontend**: `/home/solvina/projects/options/frontend/` (React + OpenAPI-generated clients)

Strategy rules and their current parameters: [STRATEGY_OPTIONS.md](STRATEGY_OPTIONS.md) and
[STRATEGY_STOCKS.md](STRATEGY_STOCKS.md). Work in progress and open problems: [CURRENT.md](CURRENT.md).
Parameter values live in `engine/src/main/resources/application.yml` (+ `application-rpi.yml`
profile overrides). This document describes *mechanics*, not values, so it doesn't rot as
parameters are tuned.

---

## Architecture

- **Domain** (`domain/features/*`): strategy logic, exit rules, scanners, risk services — no IBKR
  or persistence types; talks outward only through **ports** (interfaces).
- **Outbound adapters** (`adapters/outbound/*`): IBKR (TWS API via EClientSocket/EWrapper +
  registries correlating async callbacks), Postgres (JPA + Liquibase), InfluxDB, Telegram/email.
- **Inbound adapters** (`adapters/inbound/*`): REST API (OpenAPI-first — specs in
  `engine/openapi/*.yaml` generate the Kotlin interfaces and the frontend clients) and scheduled
  jobs (scanner cron, spread monitor, reconciliation, dividend refresh, regime warmup).
- **Fatal lockout** (`FatalLockoutService` + `GuardedEClientSocket`): a latched fatal condition
  (e.g. gateway logged into a different account than configured) blocks ALL `placeOrder` calls at
  the socket, fires a CRITICAL alert, and shows on `/health/fatal` + a red frontend banner.
  Cleared only by fixing the cause and restarting.
- **Market data modes**: live (`reqMarketDataType(1)`) or delayed
  (`ibkr.connection.use-live-market-data=false` → type 3). Delayed tick IDs are normalized in
  `IbkrMarketDataRegistry`; tick-by-tick and most generic ticks are unavailable on delayed, and
  real-time bars (flag strategy) require a live subscription. Dividend data (IB_DIVIDENDS tick)
  is also live-only — a delayed deployment reads it from the prod engine's DB instead
  (`dividends.remote.*`, health recorded in `dividend_sync_status`).

### Execution Flow

1. **Scan Phase** (every 15 min)
   - Calculate IV Rank for all 30+ stocks
   - Select candidates with IV Rank > 45%
   - For each candidate, calculate strike prices and spreads
   - Queue spreads for execution

2. **Execution Phase** (per spread)
   - Validate fresh market prices (within $0.05 of target credit)
   - Submit order to IBKR (BAG order for US, leg-by-leg for EU)
   - Monitor for fills, drift, and price movement
   - Ladder price down if market moves away
   - Cancel if floor credit reached or timeout exceeded

3. **Monitoring Phase** (post-entry)
   - **Take-Profit**: Exit when spread value ≤ 50% of entry credit (e.g., $0.75 on $1.50 credit)
   - **Stop-Loss**: Exit when spread value ≥ 200% of entry credit (e.g., $3.00 loss on $1.50 credit)
   - **Time Exit**: Exit at 21 DTE (decay advantage diminishes, gamma risk increases)
   - **Drift Protection**: Abort if underlying moves > 5% during entry submission
   - Quote health monitoring (Phase 1): Log LIVE/STALE/BLIND state transitions
   - Monitor for partial fills (hedge risk) — especially EU leg-by-leg orders

---

## Order Execution

### Native Combo Orders (US Markets)

**Markets**: CBOE, ISE  
**Order Type**: BAG (basket order with both legs)  
**How it works**:

1. Submit single atomic order to exchange's combo book:
   ```
   Action: BUY
   Leg 1: SELL 100 shares of 1340P
   Leg 2: BUY 100 shares of 1330P
   Limit Price: -1.84  (negative = net credit)
   ```

2. Exchange guarantees both legs fill together or neither fills

3. IBKR's SmartRouter routes across best price across CBOE/ISE

**Implementation**: `NativeComboOrderStrategy.kt`

### Leg-by-Leg Orders (EU Markets - EUREX)

**Markets**: Frankfurt DAX, Euronext  
**Challenge**: EUREX has no native combo book  
**How it works**:

1. Submit SELL leg to order book first
2. Wait for partial fill (just the short)
3. Submit BUY leg separately

**Risk**: Partial fill if market moves between steps → naked short position  
**Current Status**: Functional but fragile (0.8% timeout rate on ASML)

**Improvement Plan**: Migrate to BAG contract definition so IBKR treats as unified spread (requires changes to contract building logic)

**Implementation**: `LegByLegOrderStrategy.kt`

---

## Market Data & Quote Monitoring

### Phase 1: Quote-Age Monitoring (Active — 2026-06-26)

Monitors quote freshness and classifies data health during exit decisions:

- **LIVE**: Quote age < 60 seconds (fresh data)
- **STALE**: 60–300 seconds (warning level, logged)
- **BLIND**: ≥ 300 seconds (critical level, logged, no trading action yet)

**Features**:
- Timestamp on every market data tick (bid, ask, greek updates)
- Age computed in real-time during exit checks
- Transitions logged as WARN/CRITICAL
- No automated trading action (observability only)

**Why it matters**: Prevents silent blindness when quote stream stalls. Enables Phase 2 risk-only fallback.

**Code**: `MarketDataSnapshot.asOf`, `QuoteHealthService`, `SpreadManagementService.checkSpreadExit()`

### Phase 2: Black-Scholes Risk-Only Fallback (Pending)

When quotes are BLIND (> 5 min stale) but underlying is fresh:
- Synthetic BS pricing for stop-loss exits ONLY
- Never for take-profit or entry
- 2-cycle hysteresis (requires 2 consecutive BLIND periods)
- Feature-gated, dry-run mode first
- Designed as emergency circuit-breaker, not primary pricing model

**Status**: Code ready (`QuoteHealthService`, config keys in `application.yml`), awaiting 2–3 sessions of Phase 1 validation.

---

## Price Execution Logic

### Quote Freshness Validation

Before submitting any order, the engine validates market prices:

1. **Capture target credit** from scanner's IV analysis
2. **Wait 500ms** for fresh market data to settle
3. **Take the first fresh quote tick** — up to a 3s wait; if no tick arrives, abort `MARKET_MOVED_TOO_FAR`
4. **Check drift**: If mid-credit drifted >$0.10 from target, abort with `MARKET_MOVED_TOO_FAR`
5. **Calculate bid credit**: `soldBid - boughtAsk` (widest spread, safest entry)
6. **Compare to floor**: Use max(bidCredit, floorCredit) as submission price

**Why this matters**: Prevents submitting orders at stale prices that IBKR will reject

**Code**: `TradeExecutionService.calculateFreshCredit()` — consumes the first usable tick via
`stream.first()`. (Until 2026-06-14 it collected the hot quote stream inside a 3s `withTimeout`
without breaking, so the timeout always fired and the computed credit was discarded — blocking
every entry. That was the **E1** no-fill root cause.)

### Price Laddering

If market moves away during order submission:

1. **Initial order** submitted at calculated credit
2. **Wait for 3 market ticks** with data
3. **If no fill** after 3 ticks, **ladder down** by one tick size:
   - Tick size: $0.01 for prices < $3.00, $0.05 for higher
   - Example: Submitted at $0.90 → ladder to $0.85
4. **Replace order** with new price via `replaceComboWithNewPrice()`
5. **Repeat** until filled or floor reached

**Why this matters**: Chases price down to improve fill probability without going below risk limits

**Code**: `TradeExecutionService.ladder()` and event loop

### Floor Protection

Sets a minimum credit below which the engine won't execute:

- **Default floor**: 50% of target credit
- **Example**: Target $1.00 credit → floor = $0.50
- **When floor reached**: Abort with `FLOOR_REACHED` outcome (no fill attempt)

**Why this matters**: Prevents entering spreads that have deteriorated too much to be profitable

---

## Risk Management & Monitoring

### Orphan Detection & Position Reconciliation

The engine continuously reconciles IBKR account positions against its managed OPEN spreads:

**What it does**:
- Every 5 minutes: Compare actual IBKR positions vs engine-tracked OPEN spreads
- Detect orphaned positions (unmanaged by engine)
- Alert to Telegram when orphans found
- Auto-adopt clean spreads (both legs matching) on startup recovery

**Why it matters**: 
- Prevents accumulation of stranded positions from failed recovery or partial fills
- Ensures visibility: if orphans appear, Telegram alerts immediately
- Categorizes orphans: naked shorts, inverted pairs, stock assignments, etc.

**Code**: `PositionReconciliationScheduler`, `OrphanPositionDetector`, `TelegramAlertAdapter`, `StartupRecoveryService`

### Drift Protection

Underlying price movement threshold during order submission:

- **Maximum drift**: 5% from current price at entry (configurable: `drift-protection-pct`)
- **Example**: ASML at $500 → abort if ASML moves to $525 or $475 during order submission
- **Why**: Protects against large adverse price moves that invalidate spread pricing
- **Conservative tuning**: Increased from 1% → 5% (2026-06-12) to improve fill rates on volatile names

**Code**: Checked in event loop during execution

### Unhedged-Leg Protection

The engine prevents naked exposure **by construction**, rather than relying on post-hoc
partial-fill cleanup:

- **US (native combo / BAG)**: a single atomic order — both legs fill together or neither does,
  so a partial fill cannot occur.
- **EU (leg-by-leg)**: the protective **long** leg is submitted and confirmed filled **first**,
  then the short. Worst case is a paid-for long put (bounded debit), never a naked short. If the
  short fails after the long fills, the entry is recorded as `BROKEN_LONG_ONLY` (surfaced via
  `StrandedLongLegException`) for manual handling, and `verifyBothLegsFilled` gates the OPEN
  transition.

**Code**: `LegByLegOrderStrategy.kt`, `PositionReconciliationService.kt`, `TradeExecutionService`
(BROKEN_LONG_ONLY handling). *(The former `PartialFillDetectionService` was unwired and removed
2026-06-14 — its role is covered by the above.)*

### Contract Lookup Timeout

IBKR contract detail requests (conId lookups):

- **Timeout**: the cache awaits IBKR for 5s internally; the order strategies wrap the call at 6s so
  the cache's own timeout governs and cleans up its in-flight state. (A sub-second wrapper used to
  cancel the cache mid-lookup and orphan its in-flight deferred, hanging later callers — **E3**.)
- **In-flight dedup**: concurrent lookups for the same key share one request (`putIfAbsent`); the
  entry is cleared on completion *or* cancellation so later callers never await a dead deferred.
- **Negative cache**: a key with no contract is remembered for 10 minutes (not the whole trading
  day), so a transient empty response doesn't block a strike — **E5**.

**Code**: `IbkrContractCache.kt`

---

## Caching Strategy

### Option Chain Cache (IbkrOptionParamsCache)

Holds option chain metadata (expirations, strikes, available contracts):

- **TTL**: 60 minutes (expires after 1 hour)
- **Refresh trigger**: Cache miss OR TTL expired
- **Why needed**: As underlying price moves, far-OTM/ITM strikes become illiquid and are delisted by exchange
  - Example: AMD moves $20 → strikes identified 1 hour ago no longer trade
  - Without refresh: Engine would try to trade phantom contracts → Error 200 "No security definition"
- **Implementation**: Lazy refresh on-demand, not proactive (reduces IBKR API load)

**Code**: `IbkrOptionParamsCache.kt`

---

## Database Schema

The schema is defined by Liquibase migrations in
`engine/src/main/resources/db/changelog/` (one `vN__*.yaml` per change, included from
`db.changelog-master.yaml`) — that directory is the source of truth. Main tables:
`bull_put_spreads`, `bear_call_spreads` (one row per spread with entry/exit journaling),
`flag_positions` (stock strategy, with extended journal columns), `instrument_universe`
(per-symbol config + dividend data), `iv_rank_cache`, `dividend_sync_status` (prod→paper relay
health), `backtest_run`, `execution_log` (currently unwired — see CURRENT.md).

### Schema Conventions

- **Use TEXT for all string columns** unless there is a hard protocol constraint
  - Never use `VARCHAR(N)` for human-readable fields (error messages, reasons, descriptions)
  - IBKR exceptions and internal messages routinely exceed any reasonable length guess
- **Entity annotations**: `@Column(columnDefinition = "TEXT")`
- **Liquibase migrations**: Use `newDataType: TEXT` when widening columns
- **Performance**: PostgreSQL stores short TEXT identically to VARCHAR; no penalty

---

## Logging & Monitoring

### Log Files

**Trade Decisions** (`/home/solvina/options/trades.*.log`):
- Human-readable daily logs
- Format: `SKIP`, `CANDIDATE`, `ABORTED`, `FILLED`, `EXITED` decisions
- Useful for: Analyzing strategy performance, debugging skipped candidates

**Raw API** (`/home/solvina/options/tws-raw.log`):
- JSON output from IB Gateway API
- All contract details, order submissions, fills, cancellations
- Useful for: Debugging contract lookup issues, tracing order execution

### Monitoring Commands

```bash
# Watch trade decisions
grep "CANDIDATE\|ABORTED\|FILLED" /home/solvina/options/trades.*.log

# Watch raw IBKR API
tail -f /home/solvina/options/tws-raw.log | jq '.'

# Watch engine logs via systemd
journalctl -u options-engine.service -f

# Check engine health
curl -s http://localhost:8081/health
```

### Service Management

```bash
# View current status
systemctl status options-engine.service

# Restart engine (deploys new JAR)
systemctl restart options-engine.service

# Monitor recent logs
journalctl -u options-engine.service -n 100

# View service configuration
systemctl cat options-engine.service
```

---

## Development & Deployment

### Build

```bash
cd /home/solvina/projects/options/engine

# Full build with all checks (REQUIRED before committing)
./gradlew build

# Quick build without tests
./gradlew build -x test

# Format code (ktlint)
./gradlew ktlintFormat
```

**Important**: Always run `./gradlew build` (full) before final commit. ktlint style checks are mandatory.

### Deploy

```bash
# From the repo root — builds engine + frontend, uploads, restarts services,
# brings up docker compose (db, ib-gateway, loki, grafana, alloy):
./deploy.sh
```

Deployment gotchas that have bitten before:
- `deploy.sh` runs compose with `--env-file .env.rpi` — gateway credentials live in **both**
  `/home/solvina/options/.env` and `/home/solvina/options/.env.rpi`; keep them in sync.
- The engine's systemd unit reads `/home/solvina/options/engine.env` (Telegram/email
  credentials). Compose `.env` files are NOT visible to the engine process.
- IB Gateway logs in directly as the **paper username** (`svzxsu299`); the live username +
  paper mode is rejected by IBKR ("multiple Paper Trading users").

---

## Configuration

- **`application.yml`** (packaged): all engine parameters — scanner/strategy settings, IBKR
  connection, quote monitoring, regime gating, alerts, dividend relay. Inline comments document
  each key.
- **`application-rpi.yml`** (packaged, profile `rpi`): per-box overrides (paper account flag,
  EU instrument venue config, watchlist).
- **External override** (no rebuild): a file at `/home/solvina/options/config/application.yml`
  overrides packaged values on restart — use sparingly, it silently wins over the jar.

Current known issues, open work, and planned improvements are tracked in
[CURRENT.md](CURRENT.md), not here.

