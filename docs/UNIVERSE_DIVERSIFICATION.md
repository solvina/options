# Universe Diversification — Sector Analysis & +100 Ticker Proposal

**Author:** analysis for solvina, 2026-07-14
**Context:** The live paper book on 2026-07-14 was ~15 bull-put spreads, essentially all
tech/semis/AI names, all red together on a single correlated down-tick. Root cause is not the
strategy — it is the **universe**: it is ~45–50% Information Technology, so a trend/IV filter
that fires "sell puts" fires it on 15 correlated names at once. This doc proposes a sector
framework and 100 new liquid-optionable US names to fix that.

Current universe = **112 symbols** (source: `GET /options/universe`).

---

## 1. How many sectors?

Use the standard **GICS 11 sectors**. They are the same buckets index providers and IBKR use,
so they map cleanly onto correlation risk:

1. Information Technology
2. Communication Services
3. Consumer Discretionary
4. Consumer Staples
5. Health Care
6. Financials
7. Industrials
8. Energy
9. Materials
10. Utilities
11. Real Estate

(ETFs — SPY/QQQ/IWM/DIA/XLF/XLE/XLV — sit outside the 11 as index/sector proxies.)

## 2. Current concentration (the problem)

Approximate GICS bucketing of the live 112 (ETFs excluded):

| Sector | ~Count | ~Share | Verdict |
|---|---|---|---|
| Information Technology | ~46 | ~44% | **massively overweight** |
| Health Care | 9 | ~9% | ok |
| Financials | 9 | ~9% | ok |
| Communication Services | 6 | ~6% | light |
| Consumer Discretionary | 6 | ~6% | light |
| Industrials | 6 | ~6% | light |
| Consumer Staples | 4 | ~4% | thin |
| Energy | 2 | ~2% | thin |
| Materials | 1 | ~1% | **empty** |
| Utilities | 0 | 0% | **empty** |
| Real Estate | 0 | 0% | **empty** |

Nearly half the universe is one sector, and three sectors are effectively absent. On any given
day the scanner can only pick from what exists, so a tech-heavy universe produces a tech-heavy
book — exactly what we saw.

## 3. How many tickers per sector makes sense?

Two constraints:

- **Enough depth to always field candidates.** The scanner needs a handful of names per sector
  that can pass IV Rank > 45 on a given day. IV rank is regime-driven, so on a quiet day maybe
  1-in-4 names qualify — you want **≥ 12 liquid names per sector** so ≥ 3 are typically eligible.
- **A hard cap on any single sector** so one theme can't dominate the book. Target **no sector
  above ~15% of the universe.**

Recommended target for a ~200-name universe:

| Sector | Target names | Rationale |
|---|---|---|
| Information Technology | 25–30 (cap) | Most liquid + highest IV, but cap it — we are already here |
| Health Care | 18–20 | Deep liquid optionable set, idiosyncratic (low correlation to tech) |
| Financials | 18–20 | Deep, liquid, distinct rate-driven regime |
| Consumer Discretionary | 16–18 | Deep, liquid |
| Industrials | 16–18 | Deep, liquid |
| Consumer Staples | 12–14 | Lower-IV but great diversifier / defensive |
| Energy | 12–14 | High IV, negatively correlated to tech at times |
| Communication Services | 12–14 | Overlaps mega-cap tech; keep distinct (telecom/media) |
| Materials | 10–12 | High IV commodity names, currently empty |
| Utilities | 10–12 | Low-beta ballast, currently empty |
| Real Estate | 8–10 | REITs, rate-sensitive diversifier, currently empty |

The fix is **not fewer tech names** — it is many more non-tech names, so the qualifying set on
any day is spread across sectors. That is what the list below does: it deliberately underweights
IT (+4) and fills the thin/empty sectors.

---

## 4. Proposed +100 tickers (all US-listed, liquid options, none already in the 112)

Every name below has actively traded weekly or monthly options with meaningful open interest.

### Communication Services (9)
T, TMUS, DIS, CMCSA, CHTR, WBD, EA, TTWO, SPOT

### Consumer Discretionary (11)
NKE, SBUX, LOW, TGT, LULU, CMG, ABNB, GM, F, RCL, DKNG

### Consumer Staples (8)
KO, PM, MO, MDLZ, CL, KMB, GIS, KR

### Health Care (11)
ABBV, AMGN, GILD, BMY, MRNA, ISRG, VRTX, REGN, MDT, BSX, DHR

### Financials (12)
WFC, AXP, BLK, USB, COF, BX, KKR, PYPL, SOFI, ICE, CME, SPGI

### Industrials (11)
UPS, FDX, LMT, RTX, DE, MMM, UNP, CSX, DAL, UAL, ETN

### Energy (9)
COP, SLB, OXY, EOG, MPC, VLO, HAL, DVN, KMI

### Materials (9)
FCX, NEM, NUE, DOW, APD, SHW, CF, ALB, CLF

### Utilities (9)
NEE, DUK, SO, D, AEP, EXC, VST, CEG, PCG

### Real Estate (7)
AMT, PLD, SPG, O, CCI, EQIX, DLR

### Information Technology (4) — intentionally light, we are already saturated
ARM, NET, FTNT, ON

**Total: 100.**

### Resulting universe shape (112 + 100 = 212)

| Sector | ~After | ~Share |
|---|---|---|
| Information Technology | ~50 | ~24% |
| Health Care | 20 | ~9% |
| Financials | 21 | ~10% |
| Consumer Discretionary | 17 | ~8% |
| Industrials | 17 | ~8% |
| Communication Services | 15 | ~7% |
| Consumer Staples | 12 | ~6% |
| Energy | 11 | ~5% |
| Materials | 10 | ~5% |
| Utilities | 9 | ~4% |
| Real Estate | 7 | ~3% |

IT drops from ~44% to ~24% (still the largest, appropriately) and every empty sector is now
populated enough to field candidates.

---

## 5. How to load them

The universe is DB-driven (`instrument_universe`, `enabled` flag). Add these the same way the
v17/v19 expansions did — a Liquibase changelog seeding rows with `enabled = true`
(`flag_enabled = false`, since the bull-flag strategy needs real-time bars), or via the universe
API if it supports inserts. Suggested rollout:

1. **Stage in batches by sector**, enabling one or two thin sectors at a time, so any new-name
   contract-lookup / market-data issue is attributable.
2. **Watch the scanner** after each batch — new symbols mean new option-chain lookups; confirm
   no `no_market_data` (missing-exchange) regressions on the added names.
3. **Consider a per-sector open-spread cap** in risk config (e.g. ≤ 3 open spreads per GICS
   sector) so a populated universe actually translates into a diversified book — a bigger
   universe alone doesn't guarantee it if the IV filter still clusters on one theme.

> Note: this list is a starting universe, not a hand-tuned watchlist. Before enabling, it's worth
> a one-pass liquidity screen (option OI / bid-ask width) on the RPi against live IBKR data —
> a couple of the smaller names (e.g. CLF, DKNG, SOFI) have wider spreads than the mega-caps.
