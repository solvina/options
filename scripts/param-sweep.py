#!/usr/bin/env python3
"""Parameter sweep over the stock backtest (/backtest/stock) driven by a JSON config.

Every field of the backtest request can be fixed (under "request") or swept (under "sweep");
the sweep takes the cartesian product over all swept params, runs them with N parallel
requests against a backtest-profile engine, and writes one CSV row per combination.

Config example (sweep any request fields, not just these):

    {
      "name": "aapl-sl-tp",                  // optional; also the default output dir name
      "outputDir": "sweeps/aapl-sl-tp",      // optional; default sweeps/<name or timestamp>
      "baseUrl": "http://localhost:8082/options",
      "jobs": 10,
      "request": {                            // fixed request fields
        "symbols": ["AAPL", "MSFT"],
        "from": "2015-01-01",
        "to": "2026-07-17",
        "timeframe": "1d",
        "initialCapital": 20000,
        "maxOpenPositions": 3
      },
      "sweep": {                              // range spec or explicit value list per param
        "stopLossPct":  {"min": 2, "max": 10, "step": 0.1},
        "targetPct":    {"min": 3, "max": 10, "step": 0.1},
        "rsiOversold":  [30, 40, 50]
      }
    }

Outputs in the run directory:
    config.json   copy of the config (provenance)
    results.csv   one row per combo: swept params + summary metrics
    failures.csv  combos that errored (rerun the sweep to retry just these)

Re-running with the same output dir RESUMES: combos already present in results.csv are skipped.

The grid is never held in memory: the combo count is computed arithmetically (instant even for
billions), combos stream lazily from the generator, and only ~2x jobs requests are in flight at
once — so oversized grids fail by taking too long, never by exhausting RAM. Combos made redundant
by the engine's ATR-override semantics (see ATR_OVERRIDES) are pruned from the stream.

Usage:
    ./scripts/param-sweep.py sweep-config.json
    ./scripts/param-sweep.py sweep-config.json --dry-run    # just print the combo count

Author: vseliga
"""

import argparse
import csv
import itertools
import json
import math
import shutil
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path

METRICS = [
    "tradeCount", "winCount", "lossCount", "eodCount", "winRate",
    "totalPnl", "totalPnlPct", "profitFactor", "maxDrawdownPct",
    "annualizedReturnPct", "avgRMultiple", "avgWinR", "avgLossR",
    "finalCapital", "buyHoldPnlPct", "buyHoldAnnualizedPct",
]
REQUEST_TIMEOUT_S = 900
RETRIES = 2

# Engine semantics: an ATR-multiple exit overrides the corresponding percent exit, so when the
# ATR param is > 0 every swept value of the percent param yields a byte-identical backtest.
# Such combos are pruned from the grid (only the percent param's first value is kept).
ATR_OVERRIDES = {"stopAtrMultiple": "stopLossPct", "targetAtrMultiple": "targetPct"}


def expand(spec) -> list:
    """A sweep spec is either an explicit list or {"min","max","step"} (inclusive, Decimal-exact)."""
    if isinstance(spec, list):
        return spec
    if isinstance(spec, dict) and {"min", "max", "step"} <= spec.keys():
        lo, hi, step = (Decimal(str(spec[k])) for k in ("min", "max", "step"))
        if step <= 0:
            sys.exit(f"sweep step must be > 0: {spec}")
        vals, v = [], lo
        while v <= hi:
            vals.append(float(v) if v != v.to_integral_value() else int(v))
            v += step
        return vals
    sys.exit(f"Unsupported sweep spec (want list or min/max/step object): {spec}")


def cell(value) -> str:
    if value is None:
        return ""
    return str(value)


def run_one(base_url: str, payload: dict) -> dict:
    req = urllib.request.Request(
        f"{base_url}/backtest/stock",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    last_err = None
    for _ in range(RETRIES + 1):
        try:
            with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_S) as resp:
                return json.load(resp)["summary"]
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, json.JSONDecodeError) as e:
            last_err = e
            time.sleep(1)
    raise RuntimeError(str(last_err))


def main() -> None:
    ap = argparse.ArgumentParser(description="Backtest parameter sweep (JSON-config driven)")
    ap.add_argument("config", help="path to the sweep config JSON")
    ap.add_argument("--dry-run", action="store_true", help="print combo count and exit")
    args = ap.parse_args()

    cfg_path = Path(args.config)
    cfg = json.loads(cfg_path.read_text())
    base_url = cfg.get("baseUrl", "http://localhost:8082/options")
    jobs = int(cfg.get("jobs", 10))
    request: dict = cfg.get("request") or sys.exit('config needs a "request" object')
    sweep: dict = cfg.get("sweep") or sys.exit('config needs a non-empty "sweep" object')
    if "symbols" not in request:
        sys.exit('request.symbols is required')

    # The grid is never materialized: counts are computed arithmetically and combos stream
    # lazily through a bounded window of in-flight requests, so grid size is limited by
    # patience, not memory.
    sweep_params = list(sweep.keys())
    value_lists = [expand(sweep[p]) for p in sweep_params]
    values_by_param = dict(zip(sweep_params, value_lists))
    total = math.prod(len(v) for v in value_lists)
    print(f"Sweep over {sweep_params}: {' x '.join(str(len(v)) for v in value_lists)} = {total:,} combos")

    def positive(v) -> bool:
        return isinstance(v, (int, float)) and v > 0

    # Redundancy check by tuple index (runs once per generated combo — keep it dict-free).
    idx = {p: i for i, p in enumerate(sweep_params)}
    checks = [
        (idx.get(atr_p), request.get(atr_p, 0), idx[pct_p], values_by_param[pct_p][0])
        for atr_p, pct_p in ATR_OVERRIDES.items()
        if pct_p in idx
    ]

    def is_redundant(combo) -> bool:
        for atr_i, atr_fixed, pct_i, pct_first in checks:
            atr_v = combo[atr_i] if atr_i is not None else atr_fixed
            if positive(atr_v) and combo[pct_i] != pct_first:
                return True
        return False

    # Exact survivor count without walking the grid: the override pairs touch disjoint axes,
    # so the grid factorizes into (pair subgrid) x (pair subgrid) x (remaining axes).
    consumed, factor = set(), 1
    for atr_p, pct_p in ATR_OVERRIDES.items():
        if pct_p not in values_by_param:
            continue
        n_pct = len(values_by_param[pct_p])
        if atr_p in values_by_param:
            pos = sum(1 for v in values_by_param[atr_p] if positive(v))
            factor *= pos + (len(values_by_param[atr_p]) - pos) * n_pct
            consumed |= {atr_p, pct_p}
        else:
            factor *= 1 if positive(request.get(atr_p, 0)) else n_pct
            consumed.add(pct_p)
    survivors = factor * math.prod(len(v) for p, v in values_by_param.items() if p not in consumed)
    if survivors < total:
        print(f"{total - survivors:,} combos are redundant (an ATR multiple > 0 overrides the swept "
              f"percent exit) -> {survivors:,} to run")
    if args.dry_run:
        return

    name = cfg.get("name") or datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = Path(cfg.get("outputDir") or Path("sweeps") / name)
    out_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy(cfg_path, out_dir / "config.json")
    results_path = out_dir / "results.csv"
    failures_path = out_dir / "failures.csv"

    # Resume: skip combos whose swept-param tuple is already in results.csv
    done_keys = set()
    if results_path.exists():
        with results_path.open() as f:
            for row in csv.DictReader(f):
                done_keys.add(tuple(row.get(p, "") for p in sweep_params))
    est_todo = max(survivors - len(done_keys), 0)
    if done_keys:
        print(f"Resume: {len(done_keys):,} combos already in {results_path}, ~{est_todo:,} to go")

    header = sweep_params + METRICS
    write_header = not results_path.exists()
    results_f = results_path.open("a", newline="")
    results = csv.writer(results_f)
    if write_header:
        results.writerow(header)
    failures_f = failures_path.open("a", newline="")
    failures = csv.writer(failures_f)
    if failures_path.stat().st_size == 0:
        failures.writerow(sweep_params + ["error"])

    done = failed = 0
    start = time.time()

    def run_combo(combo):
        payload = {**request, **dict(zip(sweep_params, combo))}
        return run_one(base_url, payload)

    def progress():
        elapsed = time.time() - start
        eta = timedelta(seconds=int(elapsed / done * max(est_todo - done, 0))) if done else "?"
        print(f"  {done:,}/~{est_todo:,} ({failed} failed) elapsed {timedelta(seconds=int(elapsed))} eta {eta}", flush=True)
        results_f.flush()
        failures_f.flush()

    # Lazy pipeline: generate -> prune -> skip already-done -> keep at most 2*jobs requests
    # in flight. Completed rows are written from this (main) thread only.
    stream = (
        c
        for c in itertools.product(*value_lists)
        if not is_redundant(c) and tuple(cell(v) for v in c) not in done_keys
    )

    with ThreadPoolExecutor(max_workers=jobs) as pool:
        in_flight = {}

        def top_up():
            for c in itertools.islice(stream, max(jobs * 2 - len(in_flight), 0)):
                in_flight[pool.submit(run_combo, c)] = c

        top_up()
        while in_flight:
            finished, _ = wait(in_flight, return_when=FIRST_COMPLETED)
            for fut in finished:
                combo = in_flight.pop(fut)
                try:
                    summary = fut.result()
                    results.writerow([cell(v) for v in combo] + [cell(summary.get(m)) for m in METRICS])
                except Exception as e:
                    failed += 1
                    failures.writerow([cell(v) for v in combo] + [str(e)])
                done += 1
                if done % 25 == 0:
                    progress()
            top_up()

    if done:
        progress()
    else:
        print("Nothing to do.")
    results_f.close()
    failures_f.close()

    with results_path.open() as f:
        rows = list(csv.DictReader(f))
    rows.sort(key=lambda r: float(r["totalPnlPct"] or 0), reverse=True)
    print(f"\nDone: {len(rows)} rows in {results_path} ({failed} failures -> {failures_path.name})")
    print("Top 5 by totalPnlPct:")
    top_cols = sweep_params + ["tradeCount", "winRate", "totalPnlPct", "profitFactor", "maxDrawdownPct"]
    print("  " + " | ".join(top_cols))
    for r in rows[:5]:
        print("  " + " | ".join(str(r.get(c, "")) for c in top_cols))


if __name__ == "__main__":
    main()
