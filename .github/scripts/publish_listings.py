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
  --dry-run   Show what would change against the live listings; touch nothing.
  --pull      Overwrite the local tree with what is live on Play (bootstrap /
              resync after someone edits in the Console).
  (default)   Push every locale whose text differs from live, then commit the
              edit so it goes to Play for review.

Env:
  PLAY_SERVICE_ACCOUNT_JSON  service account JSON (contents, not a path)
  PACKAGE_NAME               defaults to radio.ks3ckc.ft8af
"""
import argparse
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


def play_session():
    import requests
    from google.auth.transport.requests import Request as GAuthRequest
    from google.oauth2 import service_account

    creds = service_account.Credentials.from_service_account_info(
        service_account_info(),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
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
    """PATCH one listing. PATCH (not PUT) so an existing promo video is left alone."""
    r = s.patch(
        "%s/applications/%s/edits/%s/listings/%s" % (API, package, edit_id, locale),
        json=listing,
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


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
        patch_listing(s, package, edit_id, locale, listing)
        print("pushed %s" % locale)
    return len(pending)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dry-run", action="store_true", help="show the diff, change nothing")
    ap.add_argument("--pull", action="store_true", help="overwrite the local tree from Play")
    ap.add_argument("--root", default=str(DEFAULT_ROOT), help="metadata root directory")
    ap.add_argument("--package", default=os.environ.get("PACKAGE_NAME", DEFAULT_PACKAGE))
    args = ap.parse_args(argv)

    if args.pull and args.dry_run:
        ap.error("--pull and --dry-run are mutually exclusive")

    local = None
    if not args.pull:
        try:
            local = load_metadata(args.root)
        except MetadataError as e:
            print("Metadata is not publishable:\n%s" % e, file=sys.stderr)
            return 1
        print("Loaded %d locale(s) from %s\n" % (len(local), args.root))

    try:
        s = play_session()
    except CredentialsError as e:
        print("%s" % e, file=sys.stderr)
        return 1

    edit = s.post("%s/applications/%s/edits" % (API, args.package), timeout=30)
    edit.raise_for_status()
    edit_id = edit.json()["id"]

    try:
        if args.pull:
            return run_pull(s, args.package, edit_id, args.root)
        pushed = run_push(s, args.package, edit_id, local, args.dry_run)
        if pushed and not args.dry_run:
            c = s.post(
                "%s/applications/%s/edits/%s:commit" % (API, args.package, edit_id), timeout=60
            )
            c.raise_for_status()
            print("\nCommitted edit %s — %d locale(s) sent to Play." % (edit_id, pushed))
            edit_id = None  # committed; do not delete
        return 0
    finally:
        # A --dry-run / --pull / no-op edit is abandoned so it does not linger as
        # the app's one open edit and block the release publish in android.yml.
        if edit_id is not None:
            abandon_edit(s, args.package, edit_id)


if __name__ == "__main__":
    sys.exit(main())
