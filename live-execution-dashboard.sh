#!/bin/bash

# Live execution dashboard - shows real-time performance and alerts

API_URL="http://192.168.0.107:8081/options/spreads?size=200"
UPDATE_INTERVAL=30  # seconds

echo "🎯 LIVE EXECUTION DASHBOARD"
echo "Market hours: 3-16 ET (starting in ~$(( ($(TZ='America/New_York' date +%s) - $(TZ='America/New_York' date -d '03:00' +%s)) / 3600 )) hours)"
echo ""

while true; do
  # Get current trades
  RESPONSE=$(curl -s --connect-timeout 5 "$API_URL" 2>/dev/null)
  
  if [ -z "$RESPONSE" ]; then
    echo "[$(date '+%H:%M:%S')] ⚠ API not responding"
    sleep $UPDATE_INTERVAL
    continue
  fi
  
  # Check if we're in market hours
  ET_HOUR=$(TZ='America/New_York' date +%H)
  ET_WEEKDAY=$(TZ='America/New_York' date +%u)
  
  if [ "$ET_HOUR" -lt 3 ] || [ "$ET_HOUR" -ge 16 ] || [ "$ET_WEEKDAY" -gt 5 ]; then
    echo "[$(date '+%H:%M:%S')] ⏸ Outside market hours (next: 3 AM ET)"
    sleep 60
    continue
  fi
  
  # Parse and analyze trades
  REPORT=$(echo "$RESPONSE" | python3 << 'ANALYSISEOF'
import sys, json
from datetime import datetime
from collections import defaultdict

try:
    data = json.load(sys.stdin)
    spreads = data['content']
except:
    sys.exit(1)

now = datetime.now()
recent = []

# Get trades from last 2 hours
for s in spreads:
    try:
        opened = datetime.fromisoformat(s['openedAt'].replace('Z', '+00:00')).replace(tzinfo=None)
        age_min = (now - opened).total_seconds() / 60
        if age_min < 120:
            recent.append(s)
    except:
        pass

if not recent:
    print("WAITING")
    sys.exit(0)

filled = [s for s in recent if s.get('closePricePerShare') is not None]
failed = [s for s in recent if s.get('closePricePerShare') is None and s['status'] != 'OPEN']
open_trades = [s for s in recent if s['status'] == 'OPEN']

closed = len(filled) + len(failed)
fill_rate = (len(filled) / closed * 100) if closed > 0 else 0

pnl = sum((s['creditPerShare'] - s.get('closePricePerShare', s['creditPerShare'])) * 100 for s in filled)

failures = defaultdict(int)
for s in failed:
    failures[s.get('closeReason', 'unknown')] += 1

# Generate report
print(f"TRADING:{closed},{len(filled)},{len(failed)},{len(open_trades)},{fill_rate:.1f},{pnl:.2f}")

for reason, count in sorted(failures.items()):
    print(f"FAIL:{reason}:{count}")

# Alert conditions
if fill_rate < 10 and closed > 0:
    print("ALERT:LOW_FILL_RATE")
if failures.get('timed_out', 0) > 3:
    print("ALERT:TIMEOUT_ISSUE")
if failures.get('drift_aborted', 0) > 2:
    print("ALERT:DRIFT_ISSUE")
if failures.get('floor_reached', 0) > 2:
    print("ALERT:FLOOR_ISSUE")
if pnl < 0 and len(filled) > 2:
    print("ALERT:LOSSES")
ANALYSISEOF
  )
  
  if [ "$REPORT" = "WAITING" ]; then
    echo "[$(date '+%H:%M:%S')] ⏳ Waiting for first trades..."
    sleep $UPDATE_INTERVAL
    continue
  fi
  
  # Parse report
  while IFS=':' read -r TYPE VALUE1 VALUE2 VALUE3 VALUE4 VALUE5; do
    case $TYPE in
      TRADING)
        TOTAL=$VALUE1
        FILLED=$VALUE2
        FAILED=$VALUE3
        OPEN=$VALUE4
        FILL_RATE=$VALUE5
        ;;
      FAIL)
        REASON=$VALUE1
        COUNT=$VALUE2
        echo "[$(date '+%H:%M:%S')] {$REASON: $COUNT" >> /tmp/failures.tmp
        ;;
      ALERT)
        ALERT=$VALUE1
        echo "[$(date '+%H:%M:%S')] 🚨 ALERT: $ALERT"
        ;;
    esac
  done < <(echo "$REPORT")
  
  # Display status
  printf "[%s] Total:%2d | Filled:%2d (%.0f%%) | Failed:%2d | Open:%2d\n" \
    "$(date '+%H:%M:%S')" "$TOTAL" "$FILLED" "$FILL_RATE" "$FAILED" "$OPEN"
  
  sleep $UPDATE_INTERVAL
done
