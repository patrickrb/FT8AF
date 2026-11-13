---
name: ft8af-release
description: >-
  Release FT8AF dev → main: open the release PR with what ships and the
  "Test on your phone" list, merge it once every check passed, watch the
  Android build publish to the Play internal track (and the desktop
  prerelease), and tell Patrick it's ready to test on his phone. Use when the
  factory loop ends a cycle that merged anything, or when asked to "cut a
  release", "release", "ship to internal", "dev to main", "push a test build".
---

# FT8AF release: dev → main → Play internal

```
feature ──PR──▶ dev ──PR──▶ main ──(android.yml)──▶ Play internal ──(Patrick)──▶ production
```

There is no `staging` branch (retired 2026-11-13). Merging dev → main is the
whole release; `docs/release-pipeline.md` has the mechanics. **Claude may merge
the dev → main PR once every check on it has passed** (Patrick, 2026-11-13).
Production stays Patrick's: he tests the internal build on his phone with a
radio and promotes it in Play Console. Never create an `android-v*` /
`desktop-v*` tag, run the manual production ship, or promote in Console.

What the merge fires:

| Workflow | Effect |
|---|---|
| `android.yml` (push to main) | Claude picks the semver bump + writes the Play notes; signed AAB → **Play internal**, GitHub prerelease `android-dev.<run#>`, versionName `<x.y.z>-dev.<run#>`, versionCode `run# + 1000` |
| `desktop.yml` (push to main) | `desktop-dev.<run#>` prerelease bundles (Windows/macOS/Linux) |
| `discord-merge-notify.yml` | 📦 post to Discord |
| `discord-play-track-watch.yml` (every 10 min) | Discord post when the internal release appears, and later when Patrick promotes it |

Releases cut at most once per day, unless a merged PR is labelled `hotfix`.
A desktop- or docs-only range builds but cuts no Android release; that's
expected, not a failure.

## 1. Open the release PR

```bash
git -C /c/Users/burns/Projects/NEXT-FT8CN fetch origin
git -C /c/Users/burns/Projects/NEXT-FT8CN log --oneline --first-parent origin/main..origin/dev   # what ships
gh pr list --base main --head dev --state open                                                   # reuse an open one
```

List the PRs in the range (`gh pr list --base dev --state merged --search "merged:>=<date of last main merge>"`
or the merge commits above) and write the body:

```markdown
## What ships
- #NNN <title> (one line each; mark user-facing ones)

## Test on your phone
Every merged PR labelled `needs-device-test`, with the one thing to check on
the radio for each (from its "Device test" section). "None" if none.

## Deploys
Play internal (Android changed: yes/no) · desktop prerelease · no production.
```

```bash
gh pr create --base main --head dev --title "Release: <headline>" --body-file release.md
```

## 2. Merge when every check passed

The PR runs the full Android suite (unit, instrumented emulator flows, lint,
release build), desktop builds, native tests and the main source gate. Wait
for them (`gh run list --commit <head sha>`, and the `pr-gate` status), then:

```bash
SHA=$(gh pr view <N> --json headRefOid --jq .headRefOid)
gh api repos/patrickrb/FT8AF/commits/$SHA/status --jq '.statuses[] | select(.context=="pr-gate") | .state'
gh pr view <N> --json mergeStateStatus --jq .mergeStateStatus
gh pr merge <N> --merge            # merge commit, never squash: dev and main must share history
```

Only on `pr-gate` = success and `mergeStateStatus` = `CLEAN`. If it is
`BLOCKED` with every workflow green, branch protection still wants a check
that no longer exists (the old `enforce-source-is-staging` / `ios-gate`
contexts): stop, tell Patrick which setting to change
(`docs/release-pipeline.md` → One-time setup), and do not use `--admin`.

## 3. Watch it land

```bash
gh run list --workflow=android.yml --branch main --limit 1
gh run watch <run id> --exit-status
gh release view android-dev.<run#> --json name,isPrerelease,body --jq '.name, .isPrerelease'
```

- The build job's "Publish to Google Play" step must succeed. If its edit
  commit was refused it retries with the opposite `changesNotSentForReview`
  value and warns when the build landed un-sent-for-review; read the warning,
  don't assume.
- Confirm on Play: within ~10 minutes `state/play-tracks` shows the new
  internal release:
  ```bash
  gh api "repos/patrickrb/FT8AF/contents/tracks.json?ref=state/play-tracks" --jq .content | base64 -d
  ```
- A failure here stops the line: open an issue labelled `priority:high` with
  the failing step and fix it before any new spec.

## 4. Tell Patrick

One message: the version name (versionName / versionCode), that it is live
on the Play internal track (Patrick's phone picks it up from the Play Store as
an internal tester), what ships, and the "Test on your phone" list. Then close the spec issues that shipped, with
the version. Announcements wait for his production promotion
(`feature-announce` skill, triggered from the factory loop).
