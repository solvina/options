# Postmortem: What Went Haywire in Spread Trading, 2026-07-06 → 2026-07-07

Three reconstructed trades, step by step, from `trades.log`, the engine journal, the broker's raw
execution records (`tws-raw.log` EXEC_DETAILS) and the `bull_put_spreads` table. Together they show
the whole loss machine: **entries donate a third to half of fair value to the market makers, the
stop-loss is then measured against fair value, and stop exits cross wide books with raw market
orders.** The July 6–7 tech selloff only made it visible faster.

Times are CEST (US market opens 15:30 CEST = 9:30 ET).

---

## Example 1 — LITE: −$346 in 29 seconds, underlying moved 0.06%

The purest case: the market did nothing, and the trade still lost 7× its credit.

**Step 1 — Scan (Jul 6, ~21:33).** The scanner flags LITE 640P/630P (exp 2026-08-21, 45 DTE,
IV rank 75.3%, underlying $732.13). It computes the spread's fair value from leg mids:
**mid = $3.95**. All entry filters pass — including the leg bid-ask width check, which runs
*only on the scan-time snapshot*.

**Step 2 — Pricing the order (21:33:13).** `TradeExecutionService` fetches a fresh tick and prices
the entry at the **natural cross**: `soldBid − boughtAsk`. By execution time the leg books were
extremely wide (combined width ≈ $6.90), so the natural cross was **$0.487** — against a fair mid
of $3.95. Nothing re-checks quote width on this fresh tick; the scan-time check already passed.

**Step 3 — Fill (21:33:17, order 4928).** The order is marketable, so it fills instantly at
$0.487. Economically the engine just **sold a $3.95 asset for $0.49 — donating ≈ $346 per spread
at the moment of the fill** (fill = 12% of fair value).

**Step 4 — The stop is pre-breached at birth.** Stop-loss = 3 × fill credit = 3 × $0.487 =
**$1.461**. But the position is *marked against mid*, which is already $3.95. The stop threshold
sits **below the fair value the position was born at**. Survival was arithmetically impossible.

**Step 5 — Stop fires (21:33:42).** The very next monitor observation sees
`SL: spread value $3.9500 ≥ $1.4610` → CLOSED_STOP → two raw market orders cross the same wide
books. Underlying at exit: $731.69 — a **−0.06%** move since entry.

**Result: −$346.30 realized in 29 seconds.** Zero market risk was involved; the entire loss is
execution: half the bid-ask spread paid on entry, the other half (plus) paid on exit.

---

## Example 2 — NBIS: −$278 in 8 minutes, right after the open

Adds three more failure modes: a crash-priced candidate passing the delta filter, the
opening-rotation junk-quote window, and market-order exit slippage that the DB then understates.

**Step 1 — Scan (Jul 7, 15:47–15:50).** NBIS 180P/175P, IVR 95–97%, underlying $201.12. IBKR's
greeks call the 180P **"delta −0.2996"** — a routine 30-delta candidate on paper. But look at the
prices: the 180P is quoted **$24.65** on a ~$201 stock, and the $5-wide spread's mid is **$2.275 —
~50% of the width**. A credit at half the width means the market prices roughly *even odds* of
finishing in the money, whatever the vol-spike-distorted delta claims. No credit/width sanity
check existed, so the candidate passed.

**Step 2 — Pricing (15:50:50).** Fresh tick: sold 24.65/25.80, bought 22.05/23.45 → mid **$2.475**.
The engine prices the entry at the natural cross: 24.65 − 23.45 = **$1.20**. The drift check
compares mid to the scan target (passes); the fill-quality gap between $2.475 and $1.20 is checked
nowhere.

**Step 3 — Fill (15:51:05, order 4946).** Broker EXEC_DETAILS: SLD 180P @ 24.65, BOT 175P @ 23.45
→ net credit $1.20 ($1.187 after fees). **Giveaway: $2.475 − $1.20 ≈ $127 (fill = 52% of fair
value).**

**Step 4 — The trap is armed.** Stop = 3 × $1.187 = **$3.561**, only **$1.09 above the fair value
at fill time**. Roughly 40% of the intended stop distance was consumed by the entry itself.

**Step 5 — Blind monitoring (15:51–15:58).** Seven consecutive monitor cycles log
`Quote health BLIND — missing live quotes` — 20 minutes after the open, the option quotes are
still junk. When a mid finally appears it is a *single* early-session observation.

**Step 6 — Stop fires on one observation (15:58:38).** `SL: spread value $3.5750 ≥ $3.5610` —
the threshold is exceeded by **1.4 cents**. No confirmation cycle, no post-entry grace period, no
avoid-the-open window. CLOSED_STOP → straight to market orders.

**Step 7 — Exit slippage (15:58:42–15:59:06).** Market orders fill: BOT 180P @ **$26.80**,
SLD 175P @ **$22.85** → actual exit $3.95, not the $3.575 that triggered the stop. The DB stores
the trigger estimate, so the recorded loss (−$238.80) understates the broker-confirmed reality
(**−$275 … −$278** incl. fees; IBKR realPnl: −216.44 − 61.44).

**Step 8 — The record lies about why.** `underlying_price_at_exit` = 213.02 — exactly the
*previous day's close* (a stale fallback), making the DB claim the stock rose +5.9% while a bull
put stopped out. The intraday feed said $201; the truth is the stock was down ~5.6% on the day and
the books were wide. Analytics built on these columns cannot see any of this.

---

## Example 3 — The July 6 batch: 21 correlated spreads into a falling market

The portfolio-level failure that multiplied examples 1 and 2 by twenty.

**Step 1 — The cap opens.** `max-open-spreads` had just been raised **5 → 30** (the "500
experiment" note in CURRENT.md). The scanner now had ~25 free slots and a 15-minute cron.

**Step 2 — The warning was on the tape.** Candidate IV ranks that afternoon: GLW **100.0**, DELL
92, APH 93, NBIS 95, DDOG 87 — and on Jul 7 LRCX, TER, BE, WDC, KLAC, SNDK all pinned at **100**.
IVR pinned at 100 across a sector is not "rich premium to harvest" — it is a vol explosion *in
progress*, i.e. the selloff is happening right now. The regime gate (SMA50/200 + RSI) reported
NEUTRAL — it is far too slow to see a two-day cascade — and blocked nothing.

**Step 3 — 15:52–21:33, the book fills.** 19 bull puts + 2 bear calls open in one session: COIN,
DELL, ASTS, MRVL, CRDO, GLW, HON, INTU, DDOG, QCOM, MSTR, ANET, IREN, PG, TMO, APH, PEP, RKLB,
PANW, LITE (+ CRM/CRWD bear calls). Nearly every position is the same trade — short tech/AI
downside — one correlated beta bet, sized 21×.

**Step 4 — Every entry pays the toll.** Measured across the batch (scanner CANDIDATE mid vs actual
ENTRY credit): average fill = **68% of mid**; worst: LITE 12%, COIN 47%, CRDO 48%, TMO 49%, NBIS
52%, VRT 53%. Total donated at entry: **≈ $1,931 (~$84 per spread)** — approximately the entire
−$1,873 unrealized loss the open book showed at analysis time (18 of 19 bull puts underwater).

**Step 5 — The market keeps falling.** Jul 6 → Jul 7: LRCX −10%, MU −8%, INTC −9.5%, VRT −9%.
PANW gaps −3.2% overnight and is stopped at 16:05 CEST (10:05 ET, again the wide-quote morning
window) at $6.25 vs $2.037 credit → **−$421**, the biggest single realized loss. Because all 21
positions share the same factor, the drawdown hits everywhere at once.

**Step 6 — The safety system saturates.** With 21 open spreads the sequential exit sweep no longer
finishes inside its 60s interval: `Spread monitor skipped: previous run still in progress` fired
**every minute during market hours** on Jul 7 (the exact canary CURRENT.md item 7 said to watch).
Stop checks ran late precisely when the book was largest and falling.

---

## Why the math never worked, even without the crash

- **Asymmetry:** TP = keep 50% of credit; SL = 3× credit → win +0.5 units, lose −2 units before
  slippage → needs **~70–80% win rate** to break even.
- **Actual record (2026-05-15 → 07-07, real fills only):** 35% win rate — 6 wins averaging +$67,
  11 losses averaging −$160. Realized total **−$1,361**. EV ≈ −$80/trade — almost exactly the
  measured per-spread entry giveaway. The strategy loses the bid-ask spread twice per round trip,
  plus stop slippage.
- **Self-tightening stops:** SL is a multiple of the *fill* credit, but positions are marked at
  *mid* — so the worse the fill, the closer the stop. LITE is the limiting case: a fill so bad the
  stop was breached before the first monitor cycle.
- **Bookkeeping hid it:** exit prices stored as trigger estimates instead of broker fills; a
  −$263 AVGO trade labeled `CLOSED_PROFIT` (and a +$95 AMD trade labeled `CLOSED_STOP`) via the
  retryClose status-preservation path; stale closes in `underlying_price_at_exit`. Status-based
  analytics could not be trusted to reveal the pattern.

## Fixes derived from these cases (implemented 2026-07-07)

| Failure seen above | Fix |
|---|---|
| Entry at natural cross (LITE 12%, NBIS 52% of mid) | Ladder starts at fresh mid; fill floor at ~85% of mid |
| Wide books at execution despite scan-time check | Re-run per-leg width check on the fresh execution tick |
| Stop keyed to fill credit, marked at mid | SL threshold keyed to entry-time mid (stored per spread) |
| Single-observation stop, 29s/8min after entry, at the open | 2-cycle confirmation + post-entry grace + skip first RTH minutes |
| Market-order exits ($3.575 trigger → $3.95 fill) | Marketable-limit stop exits, escalate to market via retryClose |
| Crash-priced "30-delta" candidates (NBIS 50%, BE 90% of width) | Credit/width sanity band on candidates |
| 21 correlated entries in one day | Cap back to 5 + daily new-entry throttle |
| Mislabeled close statuses | retryClose derives status from realized sign |

---

## What actually changed (2026-07-07, ready for the next session)

Everything below is committed and builds; deploy is after-hours (the scanner is API-paused until
then, the exit monitor keeps running). Config lives in `application.yml`; new params have safe
defaults in `ScannerConfig` / the strategy configs.

### Entry side — pay fair value or walk away
- **Ladder starts at the fresh mid**, not the natural cross (`TradeExecutionService`). It walks
  down one tick per adjust interval as before, but the floor is now
  `max(scanner floor, 85% × fresh mid)` (`entry-min-fill-pct-of-mid: 0.85`). Worst acceptable
  fill: −15% of fair value, vs the measured −32% average (and −88% for LITE).
- **Per-leg width re-check on the fresh execution tick** (`max-leg-bid-ask-spread-pct: 0.15`,
  same threshold as the scan) — a book that blew out between scan and execution now aborts the
  entry (`LIQUIDITY_REJECTED`) instead of filling into it. This alone would have prevented LITE.
- **Crash-pricing guard**: candidates whose mid exceeds **40% of spread width**
  (`max-credit-pct-of-width`) are rejected regardless of reported delta. NBIS (49.5%) and
  BE (90%) would both have been skipped.
- **Delta target 0.30 → 0.25** (band 0.20–0.30): at the new TP/SL geometry the break-even win
  rate is ~67%; ~25-delta (~75% POP) restores a real cushion.

### Exit side — stops measure fair value and fire deliberately
- **`entry_mid_per_share` is stored on every fill** (Liquibase v24) and the stop threshold is now
  `entry mid × (1 + stop-loss-percent)` with `stop-loss-percent: 1.00` — i.e. exit when the spread
  is worth **2× its fair value at entry**. TP stays at 50% of the *fill credit* (what was actually
  received). A bad fill can no longer tighten the stop.
- **A stop needs 2 consecutive breaching monitor cycles** (`stop-loss-confirm-cycles: 2`), never
  fires in the **first 15 min after entry** nor the **first 30 min of the session** — the LITE
  (29 s) and NBIS (9:59 ET) stops were pure quote artifacts and would have been absorbed.
- **Stop exits use marketable limits** (buy the short back at its ask, sell the long at its bid)
  instead of raw MKT; if the chase fails, the existing retryClose escalates to market within one
  monitor cycle. Bounded slippage, preserved certainty.
- **retryClose relabels PROFIT/STOP by realized sign** when the price sits between thresholds —
  no more −$263 trades labeled `CLOSED_PROFIT`.

### Portfolio side — one day can't sink the book
- `max-open-spreads: 30 → 5` (also relieves the saturated 60 s exit monitor — the "monitor
  skipped" canary was firing every minute intraday on Jul 7).
- **New daily throttle** `max-new-entries-per-day: 4`, counted from actual fills in the DB across
  strategies — a single scan day can never again fill the whole book with one macro bet.

### Deploy notes (tonight)
1. Deploy the engine build (Liquibase v24 adds `entry_mid_per_share` to both spread tables).
2. Run `~/options/backfill_entry_mid_2026-07-07.sql` — sets entry mids for the 19+2 open
   positions from the logged scan mids. **Without it those rows fall back to fill-credit stops
   (now 2× credit, tighter than the old 3×) and several would stop out on the first sweep.**
3. Un-pause the scanner (`POST /options/scanner/resume`) or just let the restart clear the pause.

### What we expect to see now
- **Fewer fills.** Mid-anchored entries with an 85%-of-mid floor won't be lifted instantly the
  way natural-cross orders were; more `FLOOR_REACHED`/`LIQUIDITY_REJECTED` aborts are the system
  working as intended. Quality over throughput.
- **Fill quality ≥ 85% of mid by construction** — watch the new `entryMid=$…` field on FILLED
  log lines and compare `credit_per_share` vs `entry_mid_per_share` in the DB. If fills dry up
  entirely, loosen `entry-min-fill-pct-of-mid` toward 0.80 before touching anything else.
- **No stops in the first minutes of a position or session**, and every stop preceded by a
  logged `SL breach 1/2 — awaiting consecutive confirmation` line one cycle earlier.
- **Break-even win rate ~67%** (TP 0.5 credit vs SL ≈ 1× mid) at ~75% POP entries — positive
  expected value *before* slippage for the first time, and slippage itself is now bounded on both
  ends of the trade.
- **At most 4 new positions/day, 5 total** — a repeat of Jul 6 is structurally impossible.
- The open Jul 6 book (≈ −$1.9k marked) still carries real market risk; the new stops (2× the
  backfilled entry mids) give it honest room instead of noise-trigger levels, but a continued
  selloff will still close positions at losses. That part is the market, not the machine.
