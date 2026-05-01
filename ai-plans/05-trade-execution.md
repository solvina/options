# Trade Execution Module — Implementation Plan

## Status

**IMPLEMENTED** — `./gradlew build` passes. `TradeExecutionServiceTest` runs 8 unit tests.
Smoke test wires `BacktestMarketTickAdapter` + `BacktestOrderExecutionAdapter`.

---

## Goal

Replace the two sequential, blocking `orderPort.placeAndAwaitFill()` calls in
`ScannerService.scanSymbol()` with a single atomic Combo/BAG order managed by a background
coroutine. The coroutine monitors live bid/ask ticks via `reqTickByTickData("BidAsk")`, ladders
the net-credit limit price 1 tick at a time toward a floor, and hard-cancels on underlying
drift or timeout.

Using a BAG order (both legs in one IBKR order) eliminates legging risk — fills are atomic.

---

## Architecture

```
ScannerService ──── (fire-and-forget scope.launch) ────────────────────────────────┐
                                                                                   │
TradeExecutionService                                                              │
  ├── MarketTickPort                                                               │
  │     ├── streamUnderlyingPrice()  ←── reqMktData(snapshot=false)               │
  │     └── streamSpreadCredit()     ←── reqTickByTickData("BidAsk") × 2 legs         │  domain
  │                                       + reqMktData for Greeks × 2 legs        │
  ├── OrderExecutionPort                                                           │
  │     ├── submitComboLimitOrder()  ←── BAG contract with 2 ComboLegs            │
  │     ├── awaitFill()                                                            │
  │     ├── cancelAndAwait()                                                       │
  │     └── replaceComboWithNewPrice()                                             │
  └── SpreadPort / AccountPort / Clock (existing)                                  │
                                                                                   │
IbkrMarketTickAdapter  ─── IbkrRequestRegistry.pendingTickByTick                  │  adapters
IbkrOrderExecutionAdapter ─ IbkrContractCache.getOrFetchOptionConId (existing)    │
```

---

## New domain files

### `domain/features/execution/model/TradeExecutionRequest.kt`

```kotlin
data class TradeExecutionRequest(
    val soldContract: OptionContract,
    val boughtContract: OptionContract,
    val underlyingSymbol: Symbol,
    val targetCredit: BigDecimal,              // scanner's net mid
    val floorCredit: BigDecimal,               // never submit below this
    val maxRiskPerShare: BigDecimal,           // for spread persistence
    val ivRankAtEntry: Double,                 // for spread persistence
    val soldBid: BigDecimal,                   // liquidity pre-check
    val soldAsk: BigDecimal,
    val boughtBid: BigDecimal,
    val boughtAsk: BigDecimal,
    val boughtMid: BigDecimal,                 // approximate bought-leg premium
    val underlyingPriceAtEntry: BigDecimal,    // drift anchor
    val quantity: Int = 1,
)
```

### `domain/features/execution/model/TradeExecutionResult.kt`

```kotlin
data class TradeExecutionResult(
    val outcome: ExecutionOutcome,
    val creditAchieved: BigDecimal? = null,
    val comboOrderId: Int? = null,
)

enum class ExecutionOutcome {
    FILLED, DRIFT_ABORTED, TIMED_OUT, FLOOR_REACHED,
    LIQUIDITY_REJECTED, EXPOSURE_REJECTED, CAPITAL_REJECTED,
}
```

### `domain/features/market/MarketTickPort.kt`

```kotlin
interface MarketTickPort {
    fun streamUnderlyingPrice(symbol: Symbol): Flow<Double>
    fun streamSpreadCredit(
        soldContract: OptionContract,
        boughtContract: OptionContract,
    ): Flow<SpreadCreditTick>
}

data class SpreadCreditTick(
    val soldBid: Double,
    val soldAsk: Double,
    val boughtBid: Double,
    val boughtAsk: Double,
    val netCredit: Double,
    val soldDelta: Double? = null,
    val boughtDelta: Double? = null,
)
```

### `domain/features/order/OrderExecutionPort.kt`

```kotlin
interface OrderExecutionPort {
    suspend fun submitComboLimitOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
    ): Int

    suspend fun awaitFill(orderId: Int): OrderStatus
    suspend fun cancelAndAwait(orderId: Int)

    suspend fun replaceComboWithNewPrice(
        existingOrderId: Int,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        newCredit: Money,
        qty: Int,
    ): Int
}
```

---

## Domain service: `domain/features/execution/TradeExecutionService.kt`

**Injected:** `MarketTickPort`, `OrderExecutionPort`, `SpreadPort`, `AccountPort`,
`ScannerConfig`, `Clock`.

**Internal state:** `inFlightSymbols: ConcurrentHashMap<Symbol, Unit>` — prevents duplicate
executions while a coroutine is active.

### Execution algorithm (12 steps)

```
1.  Exposure:   spreadPort.findOpen() ∪ inFlightSymbols → EXPOSURE_REJECTED
2.  Capital:    accountDetail.availableFunds < maxRiskPerContract → CAPITAL_REJECTED
3.  Liquidity:  (soldAsk - soldBid) / soldMid > maxLegBidAskSpreadPct → LIQUIDITY_REJECTED
                (boughtAsk - boughtBid) / boughtMid > maxLegBidAskSpreadPct → LIQUIDITY_REJECTED
4.  inFlightSymbols.add(symbol)
5.  comboOrderId = submitComboLimitOrder(targetCredit)
6.  Start underlyingFlow and creditFlow subscriptions
7.  withTimeout(executionTimeoutMinutes):
      for each event (underlying / credit / fill):
        Fill(currentOrderId, FILLED)  → break, FILLED
        Underlying: drift > driftProtectionPct → cancelAndAwait → DRIFT_ABORTED
        Credit (after N ticks): ladder 1 tick → FLOOR_REACHED or replace order
    TimeoutCancellationException → cancelAndAwait → TIMED_OUT
8.  Cancel all subscriptions
9.  If FILLED: persist BullPutSpread → return FILLED(creditAchieved)
10. Otherwise return outcome
11. finally: inFlightSymbols.remove(symbol)
```

**minTickFor(price):** `0.01` if price < 3.00, else `0.05`.

---

## ScannerConfig additions (5 new fields)

```kotlin
val driftProtectionPct: Double = 0.01       // 1 % underlying drift → abort
val floorCreditBuffer: Double = 0.50        // floor = targetCredit × (1 − 0.50)
val executionTimeoutMinutes: Long = 15
val ticksBeforePriceAdjust: Int = 5         // ladder after N credit ticks without fill
val maxLegBidAskSpreadPct: Double = 0.30    // liquidity gate
```

---

## AppConfig change

New bean providing the `CoroutineScope` injected into `TradeExecutionService`:

```kotlin
@Bean
fun executionCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

Tests pass `backgroundScope` from `TestScope` in its place for virtual-time control.

---

## ScannerService changes

- `OrderPort` injection removed; `TradeExecutionService` injected.
- `CoroutineScope(SupervisorJob() + Dispatchers.IO)` added.
- `scanSymbol()` steps 7–9 replaced with:
  ```kotlin
  val request = TradeExecutionRequest(
      soldContract = soldQuote.contract,
      boughtContract = boughtQuote.contract,
      underlyingSymbol = symbol,
      targetCredit = credit,
      floorCredit = credit * (1 - config.floorCreditBuffer),
      maxRiskPerShare = maxRiskPerShare,
      ivRankAtEntry = ivRank.rank,
      soldBid = soldQuote.bid.amount,
      soldAsk = soldQuote.ask.amount,
      boughtBid = boughtQuote.bid.amount,
      boughtAsk = boughtQuote.ask.amount,
      boughtMid = boughtQuote.mid.amount,
      underlyingPriceAtEntry = underlyingPrice.amount,
  )
  scope.launch { executionService.execute(request) }
  ```

---

## IBKR registry changes (`IbkrRequestRegistry`)

### New maps

```kotlin
// For reqTickByTickData("BidAsk") continuous streaming
internal val pendingTickByTick = ConcurrentHashMap<Int, PendingTickByTickRequest>()

// For reqMktData(snapshot=false) continuous Greeks
internal val pendingContinuousMarketData =
    ConcurrentHashMap<Int, PendingContinuousMarketDataRequest>()
```

### New types

```kotlin
data class TickByTickBidAsk(val time: Long, val bidPrice: Double, val askPrice: Double)

internal data class PendingTickByTickRequest(
    val trySend: (TickByTickBidAsk) -> Boolean,
)

internal data class PendingContinuousMarketDataRequest(
    val snapshot: MarketDataSnapshot,
    val onUpdate: (MarketDataSnapshot) -> Unit = {},
)
```

### Wiring

`onTickByTickBidAsk(reqId, tick)` → `pendingTickByTick[reqId]?.trySend?.invoke(tick)`

`onTickPrice` / `onTickOptionComputation`: also update `pendingContinuousMarketData[reqId]`
snapshot and call `onUpdate` (price fields only for underlying; Greeks stored silently).

`cancelAllPending()`: also clears `pendingTickByTick` and `pendingContinuousMarketData`.

### EWrapper change

`IbkrEWrapper.tickByTickBidAsk()` currently has a trace log. Add:
```kotlin
registry.onTickByTickBidAsk(reqId, TickByTickBidAsk(time, bidPrice, askPrice))
```

---

## `IbkrMarketTickAdapter` (new `@Component`)

**`streamUnderlyingPrice`** — `callbackFlow` over `reqMktData(snapshot=false)` on a stock
contract. Each `onTickPrice` update for fields 4/9 emits the latest price via `onUpdate`.
`awaitClose` calls `client.cancelMktData(reqId)`.

**`streamSpreadCredit`** — `callbackFlow` that starts four subscriptions:
- `reqTickByTickData("BidAsk")` × 2 legs → mutable state for bid/ask
- `reqMktData(snapshot=false, "100")` × 2 legs → mutable snapshot for delta
- On each bid/ask tick: compose `SpreadCreditTick` with latest delta from snapshot
- `awaitClose`: cancels all four subscriptions

---

## `IbkrOrderExecutionAdapter` (new `@Component`)

Implements `OrderExecutionPort`. Reuses **`IbkrContractCache.getOrFetchOptionConId()`** (already
exists) to resolve conIds. Builds `BAG` contract with two `ComboLeg`s (`SELL` + `BUY`).

**`submitComboLimitOrder`:**
```
soldConId   = contractCache.getOrFetchOptionConId(soldContract)
boughtConId = contractCache.getOrFetchOptionConId(boughtContract)
bag = Contract(secType="BAG", comboLegs=[SELL soldConId, BUY boughtConId])
order = Order(action="BUY", orderType="LMT", lmtPrice=netCredit, tif="DAY")
orderId = registry.nextOrderId()
registry.pendingOrderStatus[orderId] = CompletableDeferred()
client.placeOrder(orderId, bag, order)
return orderId
```

**`awaitFill`** — `registry.pendingOrderStatus[orderId]?.await() ?: CANCELLED`

**`cancelAndAwait`** — cancel + wait up to 10s for status CANCELLED confirmation, same pattern
as `OrderChaseService.cancelAndWait`.

**`replaceComboWithNewPrice`** — `cancelAndAwait(existingOrderId)` then `submitComboLimitOrder`.

---

## Backtest adapters

### `BacktestMarketTickAdapter` (`test/backtest/`)

Implements `MarketTickPort`.

`streamUnderlyingPrice`: emits a single `Double` (fixture close price for clock date) via `flow { emit(...) }`.

`streamSpreadCredit`: emits a single `SpreadCreditTick` with bid/ask from `BacktestMarketDataAdapter.getOptionMid()` for each leg (bid = mid − 5 %, ask = mid + 5 %). Delta computed via `BlackScholes.putDelta(...)` for the sold leg, bought leg.

### `BacktestOrderExecutionAdapter` (`test/backtest/`)

Implements `OrderExecutionPort`. Atomic counter for orderIds.

`submitComboLimitOrder` → records the combo in a `fills` list, returns new orderId.
`awaitFill` → returns `FILLED` immediately.
`cancelAndAwait` → no-op (optimistic fill model; cancellation shouldn't occur in backtest).
`replaceComboWithNewPrice` → delegate to `submitComboLimitOrder`.

---

## Test strategy

### `TradeExecutionServiceTest` (`test/execution/`)

`runTest` + `TestCoroutineScheduler`. All ports are in-memory stubs.

| Test | Expected |
|------|----------|
| `fills_immediately_at_target_credit` | `FILLED`, `creditAchieved = targetCredit` |
| `ladders_price_down_over_ticks` | `FILLED`, `creditAchieved < targetCredit` |
| `never_goes_below_floor` | `FLOOR_REACHED` |
| `drift_aborts_execution` | `DRIFT_ABORTED` |
| `timeout_cancels_order` | `TIMED_OUT` |
| `exposure_rejects_duplicate` | `EXPOSURE_REJECTED`, no order placed |
| `capital_rejects_underfunded` | `CAPITAL_REJECTED`, no order placed |
| `liquidity_rejects_wide_spread` | `LIQUIDITY_REJECTED`, no order placed |

### `BacktestSmokeTest` changes

Wire `TradeExecutionService` with `BacktestMarketTickAdapter` + `BacktestOrderExecutionAdapter`.
Same structural assertions must pass (≥ 0 trades, winRate 0–1, finalCapital > 0).

---

## Files modified / created

| File | Action |
|------|--------|
| `domain/features/execution/model/TradeExecutionRequest.kt` | Create |
| `domain/features/execution/model/TradeExecutionResult.kt` | Create |
| `domain/features/market/MarketTickPort.kt` | Create |
| `domain/features/order/OrderExecutionPort.kt` | Create |
| `domain/features/execution/TradeExecutionService.kt` | Create |
| `domain/features/scanner/ScannerConfig.kt` | Modify (+5 fields) |
| `domain/features/scanner/ScannerService.kt` | Modify (inject execution service) |
| `adapters/outbound/ibkr/IbkrEWrapper.kt` | Modify (wire tickByTickBidAsk) |
| `adapters/outbound/ibkr/registry/IbkrRequestRegistry.kt` | Modify (+tick-by-tick + continuous maps) |
| `adapters/outbound/ibkr/market/IbkrMarketTickAdapter.kt` | Create |
| `adapters/outbound/ibkr/order/IbkrOrderExecutionAdapter.kt` | Create |
| `resources/application.yml` | Modify (+5 scanner fields) |
| `test/backtest/BacktestMarketTickAdapter.kt` | Create |
| `test/backtest/BacktestOrderExecutionAdapter.kt` | Create |
| `test/backtest/BacktestSmokeTest.kt` | Modify |
| `test/execution/TradeExecutionServiceTest.kt` | Create |

---

## Verification

1. `./gradlew test` — `TradeExecutionServiceTest` (8 tests) + `BacktestSmokeTest` pass
2. `./gradlew build` — full compile + ktlint clean
3. TWS paper session: scan triggers → logs show combo order placed, tick updates, price laddering
4. Duplicate entry blocked: second scan on same symbol → `EXPOSURE_REJECTED` immediately
5. Drift guard: SPY moves > 1 % mid-execution → `DRIFT_ABORTED` in logs, order gone from TWS
