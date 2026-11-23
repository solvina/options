#!/usr/bin/env python3
"""
Reference AI reviewer for the options engine (engine/docs/AI_CANDIDATE_REVIEW_PLAN.md, D4).

A client of the engine's AI Review API (engine/openapi/cz.solvina.options.aireview.yaml), not
part of it. Long-running daemon: heartbeats every engine it knows, claims one task at a time,
runs ONE fresh `claude -p` per review, and posts a result or a typed failure. Replacing Claude
with another provider means another `review()` implementation, nothing in the engine.

Standard library + PyYAML only (both on the Pi's system Python).

    python3 ai_reviewer.py                    # daemon
    python3 ai_reviewer.py --dry-run FILE     # review one snapshot JSON, print the result
    python3 ai_reviewer.py --print-schema     # the JSON schema passed to claude
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo
from pathlib import Path

import yaml

REVIEWER_VERSION = "0.1.0"
CONTRACT_VERSION = "1"
SNAPSHOT_VERSIONS = [1]
PROMPT_VERSION = "v1"
HERE = Path(__file__).resolve().parent
NEW_YORK = ZoneInfo("America/New_York")

log = logging.getLogger("ai-reviewer")


# ── configuration ─────────────────────────────────────────────────────────────────────────────


@dataclass
class Engine:
    name: str
    base_url: str  # e.g. http://localhost:8081/options
    token: str


@dataclass
class Config:
    reviewer_id: str = "claude-opus"
    engines: list[Engine] = field(default_factory=list)
    claude_bin: str = "/home/solvina/.local/bin/claude"
    model: str = "opus"
    probe_model: str = "haiku"
    timeout_s: int = 600
    max_turns: int = 40
    poll_s: int = 30
    heartbeat_s: int = 30
    min_free_mb: int = 700
    workdir: Path = HERE / "work"
    prompt_file: Path = HERE / "prompt_v1.md"
    # Deployed copy next to the script, else the repo's spec.
    spec_file: Path = next(
        (p for p in (HERE / "cz.solvina.options.aireview.yaml", HERE.parent / "engine/openapi/cz.solvina.options.aireview.yaml") if p.exists()),
        HERE / "cz.solvina.options.aireview.yaml",
    )
    bot_service: str = "telegram-bot.service"

    @staticmethod
    def from_env(env: dict[str, str] = os.environ) -> "Config":
        """AI_REVIEW_ENGINES="paper=http://localhost:8081/options,live=http://host:8081/options".
        Token per engine: AI_REVIEW_API_TOKEN_<NAME>, falling back to AI_REVIEW_API_TOKEN."""
        c = Config()
        c.reviewer_id = env.get("AI_REVIEWER_ID", c.reviewer_id)
        for item in filter(None, (s.strip() for s in env.get("AI_REVIEW_ENGINES", "").split(","))):
            name, _, url = item.partition("=")
            token = env.get(f"AI_REVIEW_API_TOKEN_{name.upper()}", env.get("AI_REVIEW_API_TOKEN", ""))
            c.engines.append(Engine(name.strip(), url.strip().rstrip("/"), token))
        c.claude_bin = env.get("CLAUDE_BIN", c.claude_bin)
        c.model = env.get("AI_REVIEW_MODEL", c.model)
        c.timeout_s = int(env.get("AI_REVIEW_TIMEOUT_S", c.timeout_s))
        c.poll_s = int(env.get("AI_REVIEW_POLL_S", c.poll_s))
        c.min_free_mb = int(env.get("AI_REVIEW_MIN_FREE_MB", c.min_free_mb))
        if "AI_REVIEW_WORKDIR" in env:
            c.workdir = Path(env["AI_REVIEW_WORKDIR"])
        if "AI_REVIEW_SPEC" in env:
            c.spec_file = Path(env["AI_REVIEW_SPEC"])
        return c


# ── contract: result schema derived from the OpenAPI spec ─────────────────────────────────────

# What the model writes. The rest of ReviewResult (ids, lease, provenance, raw output) is ours.
MODEL_FIELDS = ["verdict", "confidence", "reason", "risks", "newsSummary", "preferredStrategy", "sources"]


def model_output_schema(spec_file: Path) -> dict:
    """The JSON schema handed to `claude --json-schema`, cut out of the API's own ReviewResult so
    the model's output and the API cannot drift apart."""
    schemas = yaml.safe_load(spec_file.read_text())["components"]["schemas"]

    def resolve(node):
        if isinstance(node, dict):
            if "$ref" in node:
                return resolve(schemas[node["$ref"].rsplit("/", 1)[1]])
            return {k: resolve(v) for k, v in node.items() if k != "format"}
        if isinstance(node, list):
            return [resolve(v) for v in node]
        return node

    result = schemas["ReviewResult"]
    return {
        "type": "object",
        "properties": {k: resolve(result["properties"][k]) for k in MODEL_FIELDS},
        "required": [k for k in result["required"] if k in MODEL_FIELDS],
        "additionalProperties": False,
    }


def iso_datetime(value) -> str | None:
    """The engine wants RFC 3339 date-times; models often write a bare date. Unparseable → dropped."""
    if not isinstance(value, str) or not value.strip():
        return None
    v = value.strip()
    try:
        if len(v) == 10:
            return date.fromisoformat(v).isoformat() + "T00:00:00Z"
        parsed = datetime.fromisoformat(v)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.isoformat()


def sanitize(output: dict, schema: dict) -> dict:
    """Clip lengths and counts to the API limits; a too-long reason should not cost a review.
    Raises ValueError for what cannot be repaired (missing fields, bad enum, bad confidence)."""
    props = schema["properties"]
    verdict = output.get("verdict")
    if verdict not in props["verdict"]["enum"]:
        raise ValueError(f"verdict {verdict!r}")
    confidence = output.get("confidence")
    if not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
        raise ValueError(f"confidence {confidence!r}")
    reason = output.get("reason")
    if not isinstance(reason, str) or not reason.strip():
        raise ValueError("reason missing")

    def clip(text, limit):
        if not isinstance(text, str):
            return None
        return text if len(text) <= limit else text[: limit - 1] + "…"

    risk_schema = props["risks"]["items"]["properties"]
    risks = []
    for r in (output.get("risks") or [])[: props["risks"]["maxItems"]]:
        severity = r.get("severity") if r.get("severity") in risk_schema["severity"]["enum"] else "MEDIUM"
        risks.append(
            {
                "category": clip(str(r.get("category") or "OTHER"), risk_schema["category"]["maxLength"]),
                "severity": severity,
                "description": clip(r.get("description") or "", risk_schema["description"]["maxLength"]),
            }
        )
    source_schema = props["sources"]["items"]["properties"]
    sources = []
    for s in (output.get("sources") or [])[: props["sources"]["maxItems"]]:
        if not s.get("title"):
            continue
        item = {"title": str(s["title"])}
        if s.get("url"):
            item["url"] = str(s["url"])
        published = iso_datetime(s.get("publishedAt"))
        if published:
            item["publishedAt"] = published
        if s.get("excerpt"):
            item["excerpt"] = clip(s["excerpt"], source_schema["excerpt"]["maxLength"])
        sources.append(item)
    out = {
        "verdict": verdict,
        "confidence": round(float(confidence), 4),
        "reason": clip(reason.strip(), props["reason"]["maxLength"]),
        "risks": risks,
        "sources": sources,
    }
    if output.get("newsSummary"):
        out["newsSummary"] = clip(output["newsSummary"], props["newsSummary"]["maxLength"])
    # Advisory and optional: an unknown value is dropped rather than failing the review.
    if output.get("preferredStrategy") in props["preferredStrategy"]["enum"]:
        out["preferredStrategy"] = output["preferredStrategy"]
    return out


# ── prompt ────────────────────────────────────────────────────────────────────────────────────


@dataclass
class Prompt:
    system: str
    user_template: str
    text: str
    hash: str

    @staticmethod
    def load(path: Path) -> "Prompt":
        text = path.read_text()
        system, _, user = text.partition("=== USER ===")
        return Prompt(system.strip(), user.strip(), text, hashlib.sha256(text.encode()).hexdigest())

    def render(self, task: dict) -> str:
        values = {
            "strategy": task.get("strategyId", ""),
            "symbol": task.get("symbol", ""),
            "tradingDate": task.get("tradingDate", ""),
            "deadlineAt": task.get("deadlineAt", ""),
            "snapshotVersion": str(task.get("snapshotVersion", "")),
            "snapshot": json.dumps(task.get("snapshot", {}), indent=1, sort_keys=True),
        }
        out = self.user_template
        for k, v in values.items():
            out = out.replace("{" + k + "}", v)
        return out


# ── provider: Claude Code CLI ─────────────────────────────────────────────────────────────────


class ReviewFailure(Exception):
    def __init__(self, kind: str, message: str, raw: str | None = None):
        super().__init__(message)
        self.kind = kind
        self.message = message[:2000]
        self.raw = raw


@dataclass
class ReviewOutcome:
    output: dict  # sanitized model fields
    provenance: dict
    extras: dict
    raw: str


def child_env() -> dict[str, str]:
    """claude gets our environment minus the engine tokens; it has no shell, but still."""
    env = {k: v for k, v in os.environ.items() if not k.startswith("AI_REVIEW_API_TOKEN")}
    env["DISABLE_AUTOUPDATER"] = "1"
    return env


def run_claude(cfg: Config, args: list[str], stdin: str, timeout_s: int) -> tuple[int, str, str]:
    cfg.workdir.mkdir(parents=True, exist_ok=True)
    proc = subprocess.Popen(
        [cfg.claude_bin, *args],
        cwd=cfg.workdir,  # empty dir: no CLAUDE.md / AGENTS.md / project settings to pick up
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=child_env(),
        start_new_session=True,
    )
    try:
        out, err = proc.communicate(stdin, timeout=timeout_s)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, signal.SIGKILL)
        proc.communicate()
        raise ReviewFailure("TIMEOUT", f"no answer within {timeout_s} s")
    return proc.returncode, out, err


def classify(payload: dict | None, stdout: str, stderr: str) -> ReviewFailure:
    text = " ".join(filter(None, [str((payload or {}).get("result") or ""), stderr[-2000:], stdout[-500:]]))
    status = (payload or {}).get("api_error_status")
    lowered = text.lower()
    if status in (401, 403) or "authenticate" in lowered or "/login" in lowered or "oauth" in lowered:
        return ReviewFailure("AUTH", text.strip() or "authentication failed")
    if status == 429 or "usage limit" in lowered or "rate limit" in lowered or "limit reached" in lowered:
        return ReviewFailure("RATE_LIMIT", text.strip() or "rate limited")
    return ReviewFailure("PROVIDER_ERROR", text.strip() or f"claude failed (api status {status})")


def review(cfg: Config, prompt: Prompt, schema: dict, task: dict) -> ReviewOutcome:
    """One review = one fresh `claude -p`. Raises ReviewFailure with an API failure kind."""
    args = [
        "-p",
        "--model", cfg.model,
        "--output-format", "json",
        "--json-schema", json.dumps(schema),
        "--system-prompt", prompt.system,
        "--tools", "WebSearch,WebFetch",
        "--allowedTools", "WebSearch", "WebFetch",
        "--restricted",
        "--strict-mcp-config",
        "--setting-sources", "",
        "--no-session-persistence",
        "--max-turns", str(cfg.max_turns),  # loose safety net only; not enforced as a hard cap (D4 check)
    ]
    started = time.monotonic()
    code, stdout, stderr = run_claude(cfg, args, prompt.render(task), cfg.timeout_s)
    duration_ms = int((time.monotonic() - started) * 1000)
    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError:
        raise classify(None, stdout, stderr)
    if payload.get("is_error") or code != 0:
        raise classify(payload, stdout, stderr)
    structured = payload.get("structured_output")
    if not isinstance(structured, dict):
        raise ReviewFailure("INVALID_OUTPUT", "no structured_output in claude response", stdout)
    try:
        output = sanitize(structured, schema)
    except ValueError as e:
        raise ReviewFailure("INVALID_OUTPUT", f"output fails the contract: {e}", stdout)

    usage = payload.get("usage") or {}
    models = list((payload.get("modelUsage") or {}).keys())
    provenance = {
        "provider": "anthropic",
        "model": models[0] if models else cfg.model,
        "promptVersion": PROMPT_VERSION,
        "promptHash": prompt.hash,
        "reviewerVersion": REVIEWER_VERSION,
        "durationMs": duration_ms,
        "inputTokens": sum(usage.get(k) or 0 for k in ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")),
        "outputTokens": usage.get("output_tokens") or 0,
    }
    extras = {
        "costUsdEquivalent": payload.get("total_cost_usd"),
        # usage.server_tool_use stays 0: Claude Code's WebSearch/WebFetch are client-side tools.
        # num_turns (and the cited sources) are the honest signal that it went looking.
        "numTurns": payload.get("num_turns"),
        "permissionDenials": len(payload.get("permission_denials") or []),
        "models": models,
    }
    return ReviewOutcome(output, provenance, extras, stdout[:200000])


def probe(cfg: Config) -> ReviewFailure | None:
    """Cheapest real call (haiku, no tools): `claude auth status` says loggedIn even with a dead
    token, so only an actual request proves the credentials work."""
    try:
        code, stdout, stderr = run_claude(
            cfg,
            ["-p", "--model", cfg.probe_model, "--output-format", "json", "--tools", "",
             "--restricted", "--strict-mcp-config", "--setting-sources", "", "--no-session-persistence"],
            "Reply with the single word: ok",
            120,
        )
    except ReviewFailure as f:
        return f
    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError:
        return classify(None, stdout, stderr)
    return classify(payload, stdout, stderr) if payload.get("is_error") or code != 0 else None


# ── engine API client ─────────────────────────────────────────────────────────────────────────


class ApiError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status


def call(engine: Engine, method: str, path: str, body: dict | None = None, timeout: int = 20):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(engine.base_url + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {engine.token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if resp.status != 204 and raw else None
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, e.read().decode(errors="replace"))


# ── host guard: don't compete with builds or the chat bot for the Pi's memory ─────────────────


def _process_table() -> dict[int, tuple[int, str]]:
    table = {}
    for d in Path("/proc").iterdir():
        if not d.name.isdigit():
            continue
        try:
            stat = (d / "stat").read_text()
            ppid = int(stat[stat.rindex(")") + 2 :].split()[1])
            cmd = (d / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
            table[int(d.name)] = (ppid, cmd)
        except (OSError, ValueError):
            continue
    return table


def _descendants(table: dict[int, tuple[int, str]], root: int) -> list[str]:
    children: dict[int, list[int]] = {}
    for pid, (ppid, _) in table.items():
        children.setdefault(ppid, []).append(pid)
    out, stack = [], list(children.get(root, []))
    while stack:
        pid = stack.pop()
        out.append(table[pid][1])
        stack.extend(children.get(pid, []))
    return out


def _available_mb() -> int:
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) // 1024
    return 1 << 30


def pause_reason(cfg: Config) -> str | None:
    table = _process_table()
    # A Gradle JVM, not just any command line that mentions Gradle (a shell, an editor, grep).
    gradle_mains = ("GradleDaemon", "GradleWrapperMain", "org.gradle.launcher.GradleMain")
    if any(cmd.split(" ", 1)[0].endswith("java") and any(m in cmd for m in gradle_mains) for _, cmd in table.values()):
        return "Gradle build running"
    try:
        main_pid = int(
            subprocess.run(
                ["systemctl", "show", "-p", "MainPID", "--value", cfg.bot_service],
                capture_output=True, text=True, timeout=10,
            ).stdout.strip() or 0
        )
    except (OSError, subprocess.SubprocessError, ValueError):
        main_pid = 0
    if main_pid and any("claude" in cmd for cmd in _descendants(table, main_pid)):
        return "chat-bot turn running"
    free = _available_mb()
    if free < cfg.min_free_mb:
        return f"low memory ({free} MB available)"
    return None


# ── daemon ────────────────────────────────────────────────────────────────────────────────────


class Reviewer:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.prompt = Prompt.load(cfg.prompt_file)
        self.schema = model_output_schema(cfg.spec_file)
        self.state = "READY"
        self.message: str | None = None
        self.registered: set[str] = set()
        self.next_probe = 0.0
        self.backoff_until = 0.0
        self.consecutive_failures = 0
        self.last_ok = time.monotonic()  # last successful review or probe
        self.stop = threading.Event()
        self.lock = threading.Lock()

    def set_state(self, state: str, message: str | None = None):
        with self.lock:
            changed = (state, message) != (self.state, self.message)
            self.state, self.message = state, message
        if changed:
            log.info("state %s%s", state, f" ({message})" if message else "")
            self.heartbeat_all()

    # Heartbeats run on their own thread so a 10-minute review never looks like a dead reviewer.
    def heartbeat_all(self):
        with self.lock:
            body = {"state": self.state, "message": (self.message or "")[:500] or None,
                    "provider": "anthropic", "model": self.cfg.model, "reviewerVersion": REVIEWER_VERSION}
        for e in self.cfg.engines:
            try:
                call(e, "POST", f"/ai-review/reviewers/{self.cfg.reviewer_id}/heartbeat", body, timeout=10)
            except (ApiError, OSError) as ex:
                log.debug("[%s] heartbeat failed: %s", e.name, ex)

    def heartbeat_loop(self):
        while not self.stop.wait(self.cfg.heartbeat_s):
            self.heartbeat_all()

    def register_prompt(self, e: Engine):
        if e.name in self.registered:
            return
        call(e, "PUT", f"/ai-review/prompts/{self.prompt.hash}",
             {"reviewerId": self.cfg.reviewer_id, "promptVersion": PROMPT_VERSION, "text": self.prompt.text})
        self.registered.add(e.name)

    def claim(self, e: Engine) -> dict | None:
        return call(e, "POST", "/ai-review/tasks/claim",
                    {"reviewerId": self.cfg.reviewer_id, "contractVersions": [CONTRACT_VERSION],
                     "snapshotVersions": SNAPSHOT_VERSIONS})

    def handle(self, e: Engine, task: dict):
        tid, lease = task["taskId"], task["leaseToken"]
        log.info("[%s] reviewing %s %s (task %s, attempt %s)", e.name, task["strategyId"], task["symbol"], tid, task.get("attempt"))
        self.set_state("BUSY", f"{task['symbol']} for {e.name}")
        try:
            outcome = review(self.cfg, self.prompt, self.schema, task)
        except ReviewFailure as f:
            self.fail(e, tid, lease, f)
            return
        body = {"reviewerId": self.cfg.reviewer_id, "leaseToken": lease, "contractVersion": CONTRACT_VERSION,
                **outcome.output, "provenance": outcome.provenance, "extras": outcome.extras,
                "rawResponse": outcome.raw}
        try:
            ack = call(e, "POST", f"/ai-review/tasks/{tid}/result", body)
        except ApiError as ex:
            if ex.status in (400, 422):
                self.fail(e, tid, lease, ReviewFailure("INVALID_OUTPUT", f"engine refused the result: {ex}"))
            else:
                log.warning("[%s] result for %s not accepted: %s", e.name, tid, ex)  # 409 = lease lost
            return
        self.consecutive_failures = 0
        self.last_ok = time.monotonic()
        log.info("[%s] %s %s → %s (%.2f)%s", e.name, task["symbol"], task["strategyId"], outcome.output["verdict"],
                 outcome.output["confidence"], " LATE" if (ack or {}).get("late") else "")
        self.set_state("READY")

    def fail(self, e: Engine, tid: str, lease: str, f: ReviewFailure):
        log.warning("[%s] review %s failed: %s %s", e.name, tid, f.kind, f.message[:300])
        try:
            call(e, "POST", f"/ai-review/tasks/{tid}/failure",
                 {"reviewerId": self.cfg.reviewer_id, "leaseToken": lease, "kind": f.kind, "message": f.message})
        except (ApiError, OSError) as ex:
            log.warning("[%s] could not report failure: %s", e.name, ex)
        if f.kind == "AUTH":
            self.next_probe = time.monotonic() + 600
            self.set_state("AUTH_FAILED", f.message[:300])
        elif f.kind == "RATE_LIMIT":
            self.backoff_until = time.monotonic() + 900
            self.set_state("DEGRADED", "rate limited, retrying in 15 min")
        else:
            self.consecutive_failures += 1
            self.set_state("DEGRADED" if self.consecutive_failures >= 3 else "READY",
                           f"{self.consecutive_failures} failures in a row" if self.consecutive_failures >= 3 else None)

    def premarket_probe_due(self, now_ny: datetime | None = None) -> bool:
        """Weekdays 09:00–09:30 New York, when nothing has proven the login for 12 h: catch a dead
        login before the first candidate arrives instead of on it."""
        t = now_ny or datetime.now(NEW_YORK)
        in_window = t.weekday() < 5 and (9, 0) <= (t.hour, t.minute) < (9, 30)
        return in_window and time.monotonic() - self.last_ok > 12 * 3600

    def tick(self):
        now = time.monotonic()
        if self.state != "AUTH_FAILED" and self.premarket_probe_due():
            failure = probe(self.cfg)
            if failure is None:
                self.last_ok = now
            else:
                self.next_probe = now + 600
                self.set_state("AUTH_FAILED" if failure.kind == "AUTH" else "DEGRADED", failure.message[:300])
                return
        if self.state == "AUTH_FAILED":
            if now < self.next_probe:
                return
            failure = probe(self.cfg)
            if failure is None:
                self.last_ok = now
                self.set_state("READY")
            else:
                self.next_probe = now + 600
                self.set_state("AUTH_FAILED" if failure.kind == "AUTH" else "DEGRADED", failure.message[:300])
                return
        if now < self.backoff_until:
            return
        reason = pause_reason(self.cfg)
        if reason:
            self.set_state("PAUSED", reason)
            return
        if self.state in ("PAUSED", "BUSY") or (self.state == "DEGRADED" and self.consecutive_failures < 3):
            self.set_state("READY")
        for e in self.cfg.engines:  # one review at a time across engines
            try:
                self.register_prompt(e)
                task = self.claim(e)
            except (ApiError, OSError) as ex:
                log.warning("[%s] engine unreachable: %s", e.name, ex)
                continue
            if task:
                self.handle(e, task)
                return

    def run(self):
        log.info("reviewer %s %s, engines: %s, prompt %s", self.cfg.reviewer_id, REVIEWER_VERSION,
                 ", ".join(f"{e.name}={e.base_url}" for e in self.cfg.engines), self.prompt.hash[:16])
        failure = probe(self.cfg)
        if failure:
            self.next_probe = time.monotonic() + 600
            self.set_state("AUTH_FAILED" if failure.kind == "AUTH" else "DEGRADED", failure.message[:300])
        else:
            self.heartbeat_all()
        threading.Thread(target=self.heartbeat_loop, daemon=True).start()
        while not self.stop.is_set():
            try:
                self.tick()
            except Exception:  # never let one bad iteration kill the daemon
                log.exception("tick failed")
            self.stop.wait(self.cfg.poll_s)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", metavar="SNAPSHOT_JSON", help="review one snapshot file and print the result")
    parser.add_argument("--print-schema", action="store_true")
    a = parser.parse_args()
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    cfg = Config.from_env()

    if a.print_schema:
        print(json.dumps(model_output_schema(cfg.spec_file), indent=2))
        return
    if a.dry_run:
        snapshot = json.loads(Path(a.dry_run).read_text())
        task = {"strategyId": snapshot.get("strategyId"), "symbol": snapshot.get("symbol"),
                "tradingDate": snapshot.get("tradingDate"), "deadlineAt": "(dry run)",
                "snapshotVersion": snapshot.get("version", 1), "snapshot": snapshot}
        r = Reviewer(cfg)
        try:
            o = review(cfg, r.prompt, r.schema, task)
        except ReviewFailure as f:
            print(json.dumps({"failure": f.kind, "message": f.message}, indent=2))
            sys.exit(1)
        print(json.dumps({**o.output, "provenance": o.provenance, "extras": o.extras}, indent=2))
        return
    if not cfg.engines:
        sys.exit("AI_REVIEW_ENGINES is empty")
    r = Reviewer(cfg)
    signal.signal(signal.SIGTERM, lambda *_: r.stop.set())
    r.run()


if __name__ == "__main__":
    main()
