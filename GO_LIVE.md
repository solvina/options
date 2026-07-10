# Go-Live Plan & Daily Operations Checklist

*Written 2026-07-10. Target: live trading ~August 2026 — but the gate is criteria-based, not
calendar-based. Analytics epoch: 2026-07-10 ("since last major fix" — quote snapshots, SL
confirmation, flag exit booking, and P&L refresh were all fixed that day; earlier numbers measure
bugs, not strategies).*

## Position

There has never been an uninterrupted clean paper session — every session so far surfaced an
issue. The precondition for live is therefore a **streak**, not a date:

1. **Stability gate — 10 consecutive clean sessions** (~2 weeks). *Clean* means: no manual
   intervention, no unexplained DB-vs-broker mismatch, no CRITICAL alert left unhandled, every
   close booked from a real fill. Any manual fix resets the counter. Two weeks catches the
   Friday/Monday and expiry-week paths at least twice.
2. **Edge gate — separate from stability.** Stability proves the machine works; it says nothing
   about profitability. On post-epoch data (Reports page, "Since last major fix"), each strategy
   must show positive expectancy before it trades live. Current stance: **go live with spreads
   only**; flags stay on paper until ~100 honestly-booked trades prove an edge (backtests of the
   deployed config sit at PF 1.00 — breakeven).
3. **Go live tiny.** First live week at minimum size (1 contract / smallest sensible
   risk-per-trade) regardless of paper results. Live fills, live margin, and the paid live data
   feed are new variables. Scale only after one clean live week.

Note: late July / early August is peak earnings season — expect the earnings filter to throttle
entries; don't judge the streak by volume.

## Pre-release checklist (once)

- [ ] Stability streak achieved — 10 clean sessions, counted honestly (journal line per day).
- [ ] Edge confirmed per strategy on the Reports page (since 2026-07-10); strategies without an
      edge stay disabled on live.
- [ ] Bear-call PENDING recovery gap closed (StartupRecoveryService covers bear calls).
- [ ] Orphan long puts flattened in TWS *and* reconciliation green afterwards.
- [ ] ~~Missing-leg CRITICAL alert~~ — done 2026-07-10 (PositionReconciliationScheduler +
      OrphanPositionDetector.detectMissing).
- [ ] ~~Automated pre-open / post-close audit~~ — done 2026-07-10 (DailyAuditService → Telegram).
- [ ] New-machine cutover per the existing checklist: live TWS + paid live market data
      subscription, engine on systemd with auto-start, TWS daily auto-restart handled, DB backup
      cron verified restorable.
- [ ] Alert fire-drill: deliberately trigger a CRITICAL, confirm it reaches the phone.
- [ ] Kill switch rehearsed: pause all scanners + manual flatten procedure practiced once on paper.
- [ ] Live config reviewed as a set: risk-per-trade, `flag.max-position-pct-of-capital`, max open
      positions, portfolio cap — recomputed for the real account size, not the $1.09M paper
      balance.
- [ ] Competing-session plan: which machine/user runs TWS when (error 10197 killed market data on
      paper more than once — on live that means a blind stop-loss).

## Daily routine

The `DailyAuditService` posts two Telegram messages on weekdays (alerts topic):

- **Pre-open audit** (default 08:45 Prague, `audit.pre-open-cron`): IBKR connection, open
  position counts, broker-vs-DB reconciliation (orphans + missing legs), zombie PENDING rows,
  flags without an armed fill watcher.
- **Post-close audit** (default 22:15 Prague, `audit.post-close-cron`): today's opens/closes and
  realized P&L per strategy, closes with unknown P&L, closes booked at estimated (non-fill)
  prices, reconciliation, zombies.

`CLEAN` = the day counts toward the streak. Any issue = investigate, and the streak resets if it
required manual intervention.

**Manual items the audit cannot do:**

- Before the open: react to whatever the pre-open audit found; glance at the alerts topic for
  anything that fired overnight.
- During trading: watch the *first entry of the day* end-to-end once (signal → ladder → fill →
  DB row with real credit); treat a BLIND-cycle burst as an incident (SL is blind while it
  lasts); don't open a second TWS session against the same user.
- After the close: compare the day's engine P&L against the broker statement (must agree within
  fees); write the one-line journal entry: clean yes/no, and why not.

## Incident rules learned on paper (do not relearn on live)

- **Missing leg ≠ cosmetic.** A spread showing "1 of 2 legs held" is a naked short with the
  engine mismanaging it. Never use the dashboard Close on a one-legged spread — the combo close
  sells the absent leg and opens a fresh naked position. Fix in TWS (re-buy the leg or flatten).
- **No quote = no action, never zero.** Display paths must show "unavailable", execution paths
  must skip the cycle. Anything that turns a missing quote into a price of 0 (or a theoretical
  price) corrupts either P&L or risk decisions.
- **Manual TWS actions while the engine manages positions are how legs go missing.** If manual
  cleanup is needed, cross-check every contract against the account page's leg classification
  first — surplus lots and managed legs can be the same instrument.

## Status / history

- 2026-07-10: plan written. Missing-leg alert + daily audits implemented the same day. Open
  pre-release items: bear-call recovery gap, orphan cleanup, cutover, fire-drill, kill-switch
  rehearsal, live config review.
