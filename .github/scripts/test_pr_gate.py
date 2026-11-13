"""Offline tests for pr_gate.sh: a fake `gh` serves the PR lookup and the
workflow runs, and records the status the script would post."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/pr_gate.sh"

# The fake answers the two reads pr_gate.sh makes (already --jq-filtered, as
# gh would print them) and logs the POST.
FAKE_GH = """#!/bin/sh
case "$*" in
  *"/pulls"*) cat "$FIXTURE/prs" ;;
  *"actions/runs"*) cat "$FIXTURE/runs" ;;
  *"statuses"*) echo "$*" >> "$FIXTURE/posted" ;;
esac
"""


class PrGateTest(unittest.TestCase):
    def gate(self, runs, open_prs="1", dry=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            gh = root / "gh"
            gh.write_text(FAKE_GH)
            gh.chmod(0o755)
            (root / "prs").write_text(open_prs + "\n")
            # Rows are (id, name, status, conclusion) for PR runs, or
            # (id, name, event, status, conclusion) to set the event.
            rows = [r if len(r) == 5 else (r[0], r[1], "pull_request", r[2], r[3]) for r in runs]
            (root / "runs").write_text("".join("\t".join(r) + "\n" for r in rows))
            env = dict(os.environ, PATH=tmp + os.pathsep + os.environ["PATH"], FIXTURE=tmp,
                       REPO="patrickrb/FT8AF", SHA="abc123")
            if dry:
                env["DRY_RUN"] = "1"
            result = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            posted = (root / "posted").read_text() if (root / "posted").exists() else ""
            return result.stdout, posted

    def test_all_green_posts_success(self):
        out, posted = self.gate([("1", "Android CI & Release", "completed", "success"),
                                 ("2", "Native FT8 tests", "completed", "skipped")])
        self.assertIn("success", out)
        self.assertIn("state=success", posted)
        self.assertIn("context=pr-gate", posted)

    def test_failure_beats_pending(self):
        out, posted = self.gate([("1", "Android CI & Release", "completed", "failure"),
                                 ("2", "Desktop CI & Release", "in_progress", "")])
        self.assertIn("state=failure", posted)
        self.assertIn("Android CI & Release [pull_request] (failure)", out)

    def test_queued_run_holds_the_gate(self):
        _, posted = self.gate([("1", "Android CI & Release", "queued", "")])
        self.assertIn("state=pending", posted)

    def test_latest_run_of_a_workflow_wins(self):
        # A re-run (higher id) supersedes the failed first attempt.
        _, posted = self.gate([("5", "Android CI & Release", "completed", "failure"),
                               ("9", "Android CI & Release", "completed", "success")])
        self.assertIn("state=success", posted)

    def test_push_run_cannot_hide_the_pr_run_on_the_same_commit(self):
        # A dev -> main PR's head is dev's head: a later, green dev-push run of
        # the same workflow must not stand in for the PR run that is failing.
        _, posted = self.gate([("5", "Android CI & Release", "pull_request", "completed", "failure"),
                               ("9", "Android CI & Release", "push", "completed", "success")])
        self.assertIn("state=failure", posted)

    def test_push_and_pr_runs_must_both_finish(self):
        _, posted = self.gate([("5", "Android CI & Release", "push", "completed", "success"),
                               ("9", "Android CI & Release", "pull_request", "in_progress", "")])
        self.assertIn("state=pending", posted)

    def test_post_merge_workflows_are_ignored(self):
        _, posted = self.gate([("1", "PR gate", "in_progress", ""),
                               ("2", "Discord — merge notify", "completed", "failure"),
                               ("3", "Android CI & Release", "completed", "success")])
        self.assertIn("state=success", posted)

    def test_commit_without_open_pr_is_left_alone(self):
        out, posted = self.gate([("1", "Android CI & Release", "completed", "success")], open_prs="0")
        self.assertIn("skip", out)
        self.assertEqual(posted, "")

    def test_dry_run_posts_nothing(self):
        out, posted = self.gate([("1", "Android CI & Release", "completed", "success")], dry=True)
        self.assertIn("success", out)
        self.assertEqual(posted, "")


if __name__ == "__main__":
    unittest.main()
