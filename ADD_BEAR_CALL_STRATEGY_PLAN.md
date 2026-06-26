# Multi-Strategy Spread Framework — Bear Call as Strategy #2 — 2026-06-26 (rev. 3)

**Status**: Phase 0 (slice 1) shipped; remainder pre-implementation
**Target**: After bull put validation; bear call is the first *new* strategy on a clean framework
**Effort**: ~4–5 weeks engine + data; UI/Greeks dashboard tracked separately

> **What changed in rev. 3 (operator instruction):** The goal is no longer "bolt on bear call."
> It is to **refactor into a clean, explicitly-named, extensible multi-strategy framework** where
> bull put is simply the first concrete strategy and bear call is the second — and adding a third
> (e.g. iron condor, put credit ladder) is "add a module + register it," touching **zero** core code.
> Two hard rules from the operator:
> 1. **Name things for what they are.** Code that is bull-put-specific must *say* `BullPut`
>    (classes, tables, config, endpoints, tests). No more generically-named-but-secretly-bull-put types.
> 2. **Clean and maintainable over expedient.** Prefer a small, well-factored core + thin strategy
>    modules over copy-paste. Shared behaviour lives in one place; divergence lives behind a strategy seam.

---

## Progress so far (rev. 3)

- ✅ **Phase 0, slice 1 — shipped & deployed (`97f14b6`, live on paper since 19:28 CEST 2026-06-26).**
  Fixed the orphan-creating `positionMatchesLeg` put/`"P"` mismatch (now derives the right from the
  leg's own `contract.type.ibkrCode`, correct for puts *and* calls) and **restored the engine test
  suite to compiling/green** (it had not compiled since the Phase 1 monitoring commit `1d542d5`).
  This was the prerequisite that makes the rest of Phase 0 safe to attempt.
- 📌 **Prod runs live PostgreSQL** (`options-db-1`, postgres:16 on :5433, `ddl-auto: validate`,
  Liquibase, 18 changesets applied). CLAUDE.md's "not running in production" note is stale. Every
  schema change below MUST be a Liquibase changeset that exactly matches the JPA entities, or the
  engine fails to boot. See [[prod_postgres_is_live]].

---

## Naming / Rename Mandate (operator rule #1)

Audited current names vs. what they actually are. **Bull-put-specific code gets a `BullPut` name;
genuinely-generic code keeps a neutral name.** Because prod is on `ddl-auto: validate` + Liquibase,
the table/entity renames are real migrations (build phase — clean renames are acceptable).

| Current (misleading) | What it is | Target name |
|----------------------|------------|-------------|
| `scanner/ScanCandidateSelector` | Bull-put selection (filters PUTs, sells higher strike) | `strategy/bullput/BullPutCandidateSelector` (impl of `SpreadCandidateSelector`) |
| `spread/SpreadPort` (typed `BullPutSpread`) | Bull-put persistence port | `strategy/bullput/BullPutSpreadPort` |
| `persistence/.../SpreadPositionEntity` `@Table("spread_positions")` | Bull-put row | `BullPutSpreadEntity` `@Table("bull_put_spreads")` |
| `persistence/.../SpreadPositionRepository` | Bull-put repo | `BullPutSpreadRepository` |
| `persistence/.../SpreadPersistenceAdapter` | Bull-put adapter | `BullPutSpreadPersistenceAdapter` |
| `api/SpreadsApiImpl`, `GET /spreads` | Bull-put API | `BullPutSpreadsApiImpl`, `GET /bull-put-spreads` |
| `scanner/ScannerConfig` (flat; entry/exit params are bull-put) | Mixed | Split: `scanner.bull-put.*` + `scanner.bear-call.*` + shared `scanner.*`/`scanner.portfolio.*` |
| `ScanCandidateSelectorTest` | Bull-put test | `BullPutCandidateSelectorTest` |

**Genuinely generic — keep neutral, do NOT prefix:**
`SpreadLeg`, `SpreadStatus`, `SpreadManagementService` (becomes truly generic, see below),
`ScannerService` / `ScannerScheduler` (orchestration), `TradeExecutionService`,
`PositionReconciliationService` (already option-type agnostic), `QuoteHealthService`,
`OptionContract` / `OptionType`.

---

## Target Architecture — a Strategy framework, not a fork

```
GENERIC CORE  (knows nothing about puts vs calls; never edited to add a strategy)
├── model/
│   ├── Spread            (sealed interface: id, symbol, soldLeg, boughtLeg, credit,
│   │                      maxRisk, qty, status, ivRankAtEntry, openedAt, closeReason, …)
│   ├── SpreadLeg, SpreadStatus
│   └── StrategyId        (enum: BULL_PUT, BEAR_CALL, … — discriminator)
├── SpreadStrategy<T : Spread>   (the seam — one per strategy, see interface below)
├── SpreadStrategyRegistry       (holds all registered strategies; the only injection point
│                                 the scanner/manager iterate over)
├── SpreadManagementService      (GENERIC: runs TP/SL/DTE for any Spread, then delegates to
│                                 strategy.strategyExitSignal(...) for strategy-specific exits)
├── SpreadQueryFacade            (cross-strategy reads: active count, portfolio risk, dashboards —
│                                 the home of the shared portfolio cap)
├── TradeExecutionService        (already order-centric; only the cap becomes facade-backed)
├── PositionReconciliationService, QuoteHealthService   (already generic)
└── ScannerService / Scheduler   (iterate registry × universe; no per-strategy branches)

STRATEGY MODULES  (each self-contained; adding one = new package + register, no core edits)
├── strategy/bullput/
│   ├── BullPutSpread : Spread
│   ├── BullPutCandidateSelector : SpreadCandidateSelector
│   ├── BullPutSpreadPort + persistence (BullPutSpreadEntity → table bull_put_spreads)
│   ├── BullPutStrategy : SpreadStrategy<BullPutSpread>   (strategyExitSignal = null; no extra exits)
│   └── config: scanner.bull-put.*
└── strategy/bearcall/                                    (NEW)
    ├── BearCallSpread : Spread        (+ exDividendDate)
    ├── BearCallCandidateSelector : SpreadCandidateSelector  (filters CALLs; sells LOWER strike,
    │                                  buys strike+width; entry blocked near ex-div, US only)
    ├── BearCallSpreadPort + persistence (BearCallSpreadEntity → table bear_call_spreads)
    ├── BearCallDividendService        (US/American-style assignment protection)
    ├── BearCallStrategy : SpreadStrategy<BearCallSpread>    (strategyExitSignal = dividend exit)
    └── config: scanner.bear-call.*
```

### The strategy seam

```kotlin
sealed interface Spread {
    val id: UUID?
    val symbol: Symbol
    val soldLeg: SpreadLeg
    val boughtLeg: SpreadLeg
    val creditPerShare: BigDecimal
    val maxRiskPerShare: BigDecimal
    val quantity: Int
    val status: SpreadStatus
    val strategyId: StrategyId
    // … shared lifecycle fields (openedAt, closeReason, lastSpreadValue, exit context)
}

/** One per strategy. The ONLY place put-vs-call (or future) divergence is allowed to live. */
interface SpreadStrategy<T : Spread> {
    val id: StrategyId
    /** Strategy-scoped entry/exit params (resolved with per-symbol universe overrides). */
    fun params(symbol: Symbol): StrategyParams
    /** Scan one symbol → an execution request, or null to skip. */
    suspend fun selectCandidate(symbol: Symbol, capital: Money): TradeExecutionRequest?
    /** Hard entry gate beyond credit/IV (e.g. bear-call ex-dividend buffer). Default: allow. */
    suspend fun entryBlocked(symbol: Symbol): Boolean = false
    /** Strategy-specific exit beyond generic TP/SL/DTE (e.g. dividend force-exit). Default: none. */
    suspend fun strategyExitSignal(spread: T): ExitSignal? = null
    /** Open/active rows for cap + dashboards. */
    suspend fun activeCount(): Int
    suspend fun openRisk(): BigDecimal
}
```

`SpreadManagementService.checkExits()` becomes: load open spreads across all strategies (via the
registry/facade), run the **generic** TP/SL/DTE evaluation, then for each spread call
`registry.forSpread(spread).strategyExitSignal(spread)` and act on it. Bull put returns `null`
(no change in behaviour); bear call returns the dividend exit. **No `if (spread is BearCall)`
branches in the core** — the `when` is resolved once, in the registry lookup.

**Adding strategy #3** = create `strategy/ironcondor/`, implement `SpreadStrategy`, add a Liquibase
table + entity, register the bean. Core, scanner, manager, execution, reconciliation: untouched.

---

## Config refactor (operator rule #1, applied to YAML)

`ScannerConfig` today flatly mixes bull-put entry/exit params with shared infra. Split it:

```yaml
scanner:                         # SHARED infra only
  cron: "0 */15 9-22 * * MON-FRI"
  monitor-delay-ms: 60000
  scanner-paused: false
  monitor-paused: false
  trading-enabled: true
  iv-cache-ttl-minutes: 60
  warmup-batch-size: 10
  # execution/order-chase/fees/cooldowns … (all strategy-agnostic) stay here
  portfolio:                     # SHARED across all strategies
    max-open-spreads: 5          # TOTAL, enforced at all gate sites via SpreadQueryFacade
    max-portfolio-risk-usd: 17500

  bull-put:                      # strategy-scoped (was the flat scanner.* entry/exit params)
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

  bear-call:                     # strategy-scoped (NEW; note: NO skew-adjustment, see Phase 3)
    enabled: false               # gated on dividend pipeline + paper validation
    target-delta: 0.30
    delta-min: 0.25
    delta-max: 0.30
    iv-rank-threshold: 45
    min-dte: 30
    max-dte: 50
    preferred-dte: 45
    spread-width-usd: 5.0
    min-credit-per-share: 0.40   # higher bar than bull put if desired — applied to REAL market credit
    take-profit-percent: 0.50
    stop-loss-percent: 2.00
    time-profit-dte: 21
    drift-protection-pct: 0.05
    dividend-check-window-hours: 48     # US/American-style only
    ex-dividend-entry-buffer-hours: 48
```

Preserve the existing **per-symbol universe overrides** (instrument row beats strategy default) —
just resolve them per strategy instead of from one flat config. `StrategyParams` is the resolved view.

---

## Reality Check — what is generic today (verified against `engine/src/main`)

| Component | Today | Action |
|-----------|-------|--------|
| `SpreadLeg`, `SpreadStatus`, `OptionType` (`PUT("P")`/`CALL("C")`) | Generic | Reuse |
| `TradeExecutionRequest` (carries sold/bought `OptionContract` + type) | Generic | Reuse |
| `TradeExecutionService` | Order-centric | Reuse; cap → facade |
| `PositionReconciliationService` (`pos.optionRight == optionType.ibkrCode`) | Option-type agnostic | Reuse |
| `QuoteHealthService` | Contract-based | Reuse |
| `SpreadManagementService.positionMatchesLeg` | **Fixed in slice 1** (was `"Put"` literal) | Done ✅ |
| `SpreadPort` / `SpreadManagementService` typing | Hard-typed `BullPutSpread` | Generalize to `Spread` + registry |
| Cap gates (`TradeExecutionService:98`, `ScannerService:46`, `PreTradeValidator:31`) | Count bull puts only | Route all three through `SpreadQueryFacade` |
| `ScanCandidateSelector` | Bull-put (PUT filter, sell higher) | Rename + extract `SpreadCandidateSelector` |
| `SpreadPositionEntity` `@Table("spread_positions")` | Bull-put row | Rename table + entity |

---

## Implementation Phases

### Phase 0 — Framework extraction & clean rename (gating)  ▸ slice 1 DONE
- ✅ **Slice 1 (shipped):** fix `positionMatchesLeg`; restore green test suite.
- **Slice 2 (next):** introduce `Spread` sealed interface + `StrategyId`; make `BullPutSpread`
  implement it. Introduce `SpreadStrategy<T>` + `SpreadStrategyRegistry` with **only `BullPutStrategy`**
  registered. Generalize `SpreadManagementService` to operate on `Spread` + the registry seam, keeping
  bull-put behaviour byte-for-byte (existing tests green). No bear call yet.
- **Slice 3:** the rename pass (table → `bull_put_spreads`, port/entity/repo/adapter/API/config/tests
  per the mandate table) via Liquibase changeset + matching entities (validate-safe). Pure rename +
  config reshape; no behaviour change. Deploy alone; confirm bull puts unaffected over a session.

### Phase 1 — Bear call model & DB (2 days)
`BearCallSpread : Spread` (sells **lower** strike, buys strike+width; `maxRisk = width − credit`,
same formula; `+ exDividendDate`). Liquibase `bear_call_spreads` table (same shape + `ex_dividend_date`),
`BearCallSpreadEntity`/repo/adapter/port. Register `BearCallStrategy` (selector wired in Phase 2).

### Phase 2 — Bear call scanner (2–3 days)
`BearCallCandidateSelector : SpreadCandidateSelector`: filter `OptionType.CALL`; sell delta-target
(lower strike), buy `strike + width`; credit/maxRisk math unchanged (real market quotes already
reflect call skew). `entryBlocked()` = ex-dividend within `ex-dividend-entry-buffer-hours` (US only).
**No skew-adjustment multiplier** — express any higher bar as `bear-call.min-credit-per-share` on the
real credit.

### Phase 3 — Dividend assignment protection (3–4 days) — US / American-style only
> **Enablement gate:** bear call stays `enabled: false` until the ex-dividend data pipeline is live;
> the smart exit is inert with null data, so it is NOT shipped as dead code behind a deferred ticket.

Pipeline (MVP): `universe.ex_dividend_date` + `next_dividend_amount`; scheduled `reqFundamentalData`
refresh of US instruments. `BearCallStrategy.strategyExitSignal` force-closes (via the existing
`forceCloseSpread` path, new `SpreadStatus.CLOSED_DIVIDEND_RISK`) when **all three**: ex-div within
24–48h, short call ITM (`spot > strike`), short-call extrinsic < dividend. European-style (EUREX)
names early-return — no early assignment. Fallback: if extrinsic can't be evaluated but the first two
hold, exit anyway.

### Phase 4 — Shared portfolio cap (2 days)
Route `TradeExecutionService:98`, `ScannerService:46`, `PreTradeValidator:31` through
`SpreadQueryFacade.activeSpreadCount()` / `activePortfolioRisk()` (sum over registry). Test: open 5
mixed spreads → 6th rejected at every gate.

### Phase 5 — API & UI (sized separately)
`/bull-put-spreads` (renamed), `/bear-call-spreads`, `/bear-call-spreads/dividend-risk`; aggregate
`/spreads` + `/account/all-positions` via the facade. UI tabs `[Bull Puts] [Bear Calls] [Dashboard]`.
**Combined-Greeks dashboard deferred** — no live-Greeks source on the trading path (BS removed).

---

## Risks

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Framework extraction regresses bull puts | Live strategy breaks | Slices 2–3 keep tests green byte-for-byte; deploy each slice alone to paper |
| `ddl-auto: validate` rejects schema | Engine won't boot | Every table/column change is a Liquibase changeset matched to entities; test against :5433 |
| Cap not summed at all 3 sites | Up to 10 open spreads | Facade + 6th-entry rejection test |
| Dividend data missing | Smart exit is dead code | Pipeline is MVP-gating; `enabled:false` until live |
| EU early-assignment logic misapplied | Needless force-closes | Strategy seam early-returns for European-style names |
| Over-abstraction / premature generality | Maintenance drag | Registry seam only; resist generalizing beyond 2 real strategies until #3 lands |

---

## Decision Checkpoints — LOCKED (rev. 3)

- [x] Build a **multi-strategy framework** (registry + `Spread` + `SpreadStrategy<T>` seam); bull put
      is strategy #1, bear call #2; strategy #3 needs no core edits.
- [x] **Name bull-put-specific code `BullPut`** (classes, table `bull_put_spreads`, config, endpoints,
      tests); keep genuinely-generic types neutral.
- [x] **Clean over expedient** — shared behaviour in the core, divergence behind the strategy seam; no
      `if (spread is BearCall)` branches in core.
- [x] Two concrete ports + `SpreadQueryFacade`; cap enforced at all three gate sites.
- [x] **No skew-adjustment**; use `bear-call.min-credit-per-share` on real credit.
- [x] Dividend protection **US/American-style only**; pipeline is MVP-gating, not deferred.
- [x] Liquibase rigor under live Postgres `ddl-auto: validate`.
- [x] Combined-Greeks dashboard deferred.

---

## Validation (deploy-per-delivery, paper `DU7875979`)

Each slice/phase ships alone after a green `./gradlew build`:
- Slice 2 (framework extraction) — bull puts behave identically (no trade-log diff over a session).
- Slice 3 (rename pass) — engine boots clean against renamed `bull_put_spreads`; bull puts unaffected.
- Phases 1–2 — bear-call scanner builds correct legs (sell lower call / buy higher call), BAG fills for US.
3. Dividend refresh populates dates; smart-exit fires on an ITM-near-ex-div fixture.
4. 5 mixed spreads → 6th rejected.
Run until operator is confident before any live-account consideration.

---

## Future Tickets
- [ ] Correct CLAUDE.md's stale "Postgres not running in production" note.
- [ ] Volatility-skew analytics (observe, don't haircut).
- [ ] Combined/blended Greeks (needs a live-Greeks feed first).
- [ ] Strategy #3 (iron condor / put-credit ladder) as the proof that the framework is truly additive.
</content>
