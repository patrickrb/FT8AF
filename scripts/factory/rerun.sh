#!/usr/bin/env bash
# Re-runs one workflow run's failed jobs, but only when that run can't be a
# publishing run. The factory's guard blocks raw `gh run rerun` (and the run-
# and job-level rerun APIs), because a re-run can publish again.
#
#   bash scripts/factory/rerun.sh <run id>
#
# Refused:
#   - runs on an android-v* / desktop-v* ref (the production lane);
#   - workflow_dispatch runs (manual ship paths);
#   - push runs on main: a re-run replays the ORIGINAL workflow revision, and
#     main pushes before the staging retirement published to Play production
#     and cut desktop-v* releases. A failed release run is a stop-the-line
#     event for Patrick, not a retry;
#   - anything whose ref or event can't be read.
# What's left is PR and dev-push CI, which never publishes.
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
  echo "rerun.sh: run $id is a manual dispatch (a ship path); refusing" >&2; exit 1
fi
if [[ "$event" == push && "$ref" == main ]]; then
  echo "rerun.sh: run $id is a main push (a release run, replayed at its original workflow revision); refusing — report it instead" >&2; exit 1
fi
echo "re-running failed jobs of run $id ($event on $ref)"
gh run rerun "$id" --failed
