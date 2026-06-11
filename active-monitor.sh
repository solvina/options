#!/bin/bash

# Master execution monitor - watches trades and auto-fixes issues

echo "╔════════════════════════════════════════════════════════════════════════════════╗"
echo "║            🎯 ACTIVE EXECUTION MONITOR & AUTO-FIX COORDINATOR                  ║"
echo "╚════════════════════════════════════════════════════════════════════════════════╝"
echo ""
echo "📊 Monitoring:"
echo "   • Real-time trade execution"
echo "   • Performance metrics (fill rate, P&L)"
echo "   • Strategy issues (timeouts, drift, floors)"
echo "   • Automatic fixes when issues detected"
echo ""
echo "⏰ Active during: 3-16 ET (9 AM-10 PM CEST) Mon-Fri"
echo "📍 Checking every 30 seconds"
echo ""

ISSUE_COUNT=0
MAX_ISSUES=3  # Max issues before forcing a fix

check_and_fix_issues() {
  local dashboard_log="/tmp/dashboard.log"
  
  if [ ! -f "$dashboard_log" ]; then
    return 0
  fi
  
  # Check recent logs for alerts
  local recent=$(tail -20 "$dashboard_log" 2>/dev/null)
  
  # Count different issue types in last 10 checks
  local timeout_count=$(echo "$recent" | grep -c "TIMEOUT_ISSUE" || echo "0")
  local drift_count=$(echo "$recent" | grep -c "DRIFT_ISSUE" || echo "0")
  local floor_count=$(echo "$recent" | grep -c "FLOOR_ISSUE" || echo "0")
  local fill_count=$(echo "$recent" | grep -c "LOW_FILL_RATE" || echo "0")
  
  # Apply fixes if issues persist
  if [ "$timeout_count" -gt 2 ]; then
    echo ""
    echo "🔧 FIXING: Order timeout issues (detected $timeout_count times)"
    python3 /home/solvina/projects/options/auto-fix-strategy.py timeout << 'EOF'
y
EOF
    return 1
  fi
  
  if [ "$drift_count" -gt 2 ]; then
    echo ""
    echo "🔧 FIXING: Drift protection issues (detected $drift_count times)"
    python3 /home/solvina/projects/options/auto-fix-strategy.py drift << 'EOF'
y
EOF
    return 1
  fi
  
  if [ "$floor_count" -gt 2 ]; then
    echo ""
    echo "🔧 FIXING: Price floor issues (detected $floor_count times)"
    python3 /home/solvina/projects/options/auto-fix-strategy.py floor << 'EOF'
y
EOF
    return 1
  fi
  
  if [ "$fill_count" -gt 2 ]; then
    echo ""
    echo "🔧 FIXING: Low fill rate (detected $fill_count times)"
    python3 /home/solvina/projects/options/auto-fix-strategy.py fill_rate << 'EOF'
y
EOF
    return 1
  fi
  
  return 0
}

# Main monitoring loop
while true; do
  ET_HOUR=$(TZ='America/New_York' date +%H)
  ET_WEEKDAY=$(TZ='America/New_York' date +%u)
  
  # Check if in market hours
  if [ "$ET_HOUR" -lt 3 ] || [ "$ET_HOUR" -ge 16 ] || [ "$ET_WEEKDAY" -gt 5 ]; then
    echo "[$(date '+%H:%M:%S')] ⏸ Market closed - resuming at 3 AM ET"
    sleep 300
    continue
  fi
  
  # Check for issues and fix if needed
  if ! check_and_fix_issues; then
    echo "✅ Fixes deployed - monitoring continues..."
    sleep 60
  fi
  
  sleep 30
done
