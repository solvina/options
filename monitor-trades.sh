#!/bin/bash
# Persistent trade execution monitor - logs results for review

LOG_FILE="/tmp/trade-execution-monitor.log"
SUMMARY_FILE="/tmp/trade-execution-summary.txt"

echo "========================================" >> "$LOG_FILE"
echo "Trade Execution Monitor Started" >> "$LOG_FILE"
echo "Time: $(date)" >> "$LOG_FILE"
echo "Strategy: Conservative (deployed 21:52 ET)" >> "$LOG_FILE"
echo "========================================" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

# Baseline for comparison
echo "BASELINE METRICS (old aggressive strategy):" >> "$LOG_FILE"
echo "  - Total attempts: 184" >> "$LOG_FILE"
echo "  - Fill rate: 2.2% (4 filled)" >> "$LOG_FILE"
echo "  - All fills: -\$87.68 (losses)" >> "$LOG_FILE"
echo "  - Main failures: order_rejected (78), timed_out (72)" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

while true; do
  TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
  
  TRADES=$(curl -s "http://192.168.0.107:8081/options/spreads?size=100" 2>/dev/null)
  
  if [ -n "$TRADES" ]; then
    # Extract recent trades (last 4 hours)
    FILLED=$(echo "$TRADES" | python3 << 'PYEOF'
import sys, json
from datetime import datetime, timedelta

try:
    data = json.load(sys.stdin)
    spreads = data['content']
    
    now = datetime.now()
    recent_filled = []
    
    for s in spreads:
        try:
            if s.get('closePricePerShare') is not None and s['openedAt']:
                opened = datetime.fromisoformat(s['openedAt'].replace('Z', '+00:00')).replace(tzinfo=None)
                age_hours = (now - opened).total_seconds() / 3600
                if age_hours < 4:  # Last 4 hours
                    pnl = (s['creditPerShare'] - s['closePricePerShare']) * 100
                    recent_filled.append((s['symbol'], s['creditPerShare'], s['closePricePerShare'], pnl, age_hours))
        except:
            pass
    
    print(len(recent_filled))
    for sym, entry, exit_p, pnl, age in sorted(recent_filled, key=lambda x: -x[4]):
        status = 'WIN' if pnl > 0 else 'LOSS'
        print(f"{sym},{entry:.4f},{exit_p:.4f},{pnl:+.2f},{status},{age:.1f}")
except:
    print("0")
PYEOF
    )
    
    if [ "$(echo "$FILLED" | head -1)" != "0" ]; then
      FILLED_COUNT=$(echo "$FILLED" | head -1)
      echo "[$TIMESTAMP] New fills detected: $FILLED_COUNT" >> "$LOG_FILE"
      
      TOTAL_PNL=0
      WIN_COUNT=0
      while IFS=',' read -r sym entry exit pnl status age; do
        if [ "$sym" != "" ] && [ "$sym" != "0" ]; then
          echo "  $sym: Entry \$$entry Exit \$$exit P&L: \$$pnl [$status] (${age}h ago)" >> "$LOG_FILE"
          if [ "$status" = "WIN" ]; then
            ((WIN_COUNT++))
          fi
          TOTAL_PNL=$(echo "$TOTAL_PNL + $pnl" | bc)
        fi
      done < <(echo "$FILLED" | tail -n +2)
      
      echo "  Summary: $FILLED_COUNT filled, $WIN_COUNT profitable, Total P&L: \$$TOTAL_PNL" >> "$LOG_FILE"
    fi
    
    # Get overall stats
    STATS=$(echo "$TRADES" | python3 << 'STATSEOF'
import sys, json
from datetime import datetime

try:
    data = json.load(sys.stdin)
    spreads = data['content']
    
    now = datetime.now()
    recent = []
    for s in spreads:
        try:
            if s['openedAt']:
                opened = datetime.fromisoformat(s['openedAt'].replace('Z', '+00:00')).replace(tzinfo=None)
                if (now - opened).total_seconds() < 14400:  # 4 hours
                    recent.append(s)
        except:
            pass
    
    if recent:
        filled = [s for s in recent if s.get('closePricePerShare') is not None]
        failed = [s for s in recent if s.get('closePricePerShare') is None]
        
        fill_rate = len(filled) * 100.0 / len(recent) if recent else 0
        print(f"{len(recent)},{len(filled)},{len(failed)},{fill_rate:.1f}")
        
        # Failure reasons
        reasons = {}
        for s in failed:
            r = s.get('closeReason', 'unknown')
            reasons[r] = reasons.get(r, 0) + 1
        
        for reason, count in sorted(reasons.items(), key=lambda x: -x[1])[:3]:
            print(f"{reason}:{count}")
except:
    print("0,0,0,0")
STATSEOF
    )
    
    if [ "$(echo "$STATS" | head -1 | cut -d, -f1)" != "0" ]; then
      read -r TOTAL FILLED FAILED RATE < <(echo "$STATS" | head -1 | tr ',' ' ')
      echo "[$TIMESTAMP] Stats - Total: $TOTAL | Filled: $FILLED | Failed: $FAILED | Rate: ${RATE}%" >> "$LOG_FILE"
      
      while IFS=':' read -r reason count; do
        if [ "$reason" != "" ]; then
          echo "  $reason: $count" >> "$LOG_FILE"
        fi
      done < <(echo "$STATS" | tail -n +2)
    fi
  fi
  
  # Write summary every 10 iterations (5 minutes)
  if [ $(($(date +%s) % 300)) -lt 30 ]; then
    {
      echo "==============================================="
      echo "TRADE EXECUTION SUMMARY"
      echo "Generated: $(date)"
      echo "==============================================="
      echo ""
      tail -50 "$LOG_FILE"
    } > "$SUMMARY_FILE"
  fi
  
  sleep 30
done
