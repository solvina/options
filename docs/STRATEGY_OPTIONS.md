# Options Strategies: Bull Put & Bear Call Spreads

Two credit-spread strategies share one scanner, one execution pipeline, one exit monitor, and one
portfolio cap (`scanner.max-open-spreads`). Values below are **as configured 2026-07-06** — the
source of truth is `application.yml` (`scanner.*`) and per-symbol overrides in the
`instrument_universe` table (which win over config where set).

## Shared pipeline

1. **Scan** (cron `scanner.cron`, every 15 min during EU+US hours): for each enabled universe
   symbol compute IV rank (365-day history, cached); symbols above the strategy's IV-rank
   threshold become candidates.
2. **Regime gate** (`regime.*`, gating-enabled): SMA-50/SMA-200 trend + RSI-14 → directional
   bias per symbol. Bullish bias → bull put allowed; bearish → bear call allowed.
3. **Strike selection**: option chain via conId-resolved contracts (per-strike venue routing for
   EU names); pick the short strike inside the delta band, long strike at fixed width.
4. **Entry**: fresh-credit validation (first live tick within 3s, drift check), then a limit
   order chased toward the market (`order-chase-*` keys) — atomic BAG combo on US venues,
   long-leg-first leg-by-leg on EUREX. Floor = 50% of target credit.
5. **Exit monitor** (every `scanner.monitor-delay-ms`, 60s): per open spread fetch live leg mids
   (live quotes only — synthetic prices never drive price exits) and evaluate:
   - **Take-profit**: spread value ≤ (1 − take-profit-percent) × credit → limit-chase close
   - **Stop-loss**: spread value ≥ (1 + stop-loss-percent) × credit → market close + entry
     cooldown for the symbol
   - **Time exit**: DTE ≤ time-profit-dte → close
   - Strategy-specific exits (bear call: dividend assignment protection, below)
6. **P&L**: `lastSpreadValue` is persisted every monitored cycle; UI shows credit − value.

## Bull put spread (`scanner.bull-put.*`)

Sell an OTM put, buy a further-OTM put, same expiry. Profits when the underlying stays above the
short strike; max risk = width − credit.

| Parameter | Value (2026-07-06) | Key |
|---|---|---|
| IV-rank threshold | 45 | `iv-rank-threshold` |
| DTE window / preferred | 30–50 / 45 | `min-dte` / `max-dte` / `preferred-dte` |
| Short-put delta band | 0.25–0.30 (target 0.30) | `delta-min/max`, `target-delta` |
| Spread width | $5 | `spread-width-usd` |
| Min credit / share | $0.35 | `min-credit-per-share` |
| Max risk per trade | 2.5% of account | `max-risk-percent` |
| Take-profit | 50% of credit | `take-profit-percent` |
| Stop-loss | 200% of credit (buyback ≥ 3× credit) | `stop-loss-percent` |
| Time exit | 21 DTE | `time-profit-dte` |
| Entry drift abort | 5% | `drift-protection-pct` |

## Bear call spread (`scanner.bear-call.*`, defaults in `BearCallScannerConfig`)

Sell an OTM call, buy a further-OTM call. Mirror-image mechanics of the bull put (target delta
0.30, IV rank 45, min credit $0.40, $5 width), gated to bearish-regime symbols.

**Dividend assignment protection** (bear-call-specific exit): a short ITM call is at assignment
risk just before the underlying's ex-dividend date. The engine stores per-symbol
`ex_dividend_date` / `next_dividend_amount` on the universe (refreshed daily from the IBKR
IB_DIVIDENDS tick, or relayed from prod when on delayed data) and force-closes threatened spreads
before ex-div. Currently effective for US symbols only (the refresh filters to US session — see
CURRENT.md).

## Execution outcomes worth knowing

- `CLOSED_REJECTED` — entry order rejected; spread never lived.
- `CLOSING` → `retryClose` — a close that didn't fill escalates to market orders at the next
  session open; `verifyPositionClosed` confirms broker-side flatness.
- `BROKEN_LONG_ONLY` — EUREX leg-by-leg: long filled, short didn't; bounded-debit position left
  for manual handling (`ibkr.connection.unwind-stranded-long-leg` to auto-unwind).
- Stop-loss close triggers a per-symbol entry cooldown (`stop-loss-cooldown-hours`).
