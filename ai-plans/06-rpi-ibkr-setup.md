# RPi IBKR Test Environment Setup

## Goal

Run PostgreSQL and IB Gateway on a Raspberry Pi (`192.168.0.107`) so the Spring Boot engine (fat JAR on any machine on the LAN) can connect to both without changing `application.yml`.

---

## What We Used

| Component | Choice | Reason |
|---|---|---|
| IB Gateway Docker image | `ghcr.io/gnzsnz/ib-gateway:stable` | Maintained, supports `aarch64`, includes IBC automation and SOCAT relay |
| Java | `openjdk-21-jre-headless` via apt | Java 17 not in Debian repo; 21 is LTS and Spring Boot 3.5 supports it |
| Networking | Bridge (default Docker) | SOCAT relay inside container handles TrustedIPs; no SSH tunnel needed |

---

## Key Architecture: SOCAT Relay

IB Gateway only accepts API connections from `127.0.0.1` (`TrustedIPs=127.0.0.1` in `jts.ini`).

The gnzsnz image solves this with `socat` running inside the container:

```
Engine (any LAN IP) → host:7497 → Docker → container:4004 (socat) → container:4002 (IB Gateway)
```

Because socat connects to IB Gateway from `127.0.0.1` inside the container, the TrustedIPs restriction is satisfied. The engine never needs to know about this — it just connects to `host:7497`.

Port mapping for paper trading:

| Host port | Container port | What it is |
|---|---|---|
| `7497` | `4004` | SOCAT paper relay (→ IB Gateway `4002` internally) |
| `5901` | `5900` | VNC (optional, for debugging IB Gateway UI) |

This matches `ibkr.connection.port: 7497` in `application.yml` — **no config changes needed**.

---

## What Went Wrong (and Why)

### 1. Disk space on original SD card
The Pi had only 277 MB free on a 7.5 GB card — not enough to pull Docker images (~1.3 GB total). Solved by reflashing a 128 GB SDXC card.

### 2. Bind mount hiding container template files
First attempt used `./data/ibkr-settings:/home/ibgateway/Jts` (bind mount). This shadowed `jts.ini.tmpl` and other seed files the container needs on first run, causing a crash loop. **Fix:** use a named Docker volume (`ibkr_settings:/home/ibgateway/Jts`) — Docker seeds it from the image on first start.

### 3. `network_mode: host` detour
Attempted to use host networking + `API_PORT=7497` to bypass TrustedIPs. This was unnecessary — SOCAT already handles it. Went down this path because the SOCAT architecture wasn't read from the docs first.

### 4. TrustedIPs fought manually
Tried editing `jts.ini` and `jts.ini.tmpl` directly to clear `TrustedIPs`. IBC kept adding `127.0.0.1` back whenever the value was empty. Abandoned once the SOCAT architecture was understood — TrustedIPs is irrelevant with the correct bridge + SOCAT setup.

### 5. VNC port was wrong
Initially mapped `5901:5901`, but VNC listens on `5900` inside the container. Corrected to `5901:5900`.

---

## Final Setup

### `docker-compose.rpi.yml`

```yaml
services:
  db:
    image: postgres:16
    restart: unless-stopped
    environment:
      POSTGRES_USER: options_user
      POSTGRES_PASSWORD: options_password
      POSTGRES_DB: options
    volumes:
      - ./data/postgres-data:/var/lib/postgresql/data
    ports:
      - "5433:5432"

  ib-gateway:
    image: ghcr.io/gnzsnz/ib-gateway:stable
    restart: unless-stopped
    environment:
      TWS_USERID: ${TWS_USERID}
      TWS_PASSWORD: ${TWS_PASSWORD}
      TRADING_MODE: ${TRADING_MODE:-paper}
      READ_ONLY_API: ${READ_ONLY_API:-no}
      VNC_SERVER_PASSWORD: ${VNC_SERVER_PASSWORD}
      TWOFA_TIMEOUT_ACTION: ${TWOFA_TIMEOUT_ACTION:-restart}
      RELOGIN_AFTER_TWOFA_TIMEOUT: ${RELOGIN_AFTER_TWOFA_TIMEOUT:-yes}
      EXISTING_SESSION_DETECTED_ACTION: ${EXISTING_SESSION_DETECTED_ACTION:-primary}
      AUTO_RESTART_TIME: ${AUTO_RESTART_TIME:-}
      TIME_ZONE: ${TIME_ZONE:-Etc/UTC}
      TZ: ${TIME_ZONE:-Etc/UTC}
    volumes:
      - ibkr_settings:/home/ibgateway/Jts
    ports:
      - "7497:4004"
      - "5901:5900"

volumes:
  ibkr_settings:
```

### `.env.rpi` (on the Pi, not committed)

```
TWS_USERID=...
TWS_PASSWORD=...
VNC_SERVER_PASSWORD=...
TRADING_MODE=paper
READ_ONLY_API=no
TWOFA_TIMEOUT_ACTION=restart
RELOGIN_AFTER_TWOFA_TIMEOUT=yes
EXISTING_SESSION_DETECTED_ACTION=primary
AUTO_RESTART_TIME=11:59 PM
TIME_ZONE=Europe/Prague
```

### One-time Pi bootstrap

```bash
# Docker
curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker $USER && newgrp docker

# Java (for fat JAR)
sudo apt update && sudo apt install -y openjdk-21-jre-headless
```

### Deploy

```bash
# From dev machine
scp docker-compose.rpi.yml 192.168.0.107:~/options/
# Edit ~/options/.env.rpi on the Pi with real credentials

# On the Pi
cd ~/options
docker compose -f docker-compose.rpi.yml --env-file .env.rpi up -d
```

### Engine (fat JAR) connecting from any LAN machine

```bash
./gradlew bootJar
java -jar engine/build/libs/engine.jar --ibkr.connection.host=192.168.0.107
```

`application.yml` already has `port: 7497` — only the host needs overriding when running off the Pi.

---

## Security Note

Ports `7497` and `5901` are bound to `0.0.0.0` — accessible to any device on the LAN. The IB API is **unencrypted and unauthenticated**. Acceptable for a home network alpha test. For anything beyond that, either restrict to `127.0.0.1` and use an SSH tunnel, or enable the built-in SSH tunnel feature in the gnzsnz image (`SSH_TUNNEL=yes`).
