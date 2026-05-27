# Options Engine — RPi Operations Guide for Claude

> This file is the single source of truth for an AI agent (Claude Code) operating directly on
> the Raspberry Pi. Read this before touching anything.

---

## What This System Is

An automated **Bull Put Spread** options trading engine. It:
1. Scans a watchlist for high-IV symbols every 15 min during market hours.
2. Enters a bull-put spread when IV rank > threshold (~45%) and ~45 DTE.
3. Exits at 50% profit, 2× max-loss stop, or ≤ 14 DTE.

Currently running in **paper trading mode** with **delayed market data** on a paper IBKR account.

---

## Filesystem Layout on the RPi

```
/home/solvina/options/          ← everything lives here
├── engine.jar                  ← Spring Boot fat JAR (deployed by deploy.sh)
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

To temporarily disable trading without redeploying, set `scanner.tradingEnabled=false`:
```bash
# Add to /home/solvina/options/engine.env:
echo "SCANNER_TRADING_ENABLED=false" >> ~/options/engine.env
sudo systemctl restart options-engine
```

---

## REST API Quick Reference

Base: `http://localhost:8081/options/` (internal) or `http://100.65.216.36/api/` (via nginx)

```bash
# Health / connection status
curl -s http://localhost:8081/options/health | jq

# Account overview
curl -s http://localhost:8081/options/account | jq

# Open spreads
curl -s 'http://localhost:8081/options/spreads?status=OPEN' | jq

# All spreads
curl -s http://localhost:8081/options/spreads | jq

# Analytics
curl -s http://localhost:8081/options/spreads/analytics | jq

# Scanner status (IV ranks, pause state)
curl -s http://localhost:8081/options/scanner/status | jq

# Pause scanner (no new entries)
curl -s -X POST http://localhost:8081/options/scanner/pause

# Resume scanner
curl -s -X POST http://localhost:8081/options/scanner/resume

# Pause monitor (no automatic exits)
curl -s -X POST http://localhost:8081/options/monitor/pause

# Resume monitor
curl -s -X POST http://localhost:8081/options/monitor/resume

# Manual close a spread (limit order at mid)
curl -s -X POST http://localhost:8081/options/spreads/<UUID>/close

# Force close a spread (market order)
curl -s -X POST http://localhost:8081/options/spreads/<UUID>/close-force

# Trigger a scan immediately (fire-and-forget, returns 202)
curl -s -X POST http://localhost:8081/options/scanner/run
```

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
- Paper account has a **max 50 positions** limit and cannot place BAG/combo orders the same
  way as live. The engine places individual legs sequentially if BAG fails.

### IB Gateway daily restart

IB Gateway auto-restarts around 23:45 ET. The engine's auto-reconnect watchdog
(`ibkr.connection.reconnect-interval-ms=10000`) handles this automatically — it retries
every 10 seconds until the gateway is back (~2–3 min).

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

### Scanner not firing

```bash
# Check if cron is active (should be "0 */15 3-15 * * MON-FRI")
curl -s http://localhost:8081/options/scanner/status | jq '.cronExpression'
# Check if scanner is paused
curl -s http://localhost:8081/options/scanner/status | jq '.scannerPaused'
# Check if trading is enabled
curl -s http://localhost:8081/options/scanner/status | jq '.tradingEnabled'
```

### Spread stuck in OPEN after market close

Manually close it:
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
{unit="options-engine"} |= "CLOSED_PROFIT"      # winning exits
{unit="options-engine"} |= "symbol"             # symbol-level events
```

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
