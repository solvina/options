#!/usr/bin/env python3
"""
Applies the one-position-per-title rule (v42) to shadow_spreads rows recorded before it.

Walks each title by opened_at. A row stays counted unless, when it opened, an earlier counted
would-be position on that title or a real spread on that title was still open. Nothing is deleted:
repeats get counted = false. Idempotent: it recomputes the flag on every row. SHADOW_DB picks the DB.
"""
import os, subprocess

DB = ["docker", "exec", "-i", "options-db-1", "psql", "-U", "options_user", "-d",
      os.environ.get("SHADOW_DB", "options"), "-At", "-F", "|"]

def psql(sql):
    out = subprocess.run(DB + ["-c", sql], capture_output=True, text=True, check=True).stdout
    return [l.split("|") for l in out.splitlines() if l]

FILLED = "('OPEN','CLOSING','CLOSED_PROFIT','CLOSED_STOP','CLOSED_TIME','CLOSED_MANUAL')"
real = {}
for t in ("bull_put_spreads", "bear_call_spreads"):
    for sym, opened, closed, status in psql(
        f"select symbol, extract(epoch from opened_at), coalesce(extract(epoch from closed_at)::text, ''), status "
        f"from {t} where status in {FILLED} and opened_at >= '2026-08-01'"):
        if closed or status in ("OPEN", "CLOSING"):
            real.setdefault(sym, []).append((float(opened), float(closed) if closed else float("inf")))

rows = psql("select id, symbol, extract(epoch from opened_at), coalesce(extract(epoch from closed_at)::text, '') "
            "from shadow_spreads order by opened_at")
busy, repeats = {}, []
for rid, sym, opened, closed in rows:
    o = float(opened)
    in_real = any(a <= o < b for a, b in real.get(sym, []))
    if busy.get(sym, -1) > o or in_real:
        repeats.append(rid)
        continue
    busy[sym] = float(closed) if closed else float("inf")

psql("update shadow_spreads set counted = true")
if repeats:
    psql("update shadow_spreads set counted = false where id in (" + ",".join(f"'{r}'" for r in repeats) + ")")
print(f"rows {len(rows)}, counted {len(rows) - len(repeats)}, repeats {len(repeats)}")
