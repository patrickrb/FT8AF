#!/usr/bin/env python3
"""Unit tests for publish_listings.py.

Stdlib only, no network: the Play API calls live behind play_session(), which
imports requests/google-auth lazily so this module imports cleanly without them.

Run from the repo root:  python -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import contextlib
import io
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import publish_listings as pl

REPO_ROOT = Path(__file__).resolve().parents[2]
REPO_METADATA = REPO_ROOT / "fastlane" / "metadata" / "android"
APP_RES = REPO_ROOT / "ft8af" / "app" / "src" / "main" / "res"

# Android resource qualifier -> Play Console locale code. Play codes are not the
# resource qualifiers (values-in is Indonesian, Play calls it id), so the mapping
# is spelled out rather than derived. Adding a language to the app means adding a
# row here and a listing directory; test_every_app_language_has_a_listing fails
# until both exist.
RES_TO_PLAY = {
    "values": "en-US",
    "values-ar": "ar",
    "values-cs": "cs-CZ",
    "values-es": "es-ES",
    "values-fr": "fr-FR",
    "values-in": "id",
    "values-it": "it-IT",
    "values-ja": "ja-JP",
    "values-ko": "ko-KR",
    "values-nl": "nl-NL",
    "values-pl": "pl-PL",
    "values-pt-rBR": "pt-BR",
    "values-ru": "ru-RU",
    "values-tr": "tr-TR",
    "values-uk": "uk",
    "values-zh-rCN": "zh-CN",
    "values-zh-rTW": "zh-TW",
}

# Play-only listings with no app-resource counterpart.
PLAY_ONLY = {"es-419"}

EXPECTED_LOCALES = set(RES_TO_PLAY.values()) | PLAY_ONLY


class FakeHTTPError(Exception):
    pass


class FakeResponse:
    def __init__(self, payload=None, status=200):
        self._payload = {} if payload is None else payload
        self.status_code = status

    @property
    def ok(self):
        return self.status_code < 400

    def raise_for_status(self):
        if not self.ok:
            raise FakeHTTPError("HTTP %d" % self.status_code)

    def json(self):
        return self._payload


class FakeSession:
    """Stands in for the requests.Session play_session() builds, recording calls."""

    def __init__(
        self,
        listings=None,
        edit_id="edit-1",
        commit_status=200,
        delete_status=200,
        delete_exc=None,
        patch_status=200,
    ):
        self.listings = dict(listings or {})
        self.edit_id = edit_id
        self.commit_status = commit_status
        self.delete_status = delete_status
        self.delete_exc = delete_exc
        self.patch_status = patch_status
        self.patched = {}
        self.commits = []
        self.deletes = []

    def get(self, url, timeout=None):
        return FakeResponse(
            {"listings": [dict(li, language=loc) for loc, li in sorted(self.listings.items())]}
        )

    def post(self, url, timeout=None):
        if url.endswith(":commit"):
            self.commits.append(url)
            return FakeResponse({"id": self.edit_id}, self.commit_status)
        return FakeResponse({"id": self.edit_id})

    def patch(self, url, json=None, timeout=None):
        locale = url.rsplit("/", 1)[-1]
        self.patched[locale] = json
        r = FakeResponse(dict(json or {}, language=locale), self.patch_status)
        r.raise_for_status()
        return r

    def delete(self, url, timeout=None):
        self.deletes.append(url)
        if self.delete_exc is not None:
            raise self.delete_exc
        return FakeResponse({}, self.delete_status)


def listing(title="FT8AF", short="short desc", full="full desc"):
    return {"title": title, "shortDescription": short, "fullDescription": full}


@contextlib.contextmanager
def captured():
    """Run with stdout and stderr captured; yields (out, err) StringIO buffers."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        yield out, err


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


class BuildCredentialsTest(unittest.TestCase):
    INFO = {"type": "service_account", "client_email": "ci@ft8af.iam.gserviceaccount.com"}

    def test_returns_what_the_factory_builds(self):
        sentinel = object()
        got = pl.build_credentials(self.INFO, lambda info, scopes: sentinel)
        self.assertIs(got, sentinel)

    def test_androidpublisher_scope_is_requested(self):
        seen = {}

        def factory(info, scopes):
            seen["scopes"] = scopes
            return object()

        pl.build_credentials(self.INFO, factory)
        self.assertEqual(seen["scopes"], ["https://www.googleapis.com/auth/androidpublisher"])

    def test_unusable_key_becomes_a_credentials_error(self):
        # google-auth raises ValueError (MalformedError subclasses it) for a key
        # that parses as JSON but has no private_key / token_uri. Without the
        # conversion this escapes main()'s handler as a traceback.
        def factory(info, scopes):
            raise ValueError("No key could be detected.")

        with self.assertRaises(pl.CredentialsError) as cm:
            pl.build_credentials(self.INFO, factory)
        self.assertIn("not a usable service account key", str(cm.exception))
        self.assertIn("No key could be detected.", str(cm.exception))


class MainArgsTest(unittest.TestCase):
    def test_pull_and_dry_run_are_mutually_exclusive(self):
        # argparse .error() exits 2; this must fail before any Play call.
        with self.assertRaises(SystemExit) as cm:
            pl.main(["--pull", "--dry-run"])
        self.assertEqual(cm.exception.code, 2)

    def test_check_permissions_conflicts_with_the_other_modes(self):
        for other in ("--pull", "--dry-run"):
            with self.assertRaises(SystemExit) as cm:
                pl.main(["--check-permissions", other])
            self.assertEqual(cm.exception.code, 2)

    def test_an_unusable_key_is_reported_in_one_line_not_a_traceback(self):
        err = pl.CredentialsError(
            "PLAY_SERVICE_ACCOUNT_JSON parsed but is not a usable service account key "
            "(No key could be detected.). Re-download the key from the Google Cloud console."
        )
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        write_locale(tmp, "en-US")
        with mock.patch.object(pl, "play_session", side_effect=err):
            with captured() as (_, stderr):
                code = pl.main(["--root", tmp])
        self.assertEqual(code, 1)
        self.assertIn("not a usable service account key", stderr.getvalue())
        self.assertNotIn("Traceback", stderr.getvalue())

    def test_unloadable_metadata_returns_1_without_touching_play(self):
        # An empty root fails in load_metadata(), which runs before play_session();
        # if the ordering ever regresses this test fails on the missing credentials.
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        self.assertEqual(pl.main(["--root", tmp, "--dry-run"]), 1)


class RunPushTest(unittest.TestCase):
    LOCAL = {"en-US": listing(), "fr-FR": listing(short="court")}

    def test_identical_text_sends_nothing(self):
        s = FakeSession(listings=dict(self.LOCAL))
        with captured() as (out, _):
            pushed = pl.run_push(s, "pkg", "e1", self.LOCAL, dry_run=False)
        self.assertEqual(pushed, 0)
        self.assertEqual(s.patched, {})
        self.assertIn("unchanged", out.getvalue())

    def test_only_changed_locales_are_patched(self):
        remote = {"en-US": listing(), "fr-FR": listing(short="ancien")}
        s = FakeSession(listings=remote)
        with captured():
            pushed = pl.run_push(s, "pkg", "e1", self.LOCAL, dry_run=False)
        self.assertEqual(pushed, 1)
        self.assertEqual(list(s.patched), ["fr-FR"])
        self.assertEqual(s.patched["fr-FR"]["shortDescription"], "court")

    def test_locale_absent_from_play_is_patched(self):
        s = FakeSession(listings={"en-US": listing()})
        with captured():
            pushed = pl.run_push(s, "pkg", "e1", self.LOCAL, dry_run=False)
        self.assertEqual(pushed, 1)
        self.assertEqual(list(s.patched), ["fr-FR"])

    def test_dry_run_reports_but_sends_nothing(self):
        s = FakeSession(listings={"en-US": listing()})
        with captured() as (out, _):
            pushed = pl.run_push(s, "pkg", "e1", self.LOCAL, dry_run=True)
        self.assertEqual(pushed, 0)
        self.assertEqual(s.patched, {})
        self.assertIn("DRY RUN", out.getvalue())
        self.assertIn("fr-FR", out.getvalue())

    def test_dry_run_prints_a_diff_of_the_changed_text(self):
        remote = {"en-US": listing(), "fr-FR": listing(short="ancien")}
        s = FakeSession(listings=remote)
        with captured() as (out, _):
            pl.run_push(s, "pkg", "e1", self.LOCAL, dry_run=True)
        body = out.getvalue()
        self.assertIn("-ancien", body)
        self.assertIn("+court", body)

    def test_real_push_does_not_print_diffs(self):
        # The diff is a review aid for dry runs; a real push just reports what
        # it sent, so CI logs stay readable.
        remote = {"en-US": listing(), "fr-FR": listing(short="ancien")}
        s = FakeSession(listings=remote)
        with captured() as (out, _):
            pl.run_push(s, "pkg", "e1", self.LOCAL, dry_run=False)
        self.assertNotIn("-ancien", out.getvalue())

    def test_locales_only_on_play_are_reported_and_left_alone(self):
        s = FakeSession(listings=dict(self.LOCAL, **{"de-DE": listing()}))
        with captured() as (out, _):
            pushed = pl.run_push(s, "pkg", "e1", self.LOCAL, dry_run=False)
        self.assertEqual(pushed, 0)
        self.assertNotIn("de-DE", s.patched)
        self.assertIn("de-DE", out.getvalue())


class DiffTextTest(unittest.TestCase):
    def test_same_length_change_is_visible(self):
        # The failure that motivated this: counts alone render a swapped word as
        # "9 chars -> 9 chars", so a dry run showed nothing at all.
        old, new = "hamvention", "Hamvention"
        self.assertEqual(pl.summarize("fullDescription", old, new), "fullDescription: 10 chars -> 10 chars")
        body = pl.diff_text("en-US", "fullDescription", old, new)
        self.assertIn("-hamvention", body)
        self.assertIn("+Hamvention", body)

    def test_headers_name_both_sides(self):
        body = pl.diff_text("fr-FR", "title", "old", "new")
        self.assertIn("play:fr-FR/title.txt", body)
        self.assertIn("repo:fr-FR/title.txt", body)

    def test_new_locale_shows_every_line_as_added(self):
        body = pl.diff_text("ja-JP", "fullDescription", "", "one\ntwo")
        self.assertIn("+one", body)
        self.assertIn("+two", body)
        self.assertNotIn("-one", body)

    def test_identical_text_produces_no_diff(self):
        self.assertEqual(pl.diff_text("en-US", "title", "FT8AF", "FT8AF"), "")


class RunCheckTest(unittest.TestCase):
    LOCAL = {"en-US": listing()}

    def test_patches_one_listing_and_never_commits(self):
        s = FakeSession(listings={"en-US": listing(short="live")})
        with captured() as (out, _):
            self.assertEqual(pl.run_check(s, "pkg", "e1", self.LOCAL), 0)
        self.assertEqual(list(s.patched), ["en-US"])
        self.assertEqual(s.commits, [], "the probe must never commit")
        self.assertIn("OK", out.getvalue())

    def test_probe_writes_back_plays_own_text(self):
        # So that even a committed edit — which cannot happen here — is a no-op.
        live = listing(short="live text", full="live full")
        s = FakeSession(listings={"en-US": live})
        with captured():
            pl.run_check(s, "pkg", "e1", self.LOCAL)
        self.assertEqual(s.patched["en-US"]["shortDescription"], "live text")

    def test_falls_back_to_repo_text_when_nothing_is_published(self):
        s = FakeSession(listings={})
        with captured() as (out, _):
            self.assertEqual(pl.run_check(s, "pkg", "e1", self.LOCAL), 0)
        self.assertEqual(s.patched["en-US"], self.LOCAL["en-US"])
        self.assertIn("no listings live yet", out.getvalue())

    def test_denied_patch_reports_the_missing_grant(self):
        s = FakeSession(listings={"en-US": listing()}, patch_status=403)
        with captured() as (_, err):
            self.assertEqual(pl.run_check(s, "pkg", "e1", self.LOCAL), 1)
        self.assertIn("cannot edit listings", err.getvalue())
        self.assertIn("Edit store listing", err.getvalue())


class RunPullTest(TempTreeTest):
    def test_writes_every_remote_locale_to_disk(self):
        s = FakeSession(listings={"en-US": listing(full="line one\nline two")})
        with captured():
            self.assertEqual(pl.run_pull(s, "pkg", "e1", self.tmp), 0)
        d = Path(self.tmp) / "en-US"
        self.assertEqual((d / "title.txt").read_text(encoding="utf-8"), "FT8AF\n")
        self.assertEqual(
            (d / "full_description.txt").read_text(encoding="utf-8"), "line one\nline two\n"
        )

    def test_pulled_tree_round_trips_through_load_metadata(self):
        s = FakeSession(listings={"en-US": listing(), "ja-JP": listing(short="短い説明")})
        with captured():
            pl.run_pull(s, "pkg", "e1", self.tmp)
        loaded = pl.load_metadata(self.tmp)
        self.assertEqual(loaded["ja-JP"]["shortDescription"], "短い説明")

    def test_null_field_from_play_is_written_as_empty(self):
        # An unfilled listing field comes back as JSON null; str + rstrip would
        # otherwise blow up on None.
        s = FakeSession(listings={"en-US": dict(listing(), fullDescription=None)})
        with captured():
            pl.run_pull(s, "pkg", "e1", self.tmp)
        self.assertEqual(
            (Path(self.tmp) / "en-US" / "full_description.txt").read_text(encoding="utf-8"), "\n"
        )

    def test_no_remote_listings_writes_nothing(self):
        s = FakeSession(listings={})
        with captured() as (out, _):
            self.assertEqual(pl.run_pull(s, "pkg", "e1", self.tmp), 0)
        self.assertEqual(list(Path(self.tmp).iterdir()), [])
        self.assertIn("nothing to pull", out.getvalue())

    def test_unpublished_local_locale_is_kept_and_reported(self):
        # The state this repo is in before the first publish: every non-English
        # locale exists locally and on nothing else. Deleting them would destroy
        # the very work --pull exists to protect.
        write_locale(self.tmp, "ja-JP", short="unpublished")
        s = FakeSession(listings={"en-US": listing()})
        with captured() as (out, _):
            pl.run_pull(s, "pkg", "e1", self.tmp)
        self.assertTrue((Path(self.tmp) / "ja-JP" / "title.txt").is_file())
        self.assertEqual(
            pl.read_locale(Path(self.tmp) / "ja-JP")["shortDescription"], "unpublished"
        )
        self.assertIn("ja-JP", out.getvalue())
        self.assertIn("left alone", out.getvalue())


class AbandonEditTest(unittest.TestCase):
    def test_successful_delete_is_quiet(self):
        s = FakeSession()
        with captured() as (out, err):
            self.assertTrue(pl.abandon_edit(s, "pkg", "e1"))
        self.assertEqual(len(s.deletes), 1)
        self.assertEqual(err.getvalue(), "")

    def test_failed_delete_warns_without_raising(self):
        s = FakeSession(delete_status=403)
        with captured() as (_, err):
            self.assertFalse(pl.abandon_edit(s, "pkg", "e1"))
        self.assertIn("could not abandon edit e1", err.getvalue())
        self.assertIn("403", err.getvalue())

    def test_transport_error_is_swallowed_and_named(self):
        # A timeout or dropped connection must not escape either — this runs in a
        # finally block, so it would mask whatever the caller was failing with.
        s = FakeSession(delete_exc=OSError("connection reset"))
        with captured() as (_, err):
            self.assertFalse(pl.abandon_edit(s, "pkg", "e1"))
        self.assertIn("could not abandon edit e1", err.getvalue())
        self.assertIn("OSError", err.getvalue())
        self.assertIn("connection reset", err.getvalue())

    def test_keyboard_interrupt_is_not_swallowed(self):
        # `except Exception` is deliberate: Ctrl-C during cleanup should still
        # stop the run rather than be reported as a failed delete.
        s = FakeSession(delete_exc=KeyboardInterrupt())
        with captured():
            with self.assertRaises(KeyboardInterrupt):
                pl.abandon_edit(s, "pkg", "e1")


class MainLifecycleTest(TempTreeTest):
    """End-to-end through main() with the Play session faked out."""

    def setUp(self):
        super().setUp()
        write_locale(self.tmp, "en-US", short="new short")

    def run_main(self, session, argv):
        with mock.patch.object(pl, "play_session", return_value=session):
            with captured() as (out, err):
                code = pl.main(["--root", self.tmp] + argv)
        return code, out.getvalue(), err.getvalue()

    def test_changed_listing_is_patched_then_committed_and_the_edit_is_kept(self):
        s = FakeSession(listings={"en-US": listing(short="old short")})
        code, out, _ = self.run_main(s, [])
        self.assertEqual(code, 0)
        self.assertEqual(list(s.patched), ["en-US"])
        self.assertEqual(len(s.commits), 1)
        self.assertEqual(s.deletes, [], "a committed edit must not be deleted")
        self.assertIn("Committed edit", out)

    def test_dry_run_commits_nothing_and_abandons_the_edit(self):
        s = FakeSession(listings={"en-US": listing(short="old short")})
        code, out, _ = self.run_main(s, ["--dry-run"])
        self.assertEqual(code, 0)
        self.assertEqual(s.patched, {})
        self.assertEqual(s.commits, [])
        self.assertEqual(len(s.deletes), 1, "a dry-run edit must not be left open")

    def test_no_op_run_abandons_the_edit(self):
        s = FakeSession(listings={"en-US": listing(short="new short")})
        code, out, _ = self.run_main(s, [])
        self.assertEqual(code, 0)
        self.assertEqual(s.commits, [])
        self.assertEqual(len(s.deletes), 1)
        self.assertIn("Nothing to do", out)

    def test_commit_failure_still_abandons_the_edit(self):
        s = FakeSession(listings={"en-US": listing(short="old short")}, commit_status=500)
        with mock.patch.object(pl, "play_session", return_value=s):
            with captured():
                with self.assertRaises(FakeHTTPError):
                    pl.main(["--root", self.tmp])
        self.assertEqual(len(s.deletes), 1, "a failed commit must not leak the edit")

    def test_commit_failure_is_not_masked_by_a_failing_cleanup(self):
        # abandon_edit runs in a finally block; if it raised, the 500 from the
        # commit would be replaced by a cleanup error and the real cause lost.
        s = FakeSession(
            listings={"en-US": listing(short="old short")}, commit_status=500, delete_status=403
        )
        with mock.patch.object(pl, "play_session", return_value=s):
            with captured() as (_, err):
                with self.assertRaises(FakeHTTPError) as cm:
                    pl.main(["--root", self.tmp])
        self.assertIn("500", str(cm.exception))
        self.assertIn("could not abandon edit", err.getvalue())

    def test_commit_failure_survives_a_cleanup_that_raises(self):
        # The strongest form of the masking guard: the DELETE itself blows up
        # mid-flight. The 500 from the commit must still be what propagates.
        s = FakeSession(
            listings={"en-US": listing(short="old short")},
            commit_status=500,
            delete_exc=OSError("connection reset"),
        )
        with mock.patch.object(pl, "play_session", return_value=s):
            with captured() as (_, err):
                with self.assertRaises(FakeHTTPError) as cm:
                    pl.main(["--root", self.tmp])
        self.assertIn("500", str(cm.exception))
        self.assertIn("connection reset", err.getvalue())

    def test_check_permissions_abandons_the_edit_and_never_commits(self):
        s = FakeSession(listings={"en-US": listing(short="live")})
        code, out, _ = self.run_main(s, ["--check-permissions"])
        self.assertEqual(code, 0)
        self.assertEqual(list(s.patched), ["en-US"])
        self.assertEqual(s.commits, [], "the probe must never commit")
        self.assertEqual(len(s.deletes), 1, "the probe edit must be abandoned")

    def test_check_permissions_returns_1_when_the_grant_is_missing(self):
        s = FakeSession(listings={"en-US": listing()}, patch_status=403)
        code, _, err = self.run_main(s, ["--check-permissions"])
        self.assertEqual(code, 1)
        self.assertIn("Edit store listing", err)
        self.assertEqual(len(s.deletes), 1)

    def test_pull_writes_the_tree_and_abandons_the_edit(self):
        s = FakeSession(listings={"fr-FR": listing(short="pulled")})
        code, _, _ = self.run_main(s, ["--pull"])
        self.assertEqual(code, 0)
        self.assertEqual(
            pl.read_locale(Path(self.tmp) / "fr-FR")["shortDescription"], "pulled"
        )
        self.assertEqual(len(s.deletes), 1)

    def test_bad_credentials_never_open_an_edit(self):
        s = FakeSession()
        with mock.patch.object(pl, "play_session", side_effect=pl.CredentialsError("nope")):
            with captured() as (_, err):
                code = pl.main(["--root", self.tmp, "--dry-run"])
        self.assertEqual(code, 1)
        self.assertIn("nope", err.getvalue())
        self.assertEqual(s.deletes, [])


class RepoMetadataTest(unittest.TestCase):
    """The listings actually checked in must always be publishable."""

    def test_repo_tree_loads_and_is_within_limits(self):
        # Exact set, not a count: a locale quietly disappearing would otherwise
        # still pass while its store page silently fell back to English.
        loaded = pl.load_metadata(REPO_METADATA)
        self.assertEqual(set(loaded), EXPECTED_LOCALES)

    def test_every_app_language_has_a_listing(self):
        # The failure this guards against: someone adds values-xx to the app and
        # ships a translated UI, but store visitors in that language still get an
        # English listing because nobody added the metadata directory.
        shipped = sorted(
            d.name
            for d in APP_RES.iterdir()
            if d.is_dir() and (d / "strings_compose.xml").is_file()
        )
        self.assertTrue(shipped, "found no translated resource directories — wrong path?")

        unmapped = [q for q in shipped if q not in RES_TO_PLAY]
        self.assertEqual(
            unmapped, [], "app language(s) with no Play locale mapping: %s" % unmapped
        )

        listings = set(pl.load_metadata(REPO_METADATA))
        missing = sorted(RES_TO_PLAY[q] for q in shipped if RES_TO_PLAY[q] not in listings)
        self.assertEqual(missing, [], "app language(s) with no store listing: %s" % missing)

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
