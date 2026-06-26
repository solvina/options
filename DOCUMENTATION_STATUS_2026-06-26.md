# Documentation Status & Update Plan — 2026-06-26

## Current State Audit

### 📋 AI-Plans (Historical Architecture Docs)
Located: `/home/solvina/projects/options/ai-plans/`

| File | Status | Action |
|------|--------|--------|
| 00-application-description.md | ⚠️ OUTDATED | Archive (superseded by TRADING_ENGINE.md) |
| 01-volatility-hunter-v1.md | ⚠️ OUTDATED | Archive (old strategy name) |
| 02-fixture-fetch-tests.md | ✅ VALID | Keep (test framework still used) |
| 03-strategy-flow.md | ⚠️ OUTDATED | Update with current decision points |
| 04-backtest-framework.md | ✅ VALID | Keep (backtest infrastructure current) |
| 05-trade-execution.md | ⚠️ OUTDATED | Merge into TRADING_ENGINE.md |
| 06-rpi-ibkr-setup.md | ✅ VALID | Keep (setup still current) |
| 07-add-flag-pole-strategy.md | ✅ VALID | Keep (flag strategy active) |
| 07-telegram-bot.md | ⚠️ OUTDATED | Archive (bot docs in /home/solvina/options/TELEGRAM_BOT.md) |
| paper-vs-live-account.md | ✅ VALID | Keep (paper validation still active) |

### 📊 Project Status Docs (Historical, Mostly Outdated)
| File | Status | Action |
|------|--------|--------|
| ALL_23_ISSUES_IMPLEMENTATION_SUMMARY.md | 🔴 STALE | Archive (from June 10, superseded by fixes) |
| COMPREHENSIVE_FIX_PLAN_ALL_23_ISSUES.md | 🔴 STALE | Archive (old plan) |
| CRITICAL_FIXES_IMPLEMENTATION_PLAN.md | 🔴 STALE | Archive (old plan) |
| CRITICAL_FIXES_QUICK_REFERENCE.md | 🔴 STALE | Archive (old plan) |
| EDGE_CASES_AND_BUGS_FOUND.md | 🔴 STALE | Archive (old audit) |
| PHASE_1/2/3_IMPLEMENTATION_COMPLETE.md | 🔴 STALE | Archive (false completions, all completed in single delivery) |
| EXECUTION_MONITORING_REPORT_FINAL.md | 🔴 STALE | Archive (historical report) |
| EXECUTION_MONITOR.md | 🔴 STALE | Archive (old monitoring template) |
| IMPLEMENTATION_STATUS.md | 🔴 STALE | Archive (old status) |

### ✅ Current Live Docs (Keep & Update)
| File | Location | Status |
|------|----------|--------|
| TRADING_ENGINE.md | `/home/solvina/projects/options/engine/` | ✅ JUST UPDATED |
| STRATEGY_TUNING_2026-06-26.md | `/home/solvina/projects/options/engine/` | ✅ JUST ADDED |
| TELEGRAM_BOT.md | `/home/solvina/options/` | ⚠️ NEEDS UPDATE |
| CLAUDE.md | `/home/solvina/options/` | ⚠️ NEEDS UPDATE |
| EXCHANGE_STRATEGY_IMPLEMENTATION.md | `/home/solvina/projects/options/` | ⚠️ NEEDS REVIEW |

---

## Update Actions Required

### 1. Update Strategy Flow Doc (03-strategy-flow.md)
**Current state**: Documents old 14 DTE exit, 2% drift, etc.  
**Update**: Current decision points with new 21 DTE, 5% drift, 200% stop-loss

### 2. Update TELEGRAM_BOT.md (/home/solvina/options/)
**Current state**: May reference old strategy params  
**Update**: Reference new strategy settings, Phase 1 monitoring

### 3. Update CLAUDE.md (/home/solvina/options/)
**Current state**: References old "Remediation PRs A–E pending deploy"  
**Update**: Current status (deployed, validated)

### 4. Archive Historical Docs
Move to `.archive/` folder:
- All PHASE_1/2/3_IMPLEMENTATION_COMPLETE.md
- All 23_ISSUES plans
- EXECUTION_MONITORING_REPORT_FINAL.md
- IMPLEMENTATION_STATUS.md

### 5. Create Central Strategy Reference
Consolidate into single source-of-truth:
- `/home/solvina/projects/options/engine/TRADING_ENGINE.md` — AUTHORITATIVE
- All other docs reference this

---

## Implementation Plan

### Priority 1 (Critical — Strategy Params)
- [ ] Update `ai-plans/03-strategy-flow.md` with current decision points
- [ ] Update `/home/solvina/options/CLAUDE.md` with current status
- [ ] Update `/home/solvina/options/TRADING_ENGINE.md` if different from engine version

### Priority 2 (Supporting)
- [ ] Update `/home/solvina/options/TELEGRAM_BOT.md` for strategy context
- [ ] Review `EXCHANGE_STRATEGY_IMPLEMENTATION.md` for currency/exchange details
- [ ] Verify `ai-plans/04-backtest-framework.md` still accurate

### Priority 3 (Cleanup)
- [ ] Create `.archive/` directory
- [ ] Move all stale/historical docs
- [ ] Update `README.md` or `DOCUMENTATION_INDEX.md` to map docs

---

## Notes

**Single Source of Truth**: `/home/solvina/projects/options/engine/TRADING_ENGINE.md`
- Complete strategy parameters
- Current decision points
- Implementation details
- Risk management rules

**Reference Docs**: ai-plans/* and /home/solvina/options/*
- Should cross-reference TRADING_ENGINE.md
- Update to reflect current state
- Keep supporting details (bot interface, setup, etc.)

**Archive**: Historical status docs
- Keep for record but mark DEPRECATED
- Date stamp clearly (e.g., DEPRECATED_2026-06-10)
- Don't reference in active workflows
