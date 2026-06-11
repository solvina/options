#!/bin/bash
# Quick trade results checker - run tomorrow morning

echo "════════════════════════════════════════════════════════════════════════════════"
echo "  TRADE EXECUTION RESULTS - Conservative Strategy"
echo "════════════════════════════════════════════════════════════════════════════════"
echo ""

if [ ! -f /tmp/trade-execution-monitor.log ]; then
  echo "Monitor log not found. Checking live API..."
  curl -s "http://192.168.0.107:8081/options/spreads?size=150" | python3 << 'PYEOF'
import sys, json
from datetime import datetime

data = json.load(sys.stdin)
spreads = data['content']

now = datetime.now()
print("\n📊 LIVE TRADE SUMMARY (last 24 hours):")
print("=" * 80)

recent = []
for s in spreads:
    try:
        if s['openedAt']:
            opened = datetime.fromisoformat(s['openedAt'].replace('Z', '+00:00')).replace(tzinfo=None)
            age_hours = (now - opened).total_seconds() / 3600
            if age_hours < 24:
                recent.append(s)
    except:
        pass

if recent:
    filled = [s for s in recent if s.get('closePricePerShare') is not None]
    failed = [s for s in recent if s.get('closePricePerShare') is None]

    print(f"\nTotal attempts: {len(recent)}")
    print(f"Filled: {len(filled)} ({len(filled)*100.0/len(recent):.1f}%)")
    print(f"Failed: {len(failed)}")

    if filled:
        print("\n✅ FILLED TRADES:")
        total_pnl = 0
        wins = 0
        for s in sorted(filled, key=lambda x: x.get('openedAt', ''), reverse=True):
            pnl = (s['creditPerShare'] - s['closePricePerShare']) * 100
            total_pnl += pnl
            if pnl > 0:
                wins += 1
            status = '✓ WIN' if pnl > 0 else '✗ LOSS'
            print(f"  {status} {s['symbol']:6} Entry: ${s['creditPerShare']:.4f} Exit: ${s['closePricePerShare']:.4f} P&L: ${pnl:+.2f}")
        print(f"  Total P&L: ${total_pnl:+.2f} ({wins}/{len(filled)} profitable)")

    if failed:
        print("\n❌ FAILURE BREAKDOWN:")
        reasons = {}
        for s in failed:
            r = s.get('closeReason', 'unknown')
            reasons[r] = reasons.get(r, 0) + 1
        for reason, count in sorted(reasons.items(), key=lambda x: -x[1]):
            print(f"  {reason}: {count}")
else:
    print("No recent trades found.")
PYEOF
else
  echo "📖 Monitor Log Summary:"
  echo ""
  tail -100 /tmp/trade-execution-monitor.log
fi

echo ""
echo "════════════════════════════════════════════════════════════════════════════════"
