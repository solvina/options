# Market Regime (Trend) Detection — Feature Plan — 2026-06-29

**Status**: Design / pre-implementation
**Goal**: A self-contained `regime` feature that classifies each symbol's trend (bull / bear / neutral)
from price history, and gates each spread strategy to its favorable direction — so bull puts aren't
sold into downtrends and bear calls aren't sold into uptrends.
**Effort**: ~3–5 days (signal + cache + integration + validation rollout)

---

## Why

Bull put = **bullish** (short put profits if the underlying holds/rises). Bear call = **bearish**
(short call profits if it holds/falls). Today both selectors filter **only** on IV-rank, delta, DTE,
credit, and money-management — there is **no directional awareness**. So a bull put can be sold on a
down-trending name and a bear call on an up-trending one — directionally adverse, and the short leg is
exactly where it's most likely to be breached.

This became material the moment bear call went live: without a trend filter it will sell call spreads
on strong uptrends (NVDA, MSFT, …) purely because IV-rank/delta qualify. A regime filter is the
highest-value risk/quality improvement available right now.

---

## What we already have (no new IBKR integration needed)

- **Price history from TWS**: `IbkrHistoricalDataAdapter.fetchDailyPriceBars(symbol, days)` —
  `reqHistoricalData`, `whatToShow="TRADES"` — already exists (sibling of the IV-rank `fetchDailyBars`).
- **A proven cache/warmup pattern**: `IvRankService` computes a slow-moving per-symbol signal on a
  cache (TTL, serve-stale, persistent store, startup warmup via `UniverseWarmupService`) under the
  IBKR historical rate limiter (`IbkrRateLimiter.acquireHistorical`). Regime is even slower-moving than
  IV rank, so it reuses this pattern directly.
- **The multi-strategy seam**: `SpreadStrategy` / `SpreadStrategyRegistry`. A strategy can declare its
  directional bias, and a single gate applies to all strategies — so strategy #3 (iron condor, …) is
  covered automatically.

IBKR does **not** provide a "bull/bear" label — we compute it from the bars we already pull.

---

## Architecture — a separate `domain/features/regime/` module

```
domain/features/regime/
├── TrendRegime            (enum: UPTREND, DOWNTREND, NEUTRAL)
├── RegimeSignal           (regime + the inputs: lastClose, sma50, sma200, slope — for logging/UI)
├── TrendRegimeService     (computes RegimeSignal from daily bars; cache + warmup, mirrors IvRankService)
├── RegimeConfig           (@ConfigurationProperties "regime"): enabled, periods, mode, kill switch
└── RegimeGate             (regime × strategy bias → allow/deny entry)

domain/features/spread/strategy/
└── SpreadStrategy gains   val directionalBias: DirectionalBias   (BULLISH | BEARISH | NEUTRAL)
        BullPutStrategy  -> BULLISH
        BearCallStrategy -> BEARISH
        (iron condor, …) -> NEUTRAL   ← future strategy needs no gate changes

adapters/outbound/ibkr/...  reuse IbkrHistoricalDataAdapter.fetchDailyPriceBars (no new adapter)
```

The gate is consulted in `ScannerService.scanSymbol` before running each strategy's selector:
`if (regimeGate.allows(strategy.directionalBias, symbol)) selector.select(...)`. The selectors stay
unchanged; the directional decision lives in one place.

---

## The signal (start simple, robust)

From `lookbackDays` of daily closes (default 250 so SMA200 is valid):

- **SMA50**, **SMA200**, latest close.
- **UPTREND**: close > SMA50 **and** SMA50 > SMA200 (price-up + golden alignment).
- **DOWNTREND**: close < SMA50 **and** SMA50 < SMA200.
- **NEUTRAL**: anything else (mixed / chop).
- Insufficient history (< SMA200 bars) → **NEUTRAL** (don't block on missing data).

**Gate modes** (config `regime.mode`):
- `SKIP_OPPOSITE` (default, recommended): bull put skips **DOWNTREND**; bear call skips **UPTREND**;
  NEUTRAL allows both. Theta spreads work fine in neutral-to-favorable trends, so we only veto the
  clearly-wrong direction.
- `REQUIRE_MATCH` (strict, opt-in later): bull put requires **UPTREND**, bear call requires **DOWNTREND**.

NEUTRAL band / slope thresholds and EMA/ROC refinements are future tuning; SMA50/200 alignment is the
standard, well-understood baseline.

---

## Configuration (`regime.*` — separate namespace, per-symbol overrides)

```yaml
regime:
  enabled: false            # rollout: observe-only first, then enable the gate
  lookback-days: 250
  sma-fast: 50
  sma-slow: 200
  mode: SKIP_OPPOSITE       # or REQUIRE_MATCH
  cache-ttl-hours: 24       # regime is slow-moving; daily refresh is plenty
  serve-stale-hours: 48
```

Per-symbol overrides on the universe (like the existing IV/delta overrides): e.g. force-neutral a
symbol, or exempt it from the gate. (Reuses the `InstrumentConfig` override pattern.)

---

## Phases

### Phase 1 — Signal + service (no behaviour change)
`TrendRegime`, `RegimeSignal`, `TrendRegimeService` (SMA from `fetchDailyPriceBars`, cached, warmed at
startup alongside IV rank, under the historical rate limiter). Pure SMA/regime computation
**unit-tested** with fixture price series (uptrend / downtrend / chop / insufficient data).

### Phase 2 — Observe-only (`regime.enabled: false`)
Compute + **log** each symbol's regime during the scan (e.g. `[NVDA] regime=UPTREND close>sma50>sma200`)
and expose it (universe/API field). No gating yet — validate the classifications against reality for a
few sessions before letting it veto trades. (Same cautious rollout we used for bear call + dividends.)

### Phase 3 — Gate entries (`regime.enabled: true`, `SKIP_OPPOSITE`)
Add `directionalBias` to `SpreadStrategy`; `RegimeGate.allows(bias, symbol)`; consult it in
`ScannerService.scanSymbol`. Bull put skips downtrends, bear call skips uptrends. Deploy behind the
flag; turn on after Phase-2 validation. Tests: gate truth table (regime × bias × mode).

### Phase 4 — Tuning & observability (optional)
`REQUIRE_MATCH` mode, NEUTRAL-band/slope thresholds, per-symbol overrides, a regime column in the
dashboard / scan summary, persisted regime for warmup-across-restart (mirror `IvRankStorePort`).

---

## Rollout & safety

- Ships **`regime.enabled: false`** — observe + validate the signal before it can veto a live entry
  (Phase 2 → 3), exactly like the bear-call / dividend rollouts.
- **Behaviour-preserving** while disabled: the scanner path is unchanged until the gate is enabled.
- **Fail-open**: missing/insufficient data → NEUTRAL → allow (never block trading on a data gap).
- The gate only ever **removes** candidates, so it can't cause a bad entry — worst case it's too
  conservative, tunable via `mode` and thresholds.

---

## Risks

| Risk | Mitigation |
|------|-----------|
| Historical-data pacing (reqHistoricalData limits) | Reuse `IbkrRateLimiter.acquireHistorical` + warmup batching + 24h cache (few refreshes/day) |
| SMA200 needs ~250 bars | Default `lookback-days: 250`; insufficient → NEUTRAL (fail-open) |
| Over-filtering (theta works in mild counter-trends) | Default `SKIP_OPPOSITE` (veto only the clearly-wrong direction), not `REQUIRE_MATCH` |
| Whipsaw near SMA crossovers | Slow signal + NEUTRAL band; tune in Phase 4 |
| Lag (SMA is trailing) | Acceptable for 30–50 DTE theta spreads; ROC/EMA refinement is future |

---

## Testing

- **Unit**: SMA + regime classification on fixture series (clear up/down/chop, exactly-200 bars,
  < 200 bars → NEUTRAL); `RegimeGate` truth table (UPTREND/DOWNTREND/NEUTRAL × BULLISH/BEARISH/NEUTRAL
  × SKIP_OPPOSITE/REQUIRE_MATCH).
- **Integration**: scanner skips the wrong-direction strategy for a symbol with a forced regime
  (mirrors the existing bear-call scanner tests).
- **Backtest**: the backtest already feeds price fixtures — wire regime in to confirm it filters as
  expected over a historical window.

---

## Out of scope (future)
- **Active routing** (allocate capital to the matching strategy, not just gate) — this plan only gates.
- Richer regimes (volatility regime, breadth, sector trend), multi-timeframe, ML.
- Combined bull+bear "neutral-name" iron condor strategy (the framework already supports adding it).

---

## Decision points for you
1. **Indicator**: SMA50/200 alignment (recommended) vs something else (EMA, ROC, 200-day only)?
2. **Strictness default**: `SKIP_OPPOSITE` (recommended) vs `REQUIRE_MATCH`?
3. **Scope now**: build Phases 1–2 (signal + observe-only) first and validate, then 3 (gate)? — matches
   how we de-risked bear call and dividends.
