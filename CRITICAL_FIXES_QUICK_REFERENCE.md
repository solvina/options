# Critical Fixes Quick Reference

## The 6 Issues at a Glance

| # | Issue | Root Cause | Fix | Tests | Effort | Risk |
|---|-------|-----------|-----|-------|--------|------|
| 1 | Partial Fill Rollback | Buy fills, sell times out → unhedged | Add leg tracking, auto-rollback on partial fill | 4 | 12h | 🔴 HIGH |
| 2 | Multiple Close Race | AtomicBoolean insufficient | Replace with Mutex | 3 | 4h | 🟢 LOW |
| 3 | Order Replace Window | 10s timeout not guaranteed | Remove timeout, verify removal | 4 | 8h | 🟡 MED |
| 4 | Leg-by-Leg Unhedged | SHORT fills before LONG check | Real-time monitoring, concurrent waits | 5 | 10h | 🔴 HIGH |
| 5 | Stale SELL Window | Cancel not atomic with fill | OrderCancellationService, atomic verify | 4 | 8h | 🟡 MED |
| 6 | No Symbol Mutex | Multiple signals pass checks | Per-symbol Mutex | 3 | 5h | 🟢 LOW |

**Total**: 23 tests, ~55h code, ~30h testing, 75-95h total

---

## Implementation Phases

```
PHASE 1 (Immediate, Week 1 MON-TUE)
├─ Issue #2 (4h)   Mutex for monitor scheduler
└─ Issue #6 (5h)   Symbol mutex for scanner
   Risk: LOW | Deploy gate: Existing tests pass

PHASE 2 (Mid-week, Week 1 THU-FRI)  
├─ Issue #3 (8h)   Order replacement atomicity
└─ Issue #5 (8h)   Stale SELL order cancellation
   Risk: MEDIUM | Deploy gate: Paper trading 24h

PHASE 3 (Next week, Week 2 MON-THU)
├─ Issue #4 (10h)  Leg-by-leg concurrent monitoring
└─ Issue #1 (12h)  Partial fill rollback
   Risk: HIGH | Deploy gate: Chaos tests + paper trading 48h
```

---

## What Each Test Does

### Issue #1: Partial Fill Rollback (4 tests)
1. **Buy fills, sell times out** → Triggers rollback of bought leg
2. **Sell fills, buy times out** → Triggers rollback of short leg
3. **Both fill** → Clean close, no rollback needed
4. **Both timeout** → Stays CLOSING for retry

### Issue #2: Multiple Close Race (3 tests)
1. **Concurrent monitor runs** → Mutex prevents overlap
2. **Exception handling** → Lock released even on throw
3. **Lock timeout** → Fails gracefully if already locked

### Issue #3: Order Replace Window (4 tests)
1. **Wait indefinitely for cancel** → No 10s timeout
2. **Verify removal via openOrders** → Retry loop confirms
3. **New order blocked until confirmed** → Atomicity
4. **High latency scenario** → Handles 15s+ delays

### Issue #4: Leg-by-Leg Unhedged (5 tests)
1. **SHORT fills before LONG** → Detects immediately, fails
2. **LONG submission fails** → Cancels SHORT immediately
3. **Both fill concurrently** → Both monitored, waits for both
4. **Timeout waiting** → Cancels both after 10s
5. **Position reconciliation** → Verifies both legs before success

### Issue #5: Stale SELL Window (4 tests)
1. **Verify state before cancel** → Checks filled status first
2. **Already filled order** → Skipped, not cancelled
3. **Cancel verification loop** → Retries until gone from openOrders
4. **Timeout after retries** → Warns but continues recovery

### Issue #6: No Symbol Mutex (3 tests)
1. **Concurrent signals on same symbol** → Only 1 executes
2. **Different symbols concurrent** → Both execute (no contention)
3. **Global position count** → Still enforced, position limit respected

---

## Monitoring After Deploy

Add these metrics to Grafana:

```
✅ Monitor each fix:
   - spread.close.rollback_executed (Issue #1)
   - monitor.concurrent_runs (Issue #2)
   - order.replace.failed (Issue #3)
   - leg_by_leg.short_only (Issue #4)
   - recovery.cancel_failed (Issue #5)
   - scanner.symbol_locks.active (Issue #6)

✅ Position health:
   - position.discrepancy (DB vs IBKR)
   - account.margin_usage
   - account.open_positions_count

✅ Order health:
   - order.fill_rate
   - order.timeout_rate
   - spread.close.success_rate

Alert if:
   - Any rollback_executed > 0 in 24h
   - Any cancel_failed > 0
   - position.discrepancy > 0 (CRITICAL)
```

---

## Rollback Plan

If issue causes problems:

1. **Disable via feature flag** (5 min)
   ```kotlin
   @ConditionalOnProperty("fixes.issue#.enabled")
   ```

2. **Redeploy** (5 min)

3. **If position corruption**:
   - KILL SWITCH (stop all trading)
   - Compare DB spreads vs IBKR positions
   - Manual reconciliation
   - Post-mortem

**No database migrations** needed (all backward-compatible)

---

## Success Metrics

- ✅ All 23 tests pass
- ✅ 0 regressions in existing tests  
- ✅ 24h paper trading, no unintended positions
- ✅ 0 concurrent runs detected (Issue #2)
- ✅ 0 stale orders after recovery (Issue #5)
- ✅ 0 partial fills left unrolled-back (Issue #1)
- ✅ Monitoring dashboard green

---

## Files to Create/Modify

**New Files:**
- `/src/main/kotlin/.../CloseAttemptService.kt` (Issue #1)
- `/src/main/kotlin/.../OrderReplacementService.kt` (Issue #3)
- `/src/main/kotlin/.../OrderCancellationService.kt` (Issue #5)
- `/src/main/kotlin/.../SymbolMutexManager.kt` (Issue #6)
- `/src/test/kotlin/.../PartialFillRollbackTest.kt` (Issue #1)
- `/src/test/kotlin/.../... (5 more test files)

**Modified Files:**
- `SpreadManagementService.kt` (Issues #1, #2)
- `SpreadMonitorScheduler.kt` (Issue #2)
- `IbkrOrderExecutionAdapter.kt` (Issue #3)
- `LegByLegOrderStrategy.kt` (Issue #4)
- `StartupRecoveryService.kt` (Issue #5)
- `FlagScannerService.kt` (Issue #6)

---

## Emergency Contacts

- **Position discrepancy detected** → STOP all trading immediately
- **Double-order submitted** → Manual cancel + reconciliation
- **Unhedged position found** → Market-close immediately
- **Recovery service error** → Manual order reconciliation

---

## Key Insights

1. **Root cause**: Concurrent operations on shared state without synchronization
2. **Pattern**: Check + action not atomic (status can change between them)
3. **Fix pattern**: Make operations atomic with Mutex + verify IBKR state
4. **Not a one-time fix**: Requires architectural review of all position-level code
5. **Testing critical**: Each fix has 3-5 dedicated tests that reproduce the failure

---

**Full details**: `/home/solvina/projects/options/CRITICAL_FIXES_IMPLEMENTATION_PLAN.md`
