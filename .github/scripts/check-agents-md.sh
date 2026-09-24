#!/usr/bin/env bash
# Guard the agent-neutral instructions layout:
#   1. AGENTS.md exists at the repo root (the single source of rules),
#   2. CLAUDE.md imports it with `@AGENTS.md`,
#   3. no other tracked text file refers to the old path CLAUDE.md.
#
#   check-agents-md.sh [repo-root]    # default: current directory
#   check-agents-md.sh --self-test
#
# Used by .github/workflows/agents-md.yml.
set -euo pipefail

check() {
  local root="${1:-.}"
  local failed=0
  if [[ ! -f "$root/AGENTS.md" ]]; then
    echo "::error::AGENTS.md is missing at the repo root."
    failed=1
  fi
  if ! grep -qx '@AGENTS.md' "$root/CLAUDE.md" 2>/dev/null; then
    echo "::error::CLAUDE.md must contain an '@AGENTS.md' import line."
    failed=1
  fi
  local stale
  stale=$(git -C "$root" grep -I -n 'CLAUDE\.md' -- \
    ':!CLAUDE.md' ':!AGENTS.md' \
    ':!.github/scripts/check-agents-md.sh' ':!.github/workflows/agents-md.yml' || true)
  if [[ -n "$stale" ]]; then
    echo "::error::These files still refer to CLAUDE.md; point them at AGENTS.md:"
    echo "$stale"
    failed=1
  fi
  return "$failed"
}

self_test() {
  local tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN
  git -C "$tmp" init -q
  printf '# rules\n' > "$tmp/AGENTS.md"
  printf '# FT8AF\n\n@AGENTS.md\n' > "$tmp/CLAUDE.md"
  printf 'x = 1  # see AGENTS.md\n' > "$tmp/code.py"
  git -C "$tmp" add -A

  check "$tmp" >/dev/null || { echo "self-test FAIL: valid layout rejected"; return 1; }

  printf 'x = 1  # see CLAUDE.md\n' > "$tmp/code.py"
  git -C "$tmp" add -A
  if check "$tmp" >/dev/null; then echo "self-test FAIL: stale reference accepted"; return 1; fi
  printf 'x = 1\n' > "$tmp/code.py"

  printf '# FT8AF\n' > "$tmp/CLAUDE.md"
  git -C "$tmp" add -A
  if check "$tmp" >/dev/null; then echo "self-test FAIL: missing import accepted"; return 1; fi
  printf '# FT8AF\n\n@AGENTS.md\n' > "$tmp/CLAUDE.md"

  git -C "$tmp" add -A
  rm "$tmp/AGENTS.md"
  git -C "$tmp" add -A
  if check "$tmp" >/dev/null; then echo "self-test FAIL: missing AGENTS.md accepted"; return 1; fi

  echo "self-test OK"
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
else
  check "${1:-.}"
fi
