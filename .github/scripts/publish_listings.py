#!/usr/bin/env python3
"""Publish the localized Play Store listings in fastlane/metadata/android to Google Play.

The repo is the source of truth for listing *text* (title, short description,
full description) in every language the app ships. Graphics (icon, feature
graphic, screenshots) are NOT touched — Play falls back to the default
language's graphics for any locale that has none of its own, and screenshots
are still uploaded by hand (see docs/store-listings.md).

Release notes are deliberately out of scope: android.yml already writes
distribution/whatsnew/whatsnew-en-US and hands it to the upload action.

Modes:
  --dry-run             Print a unified diff of what would change against the
                        live listings; send nothing. Reads only, so it does NOT
                        prove the account may edit listings — see below.
  --pull                Overwrite the local tree with what is live on Play
                        (bootstrap / resync after someone edits in the Console).
  --check-permissions   Write one listing (PATCH of one Play already has, or
                        PUT when Play has none yet) inside an edit that is then
                        abandoned, to prove the service account holds the
                        "Manage store presence" permission. Nothing is committed.
  --annotate-verdict N  Print the GitHub Actions annotation for probe exit code
                        N (see probe_annotation) and exit 0. The workflow relays
                        the probe's verdict through this so the mapping is
                        unit-tested here instead of living in bash.
  (default)             Push every locale whose text differs from live, then
                        commit the edit so it goes to Play for review.

Env:
  PLAY_SERVICE_ACCOUNT_JSON  service account JSON (contents, not a path)
  PACKAGE_NAME               defaults to radio.ks3ckc.ft8af
"""
import argparse
import difflib
import json
import os
import sys
from pathlib import Path

API = "https://androidpublisher.googleapis.com/androidpublisher/v3"

DEFAULT_PACKAGE = "radio.ks3ckc.ft8af"
DEFAULT_ROOT = Path(__file__).resolve().parents[2] / "fastlane" / "metadata" / "android"

# Play Console limits. Exceeding any of these is rejected by the API, so we
# check locally first and name the offending file rather than surfacing a 400.
LIMITS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}

# Exit codes. The probe's result is a verdict, not pass/fail, so it needs codes
# that nothing else can produce: annotating an unrelated failure as "you are
# missing the grant" sends someone to fix a permission that was never wrong.
#
# 2 is deliberately skipped — argparse exits 2 on a usage error, so a mistyped
# flag would otherwise be indistinguishable from a probe verdict.
EXIT_OK = 0
EXIT_ERROR = 1  # generic failure: unpublishable metadata, bad credentials, API error
EXIT_DENIED = 3  # --check-permissions: Play refused the listing write with 403
EXIT_INCONCLUSIVE = 4  # --check-permissions: the probe never reached a verdict

# Where the missing grant is set, quoted in every message that names it.
GRANT_ADVICE = (
    "Play Console -> Users and permissions -> App permissions -> Store presence "
    '-> "Manage store presence"'
)


def probe_annotation(rc):
    """(level, message) the workflow annotates a --check-permissions run with.

    Lives here rather than in the workflow's bash so the mapping is tested
    against the EXIT_* constants it depends on: swapping the 3/4 arms, or
    reporting a generic 1 as a missing grant, would send an operator to change
    a Console permission that was never wrong, and a bash `case` cannot be
    unit-tested. Only 3 and 4 are verdicts; everything else means the probe
    never ran, which is deliberately NOT annotated as anything about the grant.
    """
    if rc == EXIT_OK:
        return "notice", "The service account can edit listings."
    if rc == EXIT_DENIED:
        return "error", "The service account cannot edit listings. Grant it %s." % GRANT_ADVICE
    if rc == EXIT_INCONCLUSIVE:
        return (
            "warning",
            "The probe reached no verdict — the API call failed for a reason that says "
            "nothing about the grant (401: the token was not accepted, a credentials "
            "problem; or a timeout, rate limit, Play 5xx). Fix the cause and run it again.",
        )
    return (
        "error",
        "The probe did not run (exit %d) — see the log above. This is not a verdict "
        "about the grant." % rc,
    )


def format_annotation(level, message):
    """A GitHub Actions workflow command: `::level::message` on its own line."""
    return "::%s::%s" % (level, message)


# Listing field <-> fastlane filename.
FIELD_FILES = {
    "title": "title.txt",
    "shortDescription": "short_description.txt",
    "fullDescription": "full_description.txt",
}


class MetadataError(Exception):
    """A locale directory is missing a file or a field is over the Play limit."""


class CredentialsError(Exception):
    """PLAY_SERVICE_ACCOUNT_JSON is missing or is not the service account JSON."""


def read_locale(locale_dir):
    """Read one locale directory into a listing dict. Raises MetadataError if incomplete.

    Trailing newlines are stripped: the files end with one for POSIX tidiness,
    but Play stores the text verbatim and a trailing blank line shows up in the
    rendered listing.
    """
    listing = {}
    for field, fname in FIELD_FILES.items():
        path = locale_dir / fname
        if not path.is_file():
            raise MetadataError("%s: missing %s" % (locale_dir.name, fname))
        text = path.read_text(encoding="utf-8").rstrip("\n")
        if not text.strip():
            raise MetadataError("%s: %s is empty" % (locale_dir.name, fname))
        listing[field] = text
    return listing


def validate(locale, listing):
    """Return a list of human-readable limit violations for one listing."""
    errors = []
    for field, limit in LIMITS.items():
        n = len(listing[field])
        if n > limit:
            errors.append(
                "%s: %s is %d characters, limit is %d (over by %d)"
                % (locale, FIELD_FILES[field], n, limit, n - limit)
            )
    return errors


def locale_dirs(root):
    """Return the locale directories under root, sorted. Dot-directories are skipped."""
    root = Path(root)
    if not root.is_dir():
        return []
    return sorted(d for d in root.iterdir() if d.is_dir() and not d.name.startswith("."))


def load_metadata(root):
    """Load every locale directory under root. Raises MetadataError on any problem."""
    root = Path(root)
    if not root.is_dir():
        raise MetadataError("metadata root does not exist: %s" % root)
    locales = locale_dirs(root)
    if not locales:
        raise MetadataError("no locale directories under %s" % root)
    out = {}
    errors = []
    for d in locales:
        listing = read_locale(d)
        errors.extend(validate(d.name, listing))
        out[d.name] = listing
    if errors:
        raise MetadataError("\n".join(errors))
    return out


def diff_listing(local, remote):
    """Return {field: (old, new)} for fields that differ. remote may be None (new locale)."""
    changed = {}
    for field in FIELD_FILES:
        old = (remote or {}).get(field) or ""
        new = local[field]
        if old != new:
            changed[field] = (old, new)
    return changed


def summarize(field, old, new):
    """One-line, terminal-safe description of a field change."""
    if not old:
        return "%s: (unset) -> %d chars" % (field, len(new))
    return "%s: %d chars -> %d chars" % (field, len(old), len(new))


def diff_text(locale, field, old, new, context=1):
    """Unified diff of one field, Play's copy against the repo's.

    Character counts alone hide a same-length edit — a fixed typo or a swapped
    word reads as "3303 chars -> 3303 chars" — so a dry run, whose whole job is
    to show what a real run would send, prints this instead.
    """
    lines = difflib.unified_diff(
        old.splitlines(),
        new.splitlines(),
        fromfile="play:%s/%s" % (locale, FIELD_FILES[field]),
        tofile="repo:%s/%s" % (locale, FIELD_FILES[field]),
        lineterm="",
        n=context,
    )
    return "\n".join(lines)


# --- Play API ---------------------------------------------------------------


def service_account_info(env=None):
    """Parse PLAY_SERVICE_ACCOUNT_JSON into a dict, or explain what is wrong with it.

    Kept separate from play_session() so the failure is a one-line message rather
    than a KeyError traceback, and so it can be tested without google-auth.
    """
    env = os.environ if env is None else env
    raw = env.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        raise CredentialsError(
            "PLAY_SERVICE_ACCOUNT_JSON is not set. Export the service account JSON "
            "itself (not a path to it):\n"
            '  export PLAY_SERVICE_ACCOUNT_JSON="$(cat service-account.json)"'
        )
    try:
        info = json.loads(raw)
    except ValueError as e:
        raise CredentialsError(
            "PLAY_SERVICE_ACCOUNT_JSON is not valid JSON (%s). It must hold the "
            "file's contents, not its path." % e
        )
    if not isinstance(info, dict) or "client_email" not in info:
        raise CredentialsError(
            "PLAY_SERVICE_ACCOUNT_JSON parsed but has no client_email — that is not "
            "a Google service account key."
        )
    return info


def build_credentials(info, factory):
    """Build Play credentials from `info`, turning a bad key into a CredentialsError.

    service_account_info() only proves the JSON parses and names a service
    account. google-auth is what discovers the rest — a missing private_key or
    token_uri, a corrupted PEM — and it signals that with ValueError
    (MalformedError subclasses it). main() handles CredentialsError, so without
    this conversion a bad key file escapes as a traceback.

    `factory` is passed in so this is testable without google-auth installed.
    """
    try:
        return factory(info, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    except ValueError as e:
        raise CredentialsError(
            "PLAY_SERVICE_ACCOUNT_JSON parsed but is not a usable service account "
            "key (%s). Re-download the key from the Google Cloud console." % e
        )


def play_session():
    import requests
    from google.auth.transport.requests import Request as GAuthRequest
    from google.oauth2 import service_account

    creds = build_credentials(
        service_account_info(), service_account.Credentials.from_service_account_info
    )
    creds.refresh(GAuthRequest())
    s = requests.Session()
    s.headers["Authorization"] = "Bearer %s" % creds.token
    return s


def fetch_listings(s, package, edit_id):
    """Return {language: listing} for what is currently live."""
    r = s.get("%s/applications/%s/edits/%s/listings" % (API, package, edit_id), timeout=30)
    r.raise_for_status()
    return {li["language"]: li for li in r.json().get("listings", [])}


def abandon_edit(s, package, edit_id):
    """Delete an uncommitted edit. Returns True if Play accepted the delete.

    Deliberately does not raise, for either an error status or a transport
    failure: this runs in a finally block, so anything escaping here would
    replace whatever the caller was already failing with (a commit error, say)
    with a cleanup error. A failed delete is not fatal either — Play expires
    abandoned edits on its own — but it is worth saying out loud, because until
    it expires it is the app's one open edit.
    """
    trouble = None
    try:
        r = s.delete("%s/applications/%s/edits/%s" % (API, package, edit_id), timeout=30)
    except Exception as e:
        # Broad on purpose. A timeout or dropped connection here is exactly the
        # case where the original failure matters most, and requests is imported
        # lazily so its exception types are not in scope to name.
        trouble = "%s: %s" % (type(e).__name__, e)
    else:
        if r.ok:
            return True
        trouble = "HTTP %s" % r.status_code

    print(
        "Warning: could not abandon edit %s (%s). It will expire on its own, but "
        "until then a release publish may fail with \"This edit has expired\"."
        % (edit_id, trouble),
        file=sys.stderr,
    )
    return False


def patch_listing(s, package, edit_id, locale, listing):
    """PATCH an EXISTING listing, so a promo video already on it is left alone.

    Only valid for a language Play already has: PATCH is an update, and the API
    answers 404 for a language with no listing yet. Use put_listing to create.
    """
    r = s.patch(
        "%s/applications/%s/edits/%s/listings/%s" % (API, package, edit_id, locale),
        json=listing,
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


def put_listing(s, package, edit_id, locale, listing):
    """PUT one listing, creating it if the language has none yet.

    The body carries `language` because PUT replaces the whole resource. Nothing
    is lost by replacing here: this is only used for languages Play has never
    had a listing for, so there is no video or other field to preserve.
    """
    body = dict(listing, language=locale)
    r = s.put(
        "%s/applications/%s/edits/%s/listings/%s" % (API, package, edit_id, locale),
        json=body,
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


def upsert_listing(s, package, edit_id, locale, listing, exists):
    """Create or update one listing, whichever the language needs."""
    if exists:
        return patch_listing(s, package, edit_id, locale, listing)
    return put_listing(s, package, edit_id, locale, listing)


# --- Modes ------------------------------------------------------------------


def run_pull(s, package, edit_id, root):
    remote = fetch_listings(s, package, edit_id)
    if not remote:
        print("No listings live on Play — nothing to pull.")
        return 0
    for locale, li in sorted(remote.items()):
        d = Path(root) / locale
        d.mkdir(parents=True, exist_ok=True)
        for field, fname in FIELD_FILES.items():
            with open(d / fname, "w", encoding="utf-8", newline="\n") as fh:
                fh.write((li.get(field) or "").rstrip("\n") + "\n")
        print("pulled %s" % locale)

    # Locales in the repo that Play has never seen are the normal state for a
    # language whose listing has not been published yet — deleting them here
    # would throw away exactly the work --pull is meant to protect. Name them so
    # the operator can tell "not published yet" from "retired upstream"; a
    # genuinely retired language is removed by hand.
    local_only = [d.name for d in locale_dirs(root) if d.name not in remote]
    if local_only:
        print(
            "\nNote: %d locale(s) exist here but not on Play, and were left alone: %s\n"
            "      They are unpublished until the next push. Delete a directory by hand "
            "only if that language is being retired." % (len(local_only), ", ".join(local_only))
        )
    return 0


def run_check(s, package, edit_id, local):
    """Prove the service account may actually edit listings, without publishing.

    A dry run cannot answer this: it only reads. An account with release
    permission but not "Manage store presence" passes a dry
    run and then fails on the first real publish. So do the one thing that
    exercises the grant — writing a single listing (listings.patch on a language
    Play already has, listings.put when it has none yet) — inside an edit the
    caller abandons instead of committing. Nothing reaches the store.

    The probe writes back the text Play already has wherever possible, so even a
    committed edit (which cannot happen here) would be a no-op.

    Returns EXIT_OK if the account may edit listings, EXIT_DENIED if Play
    refused with 403 (an authenticated account that lacks the grant), and
    EXIT_INCONCLUSIVE if anything else went wrong. A 401 is inconclusive too:
    it means the access token was not accepted at all, which is a credentials
    problem and says nothing about the grant. Likewise a timeout or a 5xx proves
    nothing either way and must not be reported as a missing grant.
    """
    try:
        remote = fetch_listings(s, package, edit_id)
    except Exception as e:
        print(
            "INCONCLUSIVE: could not read the current listings (%s).\n\nThe probe "
            "never got as far as testing the grant. Try again." % e,
            file=sys.stderr,
        )
        return EXIT_INCONCLUSIVE
    if remote:
        locale = "en-US" if "en-US" in remote else sorted(remote)[0]
        probe = {f: remote[locale].get(f) or "" for f in FIELD_FILES}
        note = "writing back its current text"
    else:
        locale = "en-US" if "en-US" in local else sorted(local)[0]
        probe = local[locale]
        note = "no listings live yet, so using the repo's text"

    verb = "listings.patch" if locale in remote else "listings.put"
    print("Probing %s on %s (%s)..." % (verb, locale, note))
    try:
        upsert_listing(s, package, edit_id, locale, probe, locale in remote)
    except Exception as e:
        status = getattr(getattr(e, "response", None), "status_code", None)
        if status == 403:
            print(
                "DENIED (HTTP 403): %s\n\nThe service account cannot edit listings. "
                'Grant it Play Console -> Users and permissions -> App permissions '
                '-> Store presence -> "Manage store presence".' % e,
                file=sys.stderr,
            )
            return EXIT_DENIED
        if status == 401:
            # Only a 403 is a verdict about the grant: it means Play knew who was
            # asking and said no. A 401 means the token itself was not accepted
            # (revoked key, wrong project, clock skew) — the probe never got as
            # far as the permission check, so sending someone to Console to fix
            # a grant would be the same false diagnosis this split exists to
            # prevent.
            print(
                "INCONCLUSIVE (HTTP 401): %s\n\nPlay did not accept the access "
                "token, so the probe never reached the permission check. This is "
                "a credentials problem, not evidence about the grant: check the "
                "service-account key and run it again." % e,
                file=sys.stderr,
            )
            return EXIT_INCONCLUSIVE
        # A timeout, a rate limit, or a Play 5xx says nothing about the grant.
        # Calling those "permission denied" would send someone editing Console
        # permissions that were fine all along.
        print(
            "INCONCLUSIVE%s: %s\n\nThe probe did not reach a verdict — this is an "
            "API failure, not evidence about the grant. Try again."
            % ("" if status is None else " (HTTP %s)" % status, e),
            file=sys.stderr,
        )
        return EXIT_INCONCLUSIVE
    print("OK — the service account can edit listings. The edit is discarded, not committed.")
    return EXIT_OK


def run_push(s, package, edit_id, local, dry_run):
    remote = fetch_listings(s, package, edit_id)
    pending = {}
    for locale in sorted(local):
        changed = diff_listing(local[locale], remote.get(locale))
        if not changed:
            print("%-8s unchanged" % locale)
            continue
        pending[locale] = local[locale]
        for field, (old, new) in sorted(changed.items()):
            print("%-8s %s" % (locale, summarize(field, old, new)))
            if dry_run:
                body = diff_text(locale, field, old, new)
                if body:
                    print("\n".join("    " + ln for ln in body.splitlines()))

    extra = sorted(set(remote) - set(local))
    if extra:
        print(
            "\nNote: %d locale(s) live on Play have no directory in the repo and are "
            "left untouched: %s" % (len(extra), ", ".join(extra))
        )

    if not pending:
        print("\nAll %d locale(s) already match Play. Nothing to do." % len(local))
        return 0
    if dry_run:
        print("\nDRY RUN — %d locale(s) would be updated. Nothing was sent." % len(pending))
        return 0

    for locale, listing in sorted(pending.items()):
        existed = locale in remote
        upsert_listing(s, package, edit_id, locale, listing, existed)
        print("pushed %s%s" % (locale, "" if existed else " (created)"))
    return len(pending)


def build_arg_parser():
    """Build the CLI parser.

    Separate from main() so the tests can introspect the real option strings and
    check them against the modes play-listings.yml offers — renaming a flag here
    without updating the workflow would otherwise only surface as a failed
    manual run.
    """
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dry-run", action="store_true", help="show the diff, change nothing")
    ap.add_argument("--pull", action="store_true", help="overwrite the local tree from Play")
    ap.add_argument(
        "--check-permissions",
        action="store_true",
        help="verify the service account may edit listings, without publishing",
    )
    ap.add_argument("--root", default=str(DEFAULT_ROOT), help="metadata root directory")
    ap.add_argument("--package", default=os.environ.get("PACKAGE_NAME", DEFAULT_PACKAGE))
    ap.add_argument(
        "--annotate-verdict",
        type=int,
        metavar="RC",
        help="print the GitHub Actions annotation for probe exit code RC and exit 0",
    )
    return ap


def main(argv=None):
    ap = build_arg_parser()
    args = ap.parse_args(argv)

    if args.annotate_verdict is not None:
        # Pure formatting for the workflow: no metadata, no credentials, no
        # Play. The workflow exits with the probe's own code afterwards.
        print(format_annotation(*probe_annotation(args.annotate_verdict)))
        return EXIT_OK

    chosen = [
        name
        for name, on in (
            ("--dry-run", args.dry_run),
            ("--pull", args.pull),
            ("--check-permissions", args.check_permissions),
        )
        if on
    ]
    if len(chosen) > 1:
        ap.error("%s are mutually exclusive" % " and ".join(chosen))

    local = None
    if not args.pull:
        try:
            local = load_metadata(args.root)
        except MetadataError as e:
            print("Metadata is not publishable:\n%s" % e, file=sys.stderr)
            return EXIT_ERROR
        print("Loaded %d locale(s) from %s\n" % (len(local), args.root))

    try:
        s = play_session()
    except CredentialsError as e:
        print("%s" % e, file=sys.stderr)
        return EXIT_ERROR

    try:
        edit = s.post("%s/applications/%s/edits" % (API, args.package), timeout=30)
        edit.raise_for_status()
        edit_id = edit.json()["id"]
    except Exception as e:
        # In probe mode this must not surface as a traceback: Python would exit
        # 1, which the workflow would read as a denied verdict.
        if not args.check_permissions:
            raise
        print(
            "INCONCLUSIVE: could not open an edit (%s).\n\nThe probe never got as "
            "far as testing the grant. Try again." % e,
            file=sys.stderr,
        )
        return EXIT_INCONCLUSIVE

    try:
        if args.pull:
            return run_pull(s, args.package, edit_id, args.root)
        if args.check_permissions:
            return run_check(s, args.package, edit_id, local)
        pushed = run_push(s, args.package, edit_id, local, args.dry_run)
        if pushed and not args.dry_run:
            c = s.post(
                "%s/applications/%s/edits/%s:commit" % (API, args.package, edit_id), timeout=60
            )
            c.raise_for_status()
            print("\nCommitted edit %s — %d locale(s) sent to Play." % (edit_id, pushed))
            edit_id = None  # committed; do not delete
        return EXIT_OK
    finally:
        # A --dry-run / --pull / no-op edit is abandoned so it does not linger as
        # the app's one open edit and block the release publish in android.yml.
        if edit_id is not None:
            abandon_edit(s, args.package, edit_id)


if __name__ == "__main__":
    sys.exit(main())
