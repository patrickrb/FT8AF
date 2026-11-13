#!/usr/bin/env bash
# Sets the `pr-gate` commit status on $SHA from every workflow run on it.
# Called by .github/workflows/pr-gate.yml; runnable by hand too:
#   REPO=patrickrb/FT8AF SHA=<sha> DRY_RUN=1 bash .github/scripts/pr_gate.sh
#
# Workflow runs, not check runs, are the source of truth: a run exists from
# the moment its event fires (status `queued`), while its job check runs only
# appear once a runner picks it up — so a gate reading check runs could go
# green while a test workflow is still waiting in the queue. Only the latest
# run of each workflow counts (a re-run, or a duplicate cancelled by a
# concurrency group, is superseded by it).
#
#   a workflow's latest run failed / cancelled / timed out -> failure
#   any latest run still queued, waiting or in progress    -> pending
#   otherwise (success, skipped, neutral)                  -> success
set -euo pipefail
: "${REPO:?}" "${SHA:?}"

# Only a commit that is the head of an open PR gets a status. A green status
# written earlier, say when a manual or push-triggered run on the branch
# finished before the PR existed, would be sitting on the head when the PR
# opens and a merge would take it before this PR's own workflows had even
# queued (PR #610, 2026-11-12). Commits without an open PR are left alone;
# the PR's opening evaluation sets pending, then the real result.
prs=$(gh api "repos/$REPO/commits/$SHA/pulls" \
  --jq "[.[] | select(.state == \"open\" and .head.sha == \"$SHA\")] | length")
if [ "${prs:-0}" = 0 ] && [ -z "${FORCE:-}" ]; then
  echo "skip — $SHA is not the head of an open PR"
  exit 0
fi

# The gate itself, and workflows that act after a merge rather than test it.
IGNORE='^(PR gate|Discord — merge notify|Discord — Play track watch|Claude Code|Running Copilot Code Review|Copilot code review)$'

runs=$(gh api --paginate "repos/$REPO/actions/runs?head_sha=$SHA&per_page=100" \
  --jq '.workflow_runs[] | [.id, .name, .status, (.conclusion // "")] | @tsv' \
  | sort -t$'\t' -k1,1n \
  | awk -F'\t' '{ latest[$2] = $0 } END { for (n in latest) print latest[n] }')

failed=""; pending=""; passed=0
while IFS=$'\t' read -r _id name status conclusion; do
  [ -z "$name" ] && continue
  [[ "$name" =~ $IGNORE ]] && continue
  if [ "$status" != completed ]; then
    pending="$pending $name;"
  else
    case "$conclusion" in
      success|skipped|neutral) passed=$((passed + 1)) ;;
      *) failed="$failed $name ($conclusion);" ;;
    esac
  fi
done <<< "$runs"

if [ -n "$failed" ]; then
  state=failure; desc="Failed:$failed"
elif [ -n "$pending" ]; then
  state=pending; desc="Waiting on:$pending"
else
  state=success; desc="All $passed workflows passed"
fi
desc=${desc:0:139}  # GitHub caps a status description at 140 characters
echo "$state — $desc"
[ -n "${DRY_RUN:-}" ] && exit 0

gh api -X POST "repos/$REPO/statuses/$SHA" \
  -f state="$state" -f context=pr-gate -f description="$desc" \
  -f target_url="${GITHUB_SERVER_URL:-https://github.com}/$REPO/actions/workflows/pr-gate.yml" >/dev/null
