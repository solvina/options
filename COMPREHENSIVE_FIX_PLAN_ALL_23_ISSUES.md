Perfect. Now let me create the comprehensive implementation plan document. Since I'm in read-only mode, I'll provide the detailed plan in my response output.

---

# COMPREHENSIVE IMPLEMENTATION PLAN: ALL 23 ISSUES

**Date**: 2026-06-11  
**Scope**: Complete trading engine bug fixes  
**Total Effort**: 155-185 hours (6-7 weeks for 2 engineers)  
**Risk Level**: HIGH + MEDIUM (position-level bugs + operational issues)  
**Codebase**: Kotlin-based options trading engine (~165 files)

---

## EXECUTIVE SUMMARY

The trading engine has **23 identified issues** across 6 CRITICAL, 6 HIGH, and 11 MEDIUM priority levels. All issues share a common root cause: **concurrent operations on shared state without proper synchronization**. This plan provides:

1. **Summary table** of all 23 issues with effort/risk metrics
2. **Detailed analysis** for all 17 remaining issues (6 HIGH + 11 MEDIUM)
3. **Implementation roadmap** across 5 phases
4. **Testing strategy** for all 23 issues (92+ test cases total)
5. **Deployment safety gates** and monitoring framework

---

## SECTION A: SUMMARY TABLE - ALL 23 ISSUES

| # | Priority | Issue Name | Root Cause | Fix Pattern | Tests | Effort | Risk |
|---|----------|-----------|-----------|------------|-------|--------|------|
| 1 | CRITICAL | Partial Fill Rollback | Buy fills, sell times out → unhedged | Auto-rollback on partial | 4 | 12h | 🔴 HIGH |
| 2 | CRITICAL | Multiple Close Race | AtomicBoolean insufficient | Mutex serialization | 3 | 4h | 🟢 LOW |
| 3 | CRITICAL | Order Replace Window | 10s timeout not guaranteed | Remove timeout, verify removal | 4 | 8h | 🟡 MED |
| 4 | CRITICAL | Leg-by-Leg Unhedged | SHORT fills before LONG check | Real-time monitoring | 5 | 10h | 🔴 HIGH |
| 5 | CRITICAL | Stale SELL Window | Cancel not atomic with fill | Atomic verification loop | 4 | 8h | 🟡 MED |
| 6 | CRITICAL | No Symbol Mutex | Concurrent signals pass checks | Per-symbol Mutex | 3 | 5h | 🟢 LOW |
| 7 | HIGH | Force-Close No Cancel | Orders placed but verification times out | Cancel on verification fail | 4 | 6h | 🟡 MED |
| 8 | HIGH | Reconciliation Timeout Cleanup | Timeout leaves orders registered | Cleanup pending orders | 3 | 5h | 🟡 MED |
| 9 | HIGH | Entry/Close Not Atomic | Check + submit not atomic | Atomic transaction check | 4 | 7h | 🟡 MED |
| 10 | HIGH | Stale Cache Verification | Position adapter returns stale cache | Live position query | 3 | 5h | 🟡 MED |
| 11 | HIGH | inFlightSymbols Cleanup | Exception handling cleanup window | Lock-based ensure cleanup | 3 | 4h | 🟡 MED |
| 12 | HIGH | Order Registry Race | Deferred removed before final callback | Keep deferred until explicit remove | 4 | 6h | 🟡 MED |
| 13 | MEDIUM | Entry Slippage No Rollback | OPEN marked without slippage check | Verify slippage, rollback if needed | 3 | 5h | 🟡 MED |
| 14 | MEDIUM | No Atomic Spread Check | Another close between check + order | Atomic check within spread lock | 3 | 5h | 🟡 MED |
| 15 | MEDIUM | EOD Liquidation No Lock | Manual close + liquidation overlap | Add per-symbol lock | 3 | 5h | 🟡 MED |
| 16 | MEDIUM | Hardcoded Delay Confirm | 200ms sleep not guaranteed | Query status after delay | 3 | 4h | 🟢 LOW |
| 17 | MEDIUM | Cancel/Submit Time Window | 10s between cancel and submit unreliable | Verify cancel via query | 3 | 5h | 🟡 MED |
| 18 | MEDIUM | Quantity Not Validated | No DB vs IBKR quantity check | Validate before order placement | 3 | 4h | 🟢 LOW |
| 19 | MEDIUM | Timeout Assumes CANCELLED | Final timeout leaves orders untracked | Explicit cleanup on timeout | 3 | 5h | 🟡 MED |
| 20 | MEDIUM | No IBKR Position Verify Entry | DB-only validation misses IBKR positions | Query live IBKR positions | 4 | 6h | 🟡 MED |
| 21 | MEDIUM | Detector Reset Not Atomic | Double-fire if bars arrive during reset | Mutex-protect reset operation | 3 | 4h | 🟢 LOW |
| 22 | MEDIUM | Scheduler Blocking runBlocking | Blocking call stalls scheduler thread | Replace with async/await | 3 | 5h | 🟡 MED |
| 23 | MEDIUM | Cancel Timeout Loses Tracking | Cancel timeout marks order self-cancelled | Keep order tracked until confirmed | 3 | 5h | 🟡 MED |

**Totals**: 23 issues, 92 test cases, 155-185 hours, 15 HIGH/CRITICAL risk items

---

## SECTION B: DETAILED PLANS FOR HIGH PRIORITY ISSUES (6)

---

### HIGH PRIORITY ISSUE #7: Force-Close Doesn't Cancel Orders on Verification Fail

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/spread/SpreadManagementService.kt` (lines 83-120)

**Root Cause Analysis**:
When `forceCloseSpread()` places market orders for both legs but position verification times out (e.g., IBKR API slow), the function returns early with spread still in CLOSING state. However, the orders were already submitted to IBKR. Next retry will place duplicate orders, potentially causing double-close.

**Current Code Flow**:
```
1. Place buy-back market order (sold leg)
2. Place sell-back market order (bought leg)
3. Call verifyPositionClosed() - times out after 5 seconds
4. Return spread (still CLOSING)
5. Next monitor cycle: Place DUPLICATE orders for same spread
```

**Proposed Fix**:
1. Create `ForceCloseAttemptTracker` class to store order IDs placed in current close attempt
2. If `verifyPositionClosed()` returns false, immediately cancel both orders that were just placed
3. Implement exponential backoff: retry immediately, then 2s, 4s, 8s, 16s, finally give up
4. Mark spread as CLOSED_FAILED instead of staying CLOSING if all retries exhausted

**Implementation Strategy**:
- Modify `forceCloseSpread()` to catch verification timeout
- Call `orderPort.cancelAndAwait()` for both legs if verification fails
- If cancel also times out, mark spread as CLOSED_FAILED with reason "close_unverified"
- Add metrics: `spread.close.verification_failed`, `spread.close.cancel_on_fail`

**Test Cases** (4 tests):
1. **"Force-close verifies positions and retries on timeout"** - Verification times out, orders cancelled, spread retries
2. **"Force-close with cancel failure"** - Both verification and cancel timeout, spread marked CLOSED_FAILED
3. **"Force-close success path"** - Verification passes after retry, spread transitions to CLOSED
4. **"Force-close with partial verification"** - One leg verified, other not; keeps retrying until both confirmed or fail

**Risk Assessment**:
- Risk: MEDIUM (could add unnecessary cancel calls if verification just slow)
- Mitigation: Use 5s timeout for verification (generous), log all cancel attempts
- Regression: None (adds defensive behavior, doesn't change happy path)

**Dependencies**:
- Must complete BEFORE Issue #1 (partial fill rollback)
- Depends on OrderPort.cancelAndAwait() working correctly (Issue #3 prerequisite)

**Implementation Effort**: ~6 hours (code: 3h, tests: 2h, integration: 1h)

**Files to Create/Modify**:
- Create: `ForceCloseAttemptTracker.kt`
- Modify: `SpreadManagementService.kt` (forceCloseSpread method, add cancel logic)
- Modify: `SpreadPort.kt` (add CLOSED_FAILED persistence)
- Create: `IssueSevenTest.kt` (4 test cases)

---

### HIGH PRIORITY ISSUE #8: Position Reconciliation Timeout Doesn't Cleanup Pending Orders

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/account/PositionReconciliationService.kt` (lines 73-142)

**Root Cause Analysis**:
During leg-by-leg order execution (EUREX), orders are submitted and deferred are added to registry. If position verification times out (network issues), the method returns `VerificationResult(success=false)`. However, the deferred objects remain in `pendingOrderStatus` map indefinitely. Later, if IBKR sends a fill callback, it completes a deferred that's no longer being awaited, losing the fill information.

**Current Code Flow**:
```
1. Submit SHORT leg → orderId=123, add deferred to registry
2. Submit LONG leg → orderId=124, add deferred to registry  
3. Launch verification loop (timeout=5s)
4. Timeout → return false
5. Deferreds 123, 124 still in registry, will eventually fill
6. IBKR sends "FILLED" callback for orderId=123
7. onOrderStatus() completes deferred, but caller no longer listening
8. Position verification considers order lost
```

**Proposed Fix**:
1. When verification returns false, explicitly cleanup pending order deferreds
2. Add `cancelUnverifiedOrders()` method that:
   - Iterates over all pending deferreds from current verification attempt
   - Sends cancel request to IBKR for each
   - Removes from registry
3. Implement order tracking per verification attempt (not global)
4. On timeout, return list of cancelled order IDs

**Implementation Strategy**:
- Add `verificationAttemptId` to track orders from a single verification
- Create `OrderCleanupService` that handles the cancellation
- Modify `PositionReconciliationService.verifyBothLegs()` to:
  - Track orderIds being verified
  - On timeout: call cleanup service
  - Return failure with cancelled order list
- Add logging of each cleanup attempt

**Test Cases** (3 tests):
1. **"Verification timeout cancels pending orders"** - Orders cancelled after timeout
2. **"Verification success ignores cleanup"** - Happy path unaffected
3. **"Cleanup with IBKR latency"** - Cancels work even with high latency

**Risk Assessment**:
- Risk: MEDIUM (if cancel fails, orders might still be live)
- Mitigation: Log all cancellation attempts, track via metrics
- Regression: None (timeout case rarely happens, defensive cleanup only)

**Dependencies**:
- Depends on Issue #3 (order cancellation verification)
- Prerequisite for Issue #4 (leg-by-leg atomic operations)

**Implementation Effort**: ~5 hours (code: 2.5h, tests: 1.5h, integration: 1h)

**Files to Create/Modify**:
- Create: `OrderCleanupService.kt`
- Modify: `PositionReconciliationService.kt` (add cleanup on timeout)
- Modify: `IbkrOrderRegistry.kt` (track cleanup status)
- Create: `IssueEightTest.kt` (3 test cases)

---

### HIGH PRIORITY ISSUE #9: Entry/Close Operations Not Atomic

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/execution/PreTradeValidator.kt` (lines 35-58)

**Root Cause Analysis**:
The validator checks if symbol is in CLOSING state, then later attempts to submit order. Between these two operations, another thread could transition the spread from CLOSING to CLOSED. Validator would then allow a new order on an already-closed symbol, creating overlapping positions.

**Current Code Flow**:
```
Thread A (Validator):
1. Check: symbol "AAPL" not in CLOSING
2. Time passes...
3. Submit order for AAPL

Thread B (Monitor):
1. Close order fills
2. Transition spread from CLOSING to CLOSED
3. Time passes...

Result: Both orders for AAPL exist simultaneously
```

**Proposed Fix**:
1. Make validator check + order submission atomic using database-level locking
2. Create database lock on symbol before checking status
3. Hold lock until order is submitted and spread status updated
4. Use SELECT FOR UPDATE on spreads table filtered by symbol

**Implementation Strategy**:
- Create `AtomicEntryValidator` that:
  - Acquires symbol lock at DB level
  - Performs all checks within transaction
  - Submits order
  - Persists PENDING spread
  - Releases lock
- Use Spring @Transactional with explicit lock hints
- Add retry logic (3 retries with 100ms delays) if lock times out

**Test Cases** (4 tests):
1. **"Entry blocked while close in progress"** - Lock prevents concurrent operations
2. **"Close completes before entry"** - Entry waits, then succeeds
3. **"Multiple entry attempts serialized"** - Only one succeeds per symbol
4. **"Lock timeout handled gracefully"** - Retries, eventual success

**Risk Assessment**:
- Risk: MEDIUM (database locking could impact performance)
- Mitigation: Lock is held < 100ms (just order submission), no long-lived locks
- Regression: Slight latency increase for entries (1-5ms), acceptable trade-off

**Dependencies**:
- None (orthogonal to other fixes)
- Should precede Issues #1, #4 (both affect close/entry ordering)

**Implementation Effort**: ~7 hours (code: 3h, DB schema: 1h, tests: 2h, integration: 1h)

**Files to Create/Modify**:
- Create: `AtomicEntryValidator.kt`
- Modify: `PreTradeValidator.kt` (use atomic version)
- Modify: `SpreadRepository.kt` (add FOR UPDATE query)
- Create: `IssueNineTest.kt` (4 test cases)

---

### HIGH PRIORITY ISSUE #10: Position Verification Returns True from Stale Cache

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/spread/SpreadManagementService.kt` (lines 127-172)

**Root Cause Analysis**:
The position adapter used by `verifyPositionClosed()` may return cached data (up to 1 minute old). If a position was just closed, the cache might not reflect this. Verification would incorrectly return true (position closed) when position is still open in IBKR. Next entry would then be allowed on an open position.

**Current Code Flow**:
```
1. Close order executes at T=0
2. Position closes at IBKR at T=10ms
3. Position adapter has 60s cache TTL
4. Position adapter last update at T=-30s (before close)
5. verifyPositionClosed() queries adapter at T=15ms
6. Adapter returns cached data from T=-30s
7. Returns true (position shows as closed in stale cache)
8. Spread marked CLOSED
9. New entry submitted for same symbol
10. Position actually open in IBKR → double position
```

**Proposed Fix**:
1. Add cache invalidation trigger when close orders are placed
2. Query positions via fresh API call (not cache) after close
3. Implement `PositionQuery.freshOnly()` that bypasses cache
4. Compare DB state vs live IBKR state before deciding verification success

**Implementation Strategy**:
- Create `FreshPositionQuery` wrapper that:
  - Forces cache miss by adding timestamp parameter
  - Queries latest position state directly
  - Caches result for < 5 seconds
- Modify `verifyPositionClosed()` to use FreshPositionQuery
- Add explicit cache invalidation after close orders placed
- Log cache staleness (warn if cache age > 5s)

**Test Cases** (3 tests):
1. **"Verification uses fresh positions, not cache"** - Stale cache bypassed
2. **"Verification fails if position still open"** - Live query catches unverified close
3. **"Cache invalidation after close order"** - New entries wait for fresh state

**Risk Assessment**:
- Risk: MEDIUM (adds API calls, could slow verification)
- Mitigation: Fresh query only after close (rare), normal flow unaffected
- Regression: None (makes verification more conservative, safer)

**Dependencies**:
- Depends on Issue #3 (order cancellation must work to retry)
- Prerequisite for Issues #1, #4 (both rely on accurate verification)

**Implementation Effort**: ~5 hours (code: 2.5h, tests: 1.5h, integration: 1h)

**Files to Create/Modify**:
- Create: `FreshPositionQuery.kt`
- Modify: `SpreadManagementService.kt` (use fresh query)
- Modify: `PositionsPort.kt` (add freshOnly parameter)
- Create: `IssueTenTest.kt` (3 test cases)

---

### HIGH PRIORITY ISSUE #11: inFlightSymbols Cleanup Window

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/execution/TradeExecutionService.kt` (lines 51-85)

**Root Cause Analysis**:
When an exception is thrown in `executeInternal()`, the finally block that removes the symbol from `inFlightSymbols` executes after the exception is thrown. However, between the exception and finally block execution, a new request for the same symbol could be processed before finally block runs. This creates a brief window where symbol appears not in-flight to new requests.

**Current Code Flow**:
```
Thread A (Entry for AAPL):
1. inFlightSymbols["AAPL"] = Unit
2. executeInternal() throws OrderRejected
3. Exception thrown to caller

Thread B (New entry for AAPL):
1. Checks isInFlight("AAPL")
2. Returns false (not yet removed, but exception hiding it)
3. Allows new entry

Thread A:
3. Finally block: inFlightSymbols.remove("AAPL")
```

**Proposed Fix**:
1. Use explicit try-with-resources pattern with lock
2. Acquire symbol lock BEFORE adding to inFlightSymbols
3. Keep lock held until finally block completes
4. Guarantee atomicity at Java bytecode level

**Implementation Strategy**:
- Add symbol-level ReadWriteLock via SymbolMutexManager
- Wrap entire execution in write lock
- Lock ensures symbol unavailable to other threads until fully cleaned up
- Integrate with Issue #6 (SymbolMutexManager)

**Test Cases** (3 tests):
1. **"Exception in execution removes symbol from in-flight"** - Cleanup even on error
2. **"New entry waits for cleanup to complete"** - No race condition window
3. **"Multiple concurrent rejections cleaned up"** - All symbols cleaned before reentry

**Risk Assessment**:
- Risk: MEDIUM (if lock held too long, could block other entries)
- Mitigation: Lock held only during execution (~10 seconds max), not longer
- Regression: Slightly serializes entries (acceptable due to risk reduction)

**Dependencies**:
- Depends on Issue #6 (SymbolMutexManager implementation)
- Prerequisite for sound entry validation

**Implementation Effort**: ~4 hours (code: 2h, tests: 1.5h, integration: 0.5h)

**Files to Create/Modify**:
- Modify: `TradeExecutionService.kt` (add lock wrapper)
- Modify: `SymbolMutexManager.kt` (if created for Issue #6)
- Create: `IssueElevenTest.kt` (3 test cases)

---

### HIGH PRIORITY ISSUE #12: Order Registry Remove/Callback Race

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/adapters/outbound/ibkr/registry/IbkrOrderRegistry.kt` (lines 30-44)

**Root Cause Analysis**:
When `awaitFill(orderId)` returns, it's removed from `pendingOrderStatus` map in the finally block. However, between `deferred.await()` returning and the finally block executing, IBKR could send a final status update (e.g., CANCELLED). The `onOrderStatus()` callback would try to complete an already-completed deferred. If the deferred was already removed from the map, the update is lost.

**Current Code Flow**:
```
1. awaitFill(123) called
2. deferred.await() returns with FILLED status
3. IBKR callback arrives for orderId=123 → calls onOrderStatus()
4. onOrderStatus() tries: pendingOrderStatus[orderId] ?: return
5. Deferred already removed by finally block
6. Returns early, update lost
7. Order state never recorded
```

**Proposed Fix**:
1. Keep deferred in registry indefinitely until explicitly removed
2. Add `consumeFill(orderId)` method that removes AND returns status
3. Change `awaitFill()` to not remove immediately
4. Add explicit cleanup method called by order executor after final state known

**Implementation Strategy**:
- Create `OrderFillTracker` that tracks completed fills
- Deferreds stay in map until explicitly consumed
- Add `OrderFillPort.consume(orderId)` that:
  - Removes from pending
  - Returns the status that was completed
  - Handles already-consumed case
- Update callers to use consume() instead of await() directly

**Test Cases** (4 tests):
1. **"Order status update arrives after await returns"** - Status not lost
2. **"Multiple status updates for same order"** - First completion sticks, others ignored
3. **"Stale order status handled gracefully"** - Orphaned updates don't crash
4. **"Order cleanup removes from registry"** - Explicit removal works

**Risk Assessment**:
- Risk: MEDIUM (map could grow if cleanup not called)
- Mitigation: Add health check to log map size, alerts if growing unbounded
- Regression: Requires callers to call cleanup, must be enforced via type system

**Dependencies**:
- None (orthogonal to other fixes)
- Should precede Issues #3, #4 (both use order awaiting)

**Implementation Effort**: ~6 hours (code: 3h, tests: 2h, integration: 1h)

**Files to Create/Modify**:
- Create: `OrderFillTracker.kt`
- Modify: `IbkrOrderRegistry.kt` (separate await from remove)
- Modify: `OrderExecutionPort.kt` (add consume method)
- Create: `IssueTwelveTest.kt` (4 test cases)

---

## SECTION C: DETAILED PLANS FOR MEDIUM PRIORITY ISSUES (11)

---

### MEDIUM PRIORITY ISSUE #13: Entry Slippage No Rollback

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/flag/FlagExecutionService.kt` (lines 160-167)

**Root Cause Analysis**:
When bracket order fills, the position is immediately marked OPEN without verifying that slippage between order submission and fill is acceptable. If fill price is significantly worse than expected, position is at higher risk. No compensating close is placed to rollback.

**Current Code Flow**:
```
1. Bracket order submitted at limit price X
2. Order fills at actual price Y (worse than X)
3. Status immediately set to OPEN
4. No check if Y is acceptable
5. If slippage too large, position is locked in at bad price
```

**Proposed Fix**:
1. Before marking OPEN, verify fill price vs order price slippage
2. Calculate slippage percentage: (actualPrice - expectedPrice) / expectedPrice
3. If slippage > configurable threshold (default 0.5%), immediately place market close
4. Mark position as CLOSED_EXCESSIVE_SLIPPAGE with details

**Implementation Strategy**:
- Add `SlippageValidator` that:
  - Compares expected vs actual fill price
  - Calculates slippage percentage
  - Returns acceptable/rejected decision
- Modify `FlagExecutionService.onBracketFilled()` to:
  - Check slippage before persisting OPEN
  - Place immediate market close if rejected
  - Mark position CLOSED_EXCESSIVE_SLIPPAGE
- Add config: `trading.slippage.max-percent` (default 0.5%)

**Test Cases** (3 tests):
1. **"Entry within acceptable slippage"** - OPEN marked, position held
2. **"Entry with excessive slippage"** - Market close placed immediately
3. **"Slippage check configurable"** - Threshold can be adjusted

**Risk Assessment**:
- Risk: MEDIUM (could exit good positions due to false slippage triggers)
- Mitigation: Use conservative threshold (0.5%), log all rejections
- Regression: None (defensive measure, worse case is immediate close)

**Dependencies**:
- None (isolated to flag entry flow)
- Complements Issue #1 (position risk management)

**Implementation Effort**: ~5 hours (code: 2.5h, tests: 1.5h, integration: 1h)

**Files to Create/Modify**:
- Create: `SlippageValidator.kt`
- Modify: `FlagExecutionService.kt` (add slippage check)
- Modify: `FlagTradingConfig.kt` (add slippage threshold config)
- Create: `IssueThirteenTest.kt` (3 test cases)

---

### MEDIUM PRIORITY ISSUE #14: No Atomic Spread Check Before Close

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/spread/SpreadManagementService.kt` (lines 241-289)

**Root Cause Analysis**:
The `checkExits()` method finds spreads that should be closed, but another close could execute between the check and when orders are placed. This could cause double-close attempts on the same spread or orphaned close orders.

**Current Code Flow**:
```
Thread A (checkExits):
1. Query: spreads with profitTarget met
2. Get spread "ABC-123"
3. Time passes...
4. Place close order for "ABC-123"

Thread B (Manual force-close):
1. User force-closes same spread "ABC-123"
2. Places close order
3. Time passes...

Result: Both threads attempt to close same spread
```

**Proposed Fix**:
1. Move spread status to CLOSING within same transaction as query
2. Use optimistic locking (version field) to detect concurrent updates
3. Only proceed with close if status update succeeds
4. Rollback if concurrent close detected

**Implementation Strategy**:
- Add `version` field to BullPutSpread entity
- Modify `checkExits()` to:
  - Find candidate spreads (in read-only transaction)
  - For each spread: atomically transition OPEN → CLOSING
  - Only if transition succeeds, place close order
  - If concurrent close detected, skip with log
- Use Spring Data's @Version annotation for optimistic locking

**Test Cases** (3 tests):
1. **"Scheduled close and manual close don't double-order"** - First wins, second skipped
2. **"Concurrent closes on different spreads"** - Both execute
3. **"Status check and order placement atomic"** - No race window

**Risk Assessment**:
- Risk: MEDIUM (optimistic locking could cause false conflicts)
- Mitigation: Add retry logic (max 3 retries), skip after that
- Regression: None (just adds defensive atomicity)

**Dependencies**:
- Database schema change (add version field)
- Should precede Issues #1, #7 (both affect close execution)

**Implementation Effort**: ~5 hours (code: 2.5h, DB migration: 1h, tests: 1.5h)

**Files to Create/Modify**:
- Modify: `BullPutSpread.kt` (add @Version field)
- Modify: `SpreadManagementService.kt` (atomic status transition)
- Modify: `SpreadPersistenceAdapter.kt` (handle version conflicts)
- Create: `IssueFourteenTest.kt` (3 test cases)

---

### MEDIUM PRIORITY ISSUE #15: EOD Liquidation No Per-Symbol Lock

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/adapters/inbound/jobs/FlagMonitorScheduler.kt` (lines 40-62)

**Root Cause Analysis**:
End-of-day liquidation closing all positions can overlap with manual close from user. Both attempt to close the same position via different code paths, potentially causing issues with order sequencing or duplicate closes.

**Current Code Flow**:
```
EOD Scheduler (T=15:59:45):
1. Query all open flag positions
2. Place market close orders

User (T=15:59:47):
1. Manual force-close on same symbol
2. Places different close orders

Result: Two separate close attempts, orders conflict
```

**Proposed Fix**:
1. Use per-symbol lock for EOD liquidation, same as regular closes
2. EOD acquires lock before liquidating a position
3. Manual close tries to acquire same lock
4. If already held by EOD, manual close waits or skips

**Implementation Strategy**:
- Integrate with SymbolMutexManager (Issue #6)
- Modify `FlagMonitorScheduler.liquidateEodPositions()` to:
  - For each position: acquire symbol lock
  - Place liquidation orders
  - Hold lock until orders filled/cancelled
  - Release lock
- Add config: `trading.eod-liquidation.enabled` (default true)
- Log all EOD liquidations with symbol lock timing

**Test Cases** (3 tests):
1. **"EOD liquidation acquires symbol lock"** - Lock prevents manual close
2. **"Manual close waits for EOD liquidation"** - Serialized execution
3. **"EOD liquidation on multiple symbols concurrent"** - Different locks, no contention

**Risk Assessment**:
- Risk: LOW (both code paths safe, just need serialization)
- Mitigation: Timeout on lock acquisition (1s), skip if timeout
- Regression: None (adds defensive serialization)

**Dependencies**:
- Depends on Issue #6 (SymbolMutexManager)
- Complements Issue #14 (spread-level atomicity)

**Implementation Effort**: ~5 hours (code: 2.5h, tests: 1.5h, integration: 1h)

**Files to Create/Modify**:
- Modify: `FlagMonitorScheduler.kt` (add symbol locking)
- Modify: `SymbolMutexManager.kt` (ensure handles multiple locks)
- Create: `IssueFifteenTest.kt` (3 test cases)

---

### MEDIUM PRIORITY ISSUE #16: Hardcoded Delay Without Confirmation

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/adapters/outbound/ibkr/IbkrBracketOrderAdapter.kt` (lines 108-111)

**Root Cause Analysis**:
The code places a 200ms delay after cancelling orders, assuming this is enough time for IBKR to process the cancel. No verification is done to confirm the cancel actually completed. If network is slow, the delay could be insufficient and new orders placed while old ones still live.

**Current Code Flow**:
```
1. Cancel order requested
2. delay(200)  // Sleep 200ms
3. Assume cancel is done
4. Place new order
5. But IBKR cancel still in flight, old order might execute
```

**Proposed Fix**:
1. After delay, query IBKR open orders to verify cancel completed
2. Retry cancellation if order still present
3. Only proceed with new order after confirmation
4. Log actual cancel confirmation time vs expected 200ms

**Implementation Strategy**:
- Create `VerifiedCancelService` that:
  - Issues cancel request
  - Waits 200ms
  - Queries open orders to verify removal
  - Retries if still present (max 3 retries)
  - Returns confirmation or failure
- Replace all hardcoded delays with VerifiedCancelService
- Add metric: `order.cancel.verification_time_ms` (histogram)

**Test Cases** (3 tests):
1. **"Delay followed by verification confirmation"** - Order actually gone
2. **"Cancel takes longer than 200ms"** - Verification retries until confirmed
3. **"High latency scenario"** - Still verifies even with slow IBKR

**Risk Assessment**:
- Risk: LOW (defensive measure, adds API calls)
- Mitigation: Retries max 3x (600ms total), then fails hard
- Regression: None (more robust than current hardcoded delay)

**Dependencies**:
- None (can be implemented independently)
- Complements Issue #3 (order replacement)

**Implementation Effort**: ~4 hours (code: 2h, tests: 1.5h, integration: 0.5h)

**Files to Create/Modify**:
- Create: `VerifiedCancelService.kt`
- Modify: `IbkrBracketOrderAdapter.kt` (use verified cancel)
- Modify: `IbkrOrderExecutionAdapter.kt` (use verified cancel)
- Create: `IssueSixteenTest.kt` (3 test cases)

---

### MEDIUM PRIORITY ISSUE #17: Cancel/Submit Time Window

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/adapters/outbound/ibkr/IbkrOrderExecutionAdapter.kt` (lines 104-113)

**Root Cause Analysis**:
Similar to Issue #16, but at a higher level. The 10-second timeout on cancel isn't guaranteed to be long enough for the cancel to complete at IBKR. New order submitted after timeout expires, but old order might still execute in IBKR, causing double fill.

**Current Code Flow**:
```
1. Cancel order with 10s timeout
2. Timeout expires at T=10s
3. Issue new order at T=10.1s
4. IBKR cancel arrives at T=12s
5. Old order still live, both execute
```

**Proposed Fix**:
1. Remove hardcoded 10s timeout on cancel
2. Query IBKR to verify order actually removed
3. Retry up to 10 times with 500ms delays (5s total)
4. Only submit new order after confirmation
5. If verification fails, return error (don't submit new order)

**Implementation Strategy**:
- Create `OrderReplacementService` that:
  - Cancels current order
  - Verifies removal via openOrders query (retry loop)
  - Submits new order only after verification
  - Returns new order ID or error
- Modify `replaceComboWithNewPrice()` to use OrderReplacementService
- Add metrics: `order.replace.cancel_confirmation_time_ms`, `order.replace.failed`
- Log all replacements with timing data

**Test Cases** (3 tests):
1. **"Order replacement waits for cancel confirmation"** - Verification before new submit
2. **"Replacement with slow cancel (high latency)"** - Retries until confirmed
3. **"Replacement fails if cancel can't be confirmed"** - Error returned, new order not submitted

**Risk Assessment**:
- Risk: MEDIUM (could timeout if IBKR very slow)
- Mitigation: 5s retry timeout is generous, most cancels complete in 100ms
- Regression: None (safer than current timeout approach)

**Dependencies**:
- None (can be implemented independently)
- Complements Issue #3 (order replacement atomicity)

**Implementation Effort**: ~5 hours (code: 2.5h, tests: 1.5h, integration: 1h)

**Files to Create/Modify**:
- Create: `OrderReplacementService.kt`
- Modify: `IbkrOrderExecutionAdapter.kt` (use replacement service)
- Modify: `ScannerConfig.kt` (add order.replace timeout config)
- Create: `IssueSeventeenTest.kt` (3 test cases)

---

### MEDIUM PRIORITY ISSUE #18: Quantity Not Validated Between DB and IBKR

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/spread/SpreadManagementService.kt` (lines 90-120)

**Root Cause Analysis**:
Close orders assume the quantity in the database matches the actual position quantity in IBKR. If a manual trade was made in IBKR (not through the bot), the quantities would diverge. Closing with mismatched quantity could leave part of the position open.

**Current Code Flow**:
```
1. DB says: 10 contracts of ABC-123
2. User manually closes 5 in IBKR
3. Bot still has 10 in DB
4. Monitor triggers close for 10
5. IBKR only has 5, closes 5, bot thinks all 10 closed
```

**Proposed Fix**:
1. Before close, query IBKR for actual position quantity
2. Compare with DB quantity
3. If mismatch > tolerance (e.g., 1 contract), log warning and use IBKR quantity
4. Close with IBKR quantity, not DB quantity

**Implementation Strategy**:
- Create `QuantityValidator` that:
  - Queries current position from IBKR
  - Compares with DB quantity
  - Logs discrepancies
  - Returns IBKR quantity as source-of-truth
- Modify `closeSpread()` to call QuantityValidator before placing orders
- Add config: `trading.quantity.mismatch-tolerance` (default 1 contract)
- Add metric: `position.quantity_mismatch` (counter)

**Test Cases** (3 tests):
1. **"Quantity matches DB and IBKR"** - Close proceeds with DB quantity
2. **"Quantity mismatch detected"** - Uses IBKR quantity, logs warning
3. **"Large mismatch handled safely"** - Still closes with verified quantity

**Risk Assessment**:
- Risk: LOW (defensive measure, uses IBKR source-of-truth)
- Mitigation: Log all mismatches, alert on large discrepancies
- Regression: None (makes system more robust)

**Dependencies**:
- None (can be implemented independently)
- Complements Issue #10 (position verification)

**Implementation Effort**: ~4 hours (code: 2h, tests: 1.5h, integration: 0.5h)

**Files to Create/Modify**:
- Create: `QuantityValidator.kt`
- Modify: `SpreadManagementService.kt` (call validator before close)
- Modify: `ScannerConfig.kt` (add quantity tolerance config)
- Create: `IssueEighteenTest.kt` (3 test cases)

---

### MEDIUM PRIORITY ISSUE #19: Timeout Assumes CANCELLED Status

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/order/OrderChaseService.kt` (lines 48-91)

**Root Cause Analysis**:
When waiting for an order fill times out, the code assumes the order is CANCELLED in IBKR. However, the order might still be live or might have filled after the timeout. The registry is updated to reflect CANCELLED, but the true state in IBKR is unknown. Next verification could find the order actually filled or still live, creating inconsistency.

**Current Code Flow**:
```
1. awaitFill() times out after timeout
2. Code assumes order is CANCELLED
3. Marks registry as CANCELLED
4. Returns CANCELLED status to caller
5. But order might be live in IBKR or filled
6. Next reconciliation finds discrepancy
```

**Proposed Fix**:
1. On timeout, don't assume status - query IBKR instead
2. Call getOpenOrders() to check if order still live
3. If still live, leave it (don't mark CANCELLED)
4. If not found, mark as CANCELLED
5. Return actual status from IBKR, not assumed status

**Implementation Strategy**:
- Create `TimeoutStatusResolver` that:
  - On timeout, queries openOrders
  - Checks if orderId is present
  - Returns actual status (CANCELLED or unknown)
  - Logs the resolution
- Modify `OrderChaseService.awaitFill()` to use TimeoutStatusResolver
- Add metric: `order.timeout_status_resolution` (counter by actual status)
- Log all timeout resolutions with findings

**Test Cases** (3 tests):
1. **"Timeout with order still live"** - Status stays unknown, order tracked
2. **"Timeout with order removed"** - Status marked CANCELLED based on query
3. **"Timeout with filled order"** - Status marked FILLED, position closed"

**Risk Assessment**:
- Risk: MEDIUM (adds latency on timeout paths)
- Mitigation: Timeout is already 60+ seconds, additional 1s query is acceptable
- Regression: None (removes incorrect assumptions)

**Dependencies**:
- None (can be implemented independently)
- Complements Issue #12 (order registry race)

**Implementation Effort**: ~5 hours (code: 2.5h, tests: 1.5h, integration: 1h)

**Files to Create/Modify**:
- Create: `TimeoutStatusResolver.kt`
- Modify: `OrderChaseService.kt` (query on timeout)
- Modify: `IbkrOrderRegistry.kt` (track timeout resolutions)
- Create: `IssueNineteenTest.kt` (3 test cases)

---

### MEDIUM PRIORITY ISSUE #20: No IBKR Position Verification in Entry Validator

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/execution/PreTradeValidator.kt` (lines 45-58)

**Root Cause Analysis**:
The entry validator checks if symbol is in IBKR open orders or DB open positions. However, it doesn't verify actual IBKR position state. If a position exists in IBKR but isn't reflected in DB (e.g., from a manual trade or recovery failure), the validator would incorrectly allow a new entry, creating multiple positions.

**Current Code Flow**:
```
1. Validator checks: symbol not in DB open spreads
2. Validator checks: symbol not in IBKR open orders
3. But doesn't check: actual IBKR positions
4. If position exists from manual trade: not found by validator
5. New entry allowed → multiple positions on same symbol
```

**Proposed Fix**:
1. Query current IBKR positions before allowing entry
2. Check if symbol already has position (long or short)
3. Block entry if position exists, regardless of DB state
4. Use fresh position query (not cached)

**Implementation Strategy**:
- Add `PositionExistenceValidator` that:
  - Queries fresh IBKR positions
  - Checks for symbol presence
  - Returns position details if exists
  - Blocks entry if found
- Modify `PreTradeValidator.validate()` to call PositionExistenceValidator
- Add metric: `entry.blocked_ibkr_position` (counter)
- Log all IBKR position discoveries

**Test Cases** (4 tests):
1. **"Entry allowed when no IBKR position"** - Happy path
2. **"Entry blocked by existing IBKR position"** - Prevents double position
3. **"Entry allowed after position closed in IBKR"** - Fresh query sees closure
4. **"IBKR position not in DB"** - DB out-of-sync detected and blocked"

**Risk Assessment**:
- Risk: MEDIUM (adds API calls to entry path)
- Mitigation: Call is fast, cached for 10 seconds, rare blocking cases
- Regression: None (safer, catches DB out-of-sync)

**Dependencies**:
- Depends on Issue #10 (fresh position query implementation)
- Should precede high-volume entry scenarios

**Implementation Effort**: ~6 hours (code: 3h, tests: 2h, integration: 1h)

**Files to Create/Modify**:
- Create: `PositionExistenceValidator.kt`
- Modify: `PreTradeValidator.kt` (call position validator)
- Modify: `PositionsPort.kt` (ensure fresh query available)
- Create: `IssueTwentyTest.kt` (4 test cases)

---

### MEDIUM PRIORITY ISSUE #21: Detector Reset Not Atomic

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/flag/FlagScannerService.kt` (line 396)

**Root Cause Analysis**:
The pattern detector's `reset()` method sets state back to Idle, but another thread could add bars to the buffer after reset is called but before the detector's state variable is updated. This could cause the signal to fire twice - once before reset, once after bars added but before state set to Idle.

**Current Code Flow**:
```
Thread A (reset):
1. detector.reset()  // Sets state = Idle

Thread B (onBar):
1. Add bar to buffer
2. Call detector.onNewBar()  // Might trigger signal
3. Signal fires → Entry placed

Thread A:
3. state = Idle  // Too late, signal already fired

Result: Double entry
```

**Proposed Fix**:
1. Make reset() atomic with state-clearing
2. Use lock around both reset and onNewBar() calls
3. Ensure state transition and bar addition can't interleave

**Implementation Strategy**:
- Add `Mutex` to PatternDetector
- Wrap reset() with lock acquisition/release
- Wrap onNewBar() with lock acquisition/release
- Ensure bar addition and state check are atomic
- Create `AtomicPatternDetector` wrapper if needed

**Test Cases** (3 tests):
1. **"Reset and onNewBar don't race"** - State mutation atomic
2. **"Signal fires once per breakout"** - No double-fire
3. **"Concurrent bar additions and resets"** - Thread-safe

**Risk Assessment**:
- Risk: LOW (just adds mutex, detector runs infrequently)
- Mitigation: Lock held < 1ms (just state check), minimal impact
- Regression: None (makes system safer)

**Dependencies**:
- None (can be implemented independently)
- Complements Issue #6 (symbol-level locking)

**Implementation Effort**: ~4 hours (code: 2h, tests: 1.5h, integration: 0.5h)

**Files to Create/Modify**:
- Modify: `PatternDetector.kt` (add mutex to reset/onNewBar)
- Modify: `FlagScannerService.kt` (ensure mutex used)
- Create: `IssueTwentyoneTest.kt` (3 test cases)

---

### MEDIUM PRIORITY ISSUE #22: Scheduler Blocking with runBlocking()

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/adapters/inbound/jobs/ScannerScheduler.kt` (line 30)

**Root Cause Analysis**:
The scheduler uses `runBlocking()` to run async code from a scheduled task. This blocks the entire scheduler thread while the async work runs. If the async work takes a long time (e.g., 30 seconds due to slow market data), the scheduler thread is blocked and can't process other scheduled tasks.

**Current Code Flow**:
```
1. @Scheduled task runs
2. runBlocking { ... }  // Thread blocked
3. Async work takes 30s
4. Other @Scheduled tasks queue up
5. Only execute after current finishes
```

**Proposed Fix**:
1. Replace `runBlocking()` with `scope.launch()` for fire-and-forget tasks
2. For tasks requiring completion, use `scope.launch() + await` in async context
3. Use ThreadPoolTaskScheduler to allow multiple tasks to run concurrently

**Implementation Strategy**:
- Create async wrapper for scheduler methods
- Replace `runBlocking()` with scope.launch()
- Add completion callbacks for monitoring
- Modify task execution to use async/await pattern
- Ensure no blocking operations in scheduled tasks

**Test Cases** (3 tests):
1. **"Scheduler doesn't block on long-running tasks"** - Concurrent execution
2. **"Multiple scheduled tasks run in parallel"** - No task starvation
3. **"Task completion still tracked"** - Async monitoring works

**Risk Assessment**:
- Risk: MEDIUM (removes blocking, could cause overlapping tasks)
- Mitigation: Use existing monitors (Issue #2) to prevent overlaps
- Regression: None (improves responsiveness)

**Dependencies**:
- Depends on Issue #2 (mutex-based overlap prevention)
- Should precede performance tuning

**Implementation Effort**: ~5 hours (code: 2.5h, tests: 1.5h, integration: 1h)

**Files to Create/Modify**:
- Modify: `ScannerScheduler.kt` (remove runBlocking)
- Modify: `FlagMonitorScheduler.kt` (use async pattern)
- Modify: `SpreadMonitorScheduler.kt` (use async pattern)
- Create: `IssueTwentytwoTest.kt` (3 test cases)

---

### MEDIUM PRIORITY ISSUE #23: Cancel Timeout Loses Order Tracking

**File**: `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/order/OrderChaseService.kt` (lines 97-105)

**Root Cause Analysis**:
When a cancel request times out, the code marks the order as self-cancelled in the registry (`markSelfCancelled()`), but doesn't verify if the cancel actually succeeded in IBKR. The order could still be live and could execute. Next check would find the order in IBKR and create state mismatch.

**Current Code Flow**:
```
1. Cancel order issued
2. Wait times out after 10s
3. Mark order as self-cancelled
4. Remove from tracking
5. Order is actually still live in IBKR
6. Order executes 5 minutes later
7. Fill callback arrives but order not tracked
```

**Proposed Fix**:
1. On cancel timeout, don't mark self-cancelled
2. Keep order in tracking (mark as "cancel_in_flight")
3. Query IBKR status periodically
4. Update tracking when status is confirmed
5. Only mark self-cancelled after confirmation

**Implementation Strategy**:
- Add `cancel_in_flight` status to registry
- Modify cancel timeout handler to set this status (not self-cancelled)
- Launch periodic verification (every 5 seconds) for in-flight cancels
- On verification, update status (CANCELLED or FILLED)
- Cleanup after confirmation

**Test Cases** (3 tests):
1. **"Cancel timeout keeps order tracked"** - Status marked as in-flight
2. **"Periodic verification confirms cancel"** - Status updated after verification
3. **"Order fills while cancel in-flight"** - Fill recorded correctly

**Risk Assessment**:
- Risk: MEDIUM (adds periodic background tasks)
- Mitigation: Verification runs every 5 seconds, cleans up after confirmation
- Regression: None (safer tracking)

**Dependencies**:
- None (can be implemented independently)
- Complements Issue #12 (order registry tracking)

**Implementation Effort**: ~5 hours (code: 2.5h, tests: 1.5h, integration: 1h)

**Files to Create/Modify**:
- Modify: `IbkrOrderRegistry.kt` (add cancel_in_flight status)
- Create: `CancelVerificationService.kt` (periodic verification)
- Modify: `OrderChaseService.kt` (use verification service)
- Create: `IssueTwentythreeTest.kt` (3 test cases)

---

## SECTION D: IMPLEMENTATION SEQUENCING & DEPENDENCY GRAPH

### Phase 1: Foundation & Low-Risk Fixes (Week 1 MON-TUE) - 9 hours

**Issues**: #2, #6, #16, #18, #21  
**Risk**: LOW  
**Deploy Gate**: Existing tests pass, no new failures

```
[#2] SpreadMonitorScheduler Mutex [4h]
     └─ Modifies: SpreadMonitorScheduler.kt, IbkrOrderRegistry.kt
     └─ Creates: MonitorMutexService.kt

[#6] SymbolMutexManager [5h]
     ├─ Modifies: FlagScannerService.kt
     ├─ Creates: SymbolMutexManager.kt
     └─ Dependency: None

[#16] VerifiedCancelService [4h]
     ├─ Modifies: IbkrBracketOrderAdapter.kt
     └─ Creates: VerifiedCancelService.kt

[#18] QuantityValidator [4h]
     ├─ Modifies: SpreadManagementService.kt
     └─ Creates: QuantityValidator.kt

[#21] AtomicPatternDetector [4h]
     └─ Modifies: PatternDetector.kt, FlagScannerService.kt

Total: 21 hours
```

### Phase 2: High-Risk Medium-Effort Fixes (Week 1 THU-FRI) - 22 hours

**Issues**: #3, #5, #8, #11, #14, #15, #17, #20, #22, #23  
**Risk**: MEDIUM  
**Deploy Gate**: Paper trading 24 hours, 0 unintended positions

```
[#3] OrderReplacementService [8h]
     ├─ Modifies: IbkrOrderExecutionAdapter.kt
     ├─ Creates: OrderReplacementService.kt
     └─ Dependency: None (but prerequisite for #4)

[#5] OrderCancellationService [8h]
     ├─ Modifies: StartupRecoveryService.kt, IbkrOrderRegistry.kt
     ├─ Creates: OrderCancellationService.kt
     └─ Dependency: #3 (order cancellation verification)

[#8] OrderCleanupService [5h]
     ├─ Modifies: PositionReconciliationService.kt
     ├─ Creates: OrderCleanupService.kt
     └─ Dependency: #3 (order cancellation)

[#11] inFlightSymbols Lock [4h]
     ├─ Modifies: TradeExecutionService.kt
     └─ Dependency: #6 (SymbolMutexManager)

[#14] AtomicSpreadCheck [5h]
     ├─ Modifies: SpreadManagementService.kt, BullPutSpread.kt
     ├─ DB: Add version field
     └─ Dependency: None

[#15] EOD Liquidation Lock [5h]
     ├─ Modifies: FlagMonitorScheduler.kt
     └─ Dependency: #6 (SymbolMutexManager)

[#17] OrderReplacementService Enhancement [5h]
     ├─ Modifies: IbkrOrderExecutionAdapter.kt
     └─ Dependency: #3 (foundation already there)

[#20] PositionExistenceValidator [6h]
     ├─ Modifies: PreTradeValidator.kt
     ├─ Creates: PositionExistenceValidator.kt
     └─ Dependency: #10 (fresh position query)

[#22] Scheduler Async Pattern [5h]
     ├─ Modifies: ScannerScheduler.kt, FlagMonitorScheduler.kt
     └─ Dependency: #2 (mutex overlap prevention)

[#23] CancelVerificationService [5h]
     ├─ Modifies: OrderChaseService.kt, IbkrOrderRegistry.kt
     ├─ Creates: CancelVerificationService.kt
     └─ Dependency: None

Total: 57 hours
```

### Phase 3: Critical High-Risk Fixes (Week 2 MON-WED) - 25 hours

**Issues**: #1, #4, #7, #9, #10, #12, #13, #19  
**Risk**: HIGH  
**Deploy Gate**: Chaos tests pass, paper trading 48 hours

```
[#7] ForceCloseAttemptTracker [6h]
     ├─ Modifies: SpreadManagementService.kt
     ├─ Creates: ForceCloseAttemptTracker.kt
     └─ Dependency: #3 (order cancellation works)

[#9] AtomicEntryValidator [7h]
     ├─ Modifies: PreTradeValidator.kt
     ├─ Creates: AtomicEntryValidator.kt
     └─ Dependency: None

[#10] FreshPositionQuery [5h]
     ├─ Modifies: SpreadManagementService.kt, PositionsPort.kt
     ├─ Creates: FreshPositionQuery.kt
     └─ Dependency: None

[#12] OrderFillTracker [6h]
     ├─ Modifies: IbkrOrderRegistry.kt, OrderExecutionPort.kt
     ├─ Creates: OrderFillTracker.kt
     └─ Dependency: None

[#13] SlippageValidator [5h]
     ├─ Modifies: FlagExecutionService.kt
     ├─ Creates: SlippageValidator.kt
     └─ Dependency: None

[#19] TimeoutStatusResolver [5h]
     ├─ Modifies: OrderChaseService.kt
     ├─ Creates: TimeoutStatusResolver.kt
     └─ Dependency: None

[#1] PartialFillRollback [12h]
     ├─ Modifies: SpreadManagementService.kt, BullPutSpread.kt
     ├─ Creates: CloseAttemptService.kt
     ├─ DB: Add order tracking fields
     └─ Dependency: #7 (force close works), #3 (order replacement)

[#4] LegByLegRealTimeMonitoring [10h]
     ├─ Modifies: LegByLegOrderStrategy.kt, PositionReconciliationService.kt
     ├─ Creates: RealTimeMonitoringService.kt
     └─ Dependency: #10 (position verification), #8 (order cleanup)

Total: 56 hours
```

### Phase 4: Integration & Validation (Week 2 THU-FRI) - 20 hours

**Activities**:
- Run all 92 tests together (integration suite)
- Chaos testing (10 iterations each scenario)
- Paper trading validation (24 hours)
- Monitoring dashboard setup

### Phase 5: Production Deployment (Week 3+)

**Deployment Order** (one fix at a time):
1. Issue #2, #6, #16, #18, #21 (low-risk batch)
2. Issue #3, #5, #8, #11 (medium-risk batch)
3. Issue #14, #15, #17, #20, #22, #23 (medium-risk batch)
4. Issue #7, #9, #10, #12, #13, #19 (high-risk batch)
5. Issue #1, #4 (critical-risk batch)

Each deployment:
- Deploy to staging first
- Paper trading validation (24h)
- Canary deploy (1% of traffic)
- Full production deployment
- 48h monitoring

---

## SECTION E: TESTING STRATEGY FOR ALL 23 ISSUES

### Test Framework Setup

```kotlin
// Base test class for all issue tests
abstract class IssueFixTest {
    protected fun mockIbkrAdapter(behavior: IbkrBehavior) { }
    protected fun mockMarketData(prices: Map<Symbol, BigDecimal>) { }
    protected fun delayedCallback(orderId: Int, status: OrderStatus, delayMs: Long) { }
    protected fun networkLatency(delayMs: Long) { }
    protected fun concurrentOperations(vararg ops: suspend () -> Unit) { }
}

// Test naming convention
Test("Scenario description", "Given/When/Then format")
```

### Test Matrix for All 23 Issues

| Issue | Unit Tests | Integration | Chaos | Total |
|-------|-----------|-------------|-------|-------|
| #1 (Partial Fill) | 4 | 2 | 2 | 8 |
| #2 (Multiple Close) | 3 | 2 | 2 | 7 |
| #3 (Order Replace) | 4 | 2 | 2 | 8 |
| #4 (Leg-by-Leg) | 5 | 3 | 2 | 10 |
| #5 (Stale SELL) | 4 | 2 | 2 | 8 |
| #6 (Symbol Mutex) | 3 | 2 | 2 | 7 |
| #7 (Force-Close) | 4 | 1 | 1 | 6 |
| #8 (Reconciliation) | 3 | 1 | 1 | 5 |
| #9 (Entry/Close) | 4 | 2 | 1 | 7 |
| #10 (Stale Cache) | 3 | 2 | 1 | 6 |
| #11 (inFlightSymbols) | 3 | 1 | 1 | 5 |
| #12 (Order Registry) | 4 | 2 | 1 | 7 |
| #13 (Slippage) | 3 | 1 | 1 | 5 |
| #14 (Atomic Check) | 3 | 2 | 1 | 6 |
| #15 (EOD Lock) | 3 | 1 | 1 | 5 |
| #16 (Verified Cancel) | 3 | 1 | 1 | 5 |
| #17 (Cancel/Submit) | 3 | 1 | 1 | 5 |
| #18 (Quantity) | 3 | 1 | 1 | 5 |
| #19 (Timeout Status) | 3 | 1 | 1 | 5 |
| #20 (Position Verify) | 4 | 2 | 1 | 7 |
| #21 (Detector Reset) | 3 | 1 | 1 | 5 |
| #22 (Scheduler) | 3 | 1 | 1 | 5 |
| #23 (Cancel Tracking) | 3 | 1 | 1 | 5 |

**Total**: 92 test cases (52 unit + 28 integration + 12 chaos)

### Chaos Test Scenarios

```
1. IBKR Latency Injection (5-60s delays)
   - Issue #3, #5, #7, #17, #19 affected
   - Verify cancels/verifications still work

2. Concurrent Operations (100+ operations/sec)
   - Issue #2, #6, #9, #11, #14, #15, #21, #22 affected
   - Verify no race conditions, consistent state

3. Partial Fills & Timeouts
   - Issue #1, #4, #8, #12, #23 affected
   - Verify position safety, no orphaned orders

4. Position Discrepancy (DB vs IBKR)
   - Issue #10, #18, #20 affected
   - Verify reconciliation catches mismatches

5. Order Replacement Under Stress
   - Issue #3, #17 affected
   - Rapid price changes, concurrent cancels

6. Scheduler Overlap (slow execution)
   - Issue #2, #6, #22 affected
   - Multiple monitor cycles running simultaneously

```

### Regression Testing

**Scope**: All 165 existing files, ensure no behavior change to happy path

**Coverage**:
- SpreadManagementService existing tests (15 tests)
- TradeExecutionService existing tests (20 tests)
- OrderExecution existing tests (12 tests)
- FlagScanner existing tests (18 tests)
- Integration tests (30 tests)

**Success Criteria**: All 95 existing tests pass with 0 modifications

---

## SECTION F: MONITORING & VALIDATION FRAMEWORK

### Metrics to Add (Per-Issue)

```
Issue #1: Partial Fill Rollback
  - spread.close.partial_fill_detected (counter)
  - spread.close.rollback_executed (counter)
  - Alert: > 0 in 24h

Issue #2: Multiple Close Race
  - monitor.concurrent_runs (gauge)
  - monitor.skipped_overlaps (counter)
  - Alert: concurrent > 0

Issue #3: Order Replace Window
  - order.replace.duration_seconds (histogram: p95/p99)
  - order.replace.failed (counter)
  - Alert: failed > 0

Issue #4: Leg-by-Leg Unhedged
  - leg_by_leg.submission_success_rate (gauge)
  - leg_by_leg.short_only (counter)
  - position.reconciliation.success_rate (gauge)
  - Alert: short_only > 0

Issue #5: Stale SELL Window
  - recovery.orders_cancelled (counter)
  - recovery.cancel_failed (counter)
  - recovery.cancel_duration_seconds (histogram)
  - Alert: cancel_failed > 0

Issue #6: No Symbol Mutex
  - scanner.symbol_locks.active (gauge)
  - scanner.symbol_locks.wait_time_ms (histogram)
  - scanner.concurrent_breakouts (counter per symbol)
  - Alert: wait_time > 1000ms

Issue #7: Force-Close No Cancel
  - spread.close.verification_failed (counter)
  - spread.close.cancel_on_fail (counter)
  - Alert: verification_failed > 0

Issue #8: Reconciliation Timeout
  - reconciliation.timeout_cleanup (counter)
  - reconciliation.pending_order_cleanup (counter)
  - Alert: pending_cleanup > 0

Issue #9: Entry/Close Atomic
  - entry.atomic_check_failures (counter)
  - entry.db_lock_wait_ms (histogram)
  - Alert: check_failures > 0

Issue #10: Stale Cache
  - position.fresh_query_cache_misses (counter)
  - position.staleness_detected (counter)
  - Alert: misses > 0 per 24h

Issue #11: inFlightSymbols Cleanup
  - execution.symbol_cleanup_failures (counter)
  - execution.in_flight_cleanup_time_ms (histogram)
  - Alert: cleanup_failures > 0

Issue #12: Order Registry Race
  - order.registry.deferred_lost_updates (counter)
  - order.registry.cleanup_delays (histogram)
  - Alert: lost_updates > 0

Issue #13: Entry Slippage
  - entry.excessive_slippage_detected (counter)
  - entry.slippage_percent (histogram)
  - Alert: excessive_slippage > 0

Issue #14: Atomic Spread Check
  - close.atomic_check_conflicts (counter)
  - close.version_conflicts (counter)
  - Alert: conflicts > 0 per 24h

Issue #15: EOD Liquidation Lock
  - eod.liquidation_lock_wait_ms (histogram)
  - eod.manual_close_blocked (counter)
  - Alert: lock_wait > 5000ms

Issue #16: Verified Cancel
  - cancel.verification_retries (histogram)
  - cancel.verification_failures (counter)
  - Alert: failures > 0

Issue #17: Cancel/Submit Window
  - order.replace.cancel_confirmation_time_ms (histogram)
  - order.replace.confirmation_failures (counter)
  - Alert: confirmation_failures > 0

Issue #18: Quantity Validation
  - position.quantity_mismatch (counter)
  - position.quantity_mismatch_percent (histogram)
  - Alert: mismatch > 0 per 24h

Issue #19: Timeout Status Resolution
  - order.timeout_status_resolution (counter by status)
  - order.timeout_unknown_status (counter)
  - Alert: unknown_status > 0

Issue #20: IBKR Position Verify
  - entry.ibkr_position_blocked (counter)
  - entry.ibkr_position_mismatch (counter)
  - Alert: mismatch > 0 per 24h

Issue #21: Detector Reset Atomic
  - scanner.detector_reset_race (counter)
  - scanner.double_signal_prevented (counter)
  - Alert: reset_race > 0

Issue #22: Scheduler Blocking
  - scheduler.concurrent_task_count (gauge)
  - scheduler.task_queue_depth (gauge)
  - Alert: queue_depth > 5

Issue #23: Cancel Timeout Tracking
  - cancel.in_flight_tracking (counter)
  - cancel.verification_loop_executions (counter)
  - Alert: tracking > 100
```

### Health Dashboard

```
[Position Health]
  - Position count (vs expected)
  - DB vs IBKR discrepancies
  - Unhedged position detections
  - Total margin usage

[Order Health]
  - Fill rate (orders filled / submitted)
  - Timeout rate
  - Partial fill rate
  - Close success rate

[Issue Metrics]
  - 23 issue-specific metrics shown above
  - Color-coded: green/yellow/red
  - Trends: 1h, 24h, 7d

[Alerts]
  - All rules triggered in last 24h
  - Critical alerts highlighted
  - One-click drill-down to logs
```

---

## SECTION G: DEPLOYMENT SAFETY GATES

### Pre-Deployment Checklist (Per Issue)

```
Before deploying any issue:

□ All unit tests pass (100%)
□ All integration tests pass (100%)
□ No regression in existing tests
□ Chaos tests pass (10 iterations)
□ Code review approved (2 engineers)
□ Logging verified (all paths logged)
□ Metrics added (for monitoring)
□ Alerts configured (in Grafana)
□ Database migration tested (if applicable)
□ Rollback plan documented
□ Paper trading plan documented

Feature flag configured:
  @ConditionalOnProperty("fixes.issue#.enabled", havingValue = "true")
```

### Rollback Procedure

```
If issue causes problems:

1. Disable feature flag (5 min)
   - SET fixes.issue# = false in config
   - Redeploy application

2. Immediate actions (< 5 min):
   - Check position discrepancy
   - Run reconciliation query:
     SELECT * FROM spreads WHERE status != 'CLOSED'
     vs IBKR positions
   - Check order registry for orphaned orders

3. If position corruption:
   - ACTIVATE KILL SWITCH (stop trading immediately)
   - Alert on-call engineer
   - Begin manual reconciliation
   - Do NOT retry fix until root cause found

4. Post-incident:
   - Post-mortem review
   - Update test suite to catch issue
   - Document findings
```

### Feature Flags

```kotlin
@ConditionalOnProperty("fixes.issue1.enabled", havingValue = "true", matchIfMissing = true)
class Issue1PartialFillFix { ... }

@ConditionalOnProperty("fixes.issue2.enabled", havingValue = "true", matchIfMissing = true)
class Issue2MultipleCloseFix { ... }

// etc. for all 23 issues

// Configuration file:
# application.yml
fixes:
  issue1:
    enabled: true
    partial_fill_threshold_seconds: 5
  issue2:
    enabled: true
    monitor_mutex_timeout_seconds: 1
  # ... etc
```

---

## SECTION H: EFFORT BREAKDOWN & TIMELINE

### By Phase

| Phase | Issues | Duration | Effort | Risk |
|-------|--------|----------|--------|------|
| 1 | #2, #6, #16, #18, #21 | Week 1 MON-TUE | 21h | LOW |
| 2 | #3, #5, #8, #11, #14, #15, #17, #20, #22, #23 | Week 1 THU-FRI | 57h | MED |
| 3 | #1, #4, #7, #9, #10, #12, #13, #19 | Week 2 MON-WED | 56h | HIGH |
| 4 | Integration & validation | Week 2 THU-FRI | 20h | - |
| 5 | Production deployment | Week 3+ | Ongoing | - |

**Total**: 155 hours (code + testing)

### By Engineer

**2-Engineer Team (Recommended)**:
- Engineer A: Phase 1 + Phase 3 (Issues #2, #6, #16, #18, #21, #1, #4, #7, #9, #10, #12)
- Engineer B: Phase 2 + Phase 3 (Issues #3, #5, #8, #11, #14, #15, #17, #20, #22, #23, #13, #19)
- Overlap: Integration testing, code review, deployment

**Parallel Execution**: Can complete in 4-5 weeks

**1-Engineer Team**:
- Sequential execution: 8-10 weeks

### Effort by Category

```
Code Implementation: 65 hours (42%)
├─ Critical fixes (#1-#6): 24h
├─ High-risk fixes (#7-#12): 24h
└─ Medium fixes (#13-#23): 17h

Testing: 55 hours (35%)
├─ Unit tests: 22h (52 tests × 25 min)
├─ Integration tests: 18h (28 tests × 40 min)
└─ Chaos tests: 15h (12 scenarios × 75 min)

Integration & Validation: 20 hours (13%)
├─ Conflict resolution: 5h
├─ Dashboard setup: 3h
├─ Paper trading: 8h
└─ Monitoring setup: 4h

Documentation & Deployment: 15 hours (10%)
├─ Test documentation: 4h
├─ Monitoring documentation: 3h
├─ Deployment runbooks: 4h
└─ Post-deployment validation: 4h

Total: 155 hours
```

---

## SECTION I: SUCCESS CRITERIA

### Functional Success

```
✅ All 23 issues have test cases that reproduce the failure
✅ All fixes pass their dedicated test cases
✅ All 92 tests (unit + integration + chaos) pass
✅ 0 regressions in existing test suite (95 tests)
✅ No unintended positions in 48-hour paper trading run
✅ Position reconciliation query shows DB = IBKR
✅ No double-orders detected (Issue #2, #6, #9)
✅ No partial fills without rollback (Issue #1, #4)
✅ No stale orders left after recovery (Issue #5, #7)
```

### Monitoring Success

```
✅ All 23 issue-specific metrics reporting values
✅ All alert rules configured in Grafana
✅ Dashboard shows all metrics in green
✅ No alerts triggered during 48h paper trading
✅ Position discrepancy metric = 0
✅ Order fill rate ≥ 98%
✅ Close success rate ≥ 99%
```

### Code Quality

```
✅ All new code reviewed by 2 engineers
✅ Code coverage ≥ 90% on new files
✅ No duplicate code (DRY principle)
✅ Logging follows established patterns
✅ Type safety enforced (no Any types)
✅ Error handling explicit (no bare catch-all)
```

### Operational Success

```
✅ Feature flags working (can disable each fix independently)
✅ Rollback procedure tested
✅ Runbook documented for each issue
✅ On-call playbook updated
✅ Stakeholders trained on new behavior
✅ No production incidents from fixes
```

---

## SECTION J: CRITICAL FILES FOR IMPLEMENTATION

### New Files to Create (23 total)

**Core Services**:
1. `/engine/src/main/kotlin/.../OrderReplacementService.kt`
2. `/engine/src/main/kotlin/.../OrderCancellationService.kt`
3. `/engine/src/main/kotlin/.../OrderCleanupService.kt`
4. `/engine/src/main/kotlin/.../OrderFillTracker.kt`
5. `/engine/src/main/kotlin/.../SymbolMutexManager.kt`
6. `/engine/src/main/kotlin/.../VerifiedCancelService.kt`
7. `/engine/src/main/kotlin/.../TimeoutStatusResolver.kt`
8. `/engine/src/main/kotlin/.../QuantityValidator.kt`
9. `/engine/src/main/kotlin/.../PositionExistenceValidator.kt`
10. `/engine/src/main/kotlin/.../AtomicEntryValidator.kt`
11. `/engine/src/main/kotlin/.../FreshPositionQuery.kt`
12. `/engine/src/main/kotlin/.../SlippageValidator.kt`
13. `/engine/src/main/kotlin/.../ForceCloseAttemptTracker.kt`
14. `/engine/src/main/kotlin/.../CloseAttemptService.kt`
15. `/engine/src/main/kotlin/.../RealTimeMonitoringService.kt`
16. `/engine/src/main/kotlin/.../CancelVerificationService.kt`

**Test Files**:
17-23. `/engine/src/test/kotlin/.../IssueSevenTest.kt` through `IssueThirtythreeTest.kt`

### Modified Files (15 critical files)

1. `SpreadManagementService.kt` (Issues #1, #2, #7, #9, #10, #14, #18)
2. `SpreadMonitorScheduler.kt` (Issues #2, #15)
3. `IbkrOrderExecutionAdapter.kt` (Issues #3, #17)
4. `IbkrBracketOrderAdapter.kt` (Issue #16)
5. `IbkrOrderRegistry.kt` (Issues #2, #8, #12, #23)
6. `PreTradeValidator.kt` (Issues #9, #20)
7. `TradeExecutionService.kt` (Issue #11)
8. `PatternDetector.kt` (Issue #21)
9. `FlagScannerService.kt` (Issues #6, #21)
10. `FlagMonitorScheduler.kt` (Issue #15)
11. `FlagExecutionService.kt` (Issue #13)
12. `StartupRecoveryService.kt` (Issue #5)
13. `OrderChaseService.kt` (Issues #19, #23)
14. `PositionReconciliationService.kt` (Issues #4, #8)
15. `LegByLegOrderStrategy.kt` (Issue #4)

### Database Schema Changes (4 changes)

1. `spreads` table: Add `close_attempt_id` (UUID) and order tracking fields
2. `spreads` table: Add `version` field (Long) for optimistic locking
3. `order_audit` table: Add `resolution_status` and `resolution_time` fields
4. `monitoring` table: Add `executor_lock_acquired_at` and `lock_released_at` timestamps

---

## SECTION K: RISK MITIGATION SUMMARY

### Per-Issue Risk Mitigation

| Issue | Risk | Mitigation | Trigger |
|-------|------|-----------|---------|
| #1 | HIGH | Comprehensive testing (8 tests), watchdog metrics | > 0 rollbacks in 24h |
| #2 | LOW | Mutex prevents all overlaps, impossible to regression | Concurrent > 0 |
| #3 | MED | Verification loop + retries, timeout generous | replace_failed > 0 |
| #4 | HIGH | Real-time monitoring + reconciliation, 3 test types | short_only > 0 |
| #5 | MED | Atomic cancel with retry, log all attempts | cancel_failed > 0 |
| #6 | LOW | Per-symbol locks, global count check still enforced | wait_time > 1s |
| #7 | MED | Cancel on fail + retry, exponential backoff | verification_failed > 0 |
| #8 | MED | Explicit cleanup on timeout, track pending | pending_cleanup > 0 |
| #9 | MED | DB-level locking, atomic transaction | check_failures > 0 |
| #10 | MED | Fresh query only on close, cache otherwise | cache_misses > 0 |
| #11 | MED | Symbol lock held until cleanup, type-safe | cleanup_failures > 0 |
| #12 | MED | Keep deferred in map until consumed | lost_updates > 0 |
| #13 | MED | Slippage check before OPEN, threshold configurable | excessive_slippage > 0 |
| #14 | MED | Optimistic locking with retry, version field | version_conflicts > 0 |
| #15 | LOW | Per-symbol lock same as regular close, no overhead | lock_wait > 5s |
| #16 | LOW | Retry loop (3×500ms), verify with openOrders query | failures > 0 |
| #17 | MED | Indefinite cancel wait, verification loop | confirmation_failures > 0 |
| #18 | LOW | Query IBKR before close, use source-of-truth | mismatch > 0 |
| #19 | MED | Query on timeout, actual status confirmed | unknown_status > 0 |
| #20 | MED | Fresh IBKR position query, blocks entry on mismatch | mismatch > 0 |
| #21 | LOW | Mutex around reset/onNewBar, atomic state | reset_race > 0 |
| #22 | MED | Async/await pattern, no blocking, concurrency safe | queue_depth > 5 |
| #23 | MED | Cancel in-flight tracking, periodic verification | tracking > 100 |

### Rollback Readiness

**All fixes**:
- Feature flags implemented
- Backward-compatible (no schema breaking changes)
- Rollback time: < 5 minutes
- No data corruption risk if rolled back
- Testing ensures rollback doesn't break existing functionality

---

## FINAL SUMMARY

This comprehensive implementation plan covers **all 23 issues** identified in the trading engine with:

- **Detailed root cause analysis** for each issue
- **Specific implementation strategies** with code patterns
- **92 test cases** (52 unit + 28 integration + 12 chaos)
- **5-phase implementation roadmap** (155 hours total)
- **Risk mitigation** for every issue
- **Monitoring & alerting framework** for post-deployment
- **Rollback procedures** for operational safety
- **Clear success criteria** for completion

**Critical Path**: Issues #2, #6 → #3, #5, #8, #11 → #1, #4 (4-5 weeks with 2 engineers)

**Deploy Strategy**: Fix-by-fix with paper trading validation between each deployment

The fixes address the systematic issue of **concurrent operations on shared state without synchronization**. Success requires implementing all 23 fixes in the correct sequence while maintaining comprehensive test coverage throughout.

---

## CRITICAL FILES FOR IMPLEMENTATION

1. `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/spread/SpreadManagementService.kt` - Core close/force-close logic (Issues #1, #2, #7, #9, #10, #14, #18)

2. `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/execution/PreTradeValidator.kt` - Entry validation (Issues #9, #20)

3. `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/domain/features/execution/TradeExecutionService.kt` - Trade execution (Issue #11)

4. `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/adapters/outbound/ibkr/registry/IbkrOrderRegistry.kt` - Order tracking and callbacks (Issues #2, #8, #12, #23)

5. `/home/solvina/projects/options/engine/src/main/kotlin/cz/solvina/options/adapters/inbound/jobs/SpreadMonitorScheduler.kt` - Monitoring scheduler (Issues #2, #15)