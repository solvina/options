# Bear Call Strategy Implementation Plan — 2026-06-26 (rev. 2)

**Status**: Pre-implementation design, reconciled against engine source
**Target**: Phase after bull put validation (post US session)
**Effort**: ~4 weeks engine + data; UI/Greeks dashboard tracked separately (see note)

> **Rev. 2 note**: The first draft assumed the spread management / persistence / port
> layer was already generic. It is not — it is hard-typed to `BullPutSpread`. This revision
> scopes the generalization work explicitly, removes the (broken) skew-adjustment factor,
> scopes dividend handling to US/American-style options only, and gates bear-call enablement
> on the dividend-data pipeline being live (not deferred). Verified call sites are cited inline.

---

## Executive Summary & Recommendation

### Question: Reuse Bull Put Code or Separate Implementation?

**Recommendation: SEPARATE strategy models + a SHARED `Spread` abstraction.**

This is *not* "reuse the existing services as-is" — most of them are concrete on `BullPutSpread`
today and must be generalized first. See "Reality Check" below before estimating.

#### Why separate strategy models (despite identical entry parameters):

1. **Different risk evolution** — bull put risk is downside (spreads tighten as the stock
   rises); bear call risk is upside (spreads blow out as the stock rises). Early-assignment
   mechanics differ entirely (dividend trap on short calls vs. theta on short puts).
2. **Different assignment surface** — short calls carry ex-dividend early-assignment risk on
   **American-style (US)** names. Short puts do not. This is the core reason the strategies
   diverge operationally.
3. **Future divergence is likely** — dividend calendars, hedged (call+put) portfolio analysis,
   per-strategy risk limits. Keeping models separate is cheaper than un-merging later.

#### Why we can share *most* infrastructure (after generalization):

- ✅ Order execution (`TradeExecutionService`, BAG/leg-by-leg) — already keyed off
  `TradeExecutionRequest`, which carries `soldContract`/`boughtContract` with their own
  `OptionType`. **Genuinely reusable as-is.**
- ✅ Position reconciliation — `PositionReconciliationService:158` matches on
  `pos.optionRight == optionType.ibkrCode`, derived from the contract. **Already option-type
  agnostic.**
- ✅ Quote-health monitoring (LIVE/STALE/BLIND) — operates on contracts, not put-specific.
- ⚠️ Exit framework (TP%/SL%/DTE) — logic is generic in spirit but lives in a class hard-typed
  to `BullPutSpread`. Reusable **after** the model is abstracted.

---

## Reality Check — What Is Actually Generic Today

Audited against `engine/src/main` on 2026-06-26. This supersedes the first draft's
"shared infrastructure (generic)" claims.

| Component | File | Today | Action |
|-----------|------|-------|--------|
| `SpreadLeg`, `SpreadStatus` | `spread/model/` | Generic | Reuse |
| `OptionType` | `models/OptionType.kt` | Has `PUT("P")`, `CALL("C")` | Reuse |
| `TradeExecutionRequest` | `execution/model/` | Carries sold/bought `OptionContract` w/ type | Reuse |
| `TradeExecutionService` | `execution/` | Order-centric, not put-specific | Reuse (see cap fix) |
| `PositionReconciliationService` | `ibkr/order/` | Matches on `optionRight == ibkrCode` | Reuse |
| `QuoteHealthService` | `spread/service/` | Contract-based | Reuse |
| **`SpreadPort`** | `spread/SpreadPort.kt` | **Every method typed `BullPutSpread`** | **Generalize** |
| **`SpreadManagementService`** | `spread/` | **Concrete `BullPutSpread` throughout** | **Generalize** |
| **`PreTradeValidator`** | `execution/` | Counts bull puts only (`:31`) | **Make strategy-aware** |
| **`ScannerService`** | `scanner/` | Counts bull puts only (`:46`) | **Make strategy-aware** |
| **`TradeExecutionService` cap** | `execution/` | Counts bull puts only (`:98`) | **Make strategy-aware** |
| **`SpreadManagementService.positionMatchesLeg`** | `spread/` | **Hard-codes `"Put"` (`:188`)** | **Bug — derive from contract** |
| **`SpreadPositionEntity`** | `persistence/.../entity/` | `@Table("spread_positions")` | **Rename → `bull_put_spreads`** |
| `ScanCandidateSelector` | `scanner/` | Filters `OptionType.PUT` (`:80`), sells higher strike | Fork for calls |

---

## Architecture (Revised)

```
Shared Abstraction (NEW — Phase 0)
└── sealed interface Spread  (id, symbol, soldLeg, boughtLeg, credit, maxRisk,
                              quantity, status, ivRankAtEntry, openedAt, closeReason, …)
    ├── BullPutSpread  (existing data class → implements Spread)
    └── BearCallSpread (new data class → implements Spread, + exDividendDate)

Shared Infrastructure (generalized to operate on Spread)
├── SpreadPort<T : Spread> OR two concrete ports behind a SpreadQueryFacade
├── SpreadManagementService (TP/SL/DTE exits; positionMatchesLeg fixed)
├── TradeExecutionService    (already order-centric; only the cap counts both)
├── PositionReconciliationService (already option-type agnostic)
└── QuoteHealthService

Strategy-Specific (separate)
├── Bull Put: ScanCandidateSelector (existing, PUT path)
└── Bear Call:
    ├── BearCallScanCandidateSelector (CALL path: sell higher strike, buy strike+width)
    ├── BearCallDividendService        (US American-style only; see Phase 3)
    └── bear-call config namespace
```

**Port strategy decision (pick one in Phase 0):**
- **(A) Generic `SpreadPort<T>`** — one interface, two JPA adapters/tables. Cleaner long-term,
  more upfront churn.
- **(B) Two concrete ports** (`BullPutSpreadPort`, `BearCallSpreadPort`) + a thin
  `SpreadQueryFacade` for cross-strategy reads (open counts, combined risk, dashboards).
  Less churn to existing call sites.

Recommend **(B)** — it isolates the new table without rewriting every existing
`spreadPort.findOpen()` caller, and the facade is exactly where the shared-cap logic belongs.

---

## Implementation Phases

### Phase 0: `Spread` Abstraction & Generalization (3-4 days) — NEW, gating

This did not exist in the first draft and gates everything else.

1. Extract `sealed interface Spread` with the common fields; make `BullPutSpread` implement it.
2. Introduce `BearCallSpread` (below) implementing `Spread`.
3. **Fix `positionMatchesLeg`** (`SpreadManagementService:188`) to derive the right from
   `leg.contract.type.ibkrCode` instead of the hard-coded `"Put"`. Add a regression test that
   force-closes a bear-call position and asserts both legs verify closed.
4. Decide port strategy (A vs. B above) and refactor `SpreadManagementService` /
   `SpreadPort` to the chosen shape. Keep bull-put behaviour byte-for-byte identical
   (existing tests stay green).

### Phase 1: Bear Call Model & Database (2 days)

```kotlin
data class BearCallSpread(
    val id: UUID?,
    val symbol: Symbol,
    val soldLeg: SpreadLeg,    // SHORT call (LOWER strike)
    val boughtLeg: SpreadLeg,  // LONG call (HIGHER strike)
    val creditPerShare: BigDecimal,
    val maxRiskPerShare: BigDecimal,   // = width − credit (same formula as bull put)
    val quantity: Int = 1,
    val status: SpreadStatus,
    val ivRankAtEntry: Double?,
    val underlyingPriceAtEntry: BigDecimal?,
    val openedAt: Instant,
    val closedAt: Instant? = null,
    val closeReason: String? = null,
    val closePricePerShare: BigDecimal? = null,
    val lastSpreadValue: BigDecimal? = null,
    val underlyingPriceAtExit: BigDecimal? = null,
    val ivRankAtExit: BigDecimal? = null,
    val exDividendDate: LocalDate? = null,   // NEW: dividend risk tracking (US only)
) : Spread
```

> **Strike direction**: a bear call sells the **lower** strike and buys **strike + width**
> (the higher strike is the long protection). This is the mirror of the bull put, which sells
> the higher strike. Get this right in both the model docs and the scanner.

**Database (build phase — clean rename is fine):**
- Liquibase `vNN__bull_put_rename.yaml`: `RENAME TABLE spread_positions → bull_put_spreads`
  (update `@Table` on the entity, the repository, and the v2 index names accordingly).
- Liquibase `vNN__bear_call_spreads.yaml`: create `bear_call_spreads` (same columns as
  `bull_put_spreads` + `ex_dividend_date`). `close_reason` stays `TEXT` (per project convention).
- Cross-strategy reads go through the `SpreadQueryFacade` (option B), not a DB union view.

### Phase 2: Bear Call Scanner (2-3 days)

`BearCallScanCandidateSelector` — fork of `ScanCandidateSelector` with these differences only:

1. Filter `chain.filter { it.contract.type == OptionType.CALL }` (vs. `PUT` at `:80`).
2. Short = sell at delta target; long = buy strike **+ width** (`targetBoughtStrike =
   soldStrike + spreadWidthUsd`, vs. `− width` for puts at `:98`).
3. Credit/maxRisk math is **unchanged** — `midCredit = soldMid − boughtMid`,
   `maxRisk = width − credit`. The scanner already prices off **real market quotes**, so the
   actual call skew is *already reflected* in those quotes.
4. **Entry filter (US only)**: skip if `universePort.get(symbol).exDividendDate` is within the
   `ex-dividend-entry-buffer-hours` window. No-op when the date is null or the name is EU.

**No skew-adjustment factor.** (Removed from the first draft.) Multiplying an already-real,
achievable market credit by a constant haircut models nothing and discards valid trades. If
bear calls should clear a higher bar, express that as a **separate, higher
`bear-call.min-credit-per-share`**, applied to the real credit — not a multiplier.

### Phase 3: Dividend Assignment Protection (3-4 days) — US / American-style only

> **Gating rule**: bear-call entry stays `enabled: false` until the ex-dividend data pipeline
> below is live and populating `bear_call_spreads.ex_dividend_date` /
> `universe.next_dividend_amount`. The smart-exit logic is inert with null data, so shipping it
> without the data source would mean the strategy's primary incremental risk control is dead
> code. The pipeline is part of this MVP, **not** a deferred future ticket.

**Scope**: American-style options only (US listings). EUREX equity options are typically
European-style — no early assignment — so the dividend service must early-return for EU names
(gate on exchange/style, not just on a null date).

**Dividend data pipeline (MVP, not deferred):**
- Add `universe.ex_dividend_date: LocalDate?` and `universe.next_dividend_amount: BigDecimal?`.
- Scheduled refresh job using `reqFundamentalData` (IB Gateway), updating the N least-recently-
  refreshed US instruments per cycle. (EU names skipped.)

**`BearCallDividendService.checkSmartDividendExit()`** — runs inside the generalized
`SpreadManagementService.checkExits` cycle, for bear calls on US names only. Force-close at
market when **all three** hold:
1. Ex-dividend within `dividend-check-window-hours` (24–48h).
2. Short call is **ITM** (`spot > shortCall.strike`).
3. Short-call extrinsic (live mid − intrinsic) **< estimated dividend amount**.

Close via the same `forceCloseSpread(...)` path the existing exits use (not an invented
`closeSpread(id, price, status)` signature), with a new `SpreadStatus.CLOSED_DIVIDEND_RISK`.
Fallback: if extrinsic can't be evaluated (no live quote) and conditions 1–2 hold, exit anyway
(assignment loss dominates slippage). Caveat to monitor: market-closing illiquid ITM calls near
ex-div can realize meaningful slippage — log realized vs. theoretical for tuning.

### Phase 4: Execution & Shared Portfolio Cap (2 days)

Execution itself is reused (`TradeExecutionService`, BAG for US, leg-by-leg for EU). The real
work is the **shared cap**, which today is enforced in **three** independent sites that each
count bull puts only:

- `TradeExecutionService:98` (`countByStatus(OPEN)+CLOSING+in-flight`)
- `ScannerService:46` (`PENDING+OPEN+CLOSING` early-out)
- `PreTradeValidator:31` (`findOpen()+findByStatus(CLOSING)`)

All three must sum **both** strategies via the `SpreadQueryFacade`, or the cap silently becomes
per-strategy (up to 10 open). Add a test: open 5 mixed bull-put/bear-call spreads, assert the
6th entry is rejected at every gate.

```kotlin
// SpreadQueryFacade
suspend fun activeSpreadCount(): Int =
    bullPutPort.countActive() + bearCallPort.countActive()   // OPEN + CLOSING + PENDING
suspend fun activePortfolioRisk(): BigDecimal =
    bullPutPort.openRisk() + bearCallPort.openRisk()
```

### Phase 5: API & UI (sized separately — see note)

- New endpoints: `GET /bear-call-spreads`, `/bear-call-spreads/{id}`,
  `PUT /bear-call-spreads/{id}/close`, `GET /bear-call-spreads/dividend-risk`.
- Rename `GET /spreads` → `/bull-put-spreads`; `GET /account/all-positions` and `/health`
  report both via the facade.
- Frontend: separate `[Bull Puts] [Bear Calls] [Dashboard]` tabs; bear-call tab adds a
  dividend-watch panel.

> **Combined-Greeks dashboard is NOT in this MVP.** The engine deliberately removed
> Black-Scholes/Greeks from the trading path (see CLAUDE.md), so there is no per-position live
> Greeks source to aggregate. Treat "blended Greeks / combined risk heatmap" as a follow-up that
> first needs a live-Greeks feed. The MVP dashboard shows P&L, open counts, and defined max-loss
> (which we already have), not Greeks.

---

## Configuration

**Bull Put** (existing, unchanged):
```yaml
scanner:
  bull-put:
    enabled: true
    target-delta: 0.30
    delta-min: 0.25
    delta-max: 0.30
    iv-rank-threshold: 45
    min-dte: 30
    max-dte: 50
    preferred-dte: 45
    spread-width-usd: 5.0
    min-credit-per-share: 0.35
    take-profit-percent: 0.50
    stop-loss-percent: 2.00
    time-profit-dte: 21
    drift-protection-pct: 0.05
```

**Bear Call** (new namespace; note: NO skew-adjustment):
```yaml
scanner:
  bear-call:
    enabled: false   # gated on dividend pipeline + paper validation
    target-delta: 0.30
    delta-min: 0.25
    delta-max: 0.30
    iv-rank-threshold: 45
    min-dte: 30
    max-dte: 50
    preferred-dte: 45
    spread-width-usd: 5.0
    min-credit-per-share: 0.40        # higher bar than bull put if desired — applied to REAL credit
    take-profit-percent: 0.50
    stop-loss-percent: 2.00
    time-profit-dte: 21
    drift-protection-pct: 0.05
    # Bear-call specific (US / American-style only)
    dividend-check-window-hours: 48   # smart exit if ex-div within this window
    ex-dividend-entry-buffer-hours: 48 # skip entry if ex-div within this window
```

**Portfolio limits** (SHARED across both strategies):
```yaml
scanner:
  portfolio:
    max-open-spreads: 5          # TOTAL across both strategies — enforced at all 3 gate sites
    max-portfolio-risk-usd: 17500
```

---

## Risks (Revised)

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **Generalization regresses bull puts** | Live strategy breaks | Phase 0 keeps bull-put tests green byte-for-byte; deploy Phase 0 alone to paper first |
| **`positionMatchesLeg` put hard-code** | Bear-call close/verify silently fails | Fixed in Phase 0 + regression test |
| **Cap not summed at all 3 sites** | Up to 10 open spreads | Facade + 6th-entry rejection test |
| **Dividend data missing** | Smart exit is dead code | Pipeline is MVP-gating, not deferred; `enabled:false` until live |
| **EU early-assignment logic misapplied** | Needless force-closes | Dividend service early-returns for EU/European-style names |
| **ITM call slippage near ex-div** | Larger realized loss on exit | Log realized vs. theoretical; tune window |

---

## Decision Checkpoints — LOCKED (rev. 2, 2026-06-26)

- [x] Separate strategy models behind a shared `Spread` abstraction (Phase 0 gating).
- [x] Port strategy: two concrete ports + `SpreadQueryFacade` (option B).
- [x] **Skew-adjustment removed**; use a separate `bear-call.min-credit-per-share` instead.
- [x] Dividend protection is **US / American-style only**; EU names exempt.
- [x] Dividend-data pipeline is **MVP-gating**, not a future ticket; bear calls stay
      `enabled:false` until it populates.
- [x] Shared cap enforced at all **three** existing gate sites via the facade.
- [x] **Clean rename** `spread_positions → bull_put_spreads` (acceptable in build phase).
- [x] Combined-Greeks dashboard deferred (no live-Greeks source on trading path).
- [x] UI: separate `[Bull Puts] [Bear Calls] [Dashboard]` tabs.

---

## Validation

Per the deploy-to-RPi policy, each coherent delivery ships on its own to the paper account
(`DU7875979`) after a green `./gradlew build`:

1. **Phase 0** — deploy generalization alone; confirm bull puts behave identically (no
   behavioural diff in trade logs over one session).
2. **Phases 1-2** — bear-call scanner on paper with `enabled:true` *temporarily* on a US name
   with no near-term ex-div; confirm entries build the correct legs (sell lower call / buy
   higher call), BAG fills for US.
3. **Phase 3** — verify the dividend refresh job populates dates, and that smart-exit fires
   on a real ITM-near-ex-div case (or a forced fixture).
4. **Phase 4** — open 5 mixed spreads on paper, confirm the 6th is rejected.
5. Run until operator is confident before any consideration of a live account.

---

## Future Tickets

- [ ] Volatility-skew analytics dashboard (observe, don't haircut).
- [ ] Combined/blended Greeks (requires a live-Greeks feed first).
- [ ] Hedged portfolio analysis (bull + bear simultaneously).
- [ ] BAG combo support for EU bear calls (shares the bull-put EUREX work).
</content>
</invoke>
