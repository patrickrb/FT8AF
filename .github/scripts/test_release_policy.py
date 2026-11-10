"""Execute the real release selectors offline; no credentials or publishers.

Expected routing is defined independently of the shell under test. In
particular a production-looking ref must not turn an unsupported event into
a release, and main must remain a prerelease even with existing production tags.
"""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = {name: (ROOT / f".github/workflows/{name}.yml").read_text()
             for name in ("android", "desktop")}
EVENTS = ("push", "pull_request", "workflow_dispatch", "release", "schedule",
          "workflow_run", "pull_request_target")
REFS = ("refs/heads/dev", "refs/heads/staging", "refs/heads/main",
        "refs/heads/main-extra", "refs/heads/staging-extra", "refs/heads/fix/example",
        "refs/pull/12/merge", "refs/tags/android-v2.7.1", "refs/tags/desktop-v2.7.1",
        "refs/tags/android-dev.81", "refs/tags/desktop-dev.81")


def script(platform, step_id):
    step = WORKFLOWS[platform].split(f"        id: {step_id}\n", 1)[1]
    run = step.split("        run: |\n", 1)[1].split("\n      - name:", 1)[0]
    return textwrap.dedent(re.split(r"\n  \S", run, maxsplit=1)[0])


class ReleasePolicyTest(unittest.TestCase):
    def run_script(self, platform, step_id, event, ref, tagged=True, **extra):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q", tmp], check=True)
            if tagged:
                tree = subprocess.check_output(["git", "mktree"], cwd=tmp, input=b"").strip().decode()
                for tag in ("desktop-v1.4.9", "desktop-v1.4.10", "android-v1.4.10"):
                    subprocess.run(["git", "update-ref", "refs/tags/" + tag, tree], cwd=tmp, check=True)
            scripts = root / ".github/scripts"
            scripts.mkdir(parents=True)
            shutil.copy2(ROOT / ".github/scripts/android-next-version.sh", scripts)
            env = dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_REF=ref,
                       GITHUB_REF_NAME=ref.rsplit("/", 1)[-1], GITHUB_RUN_NUMBER="81",
                       GITHUB_OUTPUT=str(root / "outputs"), ANDROID_CHANGED="true",
                       PREV_TAG="android-v1.4.10", PREV_VERSION="1.4.10",
                       CAND_FOUND="false", CAND_TAG="", CAND_VERSION="",
                       BUMP="patch", BUMP_SOURCE="fallback", CHANGED="false")
            env.update(extra)
            result = subprocess.run(["bash", "-c", script(platform, step_id)], cwd=tmp,
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return dict(line.split("=", 1) for line in (root / "outputs").read_text().splitlines())

    def android_release(self, event, ref, **extra):
        lane = self.run_script("android", "lane", event, ref)["lane"]
        return self.run_script("android", "tag", event, ref, LANE=lane, **extra)

    def test_android_event_ref_truth_table(self):
        for event in EVENTS:
            for ref in REFS:
                with self.subTest(event=event, ref=ref):
                    result = self.android_release(event, ref)
                    internal = event == "push" and ref in ("refs/heads/main", "refs/heads/staging")
                    production = event in ("push", "workflow_dispatch") and ref == "refs/tags/android-v2.7.1"
                    self.assertEqual(result["should_release"], str(internal or production).lower())
                    self.assertEqual(result["is_prerelease"], str(internal).lower())
                    self.assertEqual(result["play_track"], "internal" if internal else "production" if production else "")
                    self.assertEqual(result["release_tag"], "android-dev.81" if internal else "android-v2.7.1" if production else "")
                    self.assertEqual(result["version_name"], "1.4.11-dev.81" if internal else "2.7.1" if production else "")

    def test_desktop_event_ref_truth_table(self):
        for event in EVENTS:
            for ref in REFS:
                with self.subTest(event=event, ref=ref):
                    result = self.run_script("desktop", "tag", event, ref)
                    internal = event == "push" and ref in ("refs/heads/main", "refs/heads/staging")
                    production = event == "push" and ref == "refs/tags/desktop-v2.7.1"
                    self.assertEqual(result["should_release"], str(internal or production).lower())
                    self.assertEqual(result["is_prerelease"], str(internal).lower())
                    self.assertEqual(result["release_tag"], "desktop-dev.81" if internal else "desktop-v2.7.1" if production else "")
                    self.assertEqual(result["app_version"], "1.4.11-81" if internal else "2.7.1" if production else "")

    def test_internal_builds_without_production_tag_history(self):
        for branch in ("main", "staging"):
            with self.subTest(branch=branch):
                result = self.run_script("desktop", "tag", "push", f"refs/heads/{branch}", tagged=False)
                self.assertEqual(result["release_tag"], "desktop-dev.81")
                self.assertEqual(result["version_name"], "0.1.0-dev.81")
                self.assertEqual(result["app_version"], "0.1.1-81")
                self.assertEqual(result["is_prerelease"], "true")

    def test_android_reuses_candidate_without_production_tag_or_extra_bump(self):
        for branch in ("main", "staging"):
            with self.subTest(branch=branch):
                result = self.android_release("push", f"refs/heads/{branch}", CAND_FOUND="true",
                                              CAND_TAG="android-dev.79", CAND_VERSION="2.6.3", BUMP="major")
                self.assertEqual(result["release_tag"], "android-dev.81")
                self.assertEqual(result["version_name"], "2.6.3-dev.81")
                self.assertEqual(result["version_source"], "promoted from android-dev.79")
                self.assertEqual(result["play_track"], "internal")
                self.assertEqual(result["is_prerelease"], "true")

    def test_android_no_app_changes_never_cuts_branch_release(self):
        for branch in ("main", "staging"):
            with self.subTest(branch=branch):
                result = self.android_release("push", f"refs/heads/{branch}", ANDROID_CHANGED="false")
                self.assertEqual(result["should_release"], "false")
                self.assertEqual(result["release_tag"], "")
                self.assertEqual(result["play_track"], "")

    def test_detection_builds_publishing_pushes_and_path_filters_ci(self):
        for platform in WORKFLOWS:
            for ref in ("refs/heads/main", "refs/heads/staging", f"refs/tags/{platform}-v2.7.1"):
                with self.subTest(platform=platform, ref=ref):
                    self.assertEqual(self.run_script(platform, "decide", "push", ref)["run"], "true")
            for event, ref in (("push", "refs/heads/dev"), ("pull_request", "refs/pull/12/merge")):
                for changed in ("true", "false"):
                    with self.subTest(platform=platform, event=event, changed=changed):
                        self.assertEqual(self.run_script(platform, "decide", event, ref, CHANGED=changed)["run"], changed)

    def test_publishers_consume_selected_prerelease_and_play_track(self):
        # Pin the contract between selectors and BOTH Play upload attempts;
        # safe selector output alone is insufficient if a publisher ignores it.
        android = WORKFLOWS["android"]
        self.assertIn("prerelease: ${{ steps.tag.outputs.is_prerelease == 'true' }}", android)
        for step_id in ("play_publish", "play_publish_retry"):
            publisher = android.split(f"        id: {step_id}\n", 1)[1].split("\n      - name:", 1)[0]
            self.assertIn("if: steps.tag.outputs.should_release == 'true' && steps.tag.outputs.play_track != ''", publisher)
            self.assertIn("track: ${{ steps.tag.outputs.play_track }}", publisher)
            self.assertIn("releaseName: ${{ steps.tag.outputs.release_tag }}", publisher)
        desktop = WORKFLOWS["desktop"].split("      - name: Build & publish release bundles\n", 1)[1].split("\n  coverage:", 1)[0]
        self.assertIn("needs.version.outputs.should_release == 'true'", desktop)
        self.assertIn("tagName: ${{ needs.version.outputs.release_tag }}", desktop)
        self.assertIn("prerelease: ${{ needs.version.outputs.is_prerelease == 'true' }}", desktop)

    def test_discord_branch_notifications_do_not_announce_production(self):
        workflow = (ROOT / ".github/workflows/discord-merge-notify.yml").read_text()
        notify = textwrap.dedent(workflow.split("        run: |\n", 1)[1])
        for branch in ("main", "staging", "dev"):
            with self.subTest(branch=branch), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                curl = root / "curl"
                curl.write_text('#!/bin/sh\nwhile [ "$1" != "-d" ]; do shift; done\nprintf "%s" "$2" > payload.json\n')
                curl.chmod(0o755)
                env = dict(os.environ, PATH=tmp + os.pathsep + os.environ["PATH"],
                           BRANCH=branch, WEBHOOK_URL="https://example.invalid/unused",
                           SHA="0123456789", COMMIT_MSG="Internal candidate", AUTHOR="Fixture",
                           COMMIT_URL="https://example.invalid/commit", COMPARE_URL="https://example.invalid/compare",
                           REPO="patrickrb/FT8AF")
                result = subprocess.run(["bash", "-c", notify], env=env, cwd=tmp,
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                embed = json.loads((root / "payload.json").read_text())["embeds"][0]
                self.assertEqual(embed["color"], 15105570 if branch == "dev" else 3447003)
                self.assertTrue(embed["title"].startswith("🔧" if branch == "dev" else "📦"))


if __name__ == "__main__":
    unittest.main()
