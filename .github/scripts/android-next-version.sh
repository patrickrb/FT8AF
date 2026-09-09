#!/usr/bin/env bash
# Compute the next Android semver from a previous version and a bump kind.
#
#   android-next-version.sh <previous-version> <major|minor|patch>
#
# <previous-version> may carry an `android-v` / `v` prefix and may be the
# legacy two-part `x.y` form used by tags up to android-v0.149 — it is
# normalised to `x.y.0` before bumping, so the version sequence stays
# continuous across the switch to full semver. Prints `x.y.z` on stdout.
#
# Used by .github/workflows/android.yml; kept in its own file so the version
# maths can be exercised locally (see the self-test at the bottom:
# `android-next-version.sh --self-test`).
set -euo pipefail

normalise() {
  local v="${1#android-v}"
  v="${v#v}"
  if [[ "$v" =~ ^[0-9]+$ ]]; then
    v="$v.0.0"
  elif [[ "$v" =~ ^[0-9]+\.[0-9]+$ ]]; then
    v="$v.0"
  fi
  if ! [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "android-next-version: '$1' is not a version (x, x.y or x.y.z)" >&2
    return 1
  fi
  echo "$v"
}

bump() {
  local prev bump ma mi pa
  # Explicit `|| return` — `set -e` is suspended inside an `if`/`&&` caller,
  # so a failed normalise would otherwise fall through with an empty $prev.
  prev=$(normalise "$1") || return 1
  bump="$2"
  IFS=. read -r ma mi pa <<< "$prev"
  case "$bump" in
    major) echo "$((ma + 1)).0.0" ;;
    minor) echo "${ma}.$((mi + 1)).0" ;;
    patch) echo "${ma}.${mi}.$((pa + 1))" ;;
    *)
      echo "android-next-version: unexpected bump '$bump' (want major|minor|patch)" >&2
      return 1 ;;
  esac
}

self_test() {
  local fails=0
  check() {
    local got
    if got=$(bump "$1" "$2" 2>/dev/null) && [[ "$got" == "$3" ]]; then
      echo "ok   $1 + $2 -> $got"
    else
      echo "FAIL $1 + $2 -> '${got:-<error>}' (want $3)"; fails=$((fails + 1))
    fi
  }
  check_fails() {
    if bump "$1" "$2" >/dev/null 2>&1; then
      echo "FAIL $1 + $2 should have been rejected"; fails=$((fails + 1))
    else
      echo "ok   $1 + $2 rejected"
    fi
  }
  # legacy two-part production tags normalise to x.y.0 first
  check android-v0.149 patch 0.149.1
  check android-v0.149 minor 0.150.0
  check android-v0.149 major 1.0.0
  check 0.149 patch 0.149.1
  # full semver
  check v1.2.3 patch 1.2.4
  check 1.2.3 minor 1.3.0
  check 1.2.3 major 2.0.0
  check 1.2.9 patch 1.2.10
  # one-part
  check 2 patch 2.0.1
  # garbage in
  check_fails 1.2.3.4 patch
  check_fails abc patch
  check_fails 1.2.3 huge
  check_fails "" patch
  if (( fails )); then echo "$fails self-test failure(s)"; return 1; fi
  echo "all self-tests passed"
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
elif [[ $# -eq 2 ]]; then
  bump "$1" "$2"
else
  echo "usage: $0 <previous-version> <major|minor|patch> | --self-test" >&2
  exit 2
fi
