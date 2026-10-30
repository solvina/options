#!/usr/bin/env python3
"""
One-off back-fill of shadow_spreads (v41) from the unfilled spread attempts since 2026-08-01.

APPROXIMATE by construction — rows are written with backfilled = true and shown separately:
  * entry "mid" = the credit of the first attempt on each (strategy, symbol, strikes, expiry);
    the engine always starts its ladder at mid.
  * one volatility per spread is solved so that Black-Scholes reproduces that credit at entry,
    then held constant; daily closes (InfluxDB 1d candles) drive the spread value afterwards.
  * exits follow the live rules at daily granularity: underlying stop at the short strike (close
    basis), take-profit at 50% of the credit, time exit at <= 21 DTE, intrinsic at expiry.
  * spreads not yet resolved stay OPEN; the engine's daily job revalues them from real quotes.
Idempotent: skips any (strategy, symbol, strikes, expiry) already present in shadow_spreads.
"""
import math, os, subprocess, uuid, datetime as dt

TP, TIME_DTE, R = 0.50, 21, 0.04
DB = ["docker", "exec", "-i", "options-db-1", "psql", "-U", "options_user", "-d", os.environ.get("SHADOW_DB", "options"), "-At", "-F", "|"]

def psql(sql):
    return [l.split("|") for l in subprocess.run(DB + ["-c", sql], capture_output=True, text=True, check=True).stdout.splitlines() if l]

def cnd(x): return 0.5 * (1 + math.erf(x / math.sqrt(2)))
def bs(kind, S, K, t, s):
    if t <= 0: return max(K - S, 0) if kind == "PUT" else max(S - K, 0)
    d1 = (math.log(S / K) + (R + s * s / 2) * t) / (s * math.sqrt(t)); d2 = d1 - s * math.sqrt(t)
    if kind == "PUT": return K * math.exp(-R * t) * cnd(-d2) - S * cnd(-d1)
    return S * cnd(d1) - K * math.exp(-R * t) * cnd(d2)
def spread(kind, S, ks, kb, t, s): return bs(kind, S, ks, t, s) - bs(kind, S, kb, t, s)
def solve_vol(kind, S, ks, kb, t, credit):
    lo, hi = 0.01, 3.0
    f = lambda s: spread(kind, S, ks, kb, t, s) - credit
    if f(lo) * f(hi) > 0: return None
    for _ in range(100):
        mid = (lo + hi) / 2
        if f(lo) * f(mid) <= 0: hi = mid
        else: lo = mid
    return (lo + hi) / 2

def closes(symbol):
    q = ('from(bucket:"market_data") |> range(start: 2026-07-25T00:00:00Z) |> filter(fn:(r)=> r._measurement=="candle" '
         f'and r._field=="close" and r.interval=="1d" and r.symbol=="{symbol}") |> keep(columns:["_time","_value"])')
    out = subprocess.run(["docker", "exec", "options-influxdb-1", "influx", "query", "--org", "options",
                          "--token", "options_token_changeme", "--raw", q], capture_output=True, text=True).stdout
    px = {}
    for l in out.splitlines():
        p = l.split(",")
        if len(p) >= 5 and p[3][:4] == "2026":
            px[dt.date.fromisoformat(p[3][:10])] = float(p[4])
    return px

existing = {tuple(r) for r in psql("select strategy_id, symbol, sold_strike::numeric(10,2)::text, bought_strike::numeric(10,2)::text, expiry::text from shadow_spreads")}
attempts = psql("""
  with x as (
    select 'BULL_PUT' s, 'PUT' k, symbol, sold_strike, bought_strike, expiry_date, opened_at, credit_per_share, underlying_price_at_entry, iv_rank_at_entry
      from bull_put_spreads where opened_at >= '2026-08-01' and status = 'CLOSED_TIMEOUT'
    union all
    select 'BEAR_CALL', 'CALL', symbol, sold_strike, bought_strike, expiry_date, opened_at, credit_per_share, underlying_price_at_entry, iv_rank_at_entry
      from bear_call_spreads where opened_at >= '2026-08-01' and status = 'CLOSED_TIMEOUT')
  select distinct on (s, symbol, sold_strike, bought_strike, expiry_date)
         s, k, symbol, sold_strike::numeric(10,2)::text, bought_strike::numeric(10,2)::text, expiry_date::text, opened_at::text,
         credit_per_share, underlying_price_at_entry, coalesce(iv_rank_at_entry::text, '')
    from x order by s, symbol, sold_strike, bought_strike, expiry_date, opened_at""")

today = dt.date.today(); cache = {}; inserted = 0; skipped = []
for strat, kind, sym, ks, kb, exp, opened, credit, und, ivr in attempts:
    if (strat, sym, ks, kb, exp) in existing: continue
    ks_f, kb_f, credit_f, und_f = float(ks), float(kb), float(credit), float(und)
    expiry = dt.date.fromisoformat(exp); entry_day = dt.date.fromisoformat(opened[:10])
    px = cache.setdefault(sym, closes(sym))
    vol = solve_vol(kind, und_f, ks_f, kb_f, max((expiry - entry_day).days, 1) / 365, credit_f)
    if vol is None or not px:
        skipped.append(f"{sym} {ks}/{kb} {exp}"); continue
    status, reason, closed_on, exit_v, last_v, last_s = "OPEN", None, None, None, None, None
    for day in sorted(d for d in px if entry_day < d <= min(expiry, today)):
        S = px[day]; dte = (expiry - day).days
        v = max(0.0, min(abs(ks_f - kb_f), spread(kind, S, ks_f, kb_f, dte / 365, vol)))
        last_v, last_s = v, S
        breached = S <= ks_f if kind == "PUT" else S >= ks_f
        if dte <= 0: status, reason = "EXPIRED", f"settled at expiry, close ${S:.2f}"
        elif breached: status, reason = "CLOSED_STOP", f"close ${S:.2f} through short strike {ks}"
        elif v <= credit_f * (1 - TP): status, reason = "CLOSED_PROFIT", f"model value ${v:.2f} <= TP"
        elif dte <= TIME_DTE: status, reason = "CLOSED_TIME", f"DTE {dte} <= {TIME_DTE}"
        else: continue
        closed_on, exit_v = day, v; break
    def q(x): return "null" if x is None else f"'{x}'"
    sql = f"""insert into shadow_spreads (id, strategy_id, symbol, option_type, expiry, sold_strike, bought_strike, opened_at,
      source_outcome, mid_credit, underlying_at_entry, iv_rank_at_entry, status, close_reason, closed_at, exit_value,
      last_value, last_underlying, last_valued_at, backfilled) values ('{uuid.uuid4()}', '{strat}', '{sym}', '{kind}', '{exp}',
      {ks}, {kb}, '{opened}', 'BACKFILL', {credit_f}, {und_f}, {ivr or 'null'}, '{status}', {q(reason)},
      {q(f"{closed_on} 20:00:00+00") if closed_on else 'null'}, {q(round(exit_v, 4)) if exit_v is not None else 'null'},
      {q(round(last_v, 4)) if last_v is not None else 'null'}, {q(round(last_s, 2)) if last_s is not None else 'null'},
      {q(f"{(closed_on or today)} 20:00:00+00")}, true)"""
    psql(sql); inserted += 1
print(f"inserted {inserted}, skipped {len(skipped)} {skipped}")
