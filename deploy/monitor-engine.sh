#!/bin/bash
# Engine watchdog — run by options-engine-monitor.timer every 5 minutes.
#
# 2026-10-28 rewrite. The old script probed /health/ibkr with a 5 s timeout and kill -9'd the engine
# on any miss. That killed every booting engine (IBKR isn't connected yet in the first minute), and
# when IB Gateway itself was hung (2026-10-28: Gateway Java heap OutOfMemoryError; 2026-07-31:
# handshake never answered) it killed the engine 24 times in two hours without fixing anything.
#
# Now:
#   1. engine started < BOOT_GRACE_S ago      → skip (still booting)
#   2. /actuator/health answers               → healthy, done (liveness only — IBKR state is the
#                                               engine's own job and has its own alerts)
#   3. engine unresponsive, Gateway does NOT answer the API handshake
#                                             → Telegram alert, do NOT kill (a restart can't fix it;
#                                               recovering Gateway is a human decision)
#   4. engine unresponsive, Gateway answers   → engine hung on its own: alert + kill -9, systemd
#                                               Restart=on-failure brings it back
#
# DRY_RUN=1 logs/alerts what it would do without killing. Alerts repeat at most every ALERT_REPEAT_S.

set -u
ENGINE_URL="${ENGINE_URL:-http://127.0.0.1:8081/options/actuator/health}"
GATEWAY_HOST="${GATEWAY_HOST:-127.0.0.1}"
GATEWAY_PORT="${GATEWAY_PORT:-7497}"
BOOT_GRACE_S="${BOOT_GRACE_S:-300}"
ALERT_REPEAT_S="${ALERT_REPEAT_S:-3600}"
DRY_RUN="${DRY_RUN:-0}"
LOG_FILE="${LOG_FILE:-/home/solvina/options/engine-monitor.log}"
STATE_DIR="${STATE_DIR:-/home/solvina/options/.engine-monitor}"
ENV_FILE="${ENV_FILE:-/home/solvina/options/engine.env}"

mkdir -p "$STATE_DIR"
log() { echo "[$(date +'%Y-%m-%d %H:%M:%S')] $*" >> "$LOG_FILE"; }

alert() { # $1 = dedupe key, $2 = message
    local stamp="$STATE_DIR/alert-$1" now
    now=$(date +%s)
    if [ -f "$stamp" ] && [ $((now - $(cat "$stamp"))) -lt "$ALERT_REPEAT_S" ]; then
        log "alert '$1' suppressed (sent < ${ALERT_REPEAT_S}s ago)"
        return
    fi
    echo "$now" > "$stamp"
    [ -r "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a
    if [ -z "${TELEGRAM_BOT_TOKEN:-}" ] || [ -z "${TELEGRAM_CHAT_ID:-}" ]; then
        log "alert '$1' NOT sent: Telegram credentials missing — $2"
        return
    fi
    local args=(--data-urlencode "chat_id=$TELEGRAM_CHAT_ID" --data-urlencode "text=$2")
    [ -n "${TELEGRAM_ALERTS_TOPIC_ID:-}" ] && args+=(--data-urlencode "message_thread_id=$TELEGRAM_ALERTS_TOPIC_ID")
    # Never log the URL: it contains the bot token.
    if curl -sf --max-time 15 "${args[@]}" "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" > /dev/null; then
        log "alert '$1' sent"
    else
        log "alert '$1' FAILED to send"
    fi
}

clear_alerts() { rm -f "$STATE_DIR"/alert-* 2>/dev/null; }

gateway_answers() { # IBKR API handshake; TCP accept alone proves nothing (the 07-31 wedge accepted TCP)
    python3 - "$GATEWAY_HOST" "$GATEWAY_PORT" <<'PY'
import socket, struct, sys
try:
    s = socket.create_connection((sys.argv[1], int(sys.argv[2])), timeout=5)
    v = b"v100..176"
    s.sendall(b"API\0" + struct.pack("!I", len(v)) + v)
    s.settimeout(8)
    sys.exit(0 if s.recv(64) else 1)
except Exception:
    sys.exit(1)
PY
}

# 1. Not running at all → systemd's Restart=on-failure owns that; a stopped unit is deliberate.
if ! systemctl is-active --quiet options-engine.service; then
    log "engine unit not active ($(systemctl is-active options-engine.service)) — leaving it to systemd"
    exit 0
fi

# 2. Boot grace.
started_us=$(systemctl show options-engine.service -p ActiveEnterTimestampMonotonic --value)
now_us=$(awk '{printf "%d", $1 * 1000000}' /proc/uptime)
up_s=$(( (now_us - started_us) / 1000000 ))
if [ "$up_s" -lt "$BOOT_GRACE_S" ]; then
    log "engine up ${up_s}s < ${BOOT_GRACE_S}s boot grace — skipping"
    exit 0
fi

# 3. Liveness.
for attempt in 1 2 3; do
    if curl -sf --max-time 10 "$ENGINE_URL" > /dev/null 2>&1; then
        [ -n "$(ls "$STATE_DIR"/alert-* 2>/dev/null)" ] && log "engine healthy again — clearing alert state"
        clear_alerts
        log "✓ engine responding (up ${up_s}s)"
        exit 0
    fi
    [ "$attempt" -lt 3 ] && sleep 5
done

# 4. Engine unresponsive — is it the Gateway?
if ! gateway_answers; then
    oom=""
    docker logs --since 6h options-ib-gateway-1 2>&1 | grep -q OutOfMemoryError && oom=" Gateway log shows OutOfMemoryError."
    log "✗ engine unresponsive AND IB Gateway not answering the API handshake.$oom Not killing the engine."
    alert gateway "⚠️ IB Gateway is not answering the API handshake — the engine can't reach IBKR (no market data, no order handling).$oom Restarting the engine won't help; Gateway needs attention (docker restart options-ib-gateway-1)."
    exit 1
fi

log "✗ engine unresponsive for 3 checks while Gateway answers — engine hung on its own"
alert engine "⚠️ Engine stopped responding (Gateway is fine). Watchdog is killing it; systemd will restart it."
if [ "$DRY_RUN" = "1" ]; then
    log "DRY_RUN — would kill -9 the engine"
    exit 1
fi
pkill -9 -f "/home/solvina/options/engine.jar" && log "engine killed; systemd Restart=on-failure takes over"
exit 1
