"""Offline tests for the software factory's own tools: the guard hook, the
re-run wrapper, and the FT8AF social poster's publishing paths (every HTTP
call is mocked; nothing is posted)."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
POSTER = ROOT / ".claude/skills/feature-announce/scripts/post_socials.py"


def load_poster():
    spec = importlib.util.spec_from_file_location("post_socials", POSTER)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class FakeResponse:
    def __init__(self, status, body):
        self.status_code = status
        self._body = body
        self.text = json.dumps(body)

    def json(self):
        return self._body


class GuardTest(unittest.TestCase):
    def test_guard_self_test_passes(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/factory/guard.py"), "--self-test"],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class RerunWrapperTest(unittest.TestCase):
    def rerun(self, view_output, view_rc=0):
        with tempfile.TemporaryDirectory() as tmp:
            gh = Path(tmp) / "gh"
            gh.write_text('#!/bin/sh\n'
                          'case "$1 $2" in\n'
                          '  "run view") printf "%s" "$VIEW_OUT"; exit "$VIEW_RC" ;;\n'
                          '  "run rerun") echo "$*" >> "$LOG" ;;\n'
                          'esac\n')
            gh.chmod(0o755)
            log = Path(tmp) / "log"
            env = dict(os.environ, PATH=tmp + os.pathsep + os.environ["PATH"],
                       VIEW_OUT=view_output, VIEW_RC=str(view_rc), LOG=str(log))
            result = subprocess.run(["bash", str(ROOT / "scripts/factory/rerun.sh"), "123"],
                                    env=env, capture_output=True, text=True)
            return result.returncode, log.read_text() if log.exists() else ""

    def test_reruns_a_pr_run(self):
        rc, log = self.rerun("fix/x\tpull_request")
        self.assertEqual(rc, 0)
        self.assertIn("run rerun 123 --failed", log)

    def test_refuses_production_tags_dispatches_and_unknown_runs(self):
        for out, rc in (("android-v1.2.0\tpush", 0), ("desktop-v2.0.0\tpush", 0),
                        ("android-v1.2.0\tworkflow_dispatch", 0), ("main\tworkflow_dispatch", 0),
                        ("\t", 0), ("", 1)):
            with self.subTest(out=out, rc=rc):
                code, log = self.rerun(out, rc)
                self.assertNotEqual(code, 0)
                self.assertEqual(log, "")


class PosterTest(unittest.TestCase):
    def setUp(self):
        self.mod = load_poster()
        self.tmp = tempfile.TemporaryDirectory()
        env = Path(self.tmp.name) / "social.env"
        env.write_text("META_PAGE_ID=42\nMETA_PAGE_TOKEN=page-token-abcd\n"
                       "X_API_KEY=k\nX_API_SECRET=s\nX_ACCESS_TOKEN=t\nX_ACCESS_SECRET=ts\n"
                       "DISCORD_ANNOUNCE_WEBHOOK=https://discord.example/api/webhooks/1/secret-hook?thread_id=9\n")
        self.patch_env = mock.patch.dict(os.environ, {"FT8AF_SOCIAL_ENV": str(env)})
        self.patch_env.start()
        self.mod.load_dotenv()

    def tearDown(self):
        self.patch_env.stop()
        self.tmp.cleanup()

    def run_main(self, posts, responses, *args):
        path = Path(self.tmp.name) / "posts.json"
        path.write_text(json.dumps(posts))
        calls = []

        def fake_request(method, url, **kw):
            calls.append((method, url, kw))
            return responses.pop(0)

        out = []
        with mock.patch.object(self.mod.requests, "request", side_effect=fake_request), \
             mock.patch.object(sys, "argv", ["post_socials.py", "--posts", str(path), *args]), \
             mock.patch("builtins.print", side_effect=lambda *a, **k: out.append(" ".join(map(str, a)))):
            try:
                self.mod.main()
                code = 0
            except SystemExit as e:
                code = e.code or 0
        return code, "\n".join(out), calls

    def test_publishes_facebook_x_and_discord(self):
        code, out, calls = self.run_main(
            {"facebook": {"text": "New", "link": "https://ft8af.app/blog/a"},
             "x": {"text": "New https://ft8af.app/blog/a"},
             "discord": {"text": "New", "link": "https://ft8af.app/blog/a"}},
            [FakeResponse(200, {"id": "42_7"}), FakeResponse(201, {"data": {"id": "99"}}),
             FakeResponse(200, {"id": "5"})])
        self.assertEqual(code, 0, out)
        self.assertIn("https://www.facebook.com/42_7", out)
        self.assertIn("https://x.com/FT8AF/status/99", out)
        self.assertEqual([c[0] for c in calls], ["POST"] * 3)
        self.assertTrue(calls[1][2]["headers"]["Authorization"].startswith("OAuth "))
        self.assertIn("wait=true", calls[2][1])
        self.assertEqual(calls[2][2]["json"]["allowed_mentions"], {"parse": []})

    def test_one_network_failing_does_not_stop_the_others_and_secrets_stay_hidden(self):
        code, out, calls = self.run_main(
            {"facebook": {"text": "New", "link": "https://ft8af.app/blog/a"},
             "discord": {"text": "New"}},
            [FakeResponse(401, {"error": "bad token page-token-abcd"}), FakeResponse(200, {"id": "5"})])
        self.assertEqual(code, 1)
        self.assertEqual(len(calls), 2, "discord must still post after facebook fails")
        self.assertIn("[FAIL] facebook", out)
        self.assertIn("[ok]   discord", out)
        self.assertNotIn("page-token-abcd", out)
        self.assertNotIn("secret-hook", out)

    def test_redacts_the_query_free_webhook_url(self):
        msg = self.mod.redact("POST https://discord.example/api/webhooks/1/secret-hook -> HTTP 500")
        self.assertNotIn("secret-hook", msg)
        self.assertIn("<DISCORD_ANNOUNCE_WEBHOOK>", msg)

    def test_dry_run_makes_no_requests(self):
        code, out, calls = self.run_main(
            {"facebook": {"text": "New", "link": "https://ft8af.app/blog/a"}, "x": {"text": "New"}},
            [], "--dry-run")
        self.assertEqual(code, 0, out)
        self.assertEqual(calls, [])
        self.assertIn("DRY RUN", out)

    def test_over_length_x_post_fails_before_any_request(self):
        code, out, calls = self.run_main({"x": {"text": "x" * 281}}, [])
        self.assertEqual(code, 1)
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
