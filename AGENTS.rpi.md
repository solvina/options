# Options Engine — RPi Operations Guide for Claude

> This file is the single source of truth for an AI agent (Claude Code) operating directly on
> the Raspberry Pi. Read this before touching anything.
> Last verified: 2026-06-05.

---

## What This System Is

An automated options/equities trading engine running two independent strategies:

### Strategy 1 — Bull Put Spread (options)
1. Scans a watchlist for high-IV symbols every 15 min during market hours.
2. Enters a bull-put spread when IV rank > threshold (~45%) and ~45 DTE.
3. Exits at 50% profit, 2× max-loss stop, or ≤ 14 DTE.

### Strategy 2 — Bull Flag Momentum (equities, intraday)
1. Subscribes to 5-second real-time IBKR bars for watchlist symbols at startup.
2. Aggregates into 5-minute candles; detects flagpole + consolidation patterns using ATR/volume.
3. On breakout above the flag's resistance line: places a bracket order (stop-market BUY + stop-loss SELL + limit profit-target SELL via OCA group).
4. Auto-liquidates open positions before exchange close (`eodLiqMinutesBeforeClose`, default 15 min).
5. Kill switch: `POST /flags/scanner/pause` persists to DB and survives restarts.

Currently running in **paper trading mode** with **live market data** on a paper IBKR account
(paper account with a live data subscription — `use-live-market-data=true`).

**The RPi is our paper-trading validation environment.** It runs the paper IBKR account
(`DU7875979`) against live market data, so it is the place where every change is proven against
real market behaviour before it can ever be considered for a live account. The policy is: **every
time a consistent, self-contained change is delivered (build green, ktlint + all expected tests
passing), deploy it to the RPi and let it validate against the live paper session.** Don't batch
unrelated changes — deploy each coherent delivery so a regression can be traced to one change.
See [Deployment & Validation Policy](#deployment--validation-policy) below.

---

## Filesystem Layout on the RPi

```
/home/solvina/options/          ← everything lives here
├── engine.jar                  ← Spring Boot fat JAR (deployed by deploy.sh or manual scp)
├── engine.env                  ← Engine env vars (NOT in git). Optional — usually empty.
├── .env.rpi                    ← Docker Compose secrets (IBKR credentials). NOT in git.
├── docker-compose.yml          ← Deployed copy of docker-compose.rpi.yml from git
├── frontend/                   ← Static React build (served by nginx)
├── data/
│   └── postgres-data/          ← PostgreSQL data directory (bind-mount)
├── deploy/
│   ├── loki-config.yml
│   ├── alloy-config.alloy
│   └── grafana-provisioning/
└── options-engine.service      ← Deployed copy of systemd unit (for reference)
```

The **git source tree is on the dev machine**, not on the RPi. The RPi only has built artefacts.
To update the code, you build on the dev machine and run `deploy.sh`, OR you can pull the repo
to a temp directory and build directly on the RPi if needed (slow on the Pi — prefer dev machine).

---

## Services Overview

| Service | How it runs | Port |
|---|---|---|
| **options-engine** | systemd (`/etc/systemd/system/options-engine.service`) | 8081 |
| **nginx** | systemd | 80 |
| **PostgreSQL 16** | Docker Compose, container `options-db-1` | 5433 (host) |
| **IB Gateway** | Docker Compose, container `options-ib-gateway-1` | 7497 (host), VNC 5901 |
| **Loki** | Docker Compose, container `options-loki-1` | 127.0.0.1:3100 |
| **Grafana** | Docker Compose, container `options-grafana-1` | 127.0.0.1:3000 → `/grafana` |
| **Alloy** | Docker Compose, container `options-alloy-1` | (ships logs to Loki) |

**Network layout:**
- Engine connects to IBKR at `localhost:7497` (IB Gateway Docker container).
- Engine connects to DB at `localhost:5433` (PostgreSQL Docker container).
- nginx reverse-proxies `/api/` → `http://127.0.0.1:8081/options/`.
- nginx reverse-proxies `/grafana/` → `http://127.0.0.1:3000/grafana/`.
- External URLs: `http://100.65.216.36/` (frontend), `http://100.65.216.36/grafana/`

---

## Checking Service Status

```bash
# All at once
sudo systemctl is-active options-engine nginx
cd ~/options && docker compose --env-file .env.rpi ps

# Detailed engine status
sudo systemctl status options-engine

# Docker containers
cd ~/options && docker compose --env-file .env.rpi ps
```

---

## Logs

```bash
# Engine application logs (live tail)
journalctl -fu options-engine

# Last N lines
journalctl -u options-engine -n 100

# Since a specific time
journalctl -u options-engine --since "2026-05-27 09:00:00"

# Flag strategy events only (trades, bar subscriptions, breakouts)
journalctl -u options-engine | grep -i "flag\|bar\|breakout\|bracket"

# Spread strategy events only
journalctl -u options-engine | grep -i "spread\|scanner\|execution"

# nginx
journalctl -fu nginx

# IB Gateway container logs
cd ~/options && docker compose --env-file .env.rpi logs -f ib-gateway

# PostgreSQL logs
cd ~/options && docker compose --env-file .env.rpi logs -f db
```

---

## Restarting Services

```bash
# Restart engine only (most common — after new JAR deployed)
sudo systemctl restart options-engine

# Restart nginx (after config change)
sudo systemctl reload nginx   # graceful
sudo systemctl restart nginx  # hard restart

# Restart a Docker service
cd ~/options
docker compose --env-file .env.rpi restart db
docker compose --env-file .env.rpi restart ib-gateway

# Full Docker stack restart
cd ~/options
docker compose --env-file .env.rpi up -d
```

---

## Deployment & Validation Policy

The RPi paper account is the validation gate for every change. The standing workflow is:

1. **Make a consistent, self-contained change** — one coherent fix or feature, not a batch of
   unrelated edits. Smaller deliveries keep paper validation attributable to a single change.
2. **Build green** — `cd engine && ./gradlew build` must pass with ktlint and all expected tests.
   (An intentionally-red regression test for a known-open bug must be called out, not counted.)
3. **Commit & push** the change to `master` (`git@github.com:solvina/options.git`).
4. **Deploy to the RPi** (see below) so it runs against the live paper session.
5. **Validate on the RPi** — watch logs/health and confirm the change behaves as intended against
   real market data before moving on. Only changes proven here are candidates for a live account.

If you are already on the RPi, the build artifact is local — back up the current JAR, copy the new
fat JAR into place, and restart the service (see [Deploying Only the JAR](#deploying-only-the-jar-engine-change-only)).

---

## Updating the Code (Normal Workflow)

The standard path is to run `deploy.sh` **from the dev machine** (cross-compile + upload):

```bash
# On dev machine (not RPi):
cd ~/projects/options
git pull
./deploy.sh   # builds JAR + frontend, uploads, restarts services
```

`deploy.sh` does:
1. `./gradlew bootJar` → fat JAR in `engine/build/libs/`
2. `npm ci && npm run build` → React dist in `frontend/dist/`
3. `scp` JAR → `~/options/engine.jar` on RPi
4. `rsync` frontend dist → `~/options/frontend/`
5. `scp` docker-compose, systemd unit, nginx config, Loki/Alloy/Grafana configs
6. Remote: `docker compose up -d db ib-gateway loki grafana alloy`
7. Remote: `sudo systemctl daemon-reload && sudo systemctl restart options-engine`
8. Remote: `sudo nginx -t && sudo systemctl reload nginx`

### If building directly on the RPi (slow, ~10 min)

Only do this if you can't reach the dev machine. Java 21 is installed; the source would need
to be cloned to a temp directory.

```bash
# Clone to a temp location
git clone git@github.com:solvina/options.git /tmp/options-build
cd /tmp/options-build

# Build JAR
cd engine && ./gradlew bootJar -q
cp build/libs/engine-*.jar ~/options/engine.jar   # pick the non-plain JAR

# Build frontend
cd ../frontend && npm ci --silent && npm run build --silent
rsync -a --delete dist/ ~/options/frontend/

# Restart
sudo systemctl restart options-engine

# Cleanup
rm -rf /tmp/options-build
```

---

## Deploying Only the JAR (Engine Change Only)

If only Kotlin/Java code changed (no frontend, no config, no docker-compose change):

```bash
# On dev machine:
cd ~/projects/options/engine
./gradlew bootJar -q
scp build/libs/engine-*[!plain].jar solvina@100.65.216.36:~/options/engine.jar
ssh solvina@100.65.216.36 'sudo systemctl restart options-engine'
```

---

## Deploying Only the Frontend

```bash
# On dev machine:
cd ~/projects/options/frontend
npm ci --silent && npm run build --silent
rsync -a --delete dist/ solvina@100.65.216.36:~/options/frontend/
# nginx serves static files directly — no restart needed unless nginx.conf changed
```

**Important:** When adding a new API client in the frontend, register its base URL in
`frontend/src/main.tsx`. Every generated client under `src/generated/*/client.gen.ts` needs:
```ts
import { client as fooClient } from './generated/foo/client.gen'
fooClient.setConfig({ baseUrl: '/api' })
```
Forgetting this causes API calls to use `undefined` as baseUrl → all requests fail silently.

---

## Database Access

```bash
# Connect to PostgreSQL
cd ~/options && docker compose --env-file .env.rpi exec db psql -U options_user -d options

# Or via psql directly (DB is on port 5433)
psql -h localhost -p 5433 -U options_user -d options
# password: options_password
```

### Key tables

```sql
-- All spread positions (most recent first)
SELECT id, symbol, status, sold_strike, bought_strike, expiry_date,
       credit_per_share, close_price_per_share, iv_rank_at_entry,
       underlying_price_at_exit, iv_rank_at_exit,
       opened_at, closed_at, close_reason
FROM spread_positions
ORDER BY opened_at DESC;

-- Open spreads
SELECT * FROM spread_positions WHERE status = 'OPEN';

-- All flag positions (most recent first)
SELECT id, symbol, status,
       entry_price, stop_loss_price, profit_target_price,
       shares, risk_amount, realized_pnl,
       opened_at, closed_at, close_reason
FROM flag_positions
ORDER BY opened_at DESC;

-- Open flag positions
SELECT * FROM flag_positions WHERE status IN ('PENDING', 'OPEN');

-- Flag strategy config (single row, id=1)
SELECT * FROM flag_trading_config;

-- Liquibase migration log
SELECT id, description, date_executed FROM databasechangelog ORDER BY orderexecuted;
```

### Running a pending migration manually

Liquibase runs automatically on engine startup (`spring.liquibase.change-log`). If the engine
fails to start with a schema error, check the changelog:

```bash
journalctl -u options-engine -n 50 | grep -i liquibase
```

If a migration is stuck or failed, you can mark it as ran (careful!):
```sql
-- Only if you applied the SQL manually
INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, md5sum, description, comments, exectype, liquibase)
VALUES ('v5__spread_exit_data', 'vseliga', 'db/changelog/v5__spread_exit_data.yaml', now(), 5, '', 'addColumn', '', 'EXECUTED', '4.x');
```

---

## Environment Files (Secrets — NOT in Git)

### `/home/solvina/options/.env.rpi` — Docker Compose secrets

Used by IB Gateway container. Contains IBKR credentials. Do not print/log these.
Keys: `TWS_USERID`, `TWS_PASSWORD`, `VNC_SERVER_PASSWORD`, `TRADING_MODE`, `READ_ONLY_API`,
`TWOFA_TIMEOUT_ACTION`, `RELOGIN_AFTER_TWOFA_TIMEOUT`, `EXISTING_SESSION_DETECTED_ACTION`,
`AUTO_RESTART_TIME`, `TIME_ZONE`.

### `/home/solvina/options/engine.env` — Engine environment

Loaded by systemd (`EnvironmentFile=-/home/solvina/options/engine.env`). Currently optional
(the `-` prefix means missing file is silently ignored). Can override Spring Boot properties
as env vars if needed, e.g.:
```
SCANNER_TRADING_ENABLED=false
IBKR_CONNECTION_ENABLED=false
FLAG_WATCHLIST=SPY,QQQ,AAPL,MSFT,NVDA
```

---

## Spring Boot Configuration

The engine starts with `--spring.profiles.active=rpi`. Two layers apply:

1. **`application.yml`** — base defaults (all strategy parameters, DB, IBKR base config)
2. **`application-rpi.yml`** — RPi overrides:
   - `ibkr.connection.host=localhost` (IB Gateway is on the same machine via Docker)
   - `ibkr.connection.paper-account=true`
   - `ibkr.connection.use-live-market-data=false` → requests delayed data (type 3) so paper
     account gets bid/ask even without a live data subscription (avoids error 10189)
   - `scanner.cron=0 */15 3-15 * * MON-FRI` — 03:00–15:00 ET covers EUREX (09:00 CEST) to US close
   - EU instrument overrides: ASML (`AEB`/`EUREX`), SAP/SIE/ALV (`EUREX` not `DTB`)

### Flag strategy YAML config (not user-editable at runtime)

```yaml
flag:
  watchlist: [SPY, QQQ, AAPL, MSFT, NVDA]   # overridden by universe watchlist if populated
  atr-period: 14
  atr-multiplier: 2.0                         # pole height must be > 2× ATR
  volume-ma-period: 20
  volume-spike-multiplier: 1.5               # pole bar must have > 1.5× avg volume
  pole-min-bars: 5
  pole-max-bars: 10
  flag-min-bars: 5
  flag-max-bars: 20
  max-retracement-pct: 0.50
  historical-bootstrap-days: 3
```

Runtime settings (risk limits, kill switch) are stored in the `flag_trading_config` DB table
and editable via the UI at `/flags/positions` or `PUT /api/flags/config`.

To temporarily disable the flag scanner without redeploying:
```bash
curl -s -X POST http://localhost:8081/options/flags/scanner/pause
# or via UI: Flags → Positions → Scanner Config → Pause button
```

---

## REST API Quick Reference

Base: `http://localhost:8081/options/` (internal) or `http://100.65.216.36/api/` (via nginx)

### Spreads

```bash
# Open spreads
curl -s 'http://localhost:8081/options/spreads?status=OPEN' | jq

# All spreads (paginated)
curl -s http://localhost:8081/options/spreads | jq

# Spread analytics
curl -s http://localhost:8081/options/spreads/analytics | jq

# Scanner status (IV ranks, pause state)
curl -s http://localhost:8081/options/scanner/status | jq

# Pause/resume scanner
curl -s -X POST http://localhost:8081/options/scanner/pause
curl -s -X POST http://localhost:8081/options/scanner/resume

# Pause/resume monitor (automatic exits)
curl -s -X POST http://localhost:8081/options/monitor/pause
curl -s -X POST http://localhost:8081/options/monitor/resume

# Manual close a spread (limit order at mid)
curl -s -X POST http://localhost:8081/options/spreads/<UUID>/close

# Force close a spread (market order)
curl -s -X POST http://localhost:8081/options/spreads/<UUID>/close-force

# Trigger a scan immediately
curl -s -X POST http://localhost:8081/options/scanner/run
```

### Flags

```bash
# All flag positions (paginated)
curl -s http://localhost:8081/options/flags | jq

# Open/pending only
curl -s 'http://localhost:8081/options/flags?status=OPEN' | jq

# Single position
curl -s http://localhost:8081/options/flags/<UUID> | jq

# Flag analytics
curl -s http://localhost:8081/options/flags/analytics | jq

# Flag trading config (risk limits, kill switch)
curl -s http://localhost:8081/options/flags/config | jq

# Update config (riskPerTrade, maxOpenPositions, entryBlockMinutes, eodLiqMinutes, enabled)
curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"riskPerTrade":100,"maxOpenPositions":3,"enabled":true,"entryBlockMinutesBeforeClose":120,"eodLiqMinutesBeforeClose":15}' \
  http://localhost:8081/options/flags/config | jq

# Pause/resume flag scanner (persists to DB, survives restarts)
curl -s -X POST http://localhost:8081/options/flags/scanner/pause
curl -s -X POST http://localhost:8081/options/flags/scanner/resume

# Manually close a flag position (works for PENDING and OPEN)
curl -s -X POST http://localhost:8081/options/flags/<UUID>/close | jq
```

### Other

```bash
# Health / connection status
curl -s http://localhost:8081/options/health | jq

# Account overview
curl -s http://localhost:8081/options/account | jq
```

---

## Frontend URL Structure

```
http://100.65.216.36/                        → redirects to /spreads/positions
http://100.65.216.36/spreads/positions       → Spread positions table
http://100.65.216.36/spreads/analytics       → Spread P&L analytics
http://100.65.216.36/scanner                 → Scanner config & status
http://100.65.216.36/flags/positions         → Flag positions + scanner config
http://100.65.216.36/flags/analytics         → Flag P&L analytics
http://100.65.216.36/universe                → Universe/watchlist management
http://100.65.216.36/account                 → Account overview
http://100.65.216.36/diagnostic              → IBKR diagnostic probe
http://100.65.216.36/grafana/                → Grafana log explorer
```

Old `/spreads` and `/flags` URLs redirect automatically.

---

## IB Gateway (IBKR) Notes

### Port mapping

```
Engine → localhost:7497 → Docker → socat inside container → IB Gateway port 4002
```

The socat relay satisfies IB Gateway's `TrustedIPs=127.0.0.1` restriction.
Port 7497 = paper account relay. Port 4003 = live account relay (not used).

### Paper account quirks

- **Error 10189**: `reqTickByTickData` not supported → the engine falls back to
  `reqMarketData(type=3, delayed)` via `onUpdate` callbacks. This is handled automatically.
- **Error 354 / 10197**: No live option data → engine falls back to Black-Scholes pricing
  using `IvRankService.currentIv`. Expected and handled.
- **Error 200 (no security definition)**: Contract not found — usually wrong exchange.
  EUREX symbols need `optionExchange=EUREX` (not `DTB`). Fixed in `application-rpi.yml`.
- **Error 420 (invalid real-time query)**: `reqRealTimeBars` requires a live streaming
  market data subscription for the stock's primary exchange. EU symbols (ASML on AEB,
  SAP/SIE/ALV on XETRA) will fail with this error unless the account has the relevant
  exchange subscription. The subscription is automatically skipped for those symbols and
  logged as a warning — no action needed unless you add the subscription.
- Paper account has a **max 50 positions** limit and cannot place BAG/combo orders the same
  way as live. The engine places individual legs sequentially if BAG fails.

### Flag scanner — `reqRealTimeBars` notes

- Only works with **live** market data subscriptions — no delayed fallback exists for 5-sec bars.
- `useRTH=true` is set, so bars only arrive during regular trading hours for each exchange.
- The flag scanner watchlist defaults to the Universe table contents; falls back to
  `flag.watchlist` in `application.yml` if Universe is empty.
- If a symbol's subscription fails (error 420), the stream errors, is caught and logged,
  and the scanner simply doesn't trade that symbol. All other symbols are unaffected.

### IB Gateway daily restart

IB Gateway auto-restarts around 23:45 ET. The engine's auto-reconnect watchdog
(`ibkr.connection.reconnect-interval-ms=10000`) handles this automatically — it retries
every 10 seconds until the gateway is back (~2–3 min).

**Flag scanner after reconnect**: the `FlagScannerService` subscribes at `ApplicationReadyEvent`
only. After an IBKR reconnect, bar subscriptions need to be re-established. The engine
currently restarts the flag scanner on reconnect via the existing reconnect flow — if bar
streams are not resuming after a reconnect, restart the engine:
```bash
sudo systemctl restart options-engine
```

### VNC access (IB Gateway UI)

```
Host: 100.65.216.36  Port: 5901
Password: see VNC_SERVER_PASSWORD in ~/.env.rpi
```

Use a VNC client (e.g. `vncviewer 100.65.216.36:5901`) to inspect the IB Gateway UI.
Useful for verifying login status and pending 2FA.

---

## Docker Compose Operations

Always pass `--env-file .env.rpi` when running compose commands:

```bash
cd ~/options

# Check status
docker compose --env-file .env.rpi ps

# View logs
docker compose --env-file .env.rpi logs -f            # all services
docker compose --env-file .env.rpi logs -f ib-gateway  # just gateway

# Restart a single service
docker compose --env-file .env.rpi restart ib-gateway

# Bring everything up (idempotent)
docker compose --env-file .env.rpi up -d

# Stop everything (does NOT delete volumes / data)
docker compose --env-file .env.rpi down

# Nuclear: destroy containers + volumes (DELETES POSTGRES DATA — don't do unless replacing DB)
docker compose --env-file .env.rpi down -v
```

---

## Common Problems

### Engine won't start — port already in use

```bash
sudo lsof -i :8081
# Kill the stale process if needed
```

### Engine won't start — Liquibase schema mismatch

```bash
journalctl -u options-engine -n 100 | grep -E "Liquibase|SchemaValidation|ERROR"
```
Usually means a new migration in `db.changelog-master.yaml` needs to run. The engine applies
it automatically on start — if it's erroring, check the SQL in the migration file.

### IBKR connection stuck at "connecting"

```bash
# Check gateway is up
cd ~/options && docker compose --env-file .env.rpi ps ib-gateway
# Check gateway logs for login errors
docker compose --env-file .env.rpi logs --tail=50 ib-gateway
# Check engine logs
journalctl -u options-engine -n 50 | grep -i "ibkr\|connect\|socket"
```
If the gateway container is in a restart loop, check the credentials in `.env.rpi`.

### No market data / all IV ranks show 0

Usually caused by:
1. IB Gateway not fully logged in (check VNC).
2. `use-live-market-data=true` but paper account has no live subscription → set to `false`.
3. Historical data pacing limit (IBKR allows ~60 requests/10 min) → wait and retry.

### Spread scanner not firing

```bash
# Check if cron is active (should be "0 */15 3-15 * * MON-FRI")
curl -s http://localhost:8081/options/scanner/status | jq '.cronExpression'
# Check if scanner is paused
curl -s http://localhost:8081/options/scanner/status | jq '.scannerPaused'
# Check if trading is enabled
curl -s http://localhost:8081/options/scanner/status | jq '.tradingEnabled'
```

### Flag scanner not trading

```bash
# Check if enabled (DB-backed)
curl -s http://localhost:8081/options/flags/config | jq '.enabled'
# Check if bar streams are running (look for "Subscribing to 5-sec real-time bars")
journalctl -u options-engine | grep "real-time bars"
# Check for error 420 (no data subscription for that exchange)
journalctl -u options-engine | grep "420"
# Check for breakout signals (look for "BreakoutReady" or "Submitting bracket")
journalctl -u options-engine | grep -i "breakout\|bracket\|flag.*entry"
```

### Flag position stuck in OPEN / PENDING

```bash
# Get open flag positions
curl -s 'http://localhost:8081/options/flags?status=OPEN' | jq '.[].id'
# Manual close (cancels bracket orders + market sell for OPEN, cancel only for PENDING)
curl -s -X POST http://localhost:8081/options/flags/<UUID>/close | jq
```

### Spread stuck in OPEN after market close

```bash
# Get the spread ID
curl -s 'http://localhost:8081/options/spreads?status=OPEN' | jq '.[].id'
# Force close at market
curl -s -X POST http://localhost:8081/options/spreads/<UUID>/close-force
```

### Disk space

```bash
df -h /
# PostgreSQL data
du -sh ~/options/data/postgres-data/
# Docker images
docker system df
# Clean unused Docker images
docker image prune -f
```

---

## Monitoring — Grafana

URL: `http://100.65.216.36/grafana/`  
Login: admin / options  
Anonymous view: enabled (Viewer role)

Loki is pre-provisioned as a datasource. Log queries use LogQL:
```
{unit="options-engine"}                         # all engine logs
{unit="options-engine"} |= "ERROR"              # errors only
{unit="options-engine"} |= "CLOSED_PROFIT"      # winning spread exits
{unit="options-engine"} |= "ENTRY_FILLED"       # flag entries confirmed
{unit="options-engine"} |= "CLOSED_STOP"        # flag stop-losses
{unit="options-engine"} |= "eod_liquidation"    # flag EOD auto-close
```

Dedicated loggers:
- `FLAG_TRADES` — structured flag trade lifecycle events (ENTRY_PENDING, ENTRY_FILLED, CLOSED_*)
- `SPREAD_TRADES` — spread lifecycle events

---

## Systemd Service Details

```
/etc/systemd/system/options-engine.service

ExecStart: /usr/bin/java -Xmx512m -jar /home/solvina/options/engine.jar
           --spring.profiles.active=rpi
Restart: on-failure, RestartSec=15
EnvironmentFile: /home/solvina/options/engine.env (optional)
```

Max heap is 512 MB — appropriate for the Pi. Do not increase beyond 768 MB.

To permanently change a config value without redeploying:
1. Add it to `/home/solvina/options/engine.env` as `SPRING_PROPERTY_IN_CAPS_SNAKE=value`
   (Spring Boot maps `A_B_C` → `a.b.c` automatically).
2. `sudo systemctl restart options-engine`

---

## Git Repository

Source is at: `https://github.com/solvina/options`  
Default branch: `main`  
Working branch: `master` (PRs target `main`)

The RPi does **not** have a git checkout under normal operation. Source lives on the dev machine
at `~/projects/options/`. If you need to inspect the source from the RPi, clone to `/tmp/`.

---

## What NOT to Touch

| Thing | Why |
|---|---|
| `~/options/data/postgres-data/` | Live database. Never manually delete or move. |
| `~/options/.env.rpi` | IBKR credentials. Never log, print, or commit. |
| `docker compose ... down -v` | Destroys named volumes including `ibkr_settings` and `loki-data`. |
| `ibkr_settings` Docker volume | IB Gateway login state. Recreating it logs out and triggers 2FA. |
| `/etc/nginx/sites-enabled/default` | deploy.sh removes it; don't re-enable (conflicts with `options`). |
| `flag_trading_config` row id=1 | Single-row config table — never delete, only UPDATE. |
