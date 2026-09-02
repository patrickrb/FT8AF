#!/usr/bin/env python3
"""Restore the release notes of the GitHub Release an android-v* tag already has.

Used by the `tag` lane of android.yml. The manual production ship re-runs the
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
import sys

START = "<!-- ft8af-notes-start -->"
END = "<!-- ft8af-notes-end -->"


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


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--event", required=True, help="$GITHUB_EVENT_NAME")
    ap.add_argument("--tag", required=True)
    ap.add_argument("--gh-status", type=int, required=True, help="exit code of gh release view")
    ap.add_argument("--gh-stderr", required=True, help="file holding gh's stderr")
    ap.add_argument("--body", required=True, help="file holding gh's stdout (the body)")
    ap.add_argument("--notes-out", required=True)
    ap.add_argument("--body-out", required=True)
    ap.add_argument("--github-output", help="$GITHUB_OUTPUT; found=true|false is appended")
    args = ap.parse_args(argv)

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
