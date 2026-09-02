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
            "restore",
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

    def test_restored_notes_are_byte_capped_for_play(self):
        body = "<!-- ft8af-notes-start -->\n" + "\u0416" * 300 + "\n<!-- ft8af-notes-end -->\n"
        code, out = self.run_main("workflow_dispatch", 0, "", body)
        self.assertEqual(code, 0)
        self.assertLessEqual(len(self.read("notes.txt").encode("utf-8")), rn.PLAY_NOTES_LIMIT)
        # The body itself is untouched: only Play's copy is capped.
        self.assertIn("\u0416" * 300, self.read("existing-release-body.md"))

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


class EnsureNotesTest(unittest.TestCase):
    PR_LIST = (
        "- #801 rigs: Follow IC-705 dial changes\n"
        "- #802 Fix Bluetooth CAT write race\n"
        "- #803 ci: stop publishing to Play production on main merges\n"
    )

    def test_nonblank_notes_are_left_alone(self):
        notes, fell_back = rn.ensure_notes("Real notes.\n", self.PR_LIST)
        self.assertEqual(notes, "Real notes.\n")
        self.assertFalse(fell_back)

    def test_blank_notes_become_the_pr_titles(self):
        # Claude's schema allows an empty string and `jq -r` writes it as a
        # bare newline: exactly what used to produce blank markers.
        for blank in ("", "\n", "   \n\n", None):
            notes, fell_back = rn.ensure_notes(blank, self.PR_LIST)
            self.assertTrue(fell_back, repr(blank))
            self.assertEqual(
                notes,
                "Follow IC-705 dial changes\n"
                "Fix Bluetooth CAT write race\n"
                "stop publishing to Play production on main merges\n",
            )

    def test_blank_notes_and_no_prs_use_the_default_text(self):
        # The workflow never hands over an empty list: with no PR merges in the
        # range it writes the "(no pull-request merges in range)" placeholder,
        # which is a log line, not a release note.
        for no_prs in ("", rn.NO_PRS_SENTINEL, rn.NO_PRS_SENTINEL + "\n"):
            notes, fell_back = rn.ensure_notes("\n", no_prs)
            self.assertTrue(fell_back, repr(no_prs))
            self.assertEqual(notes, rn.DEFAULT_NOTES + "\n", repr(no_prs))
            self.assertNotIn("pull-request", notes)

    def test_sentinel_matches_what_the_workflow_writes(self):
        # Drift guard: the placeholder is spelled in android.yml; if it changes
        # there, this must change too or Play gets the placeholder as notes.
        wf = os.path.join(os.path.dirname(__file__), "..", "workflows", "android.yml")
        with open(wf, encoding="utf-8") as f:
            self.assertIn(":-" + rn.NO_PRS_SENTINEL + "}", f.read())

    def test_fallback_keeps_whole_lines_under_plays_limit(self):
        long_list = "".join("- #%d %s\n" % (i, "x" * 120) for i in range(10))
        notes = rn.fallback_notes(long_list)
        self.assertLessEqual(len(notes), rn.PLAY_NOTES_LIMIT)
        self.assertEqual(notes.count("\n"), 4)  # 4 x 121 = 484 fits, 5 would not
        self.assertTrue(all(len(l) == 120 for l in notes.splitlines()))

    def test_fallback_budgets_bytes_not_characters(self):
        # 120 emoji is 120 characters but 480 bytes: one line fits, a second
        # would not. A character budget would have taken four such lines and
        # left `head -c 500` to cut the second one mid-codepoint.
        emoji_line = "\U0001F4E1" * 120
        long_list = "".join("- #%d %s\n" % (i, emoji_line) for i in range(4))
        notes = rn.fallback_notes(long_list)
        self.assertEqual(notes, emoji_line + "\n")
        self.assertLessEqual(len(notes.encode("utf-8")), rn.PLAY_NOTES_LIMIT)
        # The byte-truncated file must still be valid UTF-8 (a no-op here).
        notes.encode("utf-8")[: rn.PLAY_NOTES_LIMIT].decode("utf-8")

    def test_cap_notes_cuts_multibyte_text_at_a_line_then_a_codepoint(self):
        # Claude's notes are asked to stay under 400 characters, which Cyrillic
        # (2 bytes each) pushes past 500 bytes.
        line = "\u0416" * 100  # 200 bytes + newline
        three_lines = (line + "\n") * 3  # 603 bytes
        capped = rn.cap_notes(three_lines)
        self.assertEqual(capped, (line + "\n") * 2)  # 402 bytes, cut at a line
        one_long_line = "\u0416" * 300  # 600 bytes, no line break to cut at
        capped = rn.cap_notes(one_long_line)
        self.assertLessEqual(len(capped.encode("utf-8")), rn.PLAY_NOTES_LIMIT)
        self.assertEqual(capped, "\u0416" * 250)  # whole codepoints only
        self.assertEqual(rn.cap_notes("short\n"), "short\n")

    def test_ensure_notes_caps_real_notes_too(self):
        notes, fell_back = rn.ensure_notes("\u0416" * 300 + "\n", "")
        self.assertFalse(fell_back)
        self.assertLessEqual(len(notes.encode("utf-8")), rn.PLAY_NOTES_LIMIT)

    def test_round_trip_producer_to_manual_ship(self):
        # The producer/consumer case: notes that went through ensure_notes
        # land between the markers "Write release notes" emits, and the manual
        # ship's restore accepts that body — while a blank producer output that
        # skipped ensure_notes would have been refused.
        def body_for(notes):
            return ("## Release notes\n\n%s\n%s\n%s\n\n<!-- ft8af-version: 0.151.0 -->\n"
                    % (rn.START, notes, rn.END))
        blank, _ = "\n", None
        refused = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", body_for(blank))
        self.assertIsNotNone(refused.error)
        ensured, _ = rn.ensure_notes(blank, self.PR_LIST)
        accepted = rn.decide("workflow_dispatch", "android-v0.151.0", 0, "", body_for(ensured))
        self.assertTrue(accepted.found)
        self.assertIn("Follow IC-705 dial changes", accepted.notes)


class EnsureNotesMainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.notes = os.path.join(self.tmp, "notes.txt")

    def run_ensure(self, content, pr_list):
        if content is not None:
            with open(self.notes, "w", encoding="utf-8", newline="") as f:
                f.write(content)
        os.environ["PR_LIST_TEST"] = pr_list
        try:
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = rn.main(["ensure-notes", "--notes", self.notes, "--pr-list-env", "PR_LIST_TEST"])
        finally:
            del os.environ["PR_LIST_TEST"]
        with open(self.notes, encoding="utf-8", newline="") as f:
            return code, stdout.getvalue(), f.read()

    def test_blank_file_is_rewritten_with_a_warning(self):
        code, out, notes = self.run_ensure("\n", "- #1 A title\n")
        self.assertEqual(code, 0)
        self.assertEqual(notes, "A title\n")
        self.assertIn("::warning", out)

    def test_missing_file_is_created(self):
        code, out, notes = self.run_ensure(None, rn.NO_PRS_SENTINEL)
        self.assertEqual(code, 0)
        self.assertEqual(notes, rn.DEFAULT_NOTES + "\n")
        # The warning must not claim PR titles were used when there were none.
        self.assertIn("::warning", out)
        self.assertNotIn("using the PR titles instead", out)

    def test_real_notes_are_untouched_and_silent(self):
        code, out, notes = self.run_ensure("Real notes.\n", "- #1 A title\n")
        self.assertEqual(code, 0)
        self.assertEqual(notes, "Real notes.\n")
        self.assertEqual(out, "")

    def test_oversized_real_notes_are_trimmed_with_a_warning(self):
        code, out, notes = self.run_ensure(("\u0416" * 100 + "\n") * 3, "")
        self.assertEqual(code, 0)
        self.assertLessEqual(len(notes.encode("utf-8")), rn.PLAY_NOTES_LIMIT)
        self.assertIn("::warning title=Release notes trimmed", out)


if __name__ == "__main__":
    unittest.main()
