# Fixture Fetch Tests — Implementation Plan

## Status

**IMPLEMENTED**. Last reviewed: 2026-06-05. Note: watchlist now includes NVDA (US) and EU symbols (ASML, SAP, SIE, ALV) — fixture files for these may need fetching separately. Core fixtures (SPY/QQQ/AAPL/MSFT) remain committed.

---

## Purpose

Fetch live IBKR data once and commit the results as static fixture files.
All subsequent unit/integration tests consume the fixtures without a live connection.

---

## Files Created

| File | Purpose |
|------|---------|
| `src/test/resources/application-tws.yml` | Spring profile: H2 in-memory DB, Liquibase off, auto-connect off, client-id 2, candidateStrikeCount 20 |
| `src/test/kotlin/.../fixtures/FixtureFetchTest.kt` | Three `@Tag("tws")` tests that write fixture files |

---

## Fixture Output

| Test | Output path | Format |
|------|-------------|--------|
| `fetch IV history` | `src/test/resources/fixtures/iv/{SYMBOL}.csv` | `date,iv` — 365 rows, iv is dimensionless (e.g. `0.1823` = 18.23%) |
| `fetch option chains` | `src/test/resources/fixtures/chain/{SYMBOL}.json` | See schema below |
| `fetch account summary` | `src/test/resources/fixtures/account.json` | `{"netLiquidation": 25000.0}` |

### Option chain JSON schema

```json
{
  "symbol": "SPY",
  "fetchedAt": "2026-04-25",
  "underlyingPrice": 570.50,
  "expirations": ["2026-06-20", "2026-06-27"],
  "selectedExpiry": "2026-06-20",
  "dteDays": 56,
  "chain": [
    {
      "strike": 540.0,
      "bid": 3.10,
      "ask": 3.30,
      "mid": 3.20,
      "delta": -0.148,
      "gamma": 0.0082,
      "theta": -0.115,
      "vega": 0.44,
      "iv": 0.182
    }
  ]
}
```

Up to `candidateStrikeCount` (20 in the tws profile) OTM put strikes, sorted ascending by strike.
The selected expiry is the one closest to `preferredDte` (45) within `[minDte, maxDte]` (30–50).

---

## How to Run

```bash
./gradlew test -Dtests.tags=tws --rerun-tasks
```

**TWS prerequisites:**
- Paper trading session on `localhost:7497`
- Configure → API → Settings → "Enable ActiveX and Socket Clients" checked
- "Read-Only API" must be **off**
- Run during market hours — option greeks are stale or absent after hours

IV history and account summary work outside market hours.
Option chain greeks require live ticks.

---

## Design Decisions

| Decision | Reason |
|----------|--------|
| H2 in-memory DB for tws profile | Fixture fetch doesn't touch persistence; avoids requiring PostgreSQL for a pure IBKR data dump |
| `client-id: 2` | Avoids connection conflict with a production/dev instance on client-id 1 |
| `candidateStrikeCount: 20` | Wider than production default of 7 — richer fixture data covers more test scenarios (low delta, high delta, near-ATM) |
| `Thread.sleep` between symbols | IBKR pacing limit: 60 historical data requests per 10 minutes; option chain snapshots also benefit from spacing |
| Fixture files committed to `src/test/resources` | Tests run offline in CI; no IBKR dependency after initial fetch |

---

## Next Step

Write unit/integration tests that load these fixture files:
- `IvRankServiceTest` — loads `iv/{SYMBOL}.csv`, verifies rank formula and cache TTL
- `ScannerServiceTest` — loads `chain/{SYMBOL}.json` + `account.json`, mocks ports, tests entry logic
- `SpreadManagementServiceTest` — uses chain mid-prices, tests TP/SL/DTE exit logic
