# Trading Engine Documentation

## Overview

The trading engine is a Kotlin/Java application (engine.jar, ~80MB) that runs on a Raspberry Pi and manages algorithmic trading of options spreads. It connects to Interactive Brokers (IB Gateway) and executes bull put spreads on equities when IV conditions are favorable.

**Source**: `/home/solvina/projects/options/engine/` (Gradle-built)  
**Binary**: `/home/solvina/options/engine.jar` (deployed)  
**Service**: `options-engine.service` (systemd)

---

## Trading Strategy

### Strategy Type: Bull Put Spreads

A bull put spread on a stock earning $1.88 per spread:
1. **SELL** 1 put at a higher strike (e.g., 1340P) — collect $1.88 premium
2. **BUY** 1 put at a lower strike (e.g., 1330P) — pay $0.04 premium
3. **Net**: Receive $1.84 credit if both legs fill

**Max Risk**: $10 per spread (1 spread = 100 shares × $0.10 strike width) at 1:100 leverage  
**Max Profit**: $1.84 per spread (collected credit)

### Current Live Strategy Parameters (2026-06-26)

| Parameter | Setting | Config Key | Notes |
|-----------|---------|------------|-------|
| **Entry Delta Target** | 0.30 | `scanner.target-delta` | Short put ~30-delta, ~$1.50 credit on $5 width |
| **Delta Band** | 0.25–0.30 | `scanner.delta-min/max` | Accept spreads in this delta range |
| **IV Rank Threshold** | > 45% | `scanner.iv-rank-threshold` | Trade only in elevated IV environments |
| **DTE Range** | 30–50 days | `scanner.min-dte/max-dte` | Prefer 45 DTE |
| **Spread Width** | $5.00 | `scanner.spread-width-usd` | Fixed width, standardized risk |
| **Min Credit** | $0.35/share | `scanner.min-credit-per-share` | Minimum $35 per spread |
| **Max Open Spreads** | 5 | `scanner.max-open-spreads` | Concurrent position limit |
| **Take-Profit** | 50% of credit | `scanner.take-profit-percent` | Exit at $0.75 on $1.50 credit |
| **Stop-Loss** | 200% of credit | `scanner.stop-loss-percent` | Exit at $3.00 loss on $1.50 credit ("breathing room") |
| **Time Exit** | 21 DTE | `scanner.time-profit-dte` | Close spreads at 21 DTE (earlier than 14 DTE) |
| **Drift Protection** | 5% | `scanner.drift-protection-pct` | Abort if underlying moves > 5% during entry |
| **Max Bid/Ask Spread** | 15% | `scanner.max-leg-bid-ask-spread-pct` | Only trade liquid options |
| **Execution Timeout** | 15 min | `scanner.execution-timeout-minutes` | Give up and rescind if not filled in 15 min |
| **Order Chase Timeout** | 3 min | `scanner.order-chase-timeout-minutes` | Ladder for 3 min max |
| **Quote Monitoring** | Phase 1 active | `quote-monitoring.*` | Track quote age (LIVE/STALE/BLIND), log transitions, no trading action |
| **Reconciliation** | Every 5 min | `reconciliation.delay-ms` | Detect orphaned positions, alert to Telegram |
| **Market Hours** | 9am–11pm CEST | — | Covers EU (9-17:30) + US (3:30pm–4pm ET) markets |

**Strategy Profile**: ~72–76% win rate, ~4 wins erase 1 max loss ($3.50 loss on $1.50 credit), sustainable.

### Selection Criteria

Spreads are identified by scanning 30+ stocks every 15 minutes (configurable: `/spreads?cron`):
- **IV Rank > 45%** — Trade only when implied volatility is elevated (configurable: `scanner.iv-rank-threshold`)
- **DTE: 30–50, preferred 45** — Day-to-expiration window (configurable: `min-dte`, `max-dte`, `preferred-dte`)
- **Credit ≥ $0.35** — Minimum $0.35 per share ($35 per spread minimum) after fees (configurable: `min-credit-per-share`)
- **Delta band: 0.25–0.30** — Short put target delta ~30-delta for ~$1.50 credit on $5 width (configurable: `delta-min`, `delta-max`, `target-delta`)
- **Spread width: $5.00** — Fixed width (configurable: `spread-width-usd`)
- **Max 5 open spreads** — Risk limit (configurable: `max-open-spreads`)
- **EU + US markets** — Active 9:00 AM - 10:59 PM CEST (covers both Frankfurt/Euronext and NYSE/NASDAQ hours)

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

**Current state** (2026-06-26):
- 5 known orphans (being manually closed at market open)
- Detection working: 5 detected every reconciliation run, deduped (no re-alert)
- Future: Auto-adoption will eliminate most new orphans

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

### Core Entities

**Spread** - Represents one entry or exit position
- `symbol` (TEXT) — Underlying stock symbol
- `status` (ENUM) — PENDING, OPEN, CLOSED_PROFIT, CLOSED_LOSS, CLOSED_REJECTED
- `entry_credit` (NUMERIC) — Credit received at entry
- `exit_credit` (NUMERIC) — Credit received at exit (if closed)
- `engineManaged` (BOOLEAN) — Whether exit is controlled by engine vs manual

**Order** - Represents IBKR order submissions
- `orderId` (INT) — IBKR's order ID for tracking
- `status` (ENUM) — SUBMITTED, FILLED, CANCELLED, REJECTED
- `limitPrice` (NUMERIC) — Price submitted to IBKR

**Trade** - Log of all decisions and outcomes
- `symbol` (TEXT) — Underlying
- `decision` (ENUM) — SKIP, CANDIDATE, ABORTED, FILLED, EXITED
- `reason` (TEXT) — Why the decision was made

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
# Build succeeds → JAR created at:
# /home/solvina/projects/options/engine/build/libs/engine.jar

# Copy to production
cp build/libs/engine.jar /home/solvina/options/engine.jar

# Restart service
systemctl restart options-engine.service

# Verify health
curl -s http://localhost:8081/health | jq '.'
```

### Code Structure

```
src/main/kotlin/cz/solvina/options/
├── adapters/
│   └── outbound/ibkr/
│       ├── order/
│       │   ├── NativeComboOrderStrategy.kt  (US markets)
│       │   └── LegByLegOrderStrategy.kt     (EU markets)
│       └── contract/
│           └── IbkrContractCache.kt         (Contract lookup caching)
├── domain/features/
│   ├── execution/
│   │   └── TradeExecutionService.kt         (Order submission & monitoring)
│   └── spread/
│       └── SpreadMonitorScheduler.kt        (Periodic monitoring)
└── application/
    └── ScannerConfig.kt                     (Cron schedule)
```

---

## Known Issues & Fixes

### FIXED (2026-06-12)
1. **BUY leg limit price = $0.00** — EUREX leg-by-leg orders rejected by exchange
   - Fix: Enforce minimum limit price of $0.01
2. **Stale option chain cache** → phantom strikes → Error 200
   - Fix: Reduced TTL from 24h to 1h with lazy refresh

### Active (0.8% impact)
- Order timeout rate on ASML, likely due to EUREX trading phases and leg-by-leg strategy fragility
- Solution planned: Migrate to BAG contract definition for EU markets

### Improvements Planned
- BAG contract support for EU markets (eliminates leg-by-leg partial fill risk)
- Pre-warming contract cache at startup (reduces first-order lookup latency)
- Circuit breaker for repeated contract lookup timeouts
- Metrics/alerting for quote staleness events

---

## Configuration

Engine configuration is controlled via:

**Application Properties**: `/home/solvina/projects/options/engine/src/main/resources/application.yml`
- IV Rank threshold (45% minimum)
- Scanner cron expression (currently: `0 */15 9-22 * * MON-FRI`)
- Fee per contract (for net credit calculation)

**Environment**: Raspberry Pi system
- IB Gateway configured for paper trading (can switch to live)
- Database: PostgreSQL (currently not running in production, only in tests)
- Network: Stable WiFi/Ethernet to IB Gateway

---

## Performance Notes

**Raspberry Pi (4GB RAM)**:
- Gradle builds take 2-5 minutes (due to slow disk I/O)
- 30+ symbols scanned every 15 minutes: ~100ms per symbol
- Average order execution: 500ms - 3s per spread (depends on market conditions)
- Concurrent order monitoring: handles 50+ open orders

**Network**:
- IB Gateway connection is stable (monitored)
- IBKR API throughput: ~100 requests/second capacity
- Current rate: ~5-10 requests/second average

