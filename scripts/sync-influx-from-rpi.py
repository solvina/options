#!/usr/bin/env python3
"""Sync the market_data bucket (measurement `candle`) from the RPi master InfluxDB
into the local InfluxDB, so a dedicated backtest instance can run off local data.

The RPi's InfluxDB is bound to 127.0.0.1, so the script opens its own ssh tunnel.
Writes are idempotent (same series + timestamp overwrites), so re-running is always safe.
Default is a FULL sync (historical backfills on the master write OLD timestamps, which a
"since last sync" watermark would miss); use --since for a quick top-up when you know
only fresh bars were added.

Usage:
  ./scripts/sync-influx-from-rpi.py                       # full sync of 1d,4h,5min
  ./scripts/sync-influx-from-rpi.py --intervals 1d,4h     # only these timeframes
  ./scripts/sync-influx-from-rpi.py --since 2026-07-01    # incremental top-up
  RPI_HOST=solvina@100.65.216.36 ./scripts/sync-influx-from-rpi.py

Author: vseliga
"""

import argparse
import csv
import io
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

RPI_HOST = os.environ.get("RPI_HOST", "solvina@192.168.0.107")
TUNNEL_PORT = int(os.environ.get("TUNNEL_PORT", "8087"))
LOCAL_URL = os.environ.get("LOCAL_INFLUX_URL", "http://localhost:8086")
LOCAL_TOKEN = os.environ.get("LOCAL_INFLUX_TOKEN", "options_token_changeme")
ORG = "options"
BUCKET = "market_data"
MEASUREMENT = "candle"
BATCH_LINES = 5000


def open_tunnel() -> subprocess.Popen:
    proc = subprocess.Popen(
        ["ssh", "-N", "-o", "BatchMode=yes", "-o", "ExitOnForwardFailure=yes",
         "-L", f"127.0.0.1:{TUNNEL_PORT}:127.0.0.1:8086", RPI_HOST],
    )
    for _ in range(50):
        if proc.poll() is not None:
            sys.exit(f"ssh tunnel to {RPI_HOST} died on startup (exit {proc.returncode})")
        try:
            with socket.create_connection(("127.0.0.1", TUNNEL_PORT), timeout=0.5):
                return proc
        except OSError:
            time.sleep(0.2)
    proc.terminate()
    sys.exit("ssh tunnel did not come up within 10s")


def remote_token() -> str:
    # Both instances currently use the application.yml default token; override if that changes.
    return os.environ.get("RPI_INFLUX_TOKEN", "options_token_changeme")


def influx_query(base_url: str, token: str, flux: str) -> str:
    req = urllib.request.Request(
        f"{base_url}/api/v2/query?org={urllib.parse.quote(ORG)}",
        data=flux.encode(),
        headers={
            "Authorization": f"Token {token}",
            "Content-Type": "application/vnd.flux",
            "Accept": "application/csv",
        },
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        return resp.read().decode()


def influx_write(lines: list[str]) -> None:
    if not lines:
        return
    req = urllib.request.Request(
        f"{LOCAL_URL}/api/v2/write?org={urllib.parse.quote(ORG)}&bucket={BUCKET}&precision=ns",
        data="\n".join(lines).encode(),
        headers={"Authorization": f"Token {LOCAL_TOKEN}"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        resp.read()


def rfc3339_to_ns(ts: str) -> int:
    # Influx CSV gives e.g. 2026-07-17T20:00:00Z (or with fraction)
    dt = datetime.fromisoformat(ts.replace("Z", "+00:00"))
    return int(dt.timestamp() * 1_000_000_000)


def sync_interval(rpi_token: str, interval: str, start: str, stop: str) -> int:
    """Sync one timeframe chunk; returns points written."""
    flux = f"""
from(bucket: "{BUCKET}")
  |> range(start: {start}, stop: {stop})
  |> filter(fn: (r) => r._measurement == "{MEASUREMENT}" and r.interval == "{interval}")
  |> pivot(rowKey: ["_time", "symbol"], columnKey: ["_field"], valueColumn: "_value")
  |> keep(columns: ["_time", "symbol", "open", "high", "low", "close", "volume"])
"""
    body = influx_query(f"http://127.0.0.1:{TUNNEL_PORT}", rpi_token, flux)
    written = 0
    batch: list[str] = []
    for row in csv.DictReader(io.StringIO(body)):
        t, sym = row.get("_time"), row.get("symbol")
        if not t or not sym:
            continue
        try:
            fields = ",".join(
                f"{f}={float(row[f])}" for f in ("open", "high", "low", "close") if row.get(f)
            )
            vol = f",volume={int(float(row['volume']))}i" if row.get("volume") else ""
            batch.append(f"{MEASUREMENT},symbol={sym},interval={interval} {fields}{vol} {rfc3339_to_ns(t)}")
        except (ValueError, KeyError):
            continue
        if len(batch) >= BATCH_LINES:
            influx_write(batch)
            written += len(batch)
            batch = []
    influx_write(batch)
    written += len(batch)
    return written


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--intervals", default="1d,4h,5min")
    # 1990, not 1999: IBKR delivers pre-1999 daily history for some symbols (JNJ reaches 1993),
    # and a 1999 default silently skipped those heads.
    ap.add_argument("--since", default="1990-01-01", help="sync from this date (default: everything)")
    args = ap.parse_args()

    print(f"Tunneling to {RPI_HOST} ...")
    tunnel = open_tunnel()
    try:
        token = remote_token()
        total = 0
        now_year = datetime.now(timezone.utc).year
        since_year = int(args.since[:4])
        for interval in args.intervals.split(","):
            for year in range(since_year, now_year + 1):
                start = f"{args.since}T00:00:00Z" if year == since_year else f"{year}-01-01T00:00:00Z"
                stop = f"{year + 1}-01-01T00:00:00Z"
                n = sync_interval(token, interval, start, stop)
                total += n
                if n:
                    print(f"  {interval} {year}: {n} points")
            print(f"{interval}: done")
        print(f"Synced {total} points total.")
    finally:
        tunnel.terminate()


if __name__ == "__main__":
    main()
