#!/usr/bin/env bash
# Download full historical bar coverage for the whole universe into InfluxDB,
# via the engine's /historical/fetch endpoint (ensure mode: idempotent, resumable —
# rerunning the script only fetches what's still missing).
#
# Usage:
#   ./scripts/download-universe-history.sh                       # 1d from 1999 + 4h from 2004, all universe symbols
#   BASE_URL=http://100.65.216.36:8081/options ./scripts/download-universe-history.sh   # against the RPi
#   ./scripts/download-universe-history.sh --enabled-only        # only enabled instruments
#   ./scripts/download-universe-history.sh --timeframes 1d       # daily only
#   ./scripts/download-universe-history.sh --symbols AAPL,MSFT   # explicit symbols instead of universe
#
# Requires: curl, jq. The engine must be running and connected to IBKR.
#
# Author: vseliga

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081/options}"
FROM_1D="${FROM_1D:-1999-01-01}"
FROM_4H="${FROM_4H:-2004-01-01}"   # IBKR intraday history rarely reaches further back
TO="${TO:-$(date +%F)}"
POLL_SECONDS="${POLL_SECONDS:-5}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-3600}"  # per symbol+timeframe job

TIMEFRAMES="1d,4h"
ENABLED_ONLY=false
SYMBOLS=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --enabled-only) ENABLED_ONLY=true; shift ;;
    --timeframes)   TIMEFRAMES="$2"; shift 2 ;;
    --symbols)      SYMBOLS="$2"; shift 2 ;;
    -h|--help)      grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

from_for() {
  case "$1" in
    1d) echo "$FROM_1D" ;;
    4h) echo "$FROM_4H" ;;
    *)  echo "$FROM_4H" ;;
  esac
}

if [[ -z "$SYMBOLS" ]]; then
  if $ENABLED_ONLY; then
    SYMBOLS=$(curl -sf "$BASE_URL/universe" | jq -r '.[] | select(.enabled) | .symbol' | sort)
  else
    SYMBOLS=$(curl -sf "$BASE_URL/universe" | jq -r '.[].symbol' | sort)
  fi
else
  SYMBOLS=$(tr ',' '\n' <<< "$SYMBOLS")
fi

if [[ -z "$SYMBOLS" ]]; then
  echo "No symbols resolved — is the engine up at $BASE_URL ?" >&2
  exit 1
fi

symbol_count=$(wc -l <<< "$SYMBOLS")
echo "Universe: $symbol_count symbols | timeframes: $TIMEFRAMES | to: $TO"
echo "Ranges: 1d from $FROM_1D, 4h from $FROM_4H"
echo

total_bars=0
failures=()

for tf in ${TIMEFRAMES//,/ }; do
  from=$(from_for "$tf")
  echo "=== Timeframe $tf ($from .. $TO) ==="
  for symbol in $SYMBOLS; do
    printf '%-8s %-4s ' "$symbol" "$tf"

    job=$(curl -sf -X POST "$BASE_URL/historical/fetch" \
      -H 'Content-Type: application/json' \
      -d "{\"symbols\":[\"$symbol\"],\"from\":\"$from\",\"to\":\"$TO\",\"timeframe\":\"$tf\",\"ensure\":true}") || {
        echo "SUBMIT FAILED"
        failures+=("$symbol/$tf: submit failed")
        continue
      }
    job_id=$(jq -r '.id' <<< "$job")

    waited=0
    status="RUNNING"
    while [[ "$status" == "RUNNING" && $waited -lt $MAX_WAIT_SECONDS ]]; do
      sleep "$POLL_SECONDS"
      waited=$((waited + POLL_SECONDS))
      job=$(curl -sf "$BASE_URL/historical/fetch/jobs/$job_id" || echo '{"status":"RUNNING"}')
      status=$(jq -r '.status' <<< "$job")
    done

    bars=$(jq -r '.barsWritten // 0' <<< "$job")
    error=$(jq -r '.error // empty' <<< "$job")
    total_bars=$((total_bars + bars))

    case "$status" in
      DONE)
        if [[ -n "$error" ]]; then
          echo "DONE with errors: $bars bars ($error)"
          failures+=("$symbol/$tf: partial — $error")
        else
          echo "DONE: $bars bars (${waited}s)"
        fi
        ;;
      FAILED)
        echo "FAILED: $error"
        failures+=("$symbol/$tf: $error")
        ;;
      *)
        echo "TIMEOUT after ${MAX_WAIT_SECONDS}s (job $job_id still running — data keeps downloading server-side)"
        failures+=("$symbol/$tf: client-side timeout, check job $job_id")
        ;;
    esac
  done
  echo
done

echo "=== Summary ==="
echo "Total bars written: $total_bars"
if [[ ${#failures[@]} -gt 0 ]]; then
  echo "Issues (${#failures[@]}):"
  printf '  %s\n' "${failures[@]}"
  exit 1
fi
echo "All symbols covered. Rerun any time — already-covered ranges are skipped."
