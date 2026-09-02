#!/usr/bin/env python3
"""Release-notes helpers for android.yml: `restore` and `ensure-notes`.

`ensure-notes` runs in "Write release notes" for the lanes that produce notes
(staging and main). The producers can yield a blank file — Claude's structured
output permits an empty `notes` string, and `jq -r` writes that as a bare
newline — and a release whose body carries blank markers would later be
refused by the manual production ship (`restore`, below). So before the notes
go into the markers, a blank file is replaced with the PR titles (whole lines,
up to Play's 500-character limit) or, failing that, "Bug fixes and
improvements." — the same fallback the AI step used when the API was
unreachable, now applied in one place for every producer.

`restore` restores the release notes of the GitHub Release an android-v* tag
already has. Used by the `tag` lane. The manual production ship re-runs the
workflow on an android-v<x.y.z> tag whose GitHub Release the main-merge run
already created, carrying the promoted staging notes in its body between the
hidden markers written by "Write release notes":

    <!-- ft8af-notes-start -->
    ...notes...
    <!-- ft8af-notes-end -->

The workflow step runs `gh release view` and hands this script the result; the
decision — existing release / missing release / lookup failure, manual dispatch
versus tag push, and the marker parsing — lives here so test_release_notes.py
can cover every branch instead of a bash step nobody can run in CI.

Outcomes (`found=...` is appended to --github-output):
  found=true   the release exists: --body-out gets its body verbatim (for the
               release step to hand back to softprops unchanged) and --notes-out
               gets the text between the markers (for Play's what's-new).
  found=false  a plain tag push found no release: nothing is written, the
               workflow falls back to the version string as before.
  exit 1       the run must stop, with a ::error:: line saying why. Always on a
               workflow_dispatch that cannot read its release or finds no notes
               in it (the manual ship is documented to send the promoted notes,
               and carrying on would overwrite the release body with a
               placeholder); on a tag push for any lookup failure other than a
               genuine "release not found".
"""
import argparse
import os
import re
import sys

START = "<!-- ft8af-notes-start -->"
END = "<!-- ft8af-notes-end -->"

# Play's what's-new limit; the fallback keeps whole lines under it.
PLAY_NOTES_LIMIT = 500
DEFAULT_NOTES = "Bug fixes and improvements."
# What the AI step's "Collect changes" writes to its prs output when the range
# had no PR merges (android.yml: `${PRS:-(no pull-request merges in range)}`).
# A placeholder for the log, not a release note; must never reach Play.
NO_PRS_SENTINEL = "(no pull-request merges in range)"


def fallback_notes(pr_list):
    """Release notes from the PR list the AI step collects ("- #123 scope: title").

    Whole lines only, stopping before the total would exceed PLAY_NOTES_LIMIT
    (each line counts its newline), mirroring the sed/awk the workflow's
    fallback used. Empty input yields DEFAULT_NOTES.
    """
    out = []
    n = 0
    for line in (pr_list or "").splitlines():
        line = re.sub(r"^- #[0-9]+ [^:]*: ?", "", line)
        line = re.sub(r"^- #[0-9]+ ", "", line)
        if not line.strip() or line.strip() == NO_PRS_SENTINEL:
            continue
        if n + len(line) + 1 > PLAY_NOTES_LIMIT:
            break
        out.append(line)
        n += len(line) + 1
    return "\n".join(out) + "\n" if out else DEFAULT_NOTES + "\n"


def ensure_notes(notes, pr_list):
    """(notes, used_fallback): nonblank notes come back unchanged."""
    if notes is not None and notes.strip():
        return notes, False
    return fallback_notes(pr_list), True


class MalformedMarkers(Exception):
    """The body does not carry exactly one ordered start/end marker pair."""


def extract_notes(body):
    """Text between the markers, line-wise, as the awk extraction produced it.

    Strict about structure: exactly one start marker, exactly one end marker,
    start before end. Anything else raises MalformedMarkers — with the end
    marker first, a lenient scan would capture everything after the start
    marker and ship trailing release text to Play as the what's-new.
    """
    starts = body.count(START)
    ends = body.count(END)
    if starts == 0 and ends == 0:
        raise MalformedMarkers("no ft8af-notes markers")
    if starts != 1 or ends != 1:
        raise MalformedMarkers(
            "expected one start and one end marker, found %d and %d" % (starts, ends)
        )
    s = body.index(START)
    e = body.index(END)
    if e < s:
        raise MalformedMarkers("end marker comes before the start marker")
    inner = body[s + len(START):e]
    # The markers sit on their own lines; drop the line break that follows the
    # start marker so the notes begin at their first line, like awk's did.
    if inner.startswith("\n"):
        inner = inner[1:]
    return inner


class Outcome:
    def __init__(self, found=None, error=None, notes="", body="", message=""):
        self.found = found      # True / False, or None when error is set
        self.error = error      # the ::error:: text, or None
        self.notes = notes
        self.body = body
        self.message = message  # informational stdout line


def decide(event, tag, gh_status, gh_stderr, body):
    """The whole decision, pure so it can be tested without gh or a runner."""
    dispatch = event == "workflow_dispatch"
    gh_stderr = (gh_stderr or "").strip()
    if gh_status != 0:
        if dispatch:
            return Outcome(error=(
                "Could not read the GitHub Release for %s (%s). A manual production "
                "ship needs the release the main merge created — check the tag and "
                "rerun." % (tag, gh_stderr)))
        if "not found" not in gh_stderr.lower():
            # A tag push with a release we merely failed to read is the same
            # overwrite risk; only a genuine "no release" may fall through.
            return Outcome(error=(
                "Could not read the GitHub Release for %s (%s); refusing to continue "
                "and risk overwriting it." % (tag, gh_stderr)))
        return Outcome(found=False, message=(
            "No existing release for %s (plain tag push) — Play's what's-new falls "
            "back to the version string." % tag))

    body = (body or "").replace("\r", "")
    try:
        notes = extract_notes(body)
    except MalformedMarkers as m:
        if dispatch:
            return Outcome(error=(
                "The GitHub Release for %s has no usable release notes (%s), so there "
                "is nothing to send to Play as the what's-new. Put the notes in the "
                "release body between %s and %s and rerun." % (tag, m, START, END)))
        # A tag push keeps the body but ships without notes, as it always did.
        notes = ""
    if dispatch and not notes.strip():
        return Outcome(error=(
            "The GitHub Release for %s has no release notes between the ft8af-notes "
            "markers, so there is nothing to send to Play as the what's-new. Add "
            "them to the release body and rerun." % tag))
    return Outcome(found=True, notes=notes, body=body, message=(
        "Release %s already exists; keeping its body." % tag))


def build_arg_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="command", required=True)

    r = sub.add_parser("restore", help="restore the notes of the release a tag already has")
    r.add_argument("--event", required=True, help="$GITHUB_EVENT_NAME")
    r.add_argument("--tag", required=True)
    r.add_argument("--gh-status", type=int, required=True, help="exit code of gh release view")
    r.add_argument("--gh-stderr", required=True, help="file holding gh's stderr")
    r.add_argument("--body", required=True, help="file holding gh's stdout (the body)")
    r.add_argument("--notes-out", required=True)
    r.add_argument("--body-out", required=True)
    r.add_argument("--github-output", help="$GITHUB_OUTPUT; found=true|false is appended")

    e = sub.add_parser("ensure-notes", help="replace a blank notes file with the fallback text")
    e.add_argument("--notes", required=True, help="notes file; created or rewritten when blank")
    e.add_argument("--pr-list-env", default="PR_LIST",
                   help="env var holding the AI step's PR list (default PR_LIST)")
    return ap


def run_ensure_notes(args):
    notes = None
    if os.path.exists(args.notes):
        with open(args.notes, encoding="utf-8", errors="replace") as f:
            notes = f.read()
    ensured, used_fallback = ensure_notes(notes, os.environ.get(args.pr_list_env, ""))
    if used_fallback:
        with open(args.notes, "w", encoding="utf-8", newline="\n") as f:
            f.write(ensured)
        print("::warning title=Release notes fell back::The notes producer left notes.txt "
              "blank — using the fallback text (the PR titles, or the default line when "
              "there were none) so the release stays shippable.")
        print("Notes:")
        print(ensured)
    return 0


def main(argv=None):
    args = build_arg_parser().parse_args(argv)
    if args.command == "ensure-notes":
        return run_ensure_notes(args)

    with open(args.gh_stderr, encoding="utf-8", errors="replace") as f:
        gh_stderr = f.read()
    with open(args.body, encoding="utf-8", errors="replace") as f:
        body = f.read()

    out = decide(args.event, args.tag, args.gh_status, gh_stderr, body)
    if out.error:
        print("::error::" + out.error)
        return 1
    if out.found:
        with open(args.body_out, "w", encoding="utf-8", newline="\n") as f:
            f.write(out.body if out.body.endswith("\n") else out.body + "\n")
        with open(args.notes_out, "w", encoding="utf-8", newline="\n") as f:
            f.write(out.notes)
    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8", newline="\n") as f:
            f.write("found=%s\n" % ("true" if out.found else "false"))
    print(out.message)
    if out.found:
        print("Notes:")
        print(out.notes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
