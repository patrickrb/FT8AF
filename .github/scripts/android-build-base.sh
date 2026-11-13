#!/usr/bin/env bash
# Prints the commit to diff against when deciding whether a main push needs a
# new Android build: the newest android-dev.* internal release that is an
# ancestor of HEAD, i.e. the last build that actually reached Play internal.
#
#   bash .github/scripts/android-build-base.sh <fallback> <run number>
#
# <fallback> (the last android-v* production tag, or the start of the log
# range) is printed when no internal release is reachable yet, e.g. on the
# first release after staging was retired (staging's android-dev.* tags sit on
# staging-only merge commits that main never contains).
#
# android-dev.<run number> itself is skipped: on a re-run, this run's own
# release already points at HEAD, and the re-run must reach the decision its
# first attempt did.
#
# Why not just diff from the production tag: production is promoted in Play
# Console, so the android-v* tag stops advancing, and every later docs- or
# desktop-only release would look like an Android change and cut a new build.
# The semver range and release notes still start at the production tag.
set -euo pipefail
fallback="${1:?usage: android-build-base.sh <fallback> <run number>}"
run="${2:?usage: android-build-base.sh <fallback> <run number>}"
while read -r tag; do
  [[ -z "$tag" || "$tag" == "android-dev.$run" ]] && continue
  if git merge-base --is-ancestor "$tag" HEAD 2>/dev/null; then
    echo "$tag"
    exit 0
  fi
done < <(git tag --list 'android-dev.*' --sort=-v:refname)
echo "$fallback"
