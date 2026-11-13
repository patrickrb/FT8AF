#!/usr/bin/env bash
# Re-runs one workflow run's failed jobs, but only when that run can't be the
# production path. The factory's guard blocks raw `gh run rerun`, because a
# re-run of an android-v* tag push or a manual dispatch would publish to Play
# production again.
#
#   bash scripts/factory/rerun.sh <run id>
#
# Refused: runs on an android-v* / desktop-v* ref, workflow_dispatch runs, and
# anything whose ref or event can't be read.
set -euo pipefail
id="${1:?usage: rerun.sh <run id>}"
[[ "$id" =~ ^[0-9]+$ ]] || { echo "rerun.sh: run id must be numeric" >&2; exit 2; }
info=$(gh run view "$id" --json headBranch,event --jq '"\(.headBranch)\t\(.event)"') || {
  echo "rerun.sh: can't read run $id; refusing" >&2; exit 1; }
ref="${info%%$'\t'*}"
event="${info#*$'\t'}"
if [[ -z "$ref" || -z "$event" ]]; then
  echo "rerun.sh: run $id has no ref/event; refusing" >&2; exit 1
fi
if [[ "$ref" =~ ^(android|desktop)-v[0-9] ]]; then
  echo "rerun.sh: run $id is on production tag $ref; only Patrick re-runs those" >&2; exit 1
fi
if [[ "$event" == workflow_dispatch ]]; then
  echo "rerun.sh: run $id is a manual dispatch (the production ship path); refusing" >&2; exit 1
fi
echo "re-running failed jobs of run $id ($event on $ref)"
gh run rerun "$id" --failed
