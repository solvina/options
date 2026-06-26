# Bear Call Strategy Implementation Plan — 2026-06-26

**Status**: Pre-implementation analysis & design  
**Target**: Phase after bull put validation (post US session)  
**Effort**: 3-4 weeks (design, implementation, validation)

---

## Executive Summary & Recommendation

### Question: Reuse Bull Put Code or Separate Implementation?

**Recommendation: SEPARATE features with SHARED infrastructure**

#### Why NOT full reuse (despite identical parameters):

1. **Different Risk Evolution** 
   - Bull put: Risk is downside (spreads tighten as stock rises)
   - Bear call: Risk is upside (spreads blow out as stock rises faster)
   - Early assignment mechanism is COMPLETELY different (dividend trap vs theta decay)
   - Code paths that manage risk will diverge significantly

2. **Volatility Skew Handling**
   - Bull puts: "Skew-friendly" — OTM puts naturally trade higher IV
   - Bear calls: "Skew-hostile" — OTM calls trade LOWER IV
   - This affects entry selection logic, position sizing, and risk management
   - Future optimization will likely differ per strategy

3. **Future Divergence is Guaranteed**
   - Early assignment risk management (dividend checks)
   - Greeks behave differently (call gamma vs put gamma)
   - Portfolio hedging strategies will likely differ (you might want calls + puts together for long strangles)
   - Platform evolution: keeping separate models costs less refactoring than merging later

4. **Operational Reality**
   - Different monitoring rules (dividend calendars for bear calls)
   - Different alert thresholds
   - Different backtest parameters
   - Risk limits may need to diverge

#### Why we CAN share infrastructure:

- ✅ Generic order execution (leg-by-leg, BAG, laddering all work for both)
- ✅ Generic monitoring scheduler (checkExits works on any 2-leg spread)
- ✅ Generic position reconciliation (orphan detection works for both)
- ✅ Generic quote health monitoring (LIVE/STALE/BLIND applies to both)
- ✅ Same exit logic framework (TP%, SL%, DTE time)

---

## Architecture Recommendation

```
Shared Infrastructure (Generic)
├── SpreadLeg, SpreadStatus, SpreadPort (already generic)
├── TradeExecutionService (works for any 2-leg spread)
├── SpreadManagementService (TP/SL/DTE exits are generic)
├── PositionReconciliation (detects orphans for any spread type)
├── QuoteHealthMonitoring (Phase 1 applies universally)
└── Order execution (BAG, leg-by-leg, laddering all generic)

Strategy-Specific Services (Separate Models)
├── Bull Put Spreads
│   ├── BullPutSpread model (EXISTING, specific class)
│   ├── BullPutScanCandidateSelector (PUT-specific logic)
│   ├── BullPutValidator (put-specific validation)
│   └── BullPutScheduler (entry scheduling)
│
└── Bear Call Spreads (NEW)
    ├── BearCallSpread model (NEW, specific class)
    ├── BearCallScanCandidateSelector (CALL-specific logic)
    ├── BearCallValidator (call-specific validation)
    ├── BearCallDividendAwarenessService (NEW, unique to calls)
    └── BearCallScheduler (entry scheduling)
```

---

## Implementation Phases

### Phase 1: Data Model & Database (2-3 days)

**Create new BearCallSpread model**:
```kotlin
data class BearCallSpread(
    val id: UUID?,
    val symbol: Symbol,
    val soldLeg: SpreadLeg,      // SHORT call (higher strike)
    val boughtLeg: SpreadLeg,    // LONG call (lower strike)
    val creditPerShare: BigDecimal,
    val maxRiskPerShare: BigDecimal,
    val quantity: Int = 1,
    val status: SpreadStatus,
    val ivRankAtEntry: Double?,
    val underlyingPriceAtEntry: BigDecimal?,
    val exDividendDate: LocalDate? = null,  // NEW: dividend risk tracking
    // ... rest identical to BullPutSpread
)
```

**Database**:
- Rename `spread_positions` table → `bull_put_spreads`
- Create new `bear_call_spreads` table (identical schema)
- Add `ex_dividend_date` column to both
- Create new view/API `all_spreads` that unions both tables

### Phase 2: Scanner & Entry Selection (3-4 days)

**BearCallScanCandidateSelector**:
```kotlin
class BearCallScanCandidateSelector(
    private val volatilityPort: VolatilityPort,
    private val marketDataPort: MarketDataPort,
    private val optionChainPort: OptionChainPort,
    private val universePort: UniversePort,
    private val config: ScannerConfig,
    private val clock: Clock,
) {
    suspend fun select(symbol: Symbol, totalCapital: Money): TradeExecutionRequest? {
        // Same as BullPutScanCandidateSelector BUT:
        // 1. Filter for CALLS instead of PUTS (line 79 in existing code)
        // 2. ENTRY FILTER: Skip if ex-dividend within next 48 hours (safety)
        // 3. Account for skew: calls trade lower IV, adjust credit expectations
        // 4. Short call = SELL at HIGHER strike, long call = BUY at LOWER strike
        //    (opposite of puts, but structure is identical)
        // 5. Apply skew-adjustment factor to expected credit (config: bear-call-skew-adjustment)
    }
}
```

**Entry Filter: Ex-Dividend Safety Check**
```kotlin
// Before submitting entry order:
if (symbol.exDividendDate != null) {
    val daysToExDiv = daysUntil(today(), symbol.exDividendDate)
    if (daysToExDiv in 0..2) {  // Within 48 hours
        logger.info("Skipping $symbol: ex-dividend in $daysToExDiv days (too close)")
        return null
    }
}
```

**Skew Adjustment (Applied at Entry)**
```
// Bull puts: "normal" skew helps us (puts trade higher IV than calls)
// Bear calls: skew hurts us (calls trade lower IV than puts at same delta)
// Solution: Apply config parameter skew-adjustment factor to entry credit
//
// Example:
// - Bull put: calc credit = $1.50 (baseline, no adjustment)
// - Bear call: calc credit = $1.50 × (1 - 0.05) = $1.425
//   (5% reduction due to skew, parameter-driven)
//
// If adjusted credit < min-credit-per-share, skip entry
```

### Phase 3: Smart Dividend Exit Logic (2-3 days)

**NEW: BearCallDividendAwarenessService**  
Runs every 60 seconds (integrated into SpreadManagementService.checkExits)

```kotlin
@Component
class BearCallDividendAwarenessService(
    private val spreadPort: SpreadPort,
    private val marketDataPort: MarketDataPort,
    private val universePort: UniversePort,  // ex-dividend dates stored here
    private val logger: Logger,
) {
    suspend fun checkSmartDividendExit(): List<UUID> {
        val closedSpreads = mutableListOf<UUID>()
        val openBearCalls = spreadPort.findOpenBearCalls()
        
        for (spread in openBearCalls) {
            val universe = universePort.get(spread.symbol)
            val exDividendDate = universe?.exDividendDate ?: continue
            
            val daysToExDiv = daysUntil(today(), exDividendDate)
            
            // Only check within risk window (24-48 hours)
            if (daysToExDiv !in 0..2) continue
            
            val shortCall = spread.soldLeg
            val spotPrice = marketDataPort.getSpotPrice(spread.symbol)
            val isITM = spotPrice > shortCall.contract.strike
            
            if (!isITM) {
                logger.debug("${spread.symbol} bear call: OTM, no dividend risk yet")
                continue
            }
            
            // Fetch extrinsic value (mid-price of short call)
            val shortCallMid = marketDataPort.getOptionMidLive(shortCall.contract)
            val estimatedDividendAmount = universe.nextDividendAmount ?: BigDecimal.ZERO
            
            // SMART EXIT: All three conditions must be true
            if (daysToExDiv in 0..2 && isITM && shortCallMid < estimatedDividendAmount) {
                logger.warn {
                    "SMART EXIT TRIGGERED: ${spread.symbol} bear call " +
                    "(short ${shortCall.contract.strike}C) is ITM with extrinsic $shortCallMid " +
                    "< dividend $estimatedDividendAmount. Ex-dividend in $daysToExDiv days."
                }
                
                // Force close at market
                val closePrice = marketDataPort.requestLiveQuote(spread.soldLeg.contract)
                spreadPort.closeSpread(spread.id, closePrice, SpreadStatus.CLOSED_DIVIDEND_RISK)
                closedSpreads.add(spread.id)
            } else {
                logger.debug {
                    "DIVIDEND WATCH: ${spread.symbol} ex-div in $daysToExDiv days. " +
                    "ITM: $isITM, extrinsic: $shortCallMid vs dividend: $estimatedDividendAmount"
                }
            }
        }
        
        return closedSpreads
    }
}
```

**Integration with SpreadManagementService.checkExits**
```kotlin
// Existing exit logic (TP/SL/DTE) runs first
// Then, for bear calls only:
if (spread is BearCallSpread) {
    val closedByDividend = bearCallDividendService.checkSmartDividendExit()
    if (spread.id in closedByDividend) {
        // Already closed, skip to next spread
        return@forEach
    }
}
```

**Database/Config Requirements**
- `universe` table: Add columns
  - `ex_dividend_date: LocalDate?` (nullable, populated by future refresh job)
  - `next_dividend_amount: BigDecimal?` (nullable, e.g., $2.50)
- `bear_call_spreads` table: Add column
  - `close_reason: String` (enum: "TAKE_PROFIT", "STOP_LOSS", "TIME_EXIT", "DIVIDEND_RISK", etc.)

**Future Work (Post-Phase 1)**
- [ ] Integrate `reqFundamentalData` to fetch ex-dividend dates automatically
- [ ] Scheduled task: Update `universe.ex_dividend_date` + `next_dividend_amount` daily (N-min interval, M records/cycle)
- [ ] Dividend calendar UI panel in bear call tab (show upcoming ex-dates)

### Phase 4: Execution & Monitoring (2-3 days)

**Reuse existing infrastructure**:
- `TradeExecutionService` — works for bear calls as-is (generic order execution)
- `SpreadManagementService` — TP/SL/DTE exit logic is generic + dividend awareness (Phase 3)
- `SpreadMonitorScheduler` — works for both spread types
- `PositionReconciliationScheduler` — orphan detection works universally
- `QuoteHealthService` — Phase 1 monitoring applies to both

**Shared Portfolio Limits** (CRITICAL)
```kotlin
// Before submitting EITHER bull put OR bear call entry:
suspend fun canEnterNewSpread(): Boolean {
    val bullPutOpen = spreadPort.countOpenBullPuts()
    val bearCallOpen = spreadPort.countOpenBearCalls()
    val totalOpen = bullPutOpen + bearCallOpen
    
    if (totalOpen >= config.maxOpenSpreads) {  // e.g., 5
        logger.info("Portfolio full: $bullPutOpen bull puts + $bearCallOpen bear calls = $totalOpen / ${config.maxOpenSpreads}")
        return false
    }
    
    // Also check combined risk
    val bullPutRisk = spreadPort.calculateOpenBullPutRisk()
    val bearCallRisk = spreadPort.calculateOpenBearCallRisk()
    val totalRisk = bullPutRisk + bearCallRisk
    
    if (totalRisk >= config.maxPortfolioRisk) {
        logger.info("Risk limit reached: $totalRisk / ${config.maxPortfolioRisk}")
        return false
    }
    
    return true
}
```

**Exit Monitoring: Same for Both**
- Exit monitoring: same thresholds (50% TP, 200% SL, 21 DTE)
- Price laddering: same 5% drift, same direction (stepping price down to find buyers)
- Order submission: same BAG (US) and leg-by-leg (EU) strategies
- Reconciliation: Both strategies in same orphan detection

### Phase 5: UI Design & Frontend (4-5 days)

See detailed UI proposal below.

---

## UI Strategy & Design

### Question: Separate Sections or Mixed View?

**Recommendation: SEPARATE sections with UNIFIED analytics**

#### Why separate sections (initially):
1. **Clarity**: Users understand each strategy independently
2. **Risk management**: Can enable/disable each strategy separately
3. **Portfolio construction**: May want both running simultaneously (hedging)
4. **Operational clarity**: Different rules (dividend calendar), different alerts

#### But unified analytics layer:
- Combined P&L charts
- Combined position heatmap
- Blended Greeks
- Single risk dashboard

### UI Layout Proposal

```
┌─────────────────────────────────────────────────────────┐
│  TRADING SYSTEM  [Bull Puts] [Bear Calls] [Dashboard]  │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  TAB 1: BULL PUTS                                        │
│  ├─ Strategy Status: 3/5 open spreads                   │
│  ├─ Today's Entries: AAPL, MSFT (pending fills)         │
│  ├─ Active Positions:                                    │
│  │  ├─ AMD 420P/415P (sold 1.50, current 0.75) 50% TP  │
│  │  ├─ NOW 80P/75P (sold 0.85, current 0.60)            │
│  │  └─ ...                                                │
│  ├─ Controls:                                            │
│  │  ├─ [Enable/Disable] Entry Scanner                   │
│  │  ├─ [View Config] Strategy Params                    │
│  │  └─ [Close Position] Manual exit                     │
│  └─ Stats: Win Rate 72%, Avg Credit $1.45, ...          │
│                                                           │
│  TAB 2: BEAR CALLS (NEW)                                 │
│  ├─ Strategy Status: 0/5 open spreads                   │
│  ├─ Today's Entries: None                               │
│  ├─ Active Positions: (empty)                           │
│  ├─ Dividend Calendar: No ex-dates this week            │
│  ├─ Controls:                                            │
│  │  ├─ [Enable/Disable] Entry Scanner (disabled)        │
│  │  ├─ [View Config] Strategy Params                    │
│  │  └─ [Close Position] Manual exit                     │
│  └─ Stats: (TBD)                                         │
│                                                           │
│  TAB 3: DASHBOARD (UNIFIED)                              │
│  ├─ Total P&L: Bull Puts +$2,340, Bear Calls +$0        │
│  ├─ Combined Greeks: Delta -0.45, Gamma 0.12, Vega -0.8 │
│  ├─ Portfolio Risk:                                      │
│  │  ├─ Max Loss (bull puts): $17,500 (5 spreads × $3.5) │
│  │  ├─ Max Loss (bear calls): $0 (0 spreads)             │
│  │  └─ Total Max Loss: $17,500 (defined risk)            │
│  ├─ Alerts:                                              │
│  │  ├─ ASML: Quote health BLIND (>5 min)                 │
│  │  ├─ AMD: Approaching stop-loss (87% of $3.00 limit)   │
│  │  └─ None from Bear Calls (no positions)               │
│  └─ Charts:                                              │
│     ├─ P&L over time (both strategies)                   │
│     ├─ Position heatmap (Greeks by underlying)           │
│     └─ Entry/Exit history (all strategies)               │
└─────────────────────────────────────────────────────────┘

DETAILED VIEW (Click on position):
┌─ AAPL 420P/415P (Bull Put)
├─ Entry: 2026-06-26 15:33, Credit: $1.50
├─ Current: $0.75 (50% profit → TP triggered)
├─ Greeks: Delta -0.28, Gamma 0.015, Vega -0.30
├─ Quote Health: LIVE (age 12 seconds)
├─ Risk: $3.50 max loss, 50% utilized
└─ Actions: [Hold] [Close Now] [Adjust]
```

### Frontend Component Structure

```
/components
├── StrategyTabs/
│   ├── BullPutStrategyTab.tsx (existing pattern)
│   ├── BearCallStrategyTab.tsx (new, mirrors bull put)
│   └── DividendAwarenessPanel.tsx (bear call exclusive)
│
├── Dashboard/
│   ├── UnifiedPnLChart.tsx (both strategies)
│   ├── CombinedGreeksPanel.tsx (aggregated Greeks)
│   ├── PortfolioRiskSummary.tsx (max loss across strategies)
│   └── AlertCenter.tsx (alerts from both)
│
├── Positions/
│   ├── BullPutPositionCard.tsx (existing)
│   ├── BearCallPositionCard.tsx (new)
│   └── DetailedPosition.tsx (generic, works for both)
│
└── Controls/
    ├── StrategyEnableToggle.tsx (per strategy)
    ├── ManualPositionClose.tsx (generic)
    └── StrategyConfig.tsx (per strategy)
```

### API Changes

**New endpoints**:
```
GET /bear-call-spreads                    # List bear call spreads
GET /bear-call-spreads/{id}               # Detail
PUT /bear-call-spreads/{id}/close         # Manual close
GET /bear-call-spreads/dividend-risk      # Alert on dividend risk
GET /dashboard/unified-pnl                # Combined P&L
GET /dashboard/combined-greeks            # Aggregated Greeks
```

**Updated endpoints**:
```
GET /spreads                              # Renamed to /bull-put-spreads
GET /account/all-positions                # Now includes both spread types
GET /health                               # Now reports on both strategies
```

---

## Configuration & Strategy Parameters

### New Config Sections: application.yml

**Bull Put Strategy** (EXISTING):
```yaml
scanner:
  bull-put:
    enabled: true
    target-delta: 0.30
    delta-min: 0.25
    delta-max: 0.30
    iv-rank-threshold: 45
    min-dte: 30
    max-dte: 50
    preferred-dte: 45
    spread-width-usd: 5.0
    min-credit-per-share: 0.35
    take-profit-percent: 0.50
    stop-loss-percent: 2.00
    time-profit-dte: 21
    drift-protection-pct: 0.05
```

**Bear Call Strategy** (NEW — SEPARATE namespace):
```yaml
scanner:
  bear-call:
    enabled: false  # Start disabled, validate bull puts first
    target-delta: 0.30  # Short call delta target (equivalent to 0.30 for puts)
    delta-min: 0.25
    delta-max: 0.30
    iv-rank-threshold: 45  # Same or higher (can adjust if needed)
    min-dte: 30
    max-dte: 50
    preferred-dte: 45
    spread-width-usd: 5.0  # Identical structure to bull put
    min-credit-per-share: 0.35
    take-profit-percent: 0.50
    stop-loss-percent: 2.00
    time-profit-dte: 21
    drift-protection-pct: 0.05
    
    # Bear call specific
    skew-adjustment: 0.05  # Accept 5% lower IV due to skew (multiply credit × 0.95)
    dividend-check-window-hours: 48  # Smart exit: check if ex-div within 48h
    ex-dividend-entry-buffer-hours: 48  # Skip entry if ex-div within 48h
```

**Portfolio Limits** (SHARED across both strategies):
```yaml
scanner:
  portfolio:
    max-open-spreads: 5  # Total across BOTH strategies (not per-strategy)
    max-portfolio-risk-usd: 17500  # $5 × $3.5 max risk × 5 spreads
    max-risk-per-spread-usd: 3500  # $5 width - $1.5 credit
```

---

## Development Priorities & Risks

### High Priority (Must-Have):
1. ✅ Separate model classes (BearCallSpread)
2. ✅ Dividend awareness service
3. ✅ Scanner logic for calls
4. ✅ Reuse execution infrastructure
5. ✅ UI with clear separation

### Medium Priority (Should-Have):
1. ⚠️ Volatility skew tracking/adjustment
2. ⚠️ Early assignment risk metrics
3. ⚠️ Hedged portfolio analysis (bull + bear together)

### Low Priority (Nice-to-Have):
1. 🔲 Algorithmic strategy mixing (when to run both)
2. 🔲 Greeks blending across strategies
3. 🔲 Risk leveling between strategies

### Known Risks:

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **Early assignment on ex-dividend** | Loss of defined risk | Dividend calendar check (Phase 3) |
| **Skew-driven worse entry quality** | Lower credit collected | Adjust IV threshold or accept lower wins |
| **Portfolio imbalance** (bull puts only) | Portfolio skew | Run both simultaneously (future) |
| **UI complexity** | User confusion | Clear separation + unified dashboard |
| **Code divergence** | Maintenance burden | Lint rules to detect divergence |

---

## Testing & Validation

### Unit Tests (by phase):
- **Phase 1**: BearCallSpread model creation, serialization
- **Phase 2**: Candidate selection logic (skew handling, dividend check)
- **Phase 3**: Dividend awareness service alerts
- **Phase 4**: Execution, exit management, reconciliation
- **Phase 5**: UI component rendering

### Integration Tests:
- Full flow: entry → monitoring → dividend event → exit
- Reconciliation with orphaned bear calls
- Cross-strategy Greeks aggregation
- UI filtering/sorting across strategies

### Paper Trading Validation:
- Run bear call scanner on paper account
- Validate entries execute properly (BAG for US, leg-by-leg for EU)
- Confirm dividend checks work correctly
- Validate exit rules fire as expected
- Monitor for false orphans

### Timeline for Validation:
- **Week 1**: Implement phases 1-3, start unit tests
- **Week 2**: Implement phases 4-5, integration testing
- **Week 3**: Paper trading validation
- **Week 4**: Bug fixes, performance tuning, code review

---

## Decision Checkpoints — LOCKED (2026-06-26)

- [x] **Phase 1**: ✅ Separate model approach APPROVED | Data schema finalized
- [x] **Phase 2**: ✅ Skew adjustment as config parameter | Separate bear-call-* namespace
- [x] **Phase 3**: ✅ Smart dividend exit logic (24-48h + ITM + extrinsic value check) | Future: reqFundamentalData integration
- [x] **Phase 4**: ✅ Reuse TradeExecutionService | Enforce shared portfolio limits (max 5 spreads total)
- [x] **Phase 5**: ✅ UI: Separate tabs [Bull Puts] [Bear Calls] [Dashboard]

---

## Approved Answers

1. **Dividend Data Source**: `reqFundamentalData` API (IB Gateway), refresh via daily scheduled task. Future ticket — not Phase 1.
2. **Skew Tolerance**: Config parameter `bear-call-skew-adjustment` (e.g., 0.05 = 5% lower IV acceptable).
3. **Early Assignment**: **Smart Force Exit Logic** — only close if ALL THREE:
   - Ex-dividend within 24–48 hours
   - Short call is ITM (strike < spot)
   - Extrinsic value < dividend amount
   Fallback: Always exit if logic impossible. Avoid entry near ex-dividend.
4. **Portfolio Mixing**: Shared limits — max 5 spreads total (both strategies combined), not per-strategy.
5. **UI Preference**: Separate tabs ✅ (with unified Dashboard tab)
6. **Paper Validation**: Defer decision (run until operator confident)

---

## Next Phase: Implementation

Ready to proceed with Phase 1 (data model). See detailed Phase 3 plan below for Smart Dividend Exit Logic.
