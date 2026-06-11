# Edge Cases and Potential Bugs Found in Trading Engine

**Analysis Date**: 2026-06-10  
**Scope**: Comprehensive code review looking for issues similar to AMD position reversal bug  
**Total Issues Found**: 23

---

## Pattern Analysis: What Makes These Bugs

All bugs share one or more of these characteristics:
1. **Stale/Orphaned Orders** - Orders placed in IBKR but never verified/cleaned up
2. **Race Conditions** - Multiple async operations on same symbol without mutual exclusion
3. **State Without Verification** - DB state transitions without confirming IBKR reality
4. **Partial Fills Without Rollback** - One leg fills, other fails, position left unhedged
5. **Timeout Windows** - Time-based assumptions (sleep 200ms, 10s timeout) without confirmation
6. **Non-Atomic Checks** - Check + action not atomic (status can change between them)

---

## CRITICAL ISSUES (6)

### 1. Partial Fill Handling Without Rollback in closeSpread()
- **File**: `SpreadManagementService.kt:353-385`
- **Risk**: CRITICAL
- **Issue**: Buy-back fills but sell-back fails → position left CLOSING with one leg closed (unhedged)
- **Code Pattern**:
  ```kotlin
  val buyBackOrder = orderPort.placeAndAwaitFill(...)  // FILLS
  if (buyBackOrder.status != OrderStatus.FILLED) return
  
  val sellBackOrder = orderPort.placeAndAwaitFill(...)  // FAILS
  if (sellBackOrder.status != OrderStatus.FILLED) return // NOW STUCK: SOLD LEG CLOSED, LONG PUT REMAINS
  ```
- **Real Scenario**: Sold put closes cleanly, but bought put order times out. Account now SHORT without hedge.
- **Fix**: Implement immediate rollback (sell back the newly-closed leg if buy-back succeeds but sell-back fails)

---

### 2. Race Condition: Multiple Simultaneous Close Attempts
- **File**: `SpreadMonitorScheduler.kt:46-57`
- **Risk**: CRITICAL
- **Issue**: `AtomicBoolean` only prevents duplicate scheduler invocations, but async work can overlap
- **Code Pattern**:
  ```kotlin
  if (!running.compareAndSet(false, true)) return  // Prevents SECOND INVOKE
  scope.launch {  // THIS LAUNCHES ASYNC
      runCatching { spreadManagementService.checkExits() }
  }
  // compareAndSet is reset AFTER scope.launch, not after work completes
  ```
- **Real Scenario**: Two scheduler ticks at T=0s and T=59s both call checkExits() simultaneously. Both detect same spread needs closing. Both place orders.
- **Fix**: Add per-symbol mutex or use semaphore to limit concurrent work per symbol

---

### 3. Order Replacement Without Ensuring Cancel Completes
- **File**: `IbkrOrderExecutionAdapter.kt:104-113`
- **Risk**: CRITICAL
- **Issue**: 10s timeout on cancel isn't guaranteed to be long enough; new order submitted while old still live
- **Code Pattern**:
  ```kotlin
  override suspend fun replaceComboWithNewPrice(...): Int {
      cancelAndAwait(existingOrderId)  // 10s timeout - assumes done after 10s
      return submitComboLimitOrder(...)  // NEW ORDER GOES IN WHILE OLD MIGHT STILL EXECUTE
  }
  ```
- **Real Scenario**: Network latency spikes to 15s, cancel timeout at 10s, new price order submitted, old order fills 2s later → double fill.
- **Fix**: Query IBKR to confirm order status is actually cancelled, don't rely on timeout

---

### 4. LegByLegOrderStrategy Missing Reconciliation on Partial Fill
- **File**: `LegByLegOrderStrategy.kt:69-110`
- **Risk**: CRITICAL
- **Issue**: SHORT leg fills, 500ms delay, LONG leg fails. Race between fill and failure check leaves unhedged.
- **Code Pattern**:
  ```kotlin
  val shortOrderId = submitSingleLeg(...)
  delay(500)  // Wait for SHORT to process
  val longOrderId = submitSingleLeg(...)
  
  if (longOrderId == 0) {
      client.cancelOrder(shortOrderId, ...)  // RACE: SHORT might have already filled during delay
  }
  ```
- **Real Scenario**: SHORT leg fills at T=100ms. At T=500ms we check LONG status. Cancel issued for SHORT at T=510ms but SHORT already filled at T=100ms.
- **Fix**: Query SHORT leg position before deciding to cancel, don't rely on timing

---

### 5. StartupRecoveryService Cancel Timing Window
- **File**: `StartupRecoveryService.kt:42-71`
- **Risk**: CRITICAL
- **Issue**: Between identifying stale SELL orders and cancelling them, both close order and SELL order can fill
- **Code Pattern**:
  ```kotlin
  val staleSellOrders = openOrders.filter { ... }  // Get list
  for (order in staleSellOrders) {
      client.cancelOrder(order.orderId, ...)  // T=10ms: SEND CANCEL
      // But close order might execute at T=5ms
      // And SELL order might execute at T=8ms
      // Cancel arrives at T=25ms but both already filled
  }
  ```
- **Real Scenario**: AMD case - stale SELL orders identified, recovery sends cancel, but orders already executed.
- **Fix**: This is hard to fix 100% - need to query IBKR position state AFTER cancel to ensure they're really gone

---

### 6. No Symbol-Level Mutual Exclusion in FlagScannerService
- **File**: `FlagScannerService.kt:385-398`
- **Risk**: CRITICAL
- **Issue**: Entry mutex only protects immediate section; two signals can pass checks before either acquires lock
- **Code Pattern**:
  ```kotlin
  entryMutex.withLock {
      val openCount = flagPort.findOpen().size
      if (openCount >= maxOpen) return  // BOTH SIGNALS READ COUNT HERE
      // Thread A reads: count=0, passes
      // Thread B reads: count=0 (BEFORE Thread A persists), passes
      // Both persist PENDING → two entries for same symbol
      flagExecutionService.execute(request)
  }
  ```
- **Real Scenario**: Flag fires at 09:30:00.100 and 09:30:00.101. Both threads read count before either persists.
- **Fix**: Make the read + persist atomic within lock, or check count multiple times

---

## HIGH PRIORITY ISSUES (6)

### 7. Force-Close Doesn't Cancel Orders on Verification Fail
- **File**: `SpreadManagementService.kt:83-120`
- **Risk**: HIGH
- **Issue**: Orders placed but verification times out; orders remain live in IBKR while code returns
- **Code Pattern**:
  ```kotlin
  orderPort.placeMarketOrder(spread.soldLeg.contract, LegAction.BUY, spread.quantity)
  orderPort.placeMarketOrder(spread.boughtLeg.contract, LegAction.SELL, spread.quantity)
  
  if (!verifyPositionClosed(spread)) {
      logger.warn { "Position verification FAILED" }
      return spread  // ORDERS STILL LIVE, SPREAD STILL CLOSING
  }
  ```
- **Real Scenario**: Place orders, position check times out, return. Spread stays CLOSING. Next retry places duplicate orders.
- **Fix**: If verification fails, immediately cancel the orders just placed

---

### 8. PositionReconciliationService Timeout Doesn't Trigger Cleanup
- **File**: `PositionReconciliationService.kt:73-142`
- **Risk**: HIGH
- **Issue**: Timeout on position verification leaves orders registered but inactive
- **Code Pattern**:
  ```kotlin
  while (Instant.now() < timeoutDeadline) {
      val positions = port.getPositions()
      if (bothLegsClosed) return VerificationResult(success=true)
  }
  return VerificationResult(success=false)  // Orders still pending in map
  ```
- **Real Scenario**: EUREX leg-by-leg order times out, returns false. Orders remain registered forever, can fill randomly.
- **Fix**: Cleanup pending orders when verification times out

---

### 9. Entry/Close Operations Not Atomic
- **File**: `PreTradeValidator.kt:35-44`
- **Risk**: HIGH
- **Issue**: Check CLOSING status, pause, order submitted. Status can clear between check and submit.
- **Code Pattern**:
  ```kotlin
  val closingSymbols = spreadPort.findByStatus(SpreadStatus.CLOSING).map { it.symbol }.toSet()
  if (request.underlyingSymbol in closingSymbols) return EXPOSURE_REJECTED
  // ... time passes
  // Thread A clears CLOSING spread
  // New orders get submitted while old ones still in IBKR
  orderExecutionPort.submitComboLimitOrder(...)
  ```
- **Real Scenario**: Spread moves from CLOSING to CLOSED between validator check and order submission.
- **Fix**: Make the check and submission atomic (single transaction), or use database lock

---

### 10. Position Verification Can Return True from Stale Cache
- **File**: `SpreadManagementService.kt:127-172`
- **Risk**: HIGH
- **Issue**: Position adapter returns stale cached data; verification incorrectly confirms close
- **Code Pattern**:
  ```kotlin
  val positions = port.getPositions()  // Could return 1-minute-old cache
  val soldLegClosed = !positions.any { pos -> ... }  // True if stale cache doesn't show position
  return true  // WRONG: Position might still be open in live IBKR
  ```
- **Real Scenario**: Position closed locally but market data adapter has 1m cache. Verification passes. New entry placed while position still open.
- **Fix**: Use live position query, not cache. Add explicit cache invalidation after close orders.

---

### 11. inFlightSymbols Cleanup Window
- **File**: `TradeExecutionService.kt:51-85`
- **Risk**: HIGH
- **Issue**: Exception handling leaves symbol available briefly before finally block runs
- **Code Pattern**:
  ```kotlin
  inFlightSymbols[symbol] = Unit
  try {
      executeInternal(request)  // Throws OrderRejected
  } finally {
      inFlightSymbols.remove(symbol)  // Runs AFTER exception
  }
  // Between throw and finally, symbol appears not in-flight to new requests
  ```
- **Real Scenario**: Order rejection throws exception. Finally block hasn't run yet. New request comes in, sees symbol not in-flight, allows new entry.
- **Fix**: Use try-with-resources or explicit locking to ensure cleanup before symbol is accessible

---

### 12. Order Registry Remove/Callback Race
- **File**: `IbkrOrderRegistry.kt:30-44`
- **Risk**: HIGH
- **Issue**: Between removing deferred and IBKR sending final status update, update is lost
- **Code Pattern**:
  ```kotlin
  override suspend fun awaitFill(orderId: Int): OrderStatus {
      val deferred = registry.pendingOrderStatus[orderId]
      return deferred.await()  // Returns
      // finally block below hasn't run yet, deferred still in map
      
      // MEANWHILE IBKR sends CANCELLED status
      // onOrderStatus() tries to complete deferred
      // Deferred already completed, update lost
  }
  finally {
      registry.pendingOrderStatus.remove(orderId)  // Cleanup too late
  }
  ```
- **Real Scenario**: Order fill callback arrives after deferred.await() returns. Update is lost, order state not recorded.
- **Fix**: Keep deferred in registry until explicitly removed, not in await() finally block

---

## MEDIUM PRIORITY ISSUES (11)

### 13. Entry Slippage No Rollback
- **File**: `FlagExecutionService.kt:160-167`
- **Issue**: Position marked OPEN without verifying slippage acceptable
- **Risk**: MEDIUM

### 14. No Atomic Spread Check Before Close
- **File**: `SpreadManagementService.kt:241-289`
- **Issue**: Another close could execute between check and order placement
- **Risk**: MEDIUM

### 15. EOD Liquidation No Per-Symbol Lock
- **File**: `FlagMonitorScheduler.kt:40-62`
- **Issue**: Manual close and scheduled liquidation can overlap
- **Risk**: MEDIUM

### 16. Hardcoded Delay Without Confirmation
- **File**: `IbkrBracketOrderAdapter.kt:108-111`
- **Issue**: 200ms sleep doesn't guarantee cancel completed
- **Risk**: MEDIUM

### 17. Cancel/Submit Time Window
- **File**: `IbkrOrderExecutionAdapter.kt:104-113`
- **Issue**: 10s timeout between cancel and new submit not reliable
- **Risk**: MEDIUM

### 18. Quantity Not Validated Between DB and IBKR
- **File**: `SpreadManagementService.kt:90-120`
- **Issue**: Orders assume DB quantity matches IBKR position
- **Risk**: MEDIUM

### 19. Timeout Assumes CANCELLED Status
- **File**: `OrderChaseService.kt:48-91`
- **Issue**: Final timeout leaves orders untracked in IBKR
- **Risk**: MEDIUM

### 20. No IBKR Position Verification in Entry Validator
- **File**: `PreTradeValidator.kt:45-58`
- **Issue**: DB-only validation misses actual IBKR positions
- **Risk**: MEDIUM

### 21. Detector Reset Not Atomic
- **File**: `FlagScannerService.kt:396`
- **Issue**: Double-fire possible if bars arrive during reset
- **Risk**: MEDIUM

### 22. Scheduler Blocking with runBlocking()
- **File**: `ScannerScheduler.kt:30`
- **Issue**: Blocking call can cause scheduler thread stalls
- **Risk**: MEDIUM

### 23. Cancel Timeout Loses Order Tracking
- **File**: `OrderChaseService.kt:97-105`
- **Issue**: Cancel timeout marks order as self-cancelled but IBKR might have it live
- **Risk**: MEDIUM

---

## Recommended Fix Priority

**Phase 1 (IMMEDIATE):**
1. Issue #1 - Partial fill rollback (unhedged positions)
2. Issue #2 - Multiple close attempts (race condition)
3. Issue #3 - Order replacement window (double fills)
4. Issue #5 - Stale SELL order window (like AMD)

**Phase 2 (THIS WEEK):**
5. Issue #7 - Cancel on verification fail
6. Issue #9 - Atomic check+submit
7. Issue #12 - Order registry race

**Phase 3 (THIS MONTH):**
- All other issues

---

## Pattern-Based Fixes

### Pattern 1: Orders Left in IBKR
**Issues**: 3, 5, 7, 8, 19, 23  
**Fix**: Always verify order status with IBKR before assuming state. Don't rely on timeouts.

### Pattern 2: Race Conditions on Symbol
**Issues**: 2, 6, 9, 14, 15, 21  
**Fix**: Implement per-symbol mutex. Make all check+action operations atomic.

### Pattern 3: State Without Verification
**Issues**: 10, 18, 20  
**Fix**: Query IBKR positions before and after operations. Cache invalidation on state changes.

### Pattern 4: Partial Fills Without Rollback
**Issues**: 1, 4, 13  
**Fix**: Implement immediate compensating trades. Track leg states independently.

### Pattern 5: Timeout Assumptions
**Issues**: 3, 16, 17, 22, 23  
**Fix**: Replace timeouts with explicit status queries. Confirm IBKR state, don't assume.

---

## Testing Recommendations

1. **Chaos Testing**: Inject 5-60s delays between operations, verify no double orders
2. **Concurrent Signals**: Send simultaneous entry/close signals, verify only one wins
3. **IBKR Latency**: Simulate high-latency cancellations (15s+), verify orders aren't double-filled
4. **Partial Fills**: Simulate one leg filling, other timing out, verify position is safe
5. **Cache Staleness**: Force position cache to be stale, verify verification still passes
6. **Scheduler Overlap**: Artificially slow down scheduler jobs to >60s, verify no race conditions

---

## Conclusion

The trading engine has a systematic issue with **concurrent operations on shared state without proper synchronization**. The AMD bug wasn't an outlier—it's a symptom of a deeper architectural problem.

Key insight: **Every operation that modifies position state (place order, close spread, change status) should be:**
- Atomic (can't be interrupted)
- Verified (confirms IBKR state, not just DB state)
- Guarded (per-symbol or global mutex)
- Idempotent (safe to retry without side effects)

Currently, none of these conditions are fully satisfied across the codebase.
