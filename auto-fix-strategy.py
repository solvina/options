#!/usr/bin/env python3
"""
Automatic strategy fixer
Adjusts parameters based on detected issues
"""

import subprocess
import json
from datetime import datetime

class StrategyFixer:
    def __init__(self):
        self.config_file = "/home/solvina/projects/options/engine/src/main/resources/application.yml"
        self.fixes_applied = []
    
    def read_config(self):
        """Read current configuration"""
        try:
            with open(self.config_file) as f:
                return f.read()
        except:
            return None
    
    def write_config(self, content):
        """Write configuration"""
        try:
            with open(self.config_file, 'w') as f:
                f.write(content)
            return True
        except:
            return False
    
    def apply_fix(self, parameter, old_value, new_value, reason):
        """Apply a configuration fix"""
        config = self.read_config()
        if not config:
            print(f"❌ Could not read config")
            return False
        
        new_config = config.replace(
            f"{parameter}: {old_value}",
            f"{parameter}: {new_value}  # AUTO-FIX: {reason}"
        )
        
        if new_config == config:
            print(f"❌ Parameter {parameter} not found")
            return False
        
        if self.write_config(new_config):
            print(f"✅ Applied fix: {parameter} {old_value} → {new_value}")
            print(f"   Reason: {reason}")
            self.fixes_applied.append((parameter, old_value, new_value, reason))
            return True
        
        return False
    
    def fix_low_fill_rate(self):
        """Fix low fill rate (< 10%)"""
        print("\n🔧 Fixing LOW FILL RATE...")
        
        # Option 1: Increase min credit (make orders more attractive)
        self.apply_fix(
            "min-credit-per-share",
            "0.35",
            "0.25",
            "Low fill rate - accepting lower credits for better execution"
        )
        
        # Option 2: Loosen bid-ask requirement
        self.apply_fix(
            "max-leg-bid-ask-spread-pct",
            "0.15",
            "0.25",
            "Low fill rate - accepting wider spreads"
        )
    
    def fix_drift_protection(self):
        """Fix excessive drift protection triggers"""
        print("\n🔧 Fixing DRIFT PROTECTION ISSUES...")
        
        self.apply_fix(
            "drift-protection-pct",
            "0.05",
            "0.10",
            "Drift protection triggering too often - doubling threshold to 10%"
        )
    
    def fix_timeout_issues(self):
        """Fix order timeouts"""
        print("\n🔧 Fixing ORDER TIMEOUTS...")
        
        # Give orders more time before chasing
        self.apply_fix(
            "ticks-before-price-adjust",
            "15",
            "25",
            "Too many timeouts - wait longer before chasing prices"
        )
        
        # Increase execution timeout
        self.apply_fix(
            "execution-timeout-minutes",
            "15",
            "20",
            "Orders timing out - extending execution window"
        )
    
    def fix_floor_reached(self):
        """Fix orders hitting price floor"""
        print("\n🔧 Fixing PRICE FLOOR ISSUES...")
        
        # Reduce price adjustment step
        self.apply_fix(
            "order-chase-price-step",
            "0.01",
            "0.005",
            "Orders hitting floor - reducing price step to half a cent"
        )
        
        # Reduce retries
        self.apply_fix(
            "order-chase-max-retries",
            "1",
            "0",
            "Orders hitting floor - stop chasing entirely"
        )
    
    def deploy(self):
        """Rebuild and deploy fixed configuration"""
        if not self.fixes_applied:
            print("No fixes to deploy")
            return False
        
        print("\n📦 Deploying fixes...")
        
        try:
            result = subprocess.run(
                ['bash', '-c', 
                 'cd /home/solvina/projects/options && RPI_HOST=solvina@192.168.0.107 ./deploy.sh > /tmp/deploy.log 2>&1'],
                capture_output=True,
                timeout=300
            )
            
            if result.returncode == 0:
                print("✅ Deployment successful")
                print(f"   Applied {len(self.fixes_applied)} fixes")
                return True
            else:
                print("❌ Deployment failed")
                return False
        except:
            print("❌ Deployment error")
            return False
    
    def generate_report(self):
        """Generate fix report"""
        if not self.fixes_applied:
            return
        
        print("\n" + "="*80)
        print("AUTOMATIC FIXES APPLIED")
        print("="*80)
        
        for param, old, new, reason in self.fixes_applied:
            print(f"\n{param}:")
            print(f"  {old} → {new}")
            print(f"  {reason}")
        
        print("\n" + "="*80)

if __name__ == '__main__':
    import sys
    
    if len(sys.argv) < 2:
        print("Usage: auto-fix-strategy.py <issue_type>")
        print("Issue types: fill_rate, drift, timeout, floor")
        sys.exit(1)
    
    fixer = StrategyFixer()
    issue = sys.argv[1].lower()
    
    if issue == 'fill_rate':
        fixer.fix_low_fill_rate()
    elif issue == 'drift':
        fixer.fix_drift_protection()
    elif issue == 'timeout':
        fixer.fix_timeout_issues()
    elif issue == 'floor':
        fixer.fix_floor_reached()
    
    if fixer.fixes_applied and input("\nDeploy these fixes? (y/n): ").lower() == 'y':
        fixer.deploy()
        fixer.generate_report()
