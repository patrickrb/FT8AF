#!/usr/bin/env python3
"""Unit tests for publish_listings.py.

Stdlib only, no network: the Play API calls live behind play_session(), which
imports requests/google-auth lazily so this module imports cleanly without them.

Run from the repo root:  python -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import shutil
import tempfile
import unittest
from pathlib import Path

import publish_listings as pl

REPO_METADATA = Path(__file__).resolve().parents[2] / "fastlane" / "metadata" / "android"


def write_locale(root, locale, title="FT8AF", short="short desc", full="full desc"):
    d = Path(root) / locale
    d.mkdir(parents=True, exist_ok=True)
    for fname, text in (
        ("title.txt", title),
        ("short_description.txt", short),
        ("full_description.txt", full),
    ):
        with open(d / fname, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text + "\n")
    return d


class TempTreeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)


class ReadLocaleTest(TempTreeTest):
    def test_reads_three_fields_and_strips_trailing_newline(self):
        d = write_locale(self.tmp, "en-US", full="line one\nline two")
        listing = pl.read_locale(d)
        self.assertEqual(
            listing,
            {"title": "FT8AF", "shortDescription": "short desc", "fullDescription": "line one\nline two"},
        )

    def test_internal_blank_lines_are_preserved(self):
        # Paragraph breaks are meaningful in the full description; only the
        # file's own trailing newline is dropped.
        d = write_locale(self.tmp, "en-US", full="para one\n\npara two")
        self.assertEqual(pl.read_locale(d)["fullDescription"], "para one\n\npara two")

    def test_missing_file_is_an_error(self):
        d = write_locale(self.tmp, "fr-FR")
        (d / "short_description.txt").unlink()
        with self.assertRaises(pl.MetadataError) as cm:
            pl.read_locale(d)
        self.assertIn("short_description.txt", str(cm.exception))

    def test_whitespace_only_file_is_an_error(self):
        d = write_locale(self.tmp, "fr-FR", short="   ")
        with self.assertRaises(pl.MetadataError) as cm:
            pl.read_locale(d)
        self.assertIn("empty", str(cm.exception))


class ValidateTest(unittest.TestCase):
    def test_within_limits_has_no_errors(self):
        listing = {"title": "FT8AF", "shortDescription": "x" * 80, "fullDescription": "y" * 4000}
        self.assertEqual(pl.validate("en-US", listing), [])

    def test_reports_each_over_limit_field_with_the_overage(self):
        listing = {
            "title": "T" * 31,
            "shortDescription": "S" * 85,
            "fullDescription": "F" * 10,
        }
        errors = pl.validate("de-DE", listing)
        self.assertEqual(len(errors), 2)
        joined = "\n".join(errors)
        self.assertIn("de-DE", joined)
        self.assertIn("title.txt is 31 characters, limit is 30 (over by 1)", joined)
        self.assertIn("short_description.txt is 85 characters, limit is 80 (over by 5)", joined)

    def test_limits_count_characters_not_bytes(self):
        # CJK and accented text is well under 80 characters but far over 80
        # bytes; Play counts characters, so this must pass.
        listing = {
            "title": "FT8AF",
            "shortDescription": "用手机玩 FT8：解码、发射、自动记录日志，不用电脑。",
            "fullDescription": "描述",
        }
        self.assertEqual(pl.validate("zh-CN", listing), [])


class LoadMetadataTest(TempTreeTest):
    def test_loads_every_locale_directory(self):
        write_locale(self.tmp, "en-US")
        write_locale(self.tmp, "ja-JP")
        loaded = pl.load_metadata(self.tmp)
        self.assertEqual(sorted(loaded), ["en-US", "ja-JP"])

    def test_dot_directories_are_skipped(self):
        write_locale(self.tmp, "en-US")
        (Path(self.tmp) / ".git").mkdir()
        self.assertEqual(sorted(pl.load_metadata(self.tmp)), ["en-US"])

    def test_missing_root_is_an_error(self):
        with self.assertRaises(pl.MetadataError):
            pl.load_metadata(Path(self.tmp) / "nope")

    def test_empty_root_is_an_error(self):
        with self.assertRaises(pl.MetadataError):
            pl.load_metadata(self.tmp)

    def test_over_limit_locale_fails_the_whole_load(self):
        write_locale(self.tmp, "en-US")
        write_locale(self.tmp, "ru-RU", short="R" * 100)
        with self.assertRaises(pl.MetadataError) as cm:
            pl.load_metadata(self.tmp)
        self.assertIn("ru-RU", str(cm.exception))

    def test_all_over_limit_locales_are_reported_together(self):
        # One run should name every bad file, not just the first.
        write_locale(self.tmp, "ru-RU", short="R" * 100)
        write_locale(self.tmp, "uk", title="U" * 40)
        with self.assertRaises(pl.MetadataError) as cm:
            pl.load_metadata(self.tmp)
        self.assertIn("ru-RU", str(cm.exception))
        self.assertIn("uk", str(cm.exception))


class DiffListingTest(unittest.TestCase):
    LOCAL = {"title": "FT8AF", "shortDescription": "new short", "fullDescription": "new full"}

    def test_identical_listing_has_no_diff(self):
        self.assertEqual(pl.diff_listing(self.LOCAL, dict(self.LOCAL)), {})

    def test_extra_remote_fields_are_ignored(self):
        # The API returns `video` and `language` too; neither is ours to manage.
        remote = dict(self.LOCAL, video="https://youtu.be/x", language="en-US")
        self.assertEqual(pl.diff_listing(self.LOCAL, remote), {})

    def test_changed_field_is_reported_with_old_and_new(self):
        remote = dict(self.LOCAL, shortDescription="old short")
        self.assertEqual(
            pl.diff_listing(self.LOCAL, remote), {"shortDescription": ("old short", "new short")}
        )

    def test_missing_remote_locale_reports_every_field(self):
        self.assertEqual(sorted(pl.diff_listing(self.LOCAL, None)), sorted(pl.FIELD_FILES))

    def test_remote_null_field_counts_as_unset(self):
        # The API returns JSON null for a field that was never filled in.
        remote = dict(self.LOCAL, fullDescription=None)
        self.assertEqual(pl.diff_listing(self.LOCAL, remote), {"fullDescription": ("", "new full")})


class SummarizeTest(unittest.TestCase):
    def test_unset_old_value_is_called_out(self):
        self.assertEqual(pl.summarize("title", "", "FT8AF"), "title: (unset) -> 5 chars")

    def test_changed_value_shows_both_lengths(self):
        self.assertEqual(pl.summarize("title", "old", "FT8AF"), "title: 3 chars -> 5 chars")


class ServiceAccountInfoTest(unittest.TestCase):
    GOOD = '{"type": "service_account", "client_email": "ci@ft8af.iam.gserviceaccount.com"}'

    def test_valid_json_is_parsed(self):
        info = pl.service_account_info({"PLAY_SERVICE_ACCOUNT_JSON": self.GOOD})
        self.assertEqual(info["client_email"], "ci@ft8af.iam.gserviceaccount.com")

    def test_missing_variable_explains_how_to_set_it(self):
        with self.assertRaises(pl.CredentialsError) as cm:
            pl.service_account_info({})
        self.assertIn("is not set", str(cm.exception))

    def test_whitespace_only_counts_as_missing(self):
        with self.assertRaises(pl.CredentialsError):
            pl.service_account_info({"PLAY_SERVICE_ACCOUNT_JSON": "   \n"})

    def test_a_path_instead_of_contents_is_caught(self):
        # The classic mistake: exporting the filename rather than `$(cat file)`.
        with self.assertRaises(pl.CredentialsError) as cm:
            pl.service_account_info({"PLAY_SERVICE_ACCOUNT_JSON": "/home/me/sa.json"})
        self.assertIn("not valid JSON", str(cm.exception))

    def test_json_without_client_email_is_rejected(self):
        with self.assertRaises(pl.CredentialsError) as cm:
            pl.service_account_info({"PLAY_SERVICE_ACCOUNT_JSON": '{"type": "authorized_user"}'})
        self.assertIn("client_email", str(cm.exception))

    def test_valid_json_of_the_wrong_shape_is_rejected(self):
        with self.assertRaises(pl.CredentialsError):
            pl.service_account_info({"PLAY_SERVICE_ACCOUNT_JSON": '["not", "an", "object"]'})


class MainArgsTest(unittest.TestCase):
    def test_pull_and_dry_run_are_mutually_exclusive(self):
        # argparse .error() exits 2; this must fail before any Play call.
        with self.assertRaises(SystemExit) as cm:
            pl.main(["--pull", "--dry-run"])
        self.assertEqual(cm.exception.code, 2)

    def test_unloadable_metadata_returns_1_without_touching_play(self):
        # An empty root fails in load_metadata(), which runs before play_session();
        # if the ordering ever regresses this test fails on the missing credentials.
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        self.assertEqual(pl.main(["--root", tmp, "--dry-run"]), 1)


class RepoMetadataTest(unittest.TestCase):
    """The listings actually checked in must always be publishable."""

    def test_repo_tree_loads_and_is_within_limits(self):
        loaded = pl.load_metadata(REPO_METADATA)
        self.assertIn("en-US", loaded, "en-US is the default listing and must exist")
        self.assertGreaterEqual(len(loaded), 2)

    def test_every_locale_has_the_same_title(self):
        # FT8AF is a brand name; a locale drifting to a different title would be
        # a rename in the store, not a translation.
        titles = {loc: li["title"] for loc, li in pl.load_metadata(REPO_METADATA).items()}
        self.assertEqual(set(titles.values()), {"FT8AF"}, titles)

    def test_no_locale_carries_a_byte_order_mark(self):
        # A BOM survives into the listing text and renders as a stray glyph.
        for path in sorted(REPO_METADATA.rglob("*.txt")):
            with open(path, "rb") as fh:
                self.assertFalse(
                    fh.read(3).startswith(b"\xef\xbb\xbf"), "%s starts with a UTF-8 BOM" % path
                )


if __name__ == "__main__":
    unittest.main()
