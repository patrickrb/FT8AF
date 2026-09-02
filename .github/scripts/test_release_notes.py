"""Tests for release_notes.py — the tag lane's release-notes restoration.

Stdlib only, like test_publish_listings.py, so android.yml's test job can run
it with no extra install. Every branch the workflow step used to carry in bash
is pinned here: existing / missing release, lookup failure, dispatch versus
tag push, and the marker structure.
"""
import contextlib
import io
import os
import shutil
import tempfile
import unittest

import release_notes as rn

BODY = (
    "## Release notes\r\n\r\n"
    "<!-- ft8af-notes-start -->\r\n"
    "New: hamlib CAT support.\r\nFixes for Icom SWR meters.\r\n\r\n"
    "<!-- ft8af-notes-end -->\r\n\r\n"
    "<!-- ft8af-version: 0.151.0 -->\r\n"
    "_Version `0.151.0` — promoted from android-dev.1155._\r\n"
)
NOTES = "New: hamlib CAT support.\nFixes for Icom SWR meters.\n\n"


class DecideTest(unittest.TestCase):
    def test_existing_release_on_dispatch_restores_notes_and_body(self):
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", BODY)
        self.assertTrue(out.found)
        self.assertIsNone(out.error)
        self.assertEqual(out.notes, NOTES)
        self.assertNotIn("\r", out.body)
        self.assertIn("<!-- ft8af-version: 0.151.0 -->", out.body)

    def test_existing_release_on_push_restores_too(self):
        out = rn.decide("push", "android-v0.151.0", 0, "", BODY)
        self.assertTrue(out.found)
        self.assertEqual(out.notes, NOTES)

    def test_missing_release_on_dispatch_is_an_error(self):
        out = rn.decide("workflow_dispatch", "android-v9.9.9", 1, "release not found", "")
        self.assertIsNotNone(out.error)
        self.assertIn("android-v9.9.9", out.error)
        self.assertIn("release not found", out.error)

    def test_missing_release_on_push_falls_back(self):
        out = rn.decide("push", "android-v9.9.9", 1, "release not found\n", "")
        self.assertFalse(out.found)
        self.assertIsNone(out.error)
        self.assertIn("plain tag push", out.message)

    def test_lookup_failure_on_push_is_an_error_not_a_fallback(self):
        # Auth, rate limit, transient API error: the release may well exist and
        # carrying on would overwrite it.
        for err in ("HTTP 401: Bad credentials", "HTTP 403: API rate limit exceeded", ""):
            out = rn.decide("push", "android-v0.151.0", 1, err, "")
            self.assertIsNotNone(out.error, err)
            self.assertIn("refusing to continue", out.error)

    def test_lookup_failure_on_dispatch_is_an_error(self):
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 1, "HTTP 401: Bad credentials", "")
        self.assertIsNotNone(out.error)
        self.assertIn("Bad credentials", out.error)

    def test_no_markers_on_dispatch_is_an_error(self):
        # A release cut by a plain tag push, or one whose body was edited.
        out = rn.decide("workflow_dispatch", "android-v0.149", 0, "", "## Release\nno markers here\n")
        self.assertIsNotNone(out.error)
        self.assertIn("no usable release notes", out.error)

    def test_no_markers_on_push_keeps_body_and_ships_without_notes(self):
        out = rn.decide("push", "android-v0.149", 0, "", "## Release\nno markers here\n")
        self.assertTrue(out.found)
        self.assertEqual(out.notes, "")
        self.assertIn("no markers here", out.body)

    def test_blank_notes_between_markers_on_dispatch_is_an_error(self):
        body = "<!-- ft8af-notes-start -->\n\n   \n<!-- ft8af-notes-end -->\n"
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", body)
        self.assertIsNotNone(out.error)
        self.assertIn("no release notes between", out.error)

    def test_end_marker_before_start_is_malformed(self):
        # A lenient scan would capture everything after the start marker and
        # ship the trailing release text to Play.
        body = "<!-- ft8af-notes-end -->\n<!-- ft8af-notes-start -->\ntrailing text\n"
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", body)
        self.assertIsNotNone(out.error)
        self.assertIn("end marker comes before", out.error)
        out = rn.decide("push", "android-v0.151.0", 0, "", body)
        self.assertTrue(out.found)
        self.assertEqual(out.notes, "")

    def test_duplicate_markers_are_malformed(self):
        body = ("<!-- ft8af-notes-start -->\nA\n<!-- ft8af-notes-end -->\n"
                "<!-- ft8af-notes-start -->\nB\n<!-- ft8af-notes-end -->\n")
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", body)
        self.assertIsNotNone(out.error)
        self.assertIn("found 2 and 2", out.error)

    def test_only_one_of_the_markers_is_malformed(self):
        out = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "",
                        "<!-- ft8af-notes-start -->\nA\n")
        self.assertIsNotNone(out.error)


class ExtractNotesTest(unittest.TestCase):
    def test_matches_the_old_awk_extraction(self):
        self.assertEqual(rn.extract_notes(BODY.replace("\r", "")), NOTES)

    def test_no_markers_raises(self):
        with self.assertRaises(rn.MalformedMarkers):
            rn.extract_notes("plain body")


class MainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def path(self, name, content=None):
        p = os.path.join(self.tmp, name)
        if content is not None:
            with open(p, "w", encoding="utf-8", newline="") as f:
                f.write(content)
        return p

    def run_main(self, event, status, stderr, body):
        argv = [
            "--event", event, "--tag", "android-v0.151.0",
            "--gh-status", str(status),
            "--gh-stderr", self.path("err.txt", stderr),
            "--body", self.path("body.raw", body),
            "--notes-out", self.path("notes.txt"),
            "--body-out", self.path("existing-release-body.md"),
            "--github-output", self.path("out.txt", ""),
        ]
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = rn.main(argv)
        return code, stdout.getvalue()

    def read(self, name):
        with open(os.path.join(self.tmp, name), encoding="utf-8", newline="") as f:
            return f.read()

    def test_found_writes_notes_body_and_output(self):
        code, out = self.run_main("workflow_dispatch", 0, "", BODY)
        self.assertEqual(code, 0)
        self.assertEqual(self.read("notes.txt"), NOTES)
        self.assertNotIn("\r", self.read("existing-release-body.md"))
        self.assertEqual(self.read("out.txt"), "found=true\n")
        self.assertIn("hamlib CAT support", out)

    def test_not_found_on_push_writes_no_files(self):
        code, out = self.run_main("push", 1, "release not found", "")
        self.assertEqual(code, 0)
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "notes.txt")))
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "existing-release-body.md")))
        self.assertEqual(self.read("out.txt"), "found=false\n")

    def test_error_exits_1_with_an_error_annotation_and_no_files(self):
        code, out = self.run_main("workflow_dispatch", 1, "release not found", "")
        self.assertEqual(code, 1)
        self.assertTrue(out.startswith("::error::"))
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "notes.txt")))
        self.assertEqual(self.read("out.txt"), "")


if __name__ == "__main__":
    unittest.main()
