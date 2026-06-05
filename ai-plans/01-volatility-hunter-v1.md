# Volatility Hunter v1.0 — Implementation Plan

> **Historical document** — this was the original build plan. For current state, see `00-application-description.md`.
> Key divergences from this plan: stop-loss is now 1× credit (not 50% of max risk); BAG/combo orders replaced sequential legs; `ScanCandidateSelector` extracted from `ScannerService`; `UniversePort` replaced `WatchlistPort`; universe is DB-backed (not in-memory); per-instrument parameter overrides added; flag strategy added; EU symbols supported; InfluxDB added.

## Status

**IMPLEMENTED** — superseded by current architecture. See `00-application-description.md` for current state.

---

## Context

Build an automated Bull Put Spread options strategy engine on top of the existing IBKR connection
infrastructure. The strategy exploits the statistical overpricing of implied volatility: sell put
spreads when IV Rank > 30, collect premium, manage exits at 50% profit / 50% of max risk loss / 14 DTE.

---

## Clarified Requirements

| Topic | Decision |
|-------|----------|
| Stop loss | 50% of **max risk** (not 200% of credit). Max risk = spread width − credit. Stop when loss = max_risk × 0.50 |
| Order placement | Two separate orders (SELL put, then BUY put). Simpler for v1; leg risk acceptable at 1 contract |
| Restart / pending orders | Cancel & discard any DB-PENDING orders on startup. Start fresh. |
| Minimum credit | Skip trade if credit < $0.30/share ($30/contract) |
| Expiry selection | Prefer expiry closest to 45 DTE within the 30–50 DTE window |
| Spread width | $5 flat for all symbols |
| Quantity | Always 1 contract (money management math makes > 1 impossible on typical accounts) |
| Delta tolerance | Accept put delta in range [−0.10, −0.20] (closest to −0.15) |

---

## Strategy Review — Correctness Notes

**Delta sign convention:** IBKR returns put deltas as negative values (−0.15, not +0.15).
`targetDelta = 0.15` is a magnitude; comparisons must use `abs(delta)`.
Delta range [−0.10, −0.20] is correct as-is for raw IBKR values.

**Spread cost to close (P&L check):** Use mid-price for P&L evaluation and initial limit order price.
Mid = (bid + ask) / 2. Cost to close = midSoldPut + midBoughtPut (signs already embedded in spread value).
- SL check: `currentSpreadValue >= creditPerShare + maxRiskPerShare * stopLossPercent`
- TP check: `currentSpreadValue <= creditPerShare * (1 - takeProfitPercent)`

**Bought strike validation:** After selecting the sold strike, verify `soldStrike − spreadWidth` exists in the
`IbkrOptionParamsCache` strike set. If not, find the nearest valid strike ≤ `soldStrike − spreadWidth`.
Do not place orders if no valid bought strike exists.

**orderId vs reqId — critical:** IBKR sends `nextValidId(orderId)` on connect. Order IDs for `placeOrder`
must be ≥ that seed. Data request IDs (`reqHistoricalData`, `reqMktData`, etc.) are independent —
use a separate counter starting at 1. `IbkrRequestRegistry` tracks both:
- `private val dataReqIdCounter = AtomicInteger(1)` — for all data requests
- `private val orderIdCounter = AtomicInteger(1)` — seeded from `nextValidId` callback in `IbkrEWrapper`

**Strike band for option chain:** When fetching candidate strikes, filter to OTM puts only:
`strike in [underlying × (1 − strikeBandPercent), underlying × 0.999]`
Do not include ITM puts (strikes above underlying). `strikeBandPercent` defaults to 0.20 (20% below).

**AccountSummary vs open spread count:** `AccountPort` fetches only IBKR data (`NetLiquidation`).
Open spread count comes from `SpreadPort.countByStatus(OPEN)`. `ScannerService` calls both separately.
`AccountSummary` has no `openSpreadCount` field.

**IV historical bars:** IBKR `OPTION_IMPLIED_VOLATILITY` historical data type returns the 30-day
model IV of the underlying — correct for IV Rank computation. Confirmed: 365 days of daily bars
gives a valid 52-week high/low range.

---

## Stop Loss Math

For a $5-wide spread sold for $1.50 credit:
- Max risk = ($5.00 − $1.50) = **$3.50/share** = $350/contract
- Take profit at 50% of credit: current spread value ≤ $0.75 → profit $75/contract
- Stop loss at 50% of max risk: current spread value ≥ $3.25 → loss $175/contract
- Time exit: 14 DTE regardless of P&L

---

## conId Caching Strategy

`reqSecDefOptParams` gives us the full set of valid strikes and expirations per symbol.
We iterate those strikes to find delta ≈ −0.15, and `reqContractDetails` on candidates
returns the conId as part of the response. Cache everything we touch.

**Startup (permanent):**
`Map<Symbol, Int>` — underlying conIds for watchlist symbols (fetched once, never expire)

**Daily cache:**
`Map<Symbol, OptionParams>` — available expirations + strikes per symbol (refresh at startup or daily)

**Lazy conId cache:**
`Map<OptionContractKey(symbol, expiry, strike, right), Int>` — populated as we scan candidates.
By the time we pick the sold/bought strikes, their conIds are already in hand from the scan step.

**Fallback:**
Build contract by description (symbol + expiry + strike + right) without conId — IBKR resolves
it for `reqMktData`. For order placement, if conId cache miss, do one `reqContractDetails` call.

---

## Package Layout

```
domain/
  models/
    Symbol.kt                  @JvmInline value class
    Money.kt                   amount: BigDecimal, currency: String
    OptionContract.kt          symbol, expiry: LocalDate, strike: BigDecimal, type: OptionType
    OptionType.kt              enum PUT("P"), CALL("C") with ibkrCode
    OptionGreeks.kt            delta, gamma, theta, vega, iv: Double
    HistoricalBar.kt           date: LocalDate, close: BigDecimal, iv: Double?
    IvRank.kt                  rank: Double (0–100), calculatedAt: Instant
  features/
    watchlist/
      WatchlistPort.kt         getWatchlist(): List<Symbol>
    volatility/
      VolatilityPort.kt        suspend getIvRank(symbol): IvRank
      HistoricalDataPort.kt    suspend fetchDailyBars(symbol, days): List<HistoricalBar>
      IvRankService.kt         domain service — pure IV rank logic + in-memory cache (TTL configurable)
    market/
      MarketDataPort.kt        suspend getUnderlyingPrice(symbol): Money
                               suspend getOptionMid(contract): Money
      OptionChainPort.kt       suspend getAvailableExpirations(symbol): Set<LocalDate>
                               suspend getOptionChain(symbol, expiry): List<OptionQuote>
      model/
        OptionQuote.kt         contract + bid/ask/mid: Money + greeks: OptionGreeks
    account/
      AccountPort.kt           suspend getAccountSummary(): AccountSummary
      AccountSummary.kt        totalCapital: Money  (openSpreadCount fetched from SpreadPort separately)
    order/
      OrderPort.kt             suspend placeAndAwaitFill(contract, action, limitPrice, qty): LegOrder
                               suspend cancelOrder(orderId: Int)
      LegOrder.kt              orderId: Int, status: OrderStatus
      LegAction.kt             enum BUY, SELL
      OrderStatus.kt           enum PENDING, FILLED, CANCELLED
    spread/
      SpreadPort.kt            save / update / findOpen / findAll / countByStatus / findByStatus
      SpreadManagementService.kt  monitors open spreads, applies exit rules
      model/
        BullPutSpread.kt       id, symbol, soldLeg, boughtLeg, creditPerShare, quantity,
                               maxRiskPerShare, status, openedAt, closedAt?, closeReason?,
                               closePricePerShare?, ivRankAtEntry?, underlyingPriceAtEntry?
        SpreadLeg.kt           contract: OptionContract, action: LegAction, premium: Money, orderId: Int
        SpreadStatus.kt        OPEN, CLOSED_PROFIT, CLOSED_STOP, CLOSED_TIME, CLOSED_MANUAL
    scanner/
      ScannerPort.kt           suspend scan()
      ScannerConfig.kt         @ConfigurationProperties("scanner")
      ScannerService.kt        main use-case orchestration + getLastRunAt() + getIvRanksSnapshot()

adapters/
  inbound/
    api/
      SpreadsApiImpl.kt        implements SpreadsApi (listSpreads, getSpreadById)
      ScannerApiImpl.kt        implements ScannerApi (triggerScan, getScannerStatus)
    jobs/
      ScannerScheduler.kt      @Scheduled cron — market hours only
      SpreadMonitorScheduler.kt @Scheduled configurable delay
  outbound/
    ibkr/
      (existing connection files unchanged)
      cache/
        OptionContractKey.kt   data class(symbol, expiry, strike, right)
        OptionParams.kt        data class(expirations: Set<LocalDate>, strikes: Set<BigDecimal>)
        IbkrContractCache.kt   underlying conIds (permanent) + option conIds (expiry-keyed)
        IbkrOptionParamsCache.kt available expirations + strikes per symbol (daily TTL)
      registry/
        MarketDataSnapshot.kt  mutable data holder: bid, ask, last, close, delta, iv, gamma, vega, theta
        IbkrRequestRegistry.kt pending request maps + separate data/order ID counters
      market/
        IbkrHistoricalDataAdapter.kt   implements HistoricalDataPort
        IbkrMarketDataAdapter.kt       implements MarketDataPort + OptionChainPort
      account/
        IbkrAccountAdapter.kt          implements AccountPort
      order/
        IbkrOrderAdapter.kt            implements OrderPort
        OrderChaseService.kt           configurable retry / cancel / reprice logic
    watchlist/
      InMemoryWatchlistAdapter.kt      implements WatchlistPort — reads ScannerConfig
    persistence/
      postgres/
        entity/SpreadPositionEntity.kt
        repository/SpreadPositionRepository.kt
        SpreadPersistenceAdapter.kt    implements SpreadPort
```

---

## Implementation Phases

### Phase 1 — Domain models and port interfaces ✅

All in `domain/`. Zero IBKR dependencies.

| File | Key fields |
|------|-----------|
| `models/Symbol.kt` | `@JvmInline value class Symbol(val value: String)` |
| `models/Money.kt` | `amount: BigDecimal, currency: String = "USD"` with arithmetic operators |
| `models/OptionContract.kt` | symbol, expiry, strike, type (PUT/CALL) |
| `models/OptionType.kt` | `enum class OptionType(val ibkrCode: String) { PUT("P"), CALL("C") }` |
| `models/OptionGreeks.kt` | delta, gamma, theta, vega, iv: Double |
| `models/HistoricalBar.kt` | date: LocalDate, close: BigDecimal, iv: Double? |
| `models/IvRank.kt` | rank: Double, calculatedAt: Instant |
| All port interfaces | As per package layout above |
| All spread model classes | BullPutSpread, SpreadLeg, SpreadStatus |

---

### Phase 2 — Domain services (pure logic) ✅

**`IvRankService`** implements `VolatilityPort`:
1. Check in-memory cache (TTL = `ivCacheTtlMinutes` per symbol)
2. `histDataPort.fetchDailyBars(symbol, ivHistoryDays)`
3. Filter bars with non-null IV
4. `rank = (currentIv − ivMin) / (ivMax − ivMin) * 100`; returns 50.0 if ivMax == ivMin
5. Cache result, return `IvRank`

**`ScannerService`** implements `ScannerPort` — `scan()`:
1. `spreadPort.countByStatus(OPEN)` → if `>= maxOpenSpreads` return early
2. `accountPort.getAccountSummary()` → totalCapital for money management
3. Load open spreads → build set of symbols already positioned
4. For each watchlist symbol not already open:
   a. `volatilityPort.getIvRank(symbol)` → skip if rank < ivRankThreshold; store in `ivRanksSnapshot`
   b. `optionChainPort.getAvailableExpirations(symbol)` → find expiry closest to preferredDte within [minDte, maxDte]
   c. `optionChainPort.getOptionChain(symbol, expiry)` — filters OTM puts in [underlying × (1 − strikeBandPercent), underlying × 0.999]
   d. Find put where `abs(delta)` closest to targetDelta; skip if none in [deltaMin, deltaMax]
   e. Sold put = chosen strike; bought put = nearest valid strike ≤ soldStrike − spreadWidth (from params cache); skip if not found
   f. Credit = soldPut.mid − boughtPut.mid; skip if credit < minCreditPerShare
   g. maxRiskPerShare = spreadWidth − credit; validate `maxRiskPerShare * 100 <= totalCapital * maxRiskPercent`; skip if not
   h. `orderPort.placeAndAwaitFill(soldContract, SELL, credit, 1)` → FILLED or abort
   i. `orderPort.placeAndAwaitFill(boughtContract, BUY, boughtMid, 1)` → FILLED or cancel sold leg + abort
   j. Persist `BullPutSpread(status=OPEN, creditPerShare=credit, maxRiskPerShare=maxRiskPerShare, ...)`
   k. Tracks `lastRunAt` and `ivRanksSnapshot` (ConcurrentHashMap) for REST API use

**`SpreadManagementService`** — `checkExits()`:
1. Load all OPEN spreads via `spreadPort.findOpen()`
2. For each spread, `marketDataPort.getOptionMid(soldLeg.contract)` and `getOptionMid(boughtLeg.contract)`
3. `currentSpreadValue = soldPut.mid − boughtPut.mid` (net cost to close at mid)
4. Check TP: `currentSpreadValue <= creditPerShare * (1 - takeProfitPercent)` → close, CLOSED_PROFIT
5. Check SL: `currentSpreadValue >= creditPerShare + maxRiskPerShare * stopLossPercent` → close, CLOSED_STOP
6. Check DTE: `ChronoUnit.DAYS.between(today, expiry) <= timeProfitDte` → close, CLOSED_TIME
7. Close: place BUY-to-close on sold put + SELL-to-close on bought put; update spread status + closePrice

---

### Phase 3 — IBKR callback infrastructure ✅

**`IbkrRequestRegistry`** — typed pending-request maps + two ID counters, all internal to ibkr package:
```
pendingHistoricalBars:   ConcurrentHashMap<Int, PendingBarsRequest>
pendingContractDetails:  ConcurrentHashMap<Int, PendingContractRequest>
pendingOptionParams:     ConcurrentHashMap<Int, PendingOptionParamsRequest>
pendingMarketData:       ConcurrentHashMap<Int, PendingMarketDataRequest>
pendingAccountSummary:   ConcurrentHashMap<Int, PendingAccountRequest>
pendingOrderStatus:      ConcurrentHashMap<Int, CompletableDeferred<OrderStatus>>

private val dataReqIdCounter = AtomicInteger(1)     // for reqHistoricalData, reqMktData, etc.
private val orderIdCounter    = AtomicInteger(1)     // seeded from nextValidId callback

fun nextDataReqId(): Int = dataReqIdCounter.getAndIncrement()
fun seedOrderId(id: Int) { orderIdCounter.set(id) }  // called by IbkrEWrapper.nextValidId()
fun nextOrderId(): Int = orderIdCounter.getAndIncrement()
```

`PendingMarketDataRequest` holds a `CompletableDeferred<MarketDataSnapshot>` + a mutable
`MarketDataSnapshot` (bid, ask, last, close, delta, iv, gamma, vega, theta — all Double.NaN until set).

Pattern:
```kotlin
// adapter side
val reqId = registry.nextDataReqId()
val deferred = CompletableDeferred<List<HistoricalBar>>()
registry.pendingHistoricalBars[reqId] = PendingBarsRequest(deferred, mutableListOf())
client.reqHistoricalData(reqId, ...)
return withTimeout(config.requestTimeoutMs) { deferred.await() }

// EWrapper side
override fun historicalData(reqId: Int, bar: Bar) = registry.onHistoricalBar(reqId, bar)
override fun historicalDataEnd(reqId: Int, ...) = registry.onHistoricalDataEnd(reqId)
override fun nextValidId(orderId: Int) = registry.seedOrderId(orderId)
```

**`IbkrEWrapper`** updated — `IbkrRequestRegistry` injected, delegates:
- `historicalData` / `historicalDataEnd` → registry
- `contractDetails` / `contractDetailsEnd` → registry
- `securityDefinitionOptionalParameter` / `...End` → registry
- `tickPrice` / `tickOptionComputation` / `tickSnapshotEnd` → registry
- `accountSummary` / `accountSummaryEnd` → registry
- `orderStatus` → registry
- `nextValidId` → `registry.seedOrderId(orderId)`
- `error(id, code, msg)` → `registry.onError(id, code, msg)` when `id > 0`

---

### Phase 4 — IBKR caches ✅

**`IbkrContractCache`**:
- Startup: for each watchlist symbol, `reqContractDetails(stockContract)` → cache underlying conId
- Lazy option cache: `getOrFetchOptionConId(OptionContractKey)` — returns conId, fetches+caches on miss
- Eviction: `evictExpired()` removes entries where `expiry < today`

**`IbkrOptionParamsCache`**:
- `getOrFetch(symbol)` — calls `reqSecDefOptParams` using underlying conId from `IbkrContractCache`
- Returns `OptionParams(expirations: Set<LocalDate>, strikes: Set<BigDecimal>)`
- TTL: `scannerConfig.optionParamsCacheTtlHours` (default 24h)

---

### Phase 5 — IBKR market data adapters ✅

**`IbkrHistoricalDataAdapter`** implements `HistoricalDataPort`:
- `fetchDailyBars(symbol, days)` → `reqHistoricalData(reqId, contract, endDate, "${days} D", "1 day", "OPTION_IMPLIED_VOLATILITY", ...)`
- Awaits `pendingHistoricalBars` deferred, maps `com.ib.client.Bar` → `HistoricalBar`

**`IbkrMarketDataAdapter`** implements `MarketDataPort` + `OptionChainPort`:
- `getUnderlyingPrice(symbol)` → `reqMktData` snapshot on stock contract, uses last/close tick
- `getOptionMid(contract)` → `reqMktData` snapshot on option contract, returns `(bid + ask) / 2`
- `getAvailableExpirations(symbol)` → delegates to `IbkrOptionParamsCache.getOrFetch(symbol).expirations`
- `getOptionChain(symbol, expiry)`:
  1. Get valid strikes from `IbkrOptionParamsCache`
  2. Filter OTM puts: `strike in [underlying × (1 − strikeBandPercent), underlying × 0.999]`
  3. Sort by `abs(strike − targetStrike)`; take top `candidateStrikeCount` nearest
  4. For each candidate: optionally get conId from `IbkrContractCache`, build option contract
  5. `reqMktData` snapshot with `genericTickList = "100"` → `tickOptionComputation` → delta, iv, bid/ask
  6. Maps to `OptionQuote` list (no IBKR types escape this class)

---

### Phase 6 — IBKR account adapter ✅

**`IbkrAccountAdapter`** implements `AccountPort`:
- `reqAccountSummary(reqId, "All", "NetLiquidation")` → awaits `pendingAccountSummary`
- Parses `NetLiquidation` tag → `AccountSummary(totalCapital = ...)`

---

### Phase 7 — IBKR order adapter + order chase ✅

**`IbkrOrderAdapter`** implements `OrderPort`:
- `placeAndAwaitFill(contract, action, limitPrice, qty)`:
  1. Optionally fetch conId from `IbkrContractCache` (graceful fallback on miss)
  2. Build `com.ib.client.Contract` + `com.ib.client.Order` (LMT, tif=DAY)
  3. `orderId = registry.nextOrderId()`; register `CompletableDeferred<OrderStatus>` in `pendingOrderStatus`
  4. `client.placeOrder(orderId, ibkrContract, ibkrOrder)`
  5. Delegate to `chaseService.waitForFillOrChase(orderId, ibkrContract, action, limitPrice, qty)`
- `cancelOrder(orderId)` → `client.cancelOrder(orderId, OrderCancel())`

**`OrderChaseService`**:
- `waitForFillOrChase(initialOrderId, contract, action, initialPrice, qty)`:
  1. Await `pendingOrderStatus[orderId]` with `orderChaseTimeoutMinutes` × 60_000ms timeout
  2. On `FILLED` → return `LegOrder(orderId, FILLED)`
  3. On `TimeoutCancellationException`:
     - `client.cancelOrder(orderId, OrderCancel())`, wait up to 10s for CANCELLED confirmation
     - `delay(500)` after cancel
     - `price = price * (1 - orderChasePriceStep)`; new orderId; place new LMT order
  4. After `orderChaseMaxRetries` exhausted → return `LegOrder(orderId, CANCELLED)`

---

### Phase 8 — Watchlist adapter ✅

**`InMemoryWatchlistAdapter`** implements `WatchlistPort`:
- Returns `ScannerConfig.watchlist.map { Symbol(it) }`
- No IBKR dependency

---

### Phase 9 — State persistence ✅ (startup cleanup TODO)

**Liquibase migration** `v1__spread_positions.yaml` creates `spread_positions`:
```sql
CREATE TABLE spread_positions (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol                    VARCHAR(10) NOT NULL,
    status                    VARCHAR(30) NOT NULL,
    sold_strike               NUMERIC(10,2) NOT NULL,
    bought_strike             NUMERIC(10,2) NOT NULL,
    expiry_date               DATE NOT NULL,
    credit_per_share          NUMERIC(10,4) NOT NULL,
    max_risk_per_share        NUMERIC(10,4) NOT NULL,
    quantity                  INT NOT NULL DEFAULT 1,
    sold_order_id             INT,
    bought_order_id           INT,
    iv_rank_at_entry          NUMERIC(5,2),
    underlying_price_at_entry NUMERIC(10,2),
    opened_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at                 TIMESTAMP WITH TIME ZONE,
    close_reason              VARCHAR(50),
    close_price_per_share     NUMERIC(10,4)
);
```

**`SpreadPositionEntity`** + **`SpreadPositionRepository`** (findByStatus, countByStatus) + **`SpreadPersistenceAdapter`**

**⚠ TODO — Startup PENDING cleanup not yet implemented:**
Per requirements, on startup `IbkrLifecycleAdapter.onApplicationReady` should:
- Load spreads with status OPEN where orders may be stuck (sold/bought order IDs in DB)
- Call `client.cancelOrder(orderId, OrderCancel())` for each stored orderId
- Update spread status to CLOSED_MANUAL in DB
Currently `onApplicationReady` only connects to IBKR; PENDING cleanup is deferred to v1.1.

---

### Phase 10 — Schedulers ✅

**`ScannerScheduler`**:
- `@Scheduled(cron = "\${scanner.cron:0 */15 10-15 * * MON-FRI}")` — configurable, default every 15 min 10:00–15:45 ET
- Guard: `if (!connectionStatusPort.isConnected()) return`

**`SpreadMonitorScheduler`**:
- `@Scheduled(fixedDelayString = "\${scanner.monitor-delay-ms:60000}")`
- Same connection guard

---

### Phase 11 — OpenAPI + REST API ✅

**`openapi/cz.solvina.options.spreads.yaml`** defines two tag groups:
- `SpreadsApi`: `GET /spreads` (optional `?status=` filter) → `Flow<SpreadDto>`, `GET /spreads/{id}` → `SpreadDto`
- `ScannerApi`: `POST /scanner/run` → 202, `GET /scanner/status` → `ScannerStatusDto`

`ScannerStatusDto` fields: `lastRunAt`, `openSpreadCount`, `ivRanks: Map<String, BigDecimal>`

Implementations:
- `SpreadsApiImpl` — `listSpreads` builds a `kotlinx.coroutines.flow.flow {}` from `spreadPort`; `getSpreadById` returns 404 if not found
- `ScannerApiImpl` — `triggerScan` fires background coroutine via `CoroutineScope(Dispatchers.Default).launch`; `getScannerStatus` reads from `ScannerService` state

---

### Phase 12 — Configuration ✅

All strategy parameters under `scanner.*` in `ScannerConfig` (`@ConfigurationProperties("scanner")`).
IBKR timeout under `ibkr.connection.request-timeout-ms`.
`OptionsApplication` annotated with `@ConfigurationPropertiesScan`.

```yaml
scanner:
  watchlist: [SPY, QQQ, AAPL, MSFT, AMZN]
  iv-rank-threshold: 30
  min-dte: 30
  max-dte: 50
  preferred-dte: 45
  target-delta: 0.15
  delta-min: 0.10
  delta-max: 0.20
  strike-band-percent: 0.20
  candidate-strike-count: 7
  spread-width-usd: 5.0
  min-credit-per-share: 0.30
  max-risk-percent: 0.025
  max-open-spreads: 5
  take-profit-percent: 0.50
  stop-loss-percent: 0.50
  time-profit-dte: 14
  order-chase-timeout-minutes: 5
  order-chase-max-retries: 3
  order-chase-price-step: 0.03
  cron: "0 */15 10-15 * * MON-FRI"
  monitor-delay-ms: 60000
  iv-history-days: 365
  iv-cache-ttl-minutes: 60
  option-params-cache-ttl-hours: 24

ibkr:
  connection:
    request-timeout-ms: 30000
```

`ScannerConfig` Kotlin class:
```kotlin
@ConfigurationProperties("scanner")
data class ScannerConfig(
    val watchlist: List<String>,
    val ivRankThreshold: Double = 30.0,
    val minDte: Int = 30,
    val maxDte: Int = 50,
    val preferredDte: Int = 45,
    val targetDelta: Double = 0.15,
    val deltaMin: Double = 0.10,
    val deltaMax: Double = 0.20,
    val strikeBandPercent: Double = 0.20,
    val candidateStrikeCount: Int = 7,
    val spreadWidthUsd: BigDecimal = BigDecimal("5.0"),
    val minCreditPerShare: BigDecimal = BigDecimal("0.30"),
    val maxRiskPercent: Double = 0.025,
    val maxOpenSpreads: Int = 5,
    val takeProfitPercent: Double = 0.50,
    val stopLossPercent: Double = 0.50,
    val timeProfitDte: Int = 14,
    val orderChaseTimeoutMinutes: Long = 5,
    val orderChaseMaxRetries: Int = 3,
    val orderChasePriceStep: Double = 0.03,
    val cron: String = "0 */15 10-15 * * MON-FRI",
    val monitorDelayMs: Long = 60_000,
    val ivHistoryDays: Int = 365,
    val ivCacheTtlMinutes: Long = 60,
    val optionParamsCacheTtlHours: Long = 24,
)
```

---

## Verification

1. `./gradlew build` passes ✅
2. `docker compose up -d` → PostgreSQL up, `spread_positions` table created by Liquibase
3. With IBKR connected + TWS paper trading:
   - `POST /spreads/scanner/run` → logs IV rank per symbol, logs selected strikes or skip reasons
   - `GET /spreads` → empty list initially
   - DB: `SELECT * FROM spread_positions` → rows appear when scanner finds opportunities and orders fill
4. Heartbeat logs show connection OK + scheduler fires every 15 min
5. With an open spread in DB, monitor cycle logs TP/SL/DTE check results every minute

## Open Items

- **Startup PENDING cleanup** (Phase 9 TODO): cancel stale orders and mark spreads CLOSED_MANUAL on reconnect
