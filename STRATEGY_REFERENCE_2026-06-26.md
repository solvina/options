# Bull Put Spread Strategy — Current Reference (2026-06-26)

**Status**: ✅ Live in production | Validated on paper account DU7875979 | Ready for US session

---

## Quick Facts

| Parameter | Setting | Change | Effective |
|-----------|---------|--------|-----------|
| **Entry Delta** | 0.30 | ↑ from 0.15 | 2026-06-26 |
| **Exit DTE** | 21 | ↑ from 14 | 2026-06-26 |
| **Stop-Loss** | 200% credit | ↑ from 100% | 2026-06-26 |
| **Take-Profit** | 50% credit | unchanged | — |
| **Drift Limit** | 5% | ↑ from 2% | 2026-06-12 |
| **IV Threshold** | > 45% | unchanged | — |
| **DTE Window** | 30–50 (prefer 45) | unchanged | — |
| **Min Credit** | $0.35/share | unchanged | — |
| **Spread Width** | $5.00 | unchanged | — |
| **Max Spreads** | 5 concurrent | unchanged | — |

---

## Entry Decision Tree

```
Scan every 15 min (9am–11pm CEST)
  ↓
Is IV Rank > 45%? ✓ YES
  ↓
Do options exist 30–50 DTE? ✓ YES
  ↓
Can we calculate 0.25–0.30 delta spread? ✓ YES
  ↓
Is credit ≥ $0.35/share ($35 per spread)? ✓ YES
  ↓
Are we < 5 open spreads? ✓ YES
  ↓
Is drift < 5% during submission? ✓ YES
  ↓
→ SUBMIT ORDER (BAG for US, leg-by-leg for EU)
```

**Decision Points**:
- IV Rank: Market condition threshold (elevated volatility only)
- DTE: Sweet spot for theta decay vs gamma risk
- Delta: Risk-reward targeting ~$1.50 credit on $5 width
- Credit: Minimum profitability threshold
- Open spreads: Portfolio concentration limit
- Drift: Underlying price stability check

---

## Exit Decision Tree

```
Every 60 seconds (during market hours)
  ↓
For each OPEN spread:
  ↓
Quote Health = LIVE/STALE/BLIND?
  ↓
If LIVE:
  Get current spread mid-price
    ↓
    Is spread value ≤ 50% of entry credit? → CLOSE (TAKE-PROFIT)
    Is spread value ≥ 200% of entry credit? → CLOSE (STOP-LOSS)
    Is DTE ≤ 21? → CLOSE (TIME EXIT)
    Otherwise → HOLD
  ↓
If STALE/BLIND:
  No exit triggers (await fresh quotes)
  Phase 2 TBD: Synthetic BS stop-loss only
```

**Decision Points**:
- Quote freshness: No decisions on stale/blind data (safety first)
- Take-profit: 50% → exit at $0.75 on $1.50 credit (capture fast theta decay)
- Stop-loss: 200% → exit at $3.00 loss on $1.50 credit (controlled max loss)
- Time: 21 DTE → close before gamma spike and increased theta risk
- No re-entry within 4 hours of exit (cooldown after stop-loss)

---

## Position Monitoring (Continuous)

**Every 5 minutes**:
- Reconcile IBKR account positions vs engine OPEN spreads
- Detect orphaned positions (unmanaged by engine)
- Alert to Telegram if orphans detected
- Auto-adopt clean spreads on startup recovery

**Every 60 seconds**:
- Monitor quote freshness (age < 60s = LIVE, 60-300s = STALE, ≥ 300s = BLIND)
- Log transitions to observability
- Prepare for Phase 2 BS risk-only fallback when BLIND

**Every 15 minutes**:
- IV Rank recalculation
- Candidate evaluation
- New entry submission if conditions met

---

## Risk Limits

| Limit | Threshold | Trigger |
|-------|-----------|---------|
| **Drift (underlying movement)** | ≤ 5% | Abort if triggered during order submission |
| **Execution timeout** | ≤ 15 min | Rescind order if no fill |
| **Order chase (price laddering)** | ≤ 3 min | Stop laddering after 3 min, then timeout |
| **Max open spreads** | 5 | Skip new entries if at limit |
| **Max risk per spread** | $3.50 | ($5 width - $1.50 credit) |
| **Stop-loss cooldown** | 4 hours | No new entries after SL exit |

---

## Execution Mechanics

### US Markets (CBOE, ISE)
- **Order Type**: BAG (single atomic combo order)
- **Guarantee**: Both legs fill together or neither fills
- **Risk**: None (no partial fill risk)
- **Reliability**: 99%+ (native combo book support)

### EU Markets (EUREX/Frankfurt)
- **Order Type**: Leg-by-leg (no native combo support)
- **Sequence**: BUY long leg first, SELL short leg second
- **Guarantee**: Long leg fills, then short leg (worst case: paid-for long)
- **Risk**: 0.8% timeout rate → stranded long position (manual recovery)
- **Status**: Functional but fragile; BAG support planned

---

## Monitoring & Alerts

**Telegram Alerts** (Real-time):
- ✅ Orphan detection (5 min reconciliation)
- 🔲 Quote health transitions (Phase 2 — not yet)
- 🔲 Order rejections (Phase 2 — not yet)

**Engine Logs** (Structured):
- Every 15 min: `IV Rank: XX%` for each symbol
- Entry attempts: `ENTRY [symbol] sold=XXP bought=YYP credit=$Z.ZZ`
- Fills: `orderId filled`
- Exits: `Closing spread at market — TP: ...` / `SL: ...` / `DTE: ...`
- Quote health: `Quote health BLIND` / `STALE` (Phase 1 active)

**Health Endpoints**:
- `http://localhost:8081/options/actuator/health` — Engine status
- `http://localhost:8081/options/health/ibkr` — IBKR connection

---

## Testing & Validation

**Paper Account** (DU7875979):
- ✅ Orphan detection working
- ✅ Strategy params live (delta 0.30, DTE 21, 200% SL)
- ✅ Reconciliation every 5 min
- ✅ Ready for US session validation

**Next Steps**:
1. Monitor US session fill rate with new 0.30-delta params
2. Confirm 200% stop-loss triggers appropriately
3. Validate quote health monitoring (Phase 1)
4. After 3 sessions: evaluate Phase 2 BS risk-only fallback

---

## Configuration Reference

Location: `/home/solvina/projects/options/engine/src/main/resources/application.yml`

```yaml
scanner:
  target-delta: 0.30           # short put delta target
  delta-min: 0.25              # delta acceptance band
  delta-max: 0.30              #
  iv-rank-threshold: 45        # entry condition
  min-dte: 30                  # days-to-expiration window
  max-dte: 50                  #
  preferred-dte: 45            #
  spread-width-usd: 5.0        # fixed spread width
  min-credit-per-share: 0.35   # minimum $35 per spread
  take-profit-percent: 0.50    # exit trigger: 50% of credit
  stop-loss-percent: 2.00      # exit trigger: 200% of credit
  time-profit-dte: 21          # time-based exit at 21 DTE
  drift-protection-pct: 0.05   # abort if underlying moves > 5%
  max-open-spreads: 5          # portfolio limit
  execution-timeout-minutes: 15 # order submission timeout
  order-chase-timeout-minutes: 3 # price ladder timeout
  order-chase-max-retries: 1    # one ladder attempt
  cron: "0 */15 9-22 * * MON-FRI"  # market hours

quote-monitoring:
  stale-seconds: 60            # STALE threshold
  blind-seconds: 300           # BLIND threshold (5 min)
  blind-cycles-before-exit: 2  # Phase 2 hysteresis
  synthetic-risk-exit-enabled: false  # Phase 2 (disabled, pending validation)

reconciliation:
  delay-ms: 300000             # run every 5 min
  initial-delay-ms: 120000     # start 2 min after boot
```

---

## Documentation Map

| Document | Location | Purpose |
|----------|----------|---------|
| **TRADING_ENGINE.md** | `engine/` | Authoritative strategy & implementation details |
| **STRATEGY_TUNING_2026-06-26.md** | `engine/` | Rationale for parameter changes |
| **03-strategy-flow.md** | `ai-plans/` | Visual flowcharts (entry/exit decisions) |
| **CLAUDE.md** | `/home/solvina/options/` | Project overview & dev guidelines |
| **TELEGRAM_BOT.md** | `/home/solvina/options/` | Bot commands & interface |
| **This file** | `projects/options/` | Quick reference & decision trees |

---

**Last Updated**: 2026-06-26, 14:10 CEST  
**Next Review**: After 3 trading sessions (2026-06-30 or later)
