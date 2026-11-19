"""Tests for ai_reviewer.py against a fake engine and a stub `claude`. No model calls.

    python3 -m unittest -v test_ai_reviewer.py
"""

import json
import os
import stat
import tempfile
import threading
import unittest
from unittest import mock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import ai_reviewer as ar

TOKEN = "test-token"


class FakeEngine:
    """Just enough of /ai-review/* to drive the reviewer; records every call."""

    def __init__(self, tasks=None, result_status=200):
        self.tasks = list(tasks or [])
        self.result_status = result_status
        self.calls = []
        engine = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self, status, body=None):
                data = b"" if body is None else json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def _handle(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length) or b"null")
                path = self.path.removeprefix("/options")
                engine.calls.append((self.command, path, body, self.headers.get("Authorization")))
                if self.headers.get("Authorization") != f"Bearer {TOKEN}":
                    return self._reply(401)
                if path == "/ai-review/tasks/claim":
                    return self._reply(200, engine.tasks.pop(0)) if engine.tasks else self._reply(204)
                if path.endswith("/result"):
                    return self._reply(engine.result_status, {"late": False})
                return self._reply(200)

            do_POST = do_PUT = _handle

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/options"

    def posted(self, suffix):
        return [c for c in self.calls if c[1].endswith(suffix)]

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def task(symbol="NUE"):
    return {
        "taskId": "11111111-1111-1111-1111-111111111111",
        "leaseToken": "lease-1",
        "leaseExpiresAt": "2026-11-19T15:00:00Z",
        "deadlineAt": "2026-11-19T18:00:00Z",
        "tradingDate": "2026-11-19",
        "strategyId": "BULL_PUT",
        "symbol": symbol,
        "snapshotVersion": 1,
        "snapshot": {"symbol": symbol, "candidate": {"midCredit": 1.8}},
        "attempt": 1,
    }


GOOD = {
    "type": "result",
    "is_error": False,
    "num_turns": 6,
    "total_cost_usd": 0.41,
    "usage": {"input_tokens": 10, "cache_read_input_tokens": 9000, "output_tokens": 800,
              "server_tool_use": {"web_search_requests": 3, "web_fetch_requests": 2}},
    "modelUsage": {"claude-opus-5-5": {}},
    "structured_output": {
        "verdict": "VETO",
        "confidence": 0.8,
        "reason": "x" * 900,
        "risks": [{"category": "tariffs", "severity": "HIGH", "description": "Section 232 ruling"}],
        "sources": [{"title": "Reuters", "url": "https://example.com", "publishedAt": "2026-11-18"},
                    {"url": "https://no-title.example"}],
        "newsSummary": "Steel tariffs in focus.",
    },
}


class Base(unittest.TestCase):
    def setUp(self):
        # The host guard must not see this machine's real processes (a Gradle build running
        # next to the tests would pause every daemon test).
        patcher = mock.patch.object(ar, "_process_table", return_value={})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.tmp = Path(tempfile.mkdtemp())
        self.claude_args = self.tmp / "args.json"

    def stub_claude(self, payload=None, exit_code=0, sleep_s=0, raw=None):
        """A fake `claude` that records its argv/stdin and prints a canned response."""
        script = self.tmp / "claude"
        out = raw if raw is not None else json.dumps(payload)
        script.write_text(
            "#!/usr/bin/env python3\n"
            "import json, sys, time, os\n"
            f"json.dump({{'argv': sys.argv[1:], 'stdin': sys.stdin.read(), 'cwd': os.getcwd(), "
            f"'leaked': [k for k in os.environ if k.startswith('AI_REVIEW_API_TOKEN')]}}, open({str(self.claude_args)!r}, 'w'))\n"
            f"time.sleep({sleep_s})\n"
            f"sys.stdout.write({out!r})\n"
            f"sys.exit({exit_code})\n"
        )
        script.chmod(script.stat().st_mode | stat.S_IEXEC)
        return script

    def config(self, engine=None, **kw):
        cfg = ar.Config(claude_bin=str(kw.pop("claude_bin", self.tmp / "claude")), workdir=self.tmp / "work",
                        poll_s=0, heartbeat_s=3600, min_free_mb=0, timeout_s=kw.pop("timeout_s", 30))
        if engine:
            cfg.engines = [ar.Engine("paper", engine.url, TOKEN)]
        return cfg


class SchemaAndSanitizeTest(Base):
    def test_schema_is_cut_from_the_openapi_result(self):
        schema = ar.model_output_schema(ar.Config().spec_file)
        self.assertEqual(set(schema["properties"]), set(ar.MODEL_FIELDS))
        self.assertEqual(schema["required"], ["verdict", "confidence", "reason"])
        self.assertEqual(schema["properties"]["verdict"]["enum"], ["APPROVE", "VETO", "ABSTAIN"])
        self.assertIn("severity", schema["properties"]["risks"]["items"]["properties"])  # $ref resolved
        self.assertNotIn("$ref", json.dumps(schema))

    def test_sanitize_clips_and_repairs_but_refuses_a_bad_verdict(self):
        schema = ar.model_output_schema(ar.Config().spec_file)
        out = ar.sanitize(GOOD["structured_output"], schema)
        self.assertEqual(len(out["reason"]), 600)
        self.assertEqual(out["sources"], [{"title": "Reuters", "url": "https://example.com",
                                           "publishedAt": "2026-11-18T00:00:00Z"}])
        with self.assertRaises(ValueError):
            ar.sanitize({**GOOD["structured_output"], "verdict": "MAYBE"}, schema)
        with self.assertRaises(ValueError):
            ar.sanitize({**GOOD["structured_output"], "confidence": 1.4}, schema)

    def test_prompt_hash_covers_the_whole_template_and_render_fills_it(self):
        p = ar.Prompt.load(ar.HERE / "prompt_v1.md")
        self.assertRegex(p.hash, "^[a-f0-9]{64}$")
        text = p.render(task())
        self.assertIn("BULL_PUT candidate on NUE", text)
        self.assertIn('"midCredit": 1.8', text)
        self.assertNotIn("{snapshot}", text)


class ReviewTest(Base):
    def run_review(self, **stub):
        self.stub_claude(**stub)
        cfg = self.config(timeout_s=stub.get("sleep_s", 0) and 1 or 30)
        r = ar.Reviewer(cfg)
        return ar.review(cfg, r.prompt, r.schema, task())

    def test_a_good_answer_becomes_a_result_with_provenance(self):
        o = self.run_review(payload=GOOD)
        self.assertEqual(o.output["verdict"], "VETO")
        self.assertEqual(o.provenance["model"], "claude-opus-5-5")
        self.assertEqual(o.provenance["inputTokens"], 9010)
        self.assertEqual(o.extras["numTurns"], 6)

        call = json.loads(self.claude_args.read_text())
        argv = call["argv"]
        self.assertNotIn("--dangerously-skip-permissions", argv)
        self.assertIn("--restricted", argv)
        self.assertEqual(argv[argv.index("--tools") + 1], "WebSearch,WebFetch")
        self.assertIn("NUE", call["stdin"])
        self.assertEqual(call["cwd"], str(self.tmp / "work"))

    def test_engine_tokens_never_reach_claude(self):
        os.environ["AI_REVIEW_API_TOKEN"] = "secret"
        try:
            self.run_review(payload=GOOD)
        finally:
            del os.environ["AI_REVIEW_API_TOKEN"]
        self.assertEqual(json.loads(self.claude_args.read_text())["leaked"], [])

    def test_failures_are_classified(self):
        cases = [
            ({"is_error": True, "api_error_status": 401, "result": "Failed to authenticate."}, 1, "AUTH"),
            ({"is_error": True, "api_error_status": 429, "result": "Claude usage limit reached"}, 1, "RATE_LIMIT"),
            ({"is_error": True, "api_error_status": 529, "result": "Overloaded"}, 1, "PROVIDER_ERROR"),
            ({"is_error": False, "result": "no schema"}, 0, "INVALID_OUTPUT"),
            ({"is_error": False, "structured_output": {"verdict": "MAYBE"}}, 0, "INVALID_OUTPUT"),
        ]
        for payload, code, kind in cases:
            with self.subTest(kind=kind), self.assertRaises(ar.ReviewFailure) as ctx:
                self.run_review(payload=payload, exit_code=code)
            self.assertEqual(ctx.exception.kind, kind)

    def test_garbage_and_timeouts(self):
        with self.assertRaises(ar.ReviewFailure) as ctx:
            self.run_review(raw="Segmentation fault", exit_code=139)
        self.assertEqual(ctx.exception.kind, "PROVIDER_ERROR")
        with self.assertRaises(ar.ReviewFailure) as ctx:
            self.run_review(payload=GOOD, sleep_s=5)
        self.assertEqual(ctx.exception.kind, "TIMEOUT")


class DaemonTest(Base):
    def test_claims_reviews_and_posts_the_result(self):
        engine = FakeEngine(tasks=[task()])
        self.stub_claude(payload=GOOD)
        r = ar.Reviewer(self.config(engine))
        r.tick()
        engine.close()

        (_, path, prompt_body, _), = engine.posted(r.prompt.hash)
        self.assertEqual(prompt_body["promptVersion"], "v1")
        (_, _, claim, _), = engine.posted("/claim")
        self.assertEqual(claim, {"reviewerId": "claude-opus", "contractVersions": ["1"], "snapshotVersions": [1]})
        (_, _, result, auth), = engine.posted("/result")
        self.assertEqual(auth, f"Bearer {TOKEN}")
        self.assertEqual(result["leaseToken"], "lease-1")
        self.assertEqual(result["verdict"], "VETO")
        self.assertEqual(result["provenance"]["promptHash"], r.prompt.hash)
        self.assertIn("structured_output", result["rawResponse"])
        self.assertEqual(r.state, "READY")
        states = [c[2]["state"] for c in engine.posted("/heartbeat")]
        self.assertIn("BUSY", states)

    def test_auth_failure_is_reported_and_stops_claiming_until_a_probe_succeeds(self):
        engine = FakeEngine(tasks=[task(), task("MPC")])
        self.stub_claude(payload={"is_error": True, "api_error_status": 401, "result": "Please run /login"}, exit_code=1)
        r = ar.Reviewer(self.config(engine))
        r.tick()
        (_, _, failure, _), = engine.posted("/failure")
        self.assertEqual(failure["kind"], "AUTH")
        self.assertEqual(r.state, "AUTH_FAILED")

        r.tick()  # probe not due yet: no claim
        self.assertEqual(len(engine.posted("/claim")), 1)

        self.stub_claude(payload=GOOD)
        r.next_probe = 0
        r.tick()  # probe ok → READY → claims MPC
        engine.close()
        self.assertEqual(len(engine.posted("/claim")), 2)
        self.assertEqual(len(engine.posted("/result")), 1)

    def test_rate_limit_backs_off(self):
        engine = FakeEngine(tasks=[task(), task("MPC")])
        self.stub_claude(payload={"is_error": True, "api_error_status": 429, "result": "usage limit"}, exit_code=1)
        r = ar.Reviewer(self.config(engine))
        r.tick()
        r.tick()
        engine.close()
        self.assertEqual(r.state, "DEGRADED")
        self.assertEqual(len(engine.posted("/claim")), 1)

    def test_an_engine_refusal_of_the_result_is_reported_as_invalid_output(self):
        engine = FakeEngine(tasks=[task()], result_status=400)
        self.stub_claude(payload=GOOD)
        ar.Reviewer(self.config(engine)).tick()
        engine.close()
        (_, _, failure, _), = engine.posted("/failure")
        self.assertEqual(failure["kind"], "INVALID_OUTPUT")

    def test_paused_while_memory_is_short(self):
        engine = FakeEngine(tasks=[task()])
        cfg = self.config(engine)
        cfg.min_free_mb = 10_000_000
        r = ar.Reviewer(cfg)
        r.tick()
        engine.close()
        self.assertEqual(r.state, "PAUSED")
        self.assertEqual(engine.posted("/claim"), [])


class PremarketProbeTest(Base):
    def test_due_only_in_the_window_and_when_nothing_proved_the_login_lately(self):
        from datetime import datetime
        r = ar.Reviewer(self.config())
        thu_0910 = datetime(2026, 11, 19, 9, 10, tzinfo=ar.NEW_YORK)
        self.assertFalse(r.premarket_probe_due(thu_0910), "just started: login proven by the startup probe")
        r.last_ok -= 13 * 3600
        self.assertTrue(r.premarket_probe_due(thu_0910))
        self.assertFalse(r.premarket_probe_due(datetime(2026, 11, 19, 10, 0, tzinfo=ar.NEW_YORK)))
        self.assertFalse(r.premarket_probe_due(datetime(2026, 11, 21, 9, 10, tzinfo=ar.NEW_YORK)), "Saturday")


class HostGuardTest(unittest.TestCase):
    def test_descendants_walk_the_whole_tree(self):
        table = {10: (1, "python bot.py"), 11: (10, "node claude -p"), 12: (11, "rg x"), 20: (1, "claude")}
        self.assertEqual(sorted(ar._descendants(table, 10)), ["node claude -p", "rg x"])

    def test_config_from_env(self):
        cfg = ar.Config.from_env({
            "AI_REVIEW_ENGINES": "paper=http://localhost:8081/options/, live=http://10.0.0.5:8081/options",
            "AI_REVIEW_API_TOKEN": "shared",
            "AI_REVIEW_API_TOKEN_LIVE": "live-only",
        })
        self.assertEqual([(e.name, e.base_url, e.token) for e in cfg.engines], [
            ("paper", "http://localhost:8081/options", "shared"),
            ("live", "http://10.0.0.5:8081/options", "live-only"),
        ])


if __name__ == "__main__":
    unittest.main()
