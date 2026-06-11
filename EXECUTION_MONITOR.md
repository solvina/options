# Active Execution Monitor - Historical Session Notes

> **HISTORICAL** — These notes document a specific 2026-05-27 monitoring session. The `/tmp/dashboard.log` and `active-monitor.sh` scripts referenced here were temporary. For current ops, see `AGENTS.rpi.md`.

# Active Execution Monitor - Real-Time Status (2026-05-27)

## System Status
- **Deployment:** ✅ Complete (Extended to 3-16 ET for EU+US)
- **Conservative Strategy:** ✅ Active
- **Monitor:** ✅ Running with auto-fix capability
- **Dashboard:** ✅ Live (updates every 30 sec during market hours)

## Market Hours
- **Trading Window:** 3 AM - 4 PM ET (9 AM - 10 PM CEST)
- **EU Market:** 3-11:30 AM ET (ASML, SAP, SIE, ALV)
- **US Market:** 9:30 AM - 4 PM ET (SPY, AAPL, NVDA, etc.)
- **Schedule:** Every 15 minutes during market hours

## Current Strategy Parameters
```
drift-protection-pct:            0.05      (5% tolerance)
ticks-before-price-adjust:       15        (wait 15 ticks before chasing)
order-chase-max-retries:         1         (one attempt only)
order-chase-timeout-minutes:     3         (give up in 3 min)
min-credit-per-share:            0.35      (accept $0.35+ credit)
order-chase-price-step:          0.01      (1¢ adjustments)
max-leg-bid-ask-spread-pct:      0.15      (liquid options only)
```

## Real-Time Monitoring

### View Live Dashboard
```bash
tail -f /tmp/dashboard.log
```

### View Master Monitor (auto-fix decisions)
```bash
tail -f /tmp/master-monitor.log
```

### View Execution Logs
```bash
ssh solvina@192.168.0.107 'journalctl -fu options-engine | grep -E "(Scanner|Execution|CLOSED_|drift|timeout|floor)"'
```

## Auto-Fix System

The monitor **automatically detects** and **fixes** these issues:

| Issue | Detection | Auto-Fix |
|-------|-----------|----------|
| **Low Fill Rate** | < 10% fills | Reduce min credit from 0.35 → 0.25 |
| **Timeouts** | > 3 timeouts | Increase ticks-before-adjust 15 → 25 |
| **Drift Triggers** | > 2 aborts | Increase drift-pct 5% → 10% |
| **Floor Reached** | > 2 floor hits | Reduce price-step 0.01 → 0.005 |
| **Losses** | < 30% win rate | Increase min credit requirement |

When an issue is detected 3+ times in a window, the auto-fixer:
1. ✅ Modifies the config
2. ✅ Rebuilds and deploys
3. ✅ Restarts the engine
4. ✅ Logs all changes

## Expected Performance

### Baseline (Old Aggressive Strategy)
- Fill rate: 2.2% (4/184 trades)
- P&L: -$87.68 (all losses)
- Main failures: order_rejected (78), timed_out (72)

### Target (New Conservative Strategy)
- Fill rate: **15%+**
- P&L: **Breakeven or positive**
- Failures: **< 20% combined rejection + timeout**
- Win rate: **> 40%**

## Quick Commands

### Check live trades
```bash
curl http://192.168.0.107:8081/options/spreads?size=50 | python3 -m json.tool | head -50
```

### Check account status
```bash
curl http://192.168.0.107:8081/options/account | python3 -m json.tool
```

### View applied auto-fixes
```bash
grep "AUTO-FIX:" /home/solvina/projects/options/engine/src/main/resources/application.yml
```

### Restart monitor (if needed)
```bash
pkill -f "active-monitor.sh"
/home/solvina/projects/options/active-monitor.sh &
```

## Status Updates

**Last checked:** Will update when market opens at 3 AM ET

📊 **Monitoring actively** - Issues will be auto-fixed, logs updated in real-time
