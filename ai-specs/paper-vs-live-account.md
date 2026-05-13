# Paper vs Live Account — Observed Differences and Expected Live Behavior

> Written: 2026-05-13. Based on observations running against IBKR paper account DU7875979 via IB Gateway (Docker, stable image).

---

## 1. Market Data — Greeks and Pricing

### Paper observation
IBKR returns **error 354** ("no live subscription") for virtually all option market data requests on a paper account. `tickSnapshotEnd` is never sent in this case, so the deferred would hang forever without the workaround. Error **10197** ("competing live session") produces the same behaviour when TWS is open elsewhere.

Result: `snapshot.delta` is always `NaN`. The app always falls back to **Black-Scholes analytical pricing** using historical IV from `IvRankService`.

### What the code does now
- Completes the snapshot deferred immediately on errors 354/10197.
- Falls back to `BlackScholes.putDelta / putPrice` using `IvRankService.currentIv` (52-week IV rank period, cached 60 min).
- Constructs synthetic bid/ask as `mid ± max(mid×5%, 0.05)`.

### Live account expectation
- IBKR sends real-time Greeks (`tickOptionComputation`, field 10–13) for all subscribed instruments.
- `snapshot.delta` will be non-`NaN`; the BS branch will **not** be taken.
- Bid/ask come from real market makers — spreads can be much wider (illiquid strikes) or tighter (liquid ETF options) than the synthetic 5%.

### Risk on paper
- BS delta uses a flat volatility surface (single `currentIv` for all strikes). No vol smile, no skew. OTM puts are typically priced at higher implied vol than ATM — BS **underestimates delta** for deep OTM puts and **overestimates** for near-ATM.
- Synthetic spreads cause the **liquidity check** (`maxLegBidAskSpreadPct`) to always pass — it never rejects a trade on paper. On live, wide-spread strikes (illiquid names, far OTM) will correctly be rejected.
- Mid price used for credit is synthetic, so the credit/risk check is against an estimated value, not a real fill.

---

## 2. Order Fills — Simulated vs Real

### Paper observation
IBKR paper fills BAG (combo) limit orders at the limit price unconditionally, as long as the order stays open. There is no market impact, no partial fills, and no queue position. A credit of $0.45 will always fill at exactly $0.45 if the order stays alive.

### Live account expectation
- Combo fills depend on the real NBBO of both legs simultaneously.
- Natural fills at the combo level are rare for illiquid names — the engine's **price ladder** (lowering credit by one tick every N credit ticks) is essential to actually get filled.
- Partial fills are possible; the current code does not handle them (awaits FILLED status only).
- At market close, unfilled orders may be cancelled by the exchange.

### Risk on paper
- Fill rate on paper is artificially 100% once the order price is accepted. The ladder logic is exercised, but it always resolves quickly. On live, many trades may **never fill** even after laddering to floor credit.
- The 15-minute execution timeout and floor-credit abort will be much more important on live.

---

## 3. Underlying and Historical Data

### Paper observation
- Underlying price (`reqMktData` for stocks/ETFs) **works** on paper — equity market data subscriptions are included.
- Historical IV bars (`reqHistoricalData`, `OPTION_IMPLIED_VOLATILITY`) work on paper.
- IV rank calculation is therefore accurate.

### Live account expectation
- Same behaviour; no difference expected.
- Optionally, a Level 2 / Options Analytics subscription would give per-strike IV surface data, enabling replacement of the flat-vol BS fallback.

---

## 4. Account and Capital

### Paper observation
- Paper account starts with $1,000,000 simulated capital.
- `totalCapital` and `availableFunds` are realistic but not tied to real buying power constraints.
- Margin requirements on paper may differ from live (IBKR sometimes applies simplified margin on paper).

### Live account expectation
- Real capital; `maxRiskPercent = 2.5%` of actual net liquidation.
- With a small live account (e.g. $25k–$50k), `maxRiskPerContract` check will reject more trades (a $5-wide spread at $4.70 risk = $470/contract; 2.5% of $25k = $625 — still passes, but barely).
- Pattern Day Trader (PDT) rule does not apply to options spreads held overnight, but worth checking.

---

## 5. IV Rank and the BS Fallback Coupling

### Paper observation
`IvRankService.currentIv` (the last bar's IV from 365-day history) is used as the single volatility input to BS. This is a reasonable proxy but conflates **index-level IV** (what historical data returns) with **per-strike implied vol**.

### Live account expectation
- With live options data, IBKR provides `impliedVol` per strike in `tickOptionComputation`.
- `snapshot.impliedVol` will be non-`NaN`; the code already captures it in `OptionGreeks.iv`.
- The BS fallback code path becomes dormant; per-strike market IV is used directly.
- `IvRankService` continues to be used for the **IV rank filter** (entry condition), but its `currentIv` is no longer the pricing input.

---

## 6. Error Handling Changes Needed for Live

| Situation | Paper behaviour | Live behaviour needed |
|---|---|---|
| Error 354 / no subscription | Complete deferred → BS fallback | Should not occur with live data subscription |
| Error 10197 / competing session | Complete deferred → BS fallback | Prevent by setting `EXISTING_SESSION_DETECTED_ACTION=primary` in docker-compose; may still occur briefly on reconnect |
| Error 200 / no security definition | Strike skipped (logged as WARN) | Same — some far OTM strikes genuinely don't have listed contracts |
| Partial fills | Not observed on paper | Handle: re-submit remainder, or cancel and retry at new price |
| Assignment risk | Not simulated | Monitor short puts approaching ITM; early assignment possible (American-style) |

---

## 7. What to Verify Before Going Live

- [ ] Confirm live options market data subscription active in IBKR account management (Options → US Options).
- [ ] Change `TRADING_MODE=live` in `.env.rpi` and verify the gateway connects to the live session.
- [ ] Verify `ibkr.connection.account` matches the live account ID (not the paper `DU*` code).
- [ ] Run one scan manually (POST /scanner/run) and confirm `snapshot.delta` is non-`NaN` in logs — meaning BS fallback is **not** triggered.
- [ ] Confirm bid/ask spreads are real: log `bid` and `ask` from `OptionQuote` for a liquid name (SPY) and compare to real market.
- [ ] Consider lowering `maxRiskPercent` or `maxOpenSpreads` for the first few live trades.
- [ ] Consider adding partial-fill handling before going live on illiquid names.
