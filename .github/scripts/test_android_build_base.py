"""android-build-base.sh against a real throwaway git history."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent / "android-build-base.sh"


class BuildBaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        self.git("init", "-q")
        self.git("config", "user.email", "t@example.invalid")
        self.git("config", "user.name", "t")

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.dir, check=True,
                              capture_output=True, text=True).stdout.strip()

    def commit(self, name):
        Path(self.dir, name).write_text(name)
        self.git("add", name)
        self.git("commit", "-q", "-m", name)
        return self.git("rev-parse", "HEAD")

    def base(self, run="400"):
        result = subprocess.run(["bash", str(SCRIPT), "android-v0.1", run], cwd=self.dir,
                                check=True, capture_output=True, text=True, env=dict(os.environ))
        return result.stdout.strip()

    def test_falls_back_without_a_reachable_internal_release(self):
        self.commit("a")
        self.git("tag", "android-v0.1")
        self.commit("b")
        self.assertEqual(self.base(), "android-v0.1")

    def test_staging_only_tags_are_not_reachable(self):
        root = self.commit("a")
        self.git("checkout", "-q", "-b", "staging")
        self.commit("staging-merge")
        self.git("tag", "android-dev.319")
        self.git("checkout", "-q", "-")
        self.commit("main-work")
        self.assertNotEqual(root, "")
        self.assertEqual(self.base(), "android-v0.1")

    def test_newest_reachable_internal_release_wins(self):
        self.commit("a")
        self.git("tag", "android-dev.10")
        self.commit("b")
        self.git("tag", "android-dev.20")
        self.commit("c")
        self.assertEqual(self.base(), "android-dev.20")

    def test_this_runs_own_release_is_skipped_on_a_rerun(self):
        self.commit("a")
        self.git("tag", "android-dev.20")
        self.commit("b")
        self.git("tag", "android-dev.400")   # this run's first attempt tagged HEAD
        self.assertEqual(self.base(run="400"), "android-dev.20")

    def test_version_sort_not_lexical(self):
        self.commit("a")
        self.git("tag", "android-dev.99")
        self.commit("b")
        self.git("tag", "android-dev.100")
        self.commit("c")
        self.assertEqual(self.base(), "android-dev.100")


if __name__ == "__main__":
    unittest.main()
