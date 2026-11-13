---
name: ft8af-factory
description: >-
  Run FT8AF's software factory: take the next `factory:ready` spec from the
  GitHub issue queue, build it in a fresh worktree, turn its scenarios into
  tests, prove it on a private emulator with screenshots, open the PR, clear
  one Copilot review, merge into dev on a green pr-gate, and release dev → main
  (Play internal track) when every check passes; then announce what Patrick
  promoted to production. Use when asked to "run the factory", "work the
  queue", "next spec", "ship the backlog", or from a /loop.
---

# FT8AF software factory

Specs go in, tested internal releases come out. Patrick writes specs, makes
product calls, tests each internal build on his phone with a real radio, and
promotes it to production himself. Everything between a ready spec and a build
on the Play internal track is this loop. **Trust comes from the scenarios
passing, not from anyone reading the diff**, so the scenarios are the part
never to cut.

Everything in AGENTS.md still holds (worktrees, PR template, tests for every
new code path, extracted logic behind thin Composables, emulator flows, the
TX-pipeline and mode/band safety rules). This skill is the order of work.

## Standing authority (Patrick, 2026-11-13)

- Merge your own feature PRs into `dev` once `pr-gate` is green, the one
  Copilot review is answered and QA evidence is on the PR.
- **Merge `dev → main` yourself when every check on that PR passes.** That is
  the release: it publishes the build to the Play **internal** track (and a
  desktop prerelease). No other approval needed.
- Not delegated, so never do them: promote to Play production (Console, an
  `android-v*` / `desktop-v*` tag, or the manual ship run), change secrets,
  signing, store listings or Play Console settings, decide an open product
  question, force-push, or merge past branch protection (`--admin`).
  `scripts/factory/guard.py` blocks the tag, ship-run and `--admin` forms.
- **Never key a transmitter.** No TX / TUNE / PTT against a live rig, and no
  emulator flow that does. RF, CAT and USB audio are validated only by
  Patrick's phone test of the internal build.

## The queue

GitHub issues in `patrickrb/FT8AF`:

| Label | Meaning |
|---|---|
| `factory:draft` | being written; not yours |
| `factory:ready` | take it (oldest first; `priority:high` first) |
| `factory:in-progress` | claimed by a running loop (comment says which worktree) |
| `factory:needs-decision` | blocked on Patrick; questions are in a comment |
| `hotfix` | on a merged PR: release today even if one already went out |

```bash
gh issue list --label factory:ready --state open --limit 1000 --json number,title,labels,createdAt \
  --jq 'sort_by((.labels|map(.name)|index("priority:high")|not), .createdAt)[] | "\(.number)\t\(.title)"'
```

Specs come from the "Factory spec" issue form (`.github/ISSUE_TEMPLATE/factory-spec.yml`).
Discord bug reports Patrick turns into specs keep the `from-discord` label;
carry it onto the PR.

Claim before starting: comment `factory-claim: <worktree path>`, then read the
issue's comments back. **The earliest `factory-claim:` comment wins** (GitHub
orders comments server-side, so this is the lock; labels are not atomic). If
it isn't yours, delete your comment and take the next spec. If it is, add
`factory:in-progress` and drop `factory:ready`.

```bash
gh issue comment <N> --body "factory-claim: <worktree path>"
gh api repos/patrickrb/FT8AF/issues/<N>/comments --paginate \
  --jq '[.[] | select(.body | startswith("factory-claim:"))][0].body'
```

Several loops can run at once, one private emulator each (ports 5590, 5592 …;
5580+ belong to Sorrel's loop, 5554 is Patrick's).

**Running unattended.** On jigglebilly a logon task starts this loop in
acceptEdits mode against the allowlist in `.claude/settings.json`
(`scripts/factory/README.md`). If a step stops at a permission prompt, send a
PushNotification naming the command before waiting. The machine's token is a
fine-grained PAT: it can't read check runs or branch protection, so read
checks with `gh run list --commit <sha>` and the `pr-gate` commit status, not
`gh pr checks`. There is no `jq` binary in Git Bash; use `gh --jq`.

## Every cycle starts here

### 0a. Announce what reached production
Patrick's promotion is the trigger for the blog and social posts. Read the
Play track snapshot `discord-play-track-watch` keeps:

```bash
gh api "repos/patrickrb/FT8AF/contents/tracks.json?ref=state/play-tracks" --jq .content \
  | base64 -d | python -c "import json,sys; d=json.load(sys.stdin); print([(r['name'], r['versionCodes']) for r in d['production'] if r['status']=='completed'])"
```

Compare **versionCodes**, not names: every lane sets versionCode = run# + 1000,
while the release name is `android-dev.<run#>` for a build Patrick promoted in
Console but `android-v<x.y.z>` for one he shipped by tag.

Run the `feature-announce` skill before anything else when either:
- an `announce` issue is still **open** (a run was interrupted: resume its
  unticked items), or
- the completed production versionCode is higher than the versionCode in
  the newest `announce` issue's title, open or closed (before the first one,
  the baseline is versionCode 2312 = `android-dev.1312`, production on
  2026-11-13).

### 0b. Stop the line
Fix these first, in this order, as their own PRs:
1. A failed `Android CI & Release` or `Desktop CI & Release` run on `main` —
   the internal build didn't publish.
2. A failing workflow on `dev`'s head, or an open dev → main PR that is red.
3. Your own open factory PRs that are stuck (red, conflicted, Copilot
   threads unanswered) — finish before starting new work.

## One spec, start to finish

### 1. Read and judge the spec
Ready means buildable without asking. If it isn't — a real product choice is
open (copy, defaults, what happens on an edge, anything touching what the
radio transmits), or a scenario contradicts AGENTS.md — do not guess: comment
the questions as a short numbered list with your recommended answer for each,
relabel `factory:needs-decision` (dropping `factory:in-progress`), **delete
your `factory-claim:` comment** so the spec can be claimed again once it's
answered, and take the next spec. Engineering choices are yours; make them and
write them in the PR.

### 2. Worktree off the latest dev
```bash
git -C /c/Users/burns/Projects/NEXT-FT8CN fetch origin
git -C /c/Users/burns/Projects/NEXT-FT8CN worktree add -b feat/<slug> /c/Users/burns/Projects/NEXT-FT8CN-<slug> origin/dev
```
Branch names follow AGENTS.md (`feat|fix|chore|docs/<issue#>-<slug>`).

### 3. Scenarios become tests first
Each scenario line becomes a test, written before the code:
- logic → JVM unit test in `ft8af/app/src/test/` (JUnit4 + Truth; Robolectric
  only when it touches Android types). Composables stay thin; test the
  extracted `internal` function.
- shared radio/CAT logic → `ham-radio-core` tests.
- DSP → the native host suite (`run_host_tests.sh`); never lower corpus floors
  or regenerate goldens to make a test pass.
- a flow across screens → an emulator flow in
  `ft8af/app/src/androidTest/kotlin/com/k1af/ft8af/flows/` (runs in CI's
  `Instrumented tests (emulator)` job). Update the flow when you change a
  screen it walks.
- desktop → vitest next to the module / `#[cfg(test)]` in `src-tauri`.
For a bug: the test must fail on `origin/dev` before the fix. Say so in the PR.

### 4. Build it
Android unless the spec's `Platforms` says otherwise. Strings go in
`strings_compose.xml` in every locale the app ships (English fallback is not
enough for UI copy). Respect the safety rules: FT8/FT4 switches stay on the
band, no automatic QSY to an untuned band, the TX pipeline notes in AGENTS.md.

### 5. Prove it locally
```bash
bash scripts/factory/gradle.sh testDebugUnitTest assembleDebug     # from the worktree root
bash ft8af/app/src/main/cpp/ft8af_glue/run_host_tests.sh          # if native code moved
npm --prefix desktop test                                           # if desktop moved
```
Then the device pass on a **private emulator** (never Patrick's phone, never
5554): install the debug APK with `adb -s emulator-<port> install -r`, walk
the PR's manual test checklist, and capture screenshots (`adb exec-out
screencap -p`) in light and dark and at font scale 2.0 when the change draws
something. Look at every screenshot before you use it; a picture of the wrong
screen is worse than none.

### 6. Open the PR as a draft, attach the evidence
```bash
gh pr create --draft --base dev --title "<what the operator gets>" --body-file pr.md
```
Fill **every** section of `.github/pull_request_template.md`. Add: `Spec: #N`,
each scenario → the test that proves it, decisions made, and the screenshots
(push them to an `assets/pr-<N>-shots` branch and link the raw URLs, the way
earlier PRs did). Wait for the CI "Debug APK" comment and link it.
Label `needs-device-test` when the diff touches anything in AGENTS.md's
device-testing list, and say why. Label `user-facing` when an operator would
notice the change; that is what the announce step collects later.

### 7. Clear review, then merge
```bash
gh pr ready <N>
gh api repos/patrickrb/FT8AF/pulls/<N>/requested_reviewers -X POST \
  -f "reviewers[]=copilot-pull-request-reviewer[bot]"
```
Then run the `copilot-review` skill on `<N>`. **Exactly one Copilot review
per PR** (reviews are expensive). Request it once, fix what's valid (including
the "previously missed" notes in the review body), reply to and resolve every
thread. Never re-request after fixing. If Copilot hasn't answered in 30
minutes, run the `code-review` skill instead and say so in the template's
"Code review" section. Docs-only PRs get no review.

Merge only when the gate says so, re-evaluated right now rather than read
from a status that a queued retry may not have touched yet:
```bash
SHA=$(gh pr view <N> --json headRefOid --jq .headRefOid)
REPO=patrickrb/FT8AF SHA=$SHA DRY_RUN=1 bash .github/scripts/pr_gate.sh     # must print "success — …"
gh api repos/patrickrb/FT8AF/commits/$SHA/status --jq '.statuses[] | select(.context=="pr-gate") | .state'
gh pr view <N> --json mergeStateStatus,isDraft --jq '"\(.mergeStateStatus) draft=\(.isDraft)"'
gh pr merge <N> --merge          # only when both say success and the PR is CLEAN
```
If a check goes red, fix it on the branch; never re-run a flaky check more
than once without finding why. Re-run only through
`bash scripts/factory/rerun.sh <run id>`: it refuses tag and manual-dispatch
runs (the production path), and the guard blocks raw `gh run rerun`.

### 8. Close out
After merge: comment the PR on the spec issue, drop `factory:in-progress`,
`git worktree remove` (the hamlib prebuilts in it are copies; the main
checkout keeps the originals). Issues close when the change reaches `main`:
`Closes #N` in a PR to dev does not fire, so close by hand after the release.
Save a memory only for what the repo can't tell the next session.

## Releasing dev → main

At the end of **every** cycle, idle ones included, check for unreleased work:
`git log --oneline origin/main..origin/dev` (ignoring merges of earlier
release PRs). If there is any, and no dev → main release merged in the last
24 hours (or a PR in the backlog is labelled `hotfix`), run the
`ft8af-release` skill. Work that missed today's release goes out on the next
cycle after the 24 hours, even if nothing new merges. The skill: it opens the
release PR (with the "Test on your phone" list), merges it when every check
passed, watches the internal publish land, and tells Patrick the build is
ready to test.

## What a cycle reports

One short note per cycle: spec taken, PR, tests added, evidence link, merged
or why not, release cut or why not, announcements posted, and anything now
waiting on Patrick (`factory:needs-decision` issues, an internal build to test
and promote, a social network that needs a login).
