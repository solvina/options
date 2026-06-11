# Critical Fixes Implementation Plan - Trading Engine Audit

**Date**: 2026-06-11  
**Scope**: Fix 6 CRITICAL issues identified in edge case audit  
**Total Effort**: ~80-100 hours (2-2.5 weeks for 2 engineers)  
**Risk Level**: HIGH (these are position-level bugs; require careful testing)

---

## Executive Summary

The 6 critical issues share a common theme: **concurrent operations without synchronization**. Fixes must be implemented in a specific order (3 phases) to avoid introducing new race conditions.

**Phase 1 (Immediate, low risk):** Implement mutexes for schedulers (Issues #2, #6)  
**Phase 2 (Mid-week, medium risk):** Fix order operations (Issues #3, #5)  
**Phase 3 (Next week, high risk):** Fix position-level atomicity (Issues #1, #4)  

Each issue has dedicated test cases that:
1. Reproduce the failure scenario
2. Verify the fix prevents the failure
3. Ensure no regression in happy path

---

## Issue #1: Partial Fill Rollback

**Root Cause:** Buy-back fills successfully, but sell-back times out. Position left CLOSING with one leg closed (unhedged).

### Fix Strategy
- Add leg fill tracking to spread record (`buyBackOrderId`, `sellBackOrderId`, `buyBackFilledAt`, `sellBackFilledAt`)
- Detect when one leg fills but other fails
- Place compensating market order immediately to close the successful leg
- Mark spread as CLOSING_FAILED for manual review

### Implementation
1. Extend `BullPutSpread` entity with order tracking fields
2. Create `CloseAttempt` tracking class
3. Refactor `closeSpread()` to place both orders in parallel (not sequential)
4. Implement `awaitBothLegs()` monitoring function
5. Add automatic rollback on partial fill detection
6. Add `CloseAttemptPort` for persistence

### Tests (4 test cases)
- "Partial fill - buy-back fills but sell-back times out - rolls back"
- "Partial fill - sell-back fills but buy-back times out - orphaned short"
- "Both legs fill concurrently - success path"
- "Both legs timeout - left as CLOSING for retry"

### Effort: ~12 hours
### Risk: HIGH (position level)
### Deploy Gate: Must pass all chaos tests

---

## Issue #2: Multiple Close Race

**Root Cause:** `AtomicBoolean` prevents double scheduling but doesn't prevent async work overlap.

### Fix Strategy
- Replace `AtomicBoolean` with `Mutex` for coroutine-level synchronization
- Use `tryLock(timeout = 0)` to fail-fast if another run is in progress
- Add per-run tracking (start time, completion time)
- Log skips with reason

### Implementation
1. Add `private val monitorMutex = Mutex()` to SpreadMonitorScheduler
2. Wrap `scope.launch` block with `monitorMutex.withLock { ... }`
3. Use try-finally to guarantee unlock
4. Add health metrics (run duration, skip reason)
5. Add watchdog timeout (warn if lock held > 90% of interval)

### Tests (3 test cases)
- "Scheduled monitor prevents concurrent execution with Mutex"
- "Lock is released even if checkExits throws"
- "Lock acquisition timeout fails gracefully"

### Effort: ~4 hours
### Risk: LOW
### Deploy Gate: Existing tests pass

---

## Issue #3: Order Replace Window

**Root Cause:** 10s timeout on cancel not guaranteed. New order placed while old still live → double fill.

### Fix Strategy
- Remove timeout on cancel operations (wait indefinitely)
- Verify cancel by querying open orders (retry up to 10 times)
- Only submit new order after old order confirmed removed from IBKR
- Add OrderReplacementService to centralize logic

### Implementation
1. Create `OrderReplacementService` with atomic replace logic
2. Replace `cancelAndAwait()` 10s timeout with indefinite wait
3. Add `verifyOrderRemoved()` function that:
   - Queries openOrders
   - Checks if orderId is present
   - Retries up to 10 times with 500ms delays
   - Fails if still present after 5s
4. Add health check: monitor average cancel confirmation time
5. Update IbkrOrderExecutionAdapter to use OrderReplacementService

### Tests (4 test cases)
- "Order replacement waits indefinitely for cancel confirmation"
- "New order only submitted after old confirmed removed"
- "Replacement fails if old order not removed after retries"
- "Cancel confirmation timeout removed - no more 10s limit"

### Effort: ~8 hours
### Risk: MEDIUM (timeout removal could cause hangs)
### Deploy Gate: Test with 5-10s network latency simulation

---

## Issue #4: Leg-by-Leg Unhedged

**Root Cause:** 500ms delay insufficient. SHORT fills before LONG submitted. Cancel issued too late.

### Fix Strategy
- Monitor SHORT leg fill in real-time during the delay
- Fail fast if SHORT fills before LONG can be submitted
- Remove arbitrary 500ms delay; replace with intelligent monitoring
- Wait for both legs to fill concurrently (not sequentially)
- Use position reconciliation to verify both legs before committing

### Implementation
1. Add real-time fill monitoring during SHORT submission wait:
   - Launch async monitor for SHORT order status
   - Wait 50ms for SHORT to settle
   - Check if SHORT already filled before submitting LONG
   - If filled, return failure with orderId
2. Create `awaitBothLegs()` function:
   - Polls both order statuses in loop (100ms intervals)
   - Waits up to 10s for both to complete
   - Returns true only if BOTH are FILLED
   - If timeout, cancels both orders
3. Integrate PositionReconciliationService:
   - After both orders report filled, verify positions
   - Only return success if both legs are in account

### Tests (5 test cases)
- "Leg-by-leg monitors SHORT fill before LONG submission"
- "Both legs submitted then we wait for concurrent fills"
- "LONG submission fails, SHORT is cancelled"
- "Timeout waiting for fills, both cancelled"
- "Both legs fill, positions verified before success"

### Effort: ~10 hours
### Risk: HIGH (EUREX order execution)
### Deploy Gate: Test in paper trading for 24 hours

---

## Issue #5: Stale SELL Window

**Root Cause:** Stale SELL orders identified but not atomically cancelled. They fill after close completes (AMD bug).

### Fix Strategy
- Create `OrderCancellationService` that:
  - Checks current order state before issuing cancel
  - Issues cancel request
  - Verifies cancellation by querying open orders
  - Retries up to 5 times (1s total)
  - Skips already-filled orders
- Make entire flow atomic
- Log all cancellations to audit table

### Implementation
1. Create `OrderCancellationService`:
   ```kotlin
   suspend fun cancelOrdersAtomic(
       orderIds: List<Int>,
       reason: String,
   ): List<Int>
   ```
2. For each order:
   - Get current state from openOrders
   - Skip if already filled
   - Issue cancel request
   - Verify removal via retry loop (5 × 200ms)
   - Log result to audit table
3. Update StartupRecoveryService:
   - Fetch open orders once
   - Filter for stale SELL orders
   - Call orderCancellationService.cancelOrdersAtomic()
   - Alert if any orders failed to cancel

### Tests (4 test cases)
- "Atomic cancel verifies order state before issuing cancel"
- "Stale order already filled before cancel - skipped"
- "Cancel with verification loop ensures confirmation"
- "Cancel timeout after 5 attempts - warn but continue"

### Effort: ~8 hours
### Risk: MEDIUM (recovery service)
### Deploy Gate: Test recovery on paper trading with stale orders

---

## Issue #6: No Symbol Mutex

**Root Cause:** Multiple signals on same symbol can pass checks before lock acquired → double orders.

### Fix Strategy
- Add per-symbol `Mutex` via `SymbolMutexManager`
- Serialize all entries for a given symbol
- Keep global mutex for position count check
- Two-level locking: symbol-level (serializes entry) + global-level (ensures position count)

### Implementation
1. Create `SymbolMutexManager`:
   ```kotlin
   suspend fun <T> withSymbolLock(
       symbol: Symbol,
       block: suspend () -> T,
   ): T
   ```
2. Add to FlagScannerService as dependency
3. Wrap `maybeEnter()` with:
   ```kotlin
   symbolMutexManager.withSymbolLock(symbol) {
       // existing code
   }
   ```
4. Keep existing `entryMutex` for position count check
5. Add monitoring:
   - Track which symbols are locked
   - Expose via API: `GET /flags/symbol-locks`
   - Log lock wait times > 100ms

### Tests (3 test cases)
- "Concurrent breakouts on same symbol are serialized"
- "Different symbols can enter concurrently (no contention)"
- "Global position count still prevents >maxOpenPositions"

### Effort: ~5 hours
### Risk: LOW
### Deploy Gate: Existing tests pass

---

## Implementation Sequencing

```
Week 1:
[MON] Issue #2 (Mutex)        [4h] - LOW RISK, quick win
      Issue #6 (Symbol Mutex)  [5h] - LOW RISK, complements #2

[TUE-WED] Code review + testing (Issue #2, #6)

[THU] Issue #5 (Stale SELL)   [8h] - Test with real recovery scenario
      Issue #3 (Order Replace) [8h] - Test with latency simulation

[FRI] Code review + testing (Issue #3, #5)

Week 2:
[MON-TUE] Issue #4 (Leg-by-Leg)     [10h] - Test in paper trading
[WED-THU] Issue #1 (Partial Rollback) [12h] - Highest risk, detailed testing
[FRI]     Integration testing + chaos tests

Week 3:
[MON-TUE] Production testing (paper trading)
[WED]     Deploy to production (1 fix at a time)
[THU-FRI] Monitoring + validation
```

---

## Testing Strategy

### Per-Fix Unit Tests
- **Scope**: Each issue has 3-5 dedicated test cases
- **Framework**: JUnit 5 + mockk
- **Async**: kotlinx.coroutines.test.runTest
- **Speed**: Target < 500ms per test

### Integration Tests
- Test Issue #1 + #3: Replacement during close attempt
- Test Issue #2 + #5: Recovery while monitor active
- Test Issue #4 + #5: Leg-by-leg + stale order cancellation

### Chaos/Stress Tests
- **Concurrent Operations**: 10 monitor runs + 10 recovery runs simultaneously
- **Rapid Signals**: 100 breakouts/sec on single symbol for 10 seconds
- **Network Latency**: Add 5-10s delays to IBKR API
- **Position Reconciliation**: Query positions during close
- **Order Fill Race**: Simulate fill during close attempt

### Regression Suite
- All existing tests must pass
- No behavior change in happy path
- Only edge cases fixed

---

## Deployment Safety Gates

Before deploying each issue to production:

1. ✅ All unit tests pass (100%)
2. ✅ All integration tests pass (100%)
3. ✅ Chaos tests pass (10 iterations each)
4. ✅ Code review approved by 2 engineers
5. ✅ Paper trading test (24 hours, no unintended positions)
6. ✅ Logging review (all important paths logged)
7. ✅ Monitoring review (metrics in place to validate)

---

## Monitoring & Validation

### Metrics to Add

**Issue #1:**
- `spread.close.partial_fill_detected` (counter)
- `spread.close.rollback_executed` (counter)
- Alert if > 0 in 24 hours

**Issue #2:**
- `monitor.concurrent_runs` (gauge)
- `monitor.skipped_overlaps` (counter)
- Alert if concurrent > 0

**Issue #3:**
- `order.replace.duration_seconds` (histogram, p95/p99)
- `order.replace.failed` (counter)
- Alert if failed > 0

**Issue #4:**
- `leg_by_leg.submission_success_rate` (gauge)
- `leg_by_leg.short_only` (counter)
- `position.reconciliation.success_rate` (gauge)

**Issue #5:**
- `recovery.orders_cancelled` (counter)
- `recovery.cancel_failed` (counter)
- `recovery.cancel_duration_seconds` (histogram)

**Issue #6:**
- `scanner.symbol_locks.active` (gauge)
- `scanner.symbol_locks.wait_time_ms` (histogram)
- `scanner.concurrent_breakouts` (counter per symbol)

### Dashboard
Create Grafana dashboard showing:
- All new metrics above
- Position count trend
- Order fill rate
- Close success rate

### Alert Rules
- `spread.close.rollback_executed > 0` → WARN
- `monitor.concurrent_runs > 0` → WARN
- `order.replace.failed > 0` → ALERT
- `recovery.cancel_failed > 0` → ALERT
- `position.discrepancy > 0` → CRITICAL

---

## Rollback Procedure

If any issue causes unexpected behavior:

1. **Feature Flag Disable:**
   ```kotlin
   @ConditionalOnProperty("fixes.issue1.enabled", havingValue = "true", matchIfMissing = true)
   @Component
   class Issue1PartialFillFix { ... }
   ```

2. **Redeploy immediately** (< 5 minutes)
3. **No database migrations required** (all backward-compatible)
4. **If position corruption:**
   - Kill switch: STOP all trading
   - Query: SELECT * FROM spreads; vs. positions from IBKR
   - Manual reconciliation with ops team
   - Post-mortem required before re-enabling

---

## Estimated Timeline

- **Total Effort**: 55-65 hours code + 20-30 hours testing = 75-95 hours
- **2 engineers working in parallel**: 4-5 weeks
- **With 1 engineer**: 8-10 weeks
- **Critical path**: Issues #2, #6 → #3, #5 → #1, #4

---

## Success Criteria

✅ All 6 issues have tests that reproduce the failure  
✅ All tests pass with the fix  
✅ No regression in existing tests  
✅ 24-hour paper trading run with no unintended positions  
✅ Monitoring dashboard shows healthy metrics  
✅ Zero false positives on chaos tests  
✅ Code reviewed by 2 engineers  
✅ Documentation updated  
