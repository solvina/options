# Conservative Strategy Execution Test

## Baseline (Old Aggressive Strategy)
- **Total attempts**: 184
- **Filled trades**: 4 (2.2% success rate)
- **P&L**: -$87.68 (all losses)
- **Main failures**: 
  - order_rejected: 78 (42%)
  - timed_out: 72 (39%)
  - drift_aborted: 12 (7%)
  - floor_reached: 8 (4%)

## New Conservative Strategy (Deployed)
Configuration changes applied:
- `drift-protection-pct`: 0.01 → 0.05 (tolerate 5% moves, not 1%)
- `ticks-before-price-adjust`: 5 → 15 (wait longer before chasing)
- `order-chase-max-retries`: 3 → 1 (stop aggressive chasing)
- `order-chase-timeout-minutes`: 5 → 3 (give up sooner, retry next scan)
- `min-credit-per-share`: 0.50 → 0.35 (accept reasonable credits)
- `order-chase-price-step`: 0.03 → 0.01 (gradual adjustments)
- `max-leg-bid-ask-spread-pct`: 0.30 → 0.15 (only liquid options)

## Goals
- [ ] Increase fill rate from 2.2% to >20%
- [ ] Reduce failures by 50%
- [ ] Achieve break-even or positive P&L on filled trades
- [ ] Reduce drift_aborted through looser protection threshold
- [ ] Reduce floor_reached through gentler price adjustments

## Execution Timeline
- **Deploy time**: ~4 min
- **Test duration**: 30-60 minutes of live trading
- **Next scan**: ~15 min intervals
- **Comparison window**: Next filled trades will show if strategy improved

## Key Metrics to Watch
1. **Fill rate**: Currently 2.2%, target >15%
2. **Rejection rate**: Currently 42%, target <15%
3. **Timeout rate**: Currently 39%, target <15%
4. **Average fill price vs entry**: Monitor for improvement
5. **P&L on filled trades**: Currently -100%, target breakeven or +

## Notes
- Paper account execution may differ from live trading
- Liquidity for some symbols (EU listings) may be limited in paper
- Strategy will adapt through next 2-3 trading cycles
