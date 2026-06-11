#!/usr/bin/env python3
"""
Real-time strategy execution monitor
Tracks trades, detects issues, recommends fixes
"""

import json
import subprocess
import time
from datetime import datetime, timedelta
from collections import defaultdict

class ExecutionMonitor:
    def __init__(self):
        self.last_fills = set()
        self.failure_history = defaultdict(list)
        self.trade_history = []
        
    def get_trades(self):
        """Fetch current trades from API"""
        try:
            result = subprocess.run(
                ['curl', '-s', '--connect-timeout', '5', 
                 'http://192.168.0.107:8081/options/spreads?size=200'],
                capture_output=True, text=True, timeout=10
            )
            if result.returncode == 0:
                return json.loads(result.stdout)
            return None
        except:
            return None
    
    def is_market_hours(self):
        """Check if we're in trading hours (3-16 ET)"""
        from datetime import datetime
        import pytz
        
        et = pytz.timezone('America/New_York')
        now = datetime.now(et)
        hour = now.hour
        weekday = now.weekday()  # 0-4 = Mon-Fri
        
        return (hour >= 3 and hour < 16) and weekday < 5
    
    def analyze_trades(self, data):
        """Analyze trades and detect issues"""
        if not data or 'content' not in data:
            return None
        
        spreads = data['content']
        now = datetime.now()
        
        # Get recent trades (last 2 hours)
        recent = []
        for s in spreads:
            try:
                opened = datetime.fromisoformat(s['openedAt'].replace('Z', '+00:00')).replace(tzinfo=None)
                age_min = (now - opened).total_seconds() / 60
                if age_min < 120:
                    recent.append(s)
            except:
                pass
        
        if not recent:
            return None
        
        filled = [s for s in recent if s.get('closePricePerShare') is not None]
        failed = [s for s in recent if s.get('closePricePerShare') is None and s['status'] != 'OPEN']
        open_trades = [s for s in recent if s['status'] == 'OPEN']
        
        # Calculate metrics
        closed = len(filled) + len(failed)
        fill_rate = (len(filled) / closed * 100) if closed > 0 else 0
        
        total_pnl = sum((s['creditPerShare'] - s.get('closePricePerShare', s['creditPerShare'])) * 100 
                       for s in filled)
        
        # Failure breakdown
        failures = defaultdict(int)
        for s in failed:
            failures[s.get('closeReason', 'unknown')] += 1
        
        return {
            'timestamp': datetime.now().strftime('%H:%M:%S'),
            'total': len(recent),
            'filled': len(filled),
            'failed': len(failed),
            'open': len(open_trades),
            'fill_rate': fill_rate,
            'pnl': total_pnl,
            'failures': dict(failures),
            'trades': filled + failed,
        }
    
    def detect_issues(self, analysis):
        """Detect strategy issues"""
        issues = []
        
        if not analysis:
            return issues
        
        # Issue 1: Low fill rate
        if analysis['fill_rate'] < 10 and analysis['failed'] > 0:
            issues.append({
                'severity': 'HIGH',
                'issue': f"Low fill rate: {analysis['fill_rate']:.1f}%",
                'suggestion': 'Increase min credit or loosen bid-ask spread requirement'
            })
        
        # Issue 2: Too many timeouts
        timeouts = analysis['failures'].get('timed_out', 0)
        if timeouts > 3:
            issues.append({
                'severity': 'MEDIUM',
                'issue': f"{timeouts} order timeouts",
                'suggestion': 'Increase order-chase-timeout or reduce execution-timeout'
            })
        
        # Issue 3: Drift protection triggering
        drifts = analysis['failures'].get('drift_aborted', 0)
        if drifts > 2:
            issues.append({
                'severity': 'MEDIUM',
                'issue': f"{drifts} drift protection triggers",
                'suggestion': 'Further increase drift-protection-pct (currently 5%)'
            })
        
        # Issue 4: Floor reached
        floors = analysis['failures'].get('floor_reached', 0)
        if floors > 2:
            issues.append({
                'severity': 'MEDIUM',
                'issue': f"{floors} orders hit price floor",
                'suggestion': 'Reduce order-chase-price-step (currently 0.01)'
            })
        
        # Issue 5: Losses
        if analysis['pnl'] < 0 and analysis['filled'] > 2:
            win_rate = sum(1 for t in analysis.get('trades', []) 
                          if (t['creditPerShare'] - t.get('closePricePerShare', 0)) > 0) / analysis['filled']
            if win_rate < 0.3:
                issues.append({
                    'severity': 'HIGH',
                    'issue': f"High loss rate: {win_rate*100:.0f}% wins",
                    'suggestion': 'Increase min credit or improve entry selection'
                })
        
        return issues
    
    def run(self):
        """Main monitoring loop"""
        print("\n" + "="*100)
        print(" "*30 + "🎯 REAL-TIME EXECUTION MONITOR")
        print("="*100)
        print(f"Started: {datetime.now()}")
        print(f"Trading hours: 3-16 ET (9 AM-10 PM CEST)")
        print("Checking every 30 seconds during market hours\n")
        
        while True:
            if not self.is_market_hours():
                print(f"[{datetime.now().strftime('%H:%M:%S')}] Outside market hours, sleeping...", flush=True)
                time.sleep(60)
                continue
            
            data = self.get_trades()
            if not data:
                print(f"[{datetime.now().strftime('%H:%M:%S')}] ⚠ Failed to fetch trades", flush=True)
                time.sleep(30)
                continue
            
            analysis = self.analyze_trades(data)
            if not analysis:
                print(f"[{datetime.now().strftime('%H:%M:%S')}] Waiting for first trades...", flush=True)
                time.sleep(30)
                continue
            
            # Print status
            print(f"[{analysis['timestamp']}] Total: {analysis['total']:2} | Filled: {analysis['filled']:2} ({analysis['fill_rate']:5.1f}%) | Failed: {analysis['failed']:2} | Open: {analysis['open']:2} | P&L: ${analysis['pnl']:+7.2f}", flush=True)
            
            # Show failures
            if analysis['failures']:
                for reason, count in sorted(analysis['failures'].items(), key=lambda x: -x[1]):
                    print(f"         └─ {reason}: {count}", flush=True)
            
            # Detect and show issues
            issues = self.detect_issues(analysis)
            if issues:
                print(f"\n⚠️  ISSUES DETECTED:", flush=True)
                for issue in issues:
                    print(f"   [{issue['severity']}] {issue['issue']}", flush=True)
                    print(f"   → Fix: {issue['suggestion']}", flush=True)
                print()
            
            time.sleep(30)

if __name__ == '__main__':
    monitor = ExecutionMonitor()
    try:
        monitor.run()
    except KeyboardInterrupt:
        print("\n✋ Monitor stopped")
