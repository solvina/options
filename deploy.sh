#!/usr/bin/env bash
# Deploy options engine + frontend to Raspberry Pi.
#
# Prerequisites on RPi (one-time):
#   sudo apt install openjdk-21-jre-headless nginx python3 python3-venv
#   # Docker: https://docs.docker.com/engine/install/raspberry-pi-os/
#   sudo usermod -aG docker solvina   # then re-login
#   # Passwordless sudo for deploy commands:
#   echo 'solvina ALL=(ALL) NOPASSWD: /usr/bin/systemctl, /usr/sbin/nginx, /usr/bin/cp, /usr/bin/ln, /usr/bin/rm' \
#     | sudo tee /etc/sudoers.d/options-deploy
#
# Usage:
#   RPI_HOST=solvina@192.168.0.107 ./deploy.sh
set -euo pipefail

RPI_HOST="${RPI_HOST:-solvina@100.65.216.36}"
DEPLOY_DIR="/home/solvina/options"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── 1. Build ──────────────────────────────────────────────────────────────────

echo "==> Building Spring Boot JAR..."
cd "$SCRIPT_DIR/engine"
./gradlew bootJar -q
JAR=$(ls "$SCRIPT_DIR/engine/build/libs/"engine-*.jar | grep -v '\-plain\.jar' | tail -1)
cd "$SCRIPT_DIR"

echo "==> Building frontend..."
cd "$SCRIPT_DIR/frontend"
npm ci --silent
npm run build --silent
cd "$SCRIPT_DIR"

# ── 2. Upload ─────────────────────────────────────────────────────────────────

echo "==> Uploading to $RPI_HOST:$DEPLOY_DIR ..."
ssh "$RPI_HOST" "mkdir -p $DEPLOY_DIR/frontend && chmod o+x /home/solvina $DEPLOY_DIR $DEPLOY_DIR/frontend"

scp "$JAR" "$RPI_HOST:$DEPLOY_DIR/engine.jar"
rsync -a --delete "$SCRIPT_DIR/frontend/dist/" "$RPI_HOST:$DEPLOY_DIR/frontend/"
scp "$SCRIPT_DIR/docker-compose.rpi.yml" "$RPI_HOST:$DEPLOY_DIR/docker-compose.yml"
scp "$SCRIPT_DIR/deploy/options-engine.service" "$RPI_HOST:$DEPLOY_DIR/"
scp "$SCRIPT_DIR/deploy/options.nginx.conf" "$RPI_HOST:$DEPLOY_DIR/"
ssh "$RPI_HOST" "mkdir -p $DEPLOY_DIR/deploy/grafana-provisioning/datasources"
scp "$SCRIPT_DIR/deploy/loki-config.yml" "$RPI_HOST:$DEPLOY_DIR/deploy/"
scp "$SCRIPT_DIR/deploy/alloy-config.alloy" "$RPI_HOST:$DEPLOY_DIR/deploy/"
rsync -a "$SCRIPT_DIR/deploy/grafana-provisioning/" "$RPI_HOST:$DEPLOY_DIR/deploy/grafana-provisioning/"

echo "==> Uploading Telegram bot..."
ssh "$RPI_HOST" "mkdir -p $DEPLOY_DIR/telegram-bot"
rsync -a "$SCRIPT_DIR/telegram-bot/" "$RPI_HOST:$DEPLOY_DIR/telegram-bot/"
scp "$SCRIPT_DIR/deploy/telegram-bot.service" "$RPI_HOST:$DEPLOY_DIR/"


# ── 3. Remote setup ───────────────────────────────────────────────────────────

echo "==> Configuring services on RPi..."
ssh "$RPI_HOST" bash << REMOTE
set -euo pipefail

# DB + IB Gateway
cd $DEPLOY_DIR
docker compose --env-file .env.rpi up -d db ib-gateway loki grafana alloy

# options-engine systemd service
sudo cp $DEPLOY_DIR/options-engine.service /etc/systemd/system/options-engine.service

# telegram bot: create venv + install deps
python3 -m venv $DEPLOY_DIR/telegram-bot/venv
$DEPLOY_DIR/telegram-bot/venv/bin/pip install -q -r $DEPLOY_DIR/telegram-bot/requirements.txt

# telegram-bot systemd service
sudo cp $DEPLOY_DIR/telegram-bot.service /etc/systemd/system/telegram-bot.service
sudo systemctl daemon-reload
sudo systemctl enable options-engine telegram-bot
sudo systemctl restart options-engine telegram-bot

# nginx
sudo mkdir -p /etc/nginx/sites-available /etc/nginx/sites-enabled
sudo cp $DEPLOY_DIR/options.nginx.conf /etc/nginx/sites-available/options
sudo ln -sf /etc/nginx/sites-available/options /etc/nginx/sites-enabled/options
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx

echo "Services status:"
sudo systemctl is-active options-engine && echo "  options-engine: running" || echo "  options-engine: FAILED"
sudo systemctl is-active telegram-bot   && echo "  telegram-bot:   running" || echo "  telegram-bot:   FAILED"
sudo systemctl is-active nginx          && echo "  nginx:          running" || echo "  nginx:          FAILED"
docker compose --env-file $DEPLOY_DIR/.env.rpi ps --format "table {{.Name}}\t{{.Status}}" 2>/dev/null || true
REMOTE

echo ""
echo "Deploy complete."
echo "  Frontend + API: http://$(echo $RPI_HOST | cut -d@ -f2)"
echo "  Grafana:        http://$(echo $RPI_HOST | cut -d@ -f2)/grafana"
echo "  App logs:       ssh $RPI_HOST 'journalctl -fu options-engine'"
echo "  Bot logs:       ssh $RPI_HOST 'journalctl -fu telegram-bot'"
