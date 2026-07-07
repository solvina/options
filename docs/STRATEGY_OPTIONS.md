# Options Strategies: Bull Put & Bear Call Spreads

Two credit-spread strategies share one scanner, one execution pipeline, one exit monitor, and one
portfolio cap (`scanner.max-open-spreads`). Values below are **as configured 2026-07-07** (the
post-P&L-review overhaul — see `POSTMORTEM_2026-07-06_SPREADS.md`) — the source of truth is
`application.yml` (`scanner.*`) and per-symbol overrides in the `instrument_universe` table
(which win over config where set).

## Shared pipeline

1. **Scan** (cron `scanner.cron`, every 15 min during EU+US hours): for each enabled universe
   symbol compute IV rank (365-day history, cached); symbols above the strategy's IV-rank
   threshold become candidates.
2. **Regime gate** (`regime.*`, gating-enabled): SMA-50/SMA-200 trend + RSI-14 → directional
   bias per symbol. Bullish bias → bull put allowed; bearish → bear call allowed.
3. **Strike selection**: option chain via conId-resolved contracts (per-strike venue routing for
   EU names); pick the short strike inside the delta band, long strike at fixed width.
   **Crash-pricing guard**: candidates whose mid credit exceeds `max-credit-pct-of-width` (40%)
   of the actual width are rejected regardless of reported delta — a spread priced at half its
   width implies near-even ITM odds and vol-spike-distorted greeks.
4. **Entry**: fresh-credit validation (first live tick within 3s, drift check, and a per-leg
   bid-ask width re-check on that fresh tick — `max-leg-bid-ask-spread-pct` — so a book that
   blew out since the scan aborts instead of filling). The limit order **starts at the fresh
   mid** (fair value) and ladders down; the floor is
   `max(scanner floor, entry-min-fill-pct-of-mid × mid)` so the worst acceptable fill is 85% of
   fair value. Atomic BAG combo on US venues, long-leg-first leg-by-leg on EUREX. The fill's
   entry mid is persisted (`entry_mid_per_share`) for exit-threshold math.
   **Daily throttle**: at most `max-new-entries-per-day` (4) fills per calendar day across
   strategies, counted from the DB — one scan day cannot fill the whole book with a single
   correlated bet.
5. **Exit monitor** (every `scanner.monitor-delay-ms`, 60s): per open spread fetch live leg mids
   (live quotes only — synthetic prices never drive price exits) and evaluate:
   - **Take-profit**: spread value ≤ (1 − take-profit-percent) × **fill credit** → limit-chase
     close at the mid.
   - **Stop-loss**: spread value ≥ (1 + stop-loss-percent) × **entry mid** (fair value at fill —
     a below-mid fill can no longer tighten the stop; pre-v24 rows fall back to the credit).
     Gated: needs `stop-loss-confirm-cycles` (2) consecutive breaching cycles, never fires in the
     first `stop-loss-grace-minutes-after-entry` (15) after the fill nor the first
     `stop-loss-skip-first-rth-minutes` (30) of the session. Closes at **marketable limits**
     (buy short back at ask, sell long at bid); an unfilled chase escalates to market via
     retryClose next cycle. Triggers the per-symbol entry cooldown.
   - **Time exit**: DTE ≤ time-profit-dte → close.
   - Strategy-specific exits (bear call: dividend assignment protection, below).
6. **P&L**: `lastSpreadValue` is persisted every monitored cycle; UI shows credit − value.

## Bull put spread (`scanner.bull-put.*`)

Sell an OTM put, buy a further-OTM put, same expiry. Profits when the underlying stays above the
short strike; max risk = width − credit.

| Parameter | Value (2026-07-07) | Key |
|---|---|---|
| IV-rank threshold | 45 | `iv-rank-threshold` |
| DTE window / preferred | 30–50 / 45 | `min-dte` / `max-dte` / `preferred-dte` |
| Short-put delta band | 0.20–0.30 (target 0.25) | `delta-min/max`, `target-delta` |
| Spread width | $5 | `spread-width-usd` |
| Min credit / share | $0.35 | `min-credit-per-share` |
| Max credit vs width | 40% of width | `max-credit-pct-of-width` |
| Max risk per trade | 2.5% of account | `max-risk-percent` |
| Take-profit | 50% of fill credit | `take-profit-percent` |
| Stop-loss | 100% over entry mid (value ≥ 2× entry mid) | `stop-loss-percent` |
| Time exit | 21 DTE | `time-profit-dte` |
| Entry drift abort | 5% | `drift-protection-pct` |

Portfolio-level (shared, `scanner.*`): `max-open-spreads: 5`, `max-new-entries-per-day: 4`,
`entry-min-fill-pct-of-mid: 0.85`, SL gates `stop-loss-confirm-cycles: 2` /
`stop-loss-grace-minutes-after-entry: 15` / `stop-loss-skip-first-rth-minutes: 30`.

Break-even geometry: TP keeps 0.5× credit, SL loses ≈ 1× entry mid → break-even win rate ≈ 67%;
a ~25-delta short (~75% POP) leaves a cushion. The pre-2026-07-07 config (30-delta, SL at 3× a
natural-cross fill credit, market-order stops) needed ~80% and realized 35% — see the postmortem.

## Bear call spread (`scanner.bear-call.*`, defaults in `BearCallScannerConfig`)

Sell an OTM call, buy a further-OTM call. Mirror-image mechanics of the bull put (target delta
0.30, IV rank 45, min credit $0.40, $5 width, same 40% credit/width guard and mid-based
stop-loss), gated to bearish-regime symbols.

**Dividend assignment protection** (bear-call-specific exit): a short ITM call is at assignment
risk just before the underlying's ex-dividend date. The engine stores per-symbol
`ex_dividend_date` / `next_dividend_amount` on the universe (refreshed daily from the IBKR
IB_DIVIDENDS tick, or relayed from prod when on delayed data) and force-closes threatened spreads
before ex-div. Currently effective for US symbols only (the refresh filters to US session — see
CURRENT.md).

## Execution outcomes worth knowing

- `CLOSED_REJECTED` — entry order rejected; spread never lived.
- `LIQUIDITY_REJECTED` — leg book too wide at scan time or on the fresh execution tick.
- `FLOOR_REACHED` — the ladder hit the fill-quality floor (85% of mid) without a fill; the
  candidate is retried on a later scan. Expected and healthy: it means we refused a bad fill.
- `DAILY_LIMIT_REACHED` / `CAP_REACHED` — daily throttle (4 fills/day) or portfolio cap (5).
- `CLOSING` → `retryClose` — a close that didn't fill escalates to market orders at the next
  monitor cycle / session open; `verifyPositionClosed` confirms broker-side flatness. Between
  TP/SL thresholds, retryClose relabels a preserved PROFIT/STOP intent by realized sign so
  analytics can trust statuses.
- `BROKEN_LONG_ONLY` — EUREX leg-by-leg: long filled, short didn't; bounded-debit position left
  for manual handling (`ibkr.connection.unwind-stranded-long-leg` to auto-unwind).
- Stop-loss close triggers a per-symbol entry cooldown (`stop-loss-cooldown-hours`).
