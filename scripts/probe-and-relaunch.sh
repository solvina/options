#!/usr/bin/env bash
# Probes IBKR historical data every 15 min (known-good AAPL 5min refetch of last Thu/Fri).
# When bars flow again, relaunches the full universe download and exits.
# Author: vseliga
set -u
BASE="http://localhost:8081/options"
while true; do
  job=$(curl -sf -X POST "$BASE/historical/fetch" -H 'Content-Type: application/json' \
    -d '{"symbols":["AAPL"],"from":"2026-07-16","to":"2026-07-17","timeframe":"5min","ensure":false}') || {
      echo "$(date '+%F %T') probe submit failed"; sleep 900; continue; }
  id=$(jq -r .id <<<"$job")
  sleep 90
  bars=$(curl -sf "$BASE/historical/fetch/jobs/$id" | jq -r '.barsWritten // 0')
  if [ "${bars:-0}" -gt 0 ]; then
    echo "$(date '+%F %T') HMDS BACK: probe wrote $bars bars — relaunching universe download"
    cd ~/options
    BASE_URL="$BASE" nohup ./scripts/download-universe-history.sh >> download-universe.log 2>&1 &
    echo "$(date '+%F %T') relaunched downloader pid $!"
    exit 0
  fi
  echo "$(date '+%F %T') probe: historical data still silent"
  sleep 900
done
