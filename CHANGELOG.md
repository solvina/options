# Changelog

Notable strategy, engine and operations changes. Newest first. Rendered in the frontend under
**Maintenance › Changelog**.

## 2026-07-18 — Spread edge overhaul (post-week-review)

Follow-up to the 2026-07-13..17 week review: −$753 realized, entire open book underwater after a
broad tech selloff. The win/loss record itself was statistically inconclusive (4W/4L post-epoch),
but three structural leaks were confirmed and fixed:

### Value stop-loss disabled — manage by TP + 21 DTE (strategy)
- `stop-loss-percent: 0` for bull put and bear call; `stopLossPercent <= 0` now means **disabled**
  in `SpreadManagementService` (also honoured per-symbol via the universe row).
- Rationale: spreads are defined-risk — max loss is capped by the width and already sized by
  `max-risk-percent` (2.5% of capital). The 2×-entry-mid value stop fired on vol expansion and
  converted recoverable drawdowns into realized losses (TXN was stopped 2026-07-17 with the
  underlying still **above** the short strike). Managed exits are now take-profit at 50% of credit
  and the 21-DTE time exit; an occasional full-width loss is an accepted, bounded outcome.
  Expected effect: realized win rate moves toward the ~75% theoretical POP of a 25-delta entry
  instead of sitting at the ~67% breakeven.
- **Applies to the existing open book immediately** (thresholds are read from config on every
  monitor cycle): the ten open Jul/Aug bull puts will no longer stop out on spread value; they run
  to TP or their 21-DTE date (Jul 31 for the Aug 21 cohort).

### Earnings-date entry filter (strategy)
- New daily `EarningsRefreshService` stores `next_earnings_date` per US instrument
  (public Nasdaq earnings-date API, Zacks data; DB migration v31).
- Both selectors reject any entry whose chosen expiry spans the earnings date
  (`EARNINGS_BEFORE_EXPIRY` in the scanner funnel). Holding a short spread through a scheduled
  binary event is uncompensated risk at 20–30 delta pricing — MRVL −22% and IONQ −25% during the
  review week were earnings-sized gaps.
- Note: late July / early August is peak earnings season — expect this filter to visibly throttle
  entries. That is the filter working, not a scanner problem.

### Credit floor raised to ≈18% of width (strategy)
- `min-credit-per-share: 0.35 → 0.90` (bull put and bear call, $5 width).
- A $0.35-credit spread's best case is a ~$17 win before commissions (PG closed +$13 for 4 days of
  risk). A fair 25-delta $5-wide spread prices ~$0.90–1.50, so genuine candidates still pass.

### Patient entry execution (execution)
- `entry-min-fill-pct-of-mid: 0.85 → 0.95` — rest near mid; if it doesn't fill, skip and let the
  15-minute scan retry. No more walking the order down toward the natural cross.
- `max-leg-bid-ask-spread-pct: 0.15 → 0.10` — only tight books are worth crossing; the worst
  slippage came from wide-legged quotes (COHR −$36, MRVL −$35 vs entry mid).
- Context: all 10 post-epoch fills printed below entry mid (avg −$17/spread ≈ 15–30% of the
  expected TP win) — a systematic drag on top of the breakeven math. Expect fewer but better fills.

### Frontend
- This changelog is now rendered at **Maintenance › Changelog** (`/changelog`).
