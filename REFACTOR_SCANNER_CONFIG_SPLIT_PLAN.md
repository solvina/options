# Refactor Plan — Clean Split of Scanner Config (Per-Strategy Params Seam)

**Status:** proposed (2026-06-30)
**Goal:** make `ScannerConfig` (`scanner.*`) a genuinely *shared* engine config and give each
strategy its own params namespace (`scanner.bull-put.*`, `scanner.bear-call.*`), routed through a
single per-strategy seam — symmetric with the existing `SpreadStrategy` / `SpreadEntryWriter` /
`SpreadCloser` registries.

## Why this is not a cosmetic rename

Investigation found `ScannerConfig` is load-bearing shared infrastructure that several
strategy-agnostic services read as if it were bull-put-only. Three latent correctness bugs fall
out of that, all of which this refactor fixes:

| # | Bug | Evidence | Effect |
|---|-----|----------|--------|
| **B1** | `OptionChainPort.getOptionChain()` is hardcoded to `OptionType.PUT` (verified strikes, contracts, snapshots). | `IbkrOptionChainAdapter:40-135` | `BearCallCandidateSelector` does `chain.filter { type == CALL }` → **always empty** → bear-call can never select a trade. |
| **B2** | `SpreadManagementService` resolves TP/SL/DTE from `config.takeProfitPercent` (ScannerConfig = bull-put) for **all** spreads; `inst` is `universePort.get(symbol)` (a per-*symbol* override), not per-strategy. | `SpreadManagementService:293-296, 350-352` | Bear-call's `stopLossPercent=2.00`, `timeProfitDte=21` are dead — it exits on bull-put's 0.50 / 14. |
| **B3** | `TradeExecutionService` reads `config.driftProtectionPct` / `executionTimeoutMinutes` (ScannerConfig) for every order regardless of `request.strategyId`. | `TradeExecutionService:304, 259` | Bear-call's `driftProtectionPct=0.05` is dead — it executes on bull-put's 0.01. |

The strike-band math is also direction-specific: bull-put bands **below** the underlying (OTM puts),
bear-call bands **above** (OTM calls). The current adapter only does the put side.

> Note: bear-call also can't trade until the competing-IBKR-session market-data problem is resolved
> (separate, environmental). B1–B3 are necessary but not sufficient for live bear-call fills.

## Target design

One seam, mirroring the established per-strategy registries:

```
StrategyParams                      // entry + exit + execution params for one strategy
  ├─ optionType: OptionType         // PUT (bull put) | CALL (bear call)   ← fixes B1
  ├─ entry:  ivRankThreshold, min/max/preferredDte, targetDelta, deltaMin/Max,
  │          strikeBandPercent, candidateStrikeCount, spreadWidthUsd,
  │          minCreditPerShare, maxRiskPercent
  ├─ exit:   takeProfitPercent, stopLossPercent, timeProfitDte             ← fixes B2
  └─ exec:   driftProtectionPct                                            ← fixes B3

StrategyParamsRegistry              // Map<StrategyId, StrategyParams>, like SpreadCloserRegistry
  └─ forStrategy(id): StrategyParams
```

`BullPutScannerConfig` (`scanner.bull-put.*`) and `BearCallScannerConfig` (`scanner.bear-call.*`)
each expose a `StrategyParams`. Strategy-agnostic services stop injecting `ScannerConfig` for
strategy params and instead resolve `registry.forStrategy(strategyId)`.

**Stays in the shared config** (`ScannerConfig`, `scanner.*` — rename optional, e.g.
`EngineScannerConfig`): `maxOpenSpreads`, `feePerContract`, `contractMultiplier`,
`maxLegBidAskSpreadPct` (shared for now), `executionTimeoutMinutes`*, `ticksBeforePriceAdjust`,
`priceAdjustIntervalSeconds`, `orderChase*`, `entryCooldownMinutes`, `stopLossCooldownHours`,
`cron`, `monitorDelayMs`, `scannerPaused`, `monitorPaused`, `tradingEnabled`, IV cache
(`ivHistoryDays`, `ivCacheTtlMinutes`, `ivServeStaleHours`, `warmupBatchSize`,
`optionParamsCacheTtlHours`). `watchlist` is legacy (universe is DB-driven) — deprecate.

\* `executionTimeoutMinutes` could move per-strategy later; keep shared in v1.

## Phased delivery (each phase ships green + paper-validated independently)

### Phase 0 — Introduce the seam (additive, zero behavior change)
- Add `StrategyParams` + `StrategyParamsRegistry` (bean, built from the strategy config beans).
- `BullPutScannerConfig` and `BearCallScannerConfig` each produce a `StrategyParams`.
  - *No yml move yet* — `BullPutScannerConfig` can be a thin view over the existing `ScannerConfig`
    bull-put fields so nothing rebinds. Keep `@ConfigurationProperties("scanner")` for now.
- Nothing consumes the registry yet. **Verify:** build green, boot green, no behavior delta.

### Phase 1 — Option-type-aware option chain (fixes B1; unblocks bear-call entry)
- `OptionChainPort.getOptionChain(symbol, expiry, underlyingPrice, strategyId)` (or pass a small
  `ChainQuery` carrying `optionType` + band params).
- `IbkrOptionChainAdapter`: resolve `StrategyParams` by strategyId; parameterize `OptionType`;
  flip the strike-band direction for calls (band above underlying, bought leg one width *above*).
- `BullPutCandidateSelector` passes `BULL_PUT`; `BearCallCandidateSelector` passes `BEAR_CALL`.
- **Tests:** adapter returns CALL quotes for bear-call; band sits above underlying for calls, below
  for puts. **Paper-verify:** with healthy market data, bear-call logs `Selected sold call …`
  (not `No call … found`).

### Phase 2 — Per-strategy exit + execution params (fixes B2, B3)
- `SpreadManagementService`: TP/SL/DTE resolution becomes
  `universeOverride ?: registry.forStrategy(spread.strategyId).<param>` (keep the per-symbol
  universe override as highest priority; the strategy registry replaces the `config.*` fallback).
- `TradeExecutionService`: `driftProtectionPct` (and any param that legitimately differs per
  strategy) from `registry.forStrategy(request.strategyId)`. Shared mechanics
  (`executionTimeoutMinutes`, ticks, fees, cap, kill switches) stay on the shared config.
- **Tests:** bear-call spread resolves SL 2.00 / DTE 21 / drift 0.05; bull-put unchanged.
  **Paper-verify:** a bear-call position shows the bear-call exit thresholds.

### Phase 3 — Physical namespace split + yml move (the visible "clean split")
- Now every strategy-param consumer goes through the registry, so the move is mechanical.
- Promote `BullPutScannerConfig` to `@ConfigurationProperties("scanner.bull-put")`; remove the
  migrated fields from `ScannerConfig`; (optional) rename it `EngineScannerConfig`.
- Move yml keys: `scanner.<bull-put fields>` → `scanner.bull-put.*` in `application.yml`,
  `application-rpi.yml`, and every test yml. Preserve current default values exactly.
- **Verify (critical):** `ddl-auto=validate` is irrelevant here, but a missed binding = boot
  failure — boot the JAR and confirm `/health` UP and a clean scan cycle before considering done.
  Keep the JAR backup for instant rollback.

## Risk & sequencing notes
- Phases 1–2 are **correctness fixes** valuable on their own (bear-call is non-functional without
  them) — worth doing even if the namespace rename (Phase 3) were dropped.
- Phase 3 carries the boot-binding risk; it goes last, after all consumers are registry-routed, so
  it's a pure key move with no logic change.
- Deploy each phase on its own (per the RPi validation policy) so any regression is attributable.
- `maxLegBidAskSpreadPct` and `executionTimeoutMinutes` are kept shared in v1 but are natural
  follow-ups to make per-strategy if bear-call liquidity/fill behavior warrants it.

## Out of scope
- Market-data competing-session fix (environmental/account).
- Bull-put fill-quality / liquidity-threshold tuning.
- Universe `watchlist` legacy field removal (deprecate, separate cleanup).
