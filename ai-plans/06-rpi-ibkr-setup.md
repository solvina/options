# RPi + IBKR Production Setup

> Last updated: 2026-06-05. Reflects the current running configuration.

---

## What Runs on the Pi

All services run on Raspberry Pi 4 at:
- LAN: `192.168.0.107`
- Tailscale: `100.65.216.36`

| Service | Runtime | Port(s) |
|---|---|---|
| options-engine | systemd (`options-engine.service`) | 8081 |
| nginx | systemd | 80 |
| PostgreSQL 16 | Docker Compose (`options-db-1`) | 5433 (host) → 5432 (container) |
| IB Gateway | Docker Compose (`options-ib-gateway-1`) | 7497 (SOCAT relay), 5901 (VNC) |
| InfluxDB | Docker Compose | 8086 |
| Loki | Docker Compose | 127.0.0.1:3100 |
| Grafana | Docker Compose | 127.0.0.1:3000 → `/grafana` path via nginx |
| Alloy | Docker Compose | (ships logs to Loki) |

---

## Key Architecture: SOCAT Relay

IB Gateway only accepts connections from `TrustedIPs=127.0.0.1`. The `gnzsnz/ib-gateway` Docker image includes a SOCAT relay:

```
Engine (localhost) → host:7497 → Docker → container:4004 (socat) → container:4002 (IB Gateway)
```

SOCAT connects to IB Gateway from `127.0.0.1` inside the container, satisfying the TrustedIPs restriction. The engine connects to `localhost:7497` — no special config needed.

Port mapping:

| Host port | Container port | Purpose |
|---|---|---|
| `7497` | `4004` | SOCAT paper relay (→ IB Gateway 4002 internally) |
| `5901` | `5900` | VNC access to IB Gateway UI |
| `4003` | `4003` | Live account relay (not used currently) |

---

## Spring Boot Configuration on RPi

Engine starts with `--spring.profiles.active=rpi`. Two config layers:

1. **`application.yml`** — base defaults (all strategy parameters, DB, IBKR base config)
2. **`application-rpi.yml`** — RPi overrides:
   - `ibkr.connection.host=localhost`
   - `ibkr.connection.paper-account=true`
   - `ibkr.connection.use-live-market-data=false` → uses delayed data (type 3) to avoid error 10189 on paper accounts without live streaming subscription
   - Scanner cron: `0 */15 3-15 * * MON-FRI` (03:00–15:00 ET covers EUREX open through US close)
   - EU instrument overrides (exchange codes for ASML, SAP, SIE, ALV)

---

## Docker Compose

File: `docker-compose.yml` (deployed copy of `docker-compose.rpi.yml` from the git repo).

Always use `--env-file .env.rpi`:

```bash
cd ~/options
docker compose --env-file .env.rpi ps
docker compose --env-file .env.rpi up -d
docker compose --env-file .env.rpi logs -f ib-gateway
docker compose --env-file .env.rpi restart ib-gateway
```

Named volumes: `ibkr_settings` (IB Gateway login state — destroying it logs you out and triggers 2FA).

---

## Secrets (NOT in git)

### `~/options/.env.rpi` — Docker Compose / IB Gateway

Keys: `TWS_USERID`, `TWS_PASSWORD`, `VNC_SERVER_PASSWORD`, `TRADING_MODE`, `READ_ONLY_API`, `TWOFA_TIMEOUT_ACTION`, `RELOGIN_AFTER_TWOFA_TIMEOUT`, `EXISTING_SESSION_DETECTED_ACTION`, `AUTO_RESTART_TIME`, `TIME_ZONE`.

### `~/options/engine.env` — Engine environment (optional)

Loaded by systemd `EnvironmentFile=-/home/solvina/options/engine.env`. The `-` prefix means a missing file is silently ignored. Override Spring Boot properties as env vars: e.g. `SCANNER_TRADING_ENABLED=false`, `FLAG_WATCHLIST=SPY,QQQ`.

---

## Deploy Workflow

```bash
# From dev machine (standard path)
cd ~/projects/options
git pull
./deploy.sh   # build JAR + frontend, upload, restart services
```

`deploy.sh` steps:
1. `./gradlew bootJar` → fat JAR
2. `npm ci && npm run build` → React dist
3. `scp` JAR → `~/options/engine.jar` on RPi
4. `rsync` frontend dist → `~/options/frontend/`
5. `scp` docker-compose, systemd unit, nginx config, Loki/Alloy/Grafana configs
6. Remote: `docker compose --env-file .env.rpi up -d db ib-gateway loki grafana alloy influxdb`
7. Remote: `sudo systemctl daemon-reload && sudo systemctl restart options-engine`
8. Remote: `sudo nginx -t && sudo systemctl reload nginx`

### JAR-only deploy (no frontend/config changes)

```bash
cd ~/projects/options/engine
./gradlew bootJar -q
scp build/libs/engine-*[!plain].jar solvina@100.65.216.36:~/options/engine.jar
ssh solvina@100.65.216.36 'sudo systemctl restart options-engine'
```

---

## IB Gateway Quirks

### Daily restart

IB Gateway auto-restarts ~23:45 ET. The engine's reconnect watchdog (`reconnect-interval-ms=10000`) handles this automatically — retries every 10 seconds until the gateway is back.

**After reconnect**: `FlagScannerService` re-establishes bar subscriptions via `onEuMarketOpen`/`onUsMarketOpen` crons (09:01 Berlin / 09:31 ET) and the 5-minute watchdog. If subscriptions are still silent after reconnect, restart the engine:

```bash
sudo systemctl restart options-engine
```

### Error codes to know

| Code | Meaning | Engine handling |
|---|---|---|
| 354 | No live market data subscription | Completes snapshot deferred → Black-Scholes fallback |
| 10197 | Competing live session | Same as 354 |
| 10189 | `reqTickByTickData` not supported | Engine uses `reqMktData(type=3, delayed)` instead |
| 200 | No security definition | Strike/contract skipped (logged WARN) |
| 399 | Order queued for after-hours | Fail-fast → CANCELLED; prevents stale overnight fills |
| 420 | Invalid real-time query | `reqRealTimeBars` failed — no live subscription for that exchange; subscription skipped (logged WARN) |

### VNC access

```
Host: 100.65.216.36   Port: 5901
Password: see VNC_SERVER_PASSWORD in ~/options/.env.rpi
```

---

## Bootstrap (one-time Pi setup)

```bash
# Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER && newgrp docker

# Java (engine runs as fat JAR)
sudo apt update && sudo apt install -y openjdk-21-jre-headless
```

---

## What NOT to Touch

| Thing | Why |
|---|---|
| `~/options/data/postgres-data/` | Live database. Never delete or move. |
| `~/options/.env.rpi` | IBKR credentials. Never log, print, or commit. |
| `docker compose ... down -v` | Destroys named volumes including `ibkr_settings` (forces 2FA re-login) and `loki-data`. |
| `ibkr_settings` volume | IB Gateway login state. |
| `flag_trading_config` row id=1 | Single-row config. Never DELETE — only UPDATE. |
| `/etc/nginx/sites-enabled/default` | deploy.sh removes it; don't re-enable (conflicts with the `options` site). |

---

## Security Note

Ports 7497 and 5901 are bound to `0.0.0.0` — accessible on the LAN. The IB API is unencrypted and unauthenticated. Acceptable for a home network. For broader exposure, restrict to `127.0.0.1` + SSH tunnel, or enable the gnzsnz image's built-in `SSH_TUNNEL=yes`.
