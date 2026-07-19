#!/usr/bin/env bash
# Configure InfluxDB Edge Data Replication: the RPi (master, where the engine writes bars)
# streams every write to the market_data bucket on this workstation's InfluxDB.
#
# Replication is push-based with a durable on-disk queue on the RPi: while this machine is
# off/asleep, writes queue up (up to MAX_QUEUE bytes) and drain on reconnect. Data older than
# the replication (or dropped after a long offline stretch) is backfilled with
# scripts/sync-influx-from-rpi.py — run that once after setting this up.
#
# Rerun this script whenever the workstation's LAN IP changes (it recreates the remote).
#
# Usage: ./scripts/setup-influx-replication.sh
#   RPI_HOST=solvina@100.65.216.36 ./scripts/setup-influx-replication.sh   # via Tailscale
#
# Author: vseliga

set -euo pipefail

RPI_HOST="${RPI_HOST:-solvina@192.168.0.107}"
TOKEN="${INFLUX_TOKEN:-options_token_changeme}"     # same default on both instances
REMOTE_NAME="backtest-box"
REPL_NAME="market-data-to-backtest"
MAX_QUEUE=$((2 * 1024 * 1024 * 1024))               # 2 GiB on-disk queue on the RPi

# Workstation IP as seen from the RPi's network
LOCAL_IP=$(ip -4 route get "${RPI_HOST##*@}" | grep -oP 'src \K[0-9.]+')
echo "Workstation InfluxDB for replication: http://$LOCAL_IP:8086"

local_org_id=$(curl -sf -H "Authorization: Token $TOKEN" http://localhost:8086/api/v2/orgs \
  | jq -r '.orgs[] | select(.name=="options") | .id')
local_bucket_id=$(curl -sf -H "Authorization: Token $TOKEN" "http://localhost:8086/api/v2/buckets?name=market_data" \
  | jq -r '.buckets[0].id')
echo "Local org=$local_org_id bucket=$local_bucket_id"

influx_rpi() {
  ssh -o BatchMode=yes "$RPI_HOST" "docker exec options-influxdb-1 influx $* --host http://localhost:8086 --token $TOKEN"
}

rpi_bucket_id=$(influx_rpi "bucket list --org options --name market_data --hide-headers" | awk '{print $1}')
echo "RPi bucket=$rpi_bucket_id"

# Recreate remote (idempotent: drop an existing one with the same name first)
existing_remote=$(influx_rpi "remote list --org options --name $REMOTE_NAME --hide-headers" 2>/dev/null | awk '{print $1}' || true)
if [[ -n "$existing_remote" ]]; then
  existing_repl=$(influx_rpi "replication list --org options --name $REPL_NAME --hide-headers" 2>/dev/null | awk '{print $1}' || true)
  [[ -n "$existing_repl" ]] && influx_rpi "replication delete --id $existing_repl" >/dev/null
  influx_rpi "remote delete --id $existing_remote" >/dev/null
  echo "Removed existing remote/replication"
fi

remote_id=$(influx_rpi "remote create --org options --name $REMOTE_NAME \
  --remote-url http://$LOCAL_IP:8086 --remote-api-token $TOKEN --remote-org-id $local_org_id --hide-headers" | awk '{print $1}')
echo "Remote created: $remote_id"

influx_rpi "replication create --org options --name $REPL_NAME --remote-id $remote_id \
  --local-bucket-id $rpi_bucket_id --remote-bucket-id $local_bucket_id --max-queue-bytes $MAX_QUEUE"

echo
echo "Replication active. Verify with:"
echo "  ssh $RPI_HOST 'docker exec options-influxdb-1 influx replication list --org options --token $TOKEN'"
echo "Backfill/catch-up remains: ./scripts/sync-influx-from-rpi.py"
