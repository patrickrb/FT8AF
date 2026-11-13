# Release pipeline

Two platforms (Android and desktop) build and release **independently** —
each has its own workflow, its own path filter, its own tag namespace, and its
own required status-check gate. A change to one platform never rebuilds the
others (except a change to the shared native C core under
`ft8af/app/src/main/cpp/**`, which both depend on). The retired iOS target and
its CI workflow have been removed; iOS/iPadOS is not a release target.

## Branch lifecycle

The source path is **feature → dev → main**. The `staging` branch is retired
(2026-11-13): it added a second promotion that tested nothing the `main` push
doesn't. `main` is the only publishing branch, and it creates **internal
prereleases only**.

- **feature → dev** — every work item. CI runs (tests, build check) but nothing
  is published. Dev builds **never** reach the Releases page. The software
  factory (`.claude/skills/ft8af-factory/SKILL.md`) merges these itself once
  `pr-gate` is green.
- **dev → main** — the release. Merging it (a push to `main`) creates an
  `android-dev.<run#>` / `desktop-dev.<run#>` GitHub **prerelease** and
  publishes the Android build to Play **internal**. It does not create any
  `android-v*` or `desktop-v*` tag. The factory opens this PR at most once a
  day and merges it only when every check on it passed
  (`.claude/skills/ft8af-release/SKILL.md`).
- **Production is a separate deliberate action, Patrick's alone:** he tests
  the internal build on his phone and promotes it in Play Console. The CI paths
  also still exist: an `android-v*` tag push selects Play production and a full
  Android GitHub release; a `desktop-v*` tag push selects a full desktop GitHub
  release; a manual Android workflow run on an existing `android-v*` tag ships
  to production (it requires usable notes in that release's hidden markers and
  preserves its body). Manual runs on branches are build-only. Agents never
  create production tags, run those paths, or promote in Play Console.

Gates:

- PRs to `main` must come from `dev` (`Main branch source gate`, reported as
  both `enforce-source-is-dev` and the legacy `enforce-source-is-staging` name
  until main's branch protection is switched over).
- `pr-gate` (`.github/workflows/pr-gate.yml`) is green on a PR head only when
  every workflow that ran on that commit finished without failing. It is what
  the factory waits for before merging.

## Tag / release namespaces

Per-platform prefixes keep the Releases page unambiguous:

| Platform | Production tag        | Dev (prerelease) tag      | Trigger of dev build |
|----------|-----------------------|---------------------------|----------------------|
| Android  | `android-v<x.y.z>`    | `android-dev.<run#>`      | push to `main` |
| Desktop  | `desktop-v<x.y.z>`    | `desktop-dev.<run#>`      | push to `main` |

- Production tags are deliberate; branch pushes never auto-bump or create them.
- Android versions are chosen by Claude (see below). Older Android tags are the
  two-part `android-v0.149` form; they normalise to `0.149.0` so numbering stays
  continuous. Android seeds from a legacy bare `v*` tag if no `android-v*`
  exists yet.

## Android versioning + release notes (AI-assisted)

Ported from Sorrel's `play-beta.yml`. On a push to `main` the Android
`build` job:

1. Takes the latest `android-v*` tag as the baseline and collects everything
   from there to `HEAD`: merged PRs (number, branch, title), the commit log,
   and size signals (commit count, `ft8af/` diff stat, changed files by area).
2. If nothing under `ft8af/` changed (a desktop-only release) it
   builds but **cuts no release** — no empty versions.
3. On the first workflow attempt only, asks Claude (`claude-opus-5`, structured output) for the semver
   bump — `major` / `minor` / `patch`, judged from both content and size, with
   changes outside `ft8af/` not counting — and for ≤400-character Play release
   notes aimed at ham operators.
4. Builds `versionName = <x.y.z>-dev.<run#>`, tags `android-dev.<run#>`, and
   publishes the notes to the GitHub prerelease body **and** the Play internal
   track's "Release notes" (`whatsNewDirectory`). The prerelease body also
   carries hidden markers (`<!-- ft8af-version: x.y.z -->` and
   `<!-- ft8af-notes-start/end -->`).

A **re-run** of the same workflow run reuses the version and notes of the
`android-dev.<run#>` release its first attempt created (read back from those
markers), so a retry never repeats the paid call or re-rolls the version. A
re-run with no such release uses a deterministic patch bump and PR-title notes.
The tag lane restores the existing release notes without Claude. No branch
lane allocates an `android-v*` tag.

Note the baseline: because production is promoted in Play Console rather than
by tag, the latest `android-v*` tag (currently `android-v0.149`) stays the
semver baseline until a production tag is cut.

`versionCode` is unchanged: still `GITHUB_RUN_NUMBER + 1000`.

The helper `.github/scripts/android-next-version.sh` does the bump arithmetic
and has a self-test (`--self-test`).

Run the offline selector truth tables with
`python3 -m unittest discover -s .github/scripts -p 'test_release_policy.py' -v`.
They execute the actual workflow shell across PR/dev/main/tag refs (plus the
retired `staging` ref, which must not release) and
unsupported events, verify prerelease/Play inputs, re-run reuse, empty app
changes, and desktop versions with and without production tag history. Both
platform detect jobs run them before any builds or publishing. The separate
`test_release_notes.py` suite stubs the paid API and proves first internal
attempt only, with deterministic retry fallbacks and saved-note reuse.

**Secret:** `ANTHROPIC_API_KEY` (repository secret). Without it, or if the API
call fails, the run annotates a warning, takes a **patch** bump, and uses the
PR titles as the notes — a release is never blocked on the AI step.

## Store listings

Play *store listing* text (title, descriptions, per-locale metadata) lives in
`fastlane/metadata/android/` and ships through its own workflow,
`play-listings.yml` — not through the Android release pipeline above, and not
on any branch push. A PR touching the metadata is **validated** (completeness
and Play's character limits) and then the text just sits in the repo; it reaches
the store only when someone runs the publish on purpose:

- **Actions → "Play store listings" → Run workflow**, mode `publish` (the
  default mode is the read-only `dry-run`), or
- locally: `python .github/scripts/publish_listings.py`.

Listing copy and app builds ship on different cadences, and neither is tied to a
`main` merge.

## One-time setup on GitHub (manual)

These cannot be done from a workflow file (and the factory's token can't read
or change branch protection) — do them in the repo settings:

1. **Branch protection for `main`** → Require status checks → require
   `enforce-source-is-dev` and drop `enforce-source-is-staging`; drop the
   retired `ios-gate` if it is still listed. Then delete the alias from the
   matrix in `main-gate.yml`.
2. **Branch protection for `dev`** → optionally require `pr-gate` (the factory
   doesn't depend on it being required; it reads the status before merging).
3. **The `staging` branch** can be deleted once no open PR targets it, and its
   branch protection rule with it.
4. **Play Console** → confirm the `PLAY_SERVICE_ACCOUNT_JSON` service account has
   release permission on the **production** track (it previously only needed
   internal). Two deliberate paths publish an AAB there: a manual workflow run
   on an existing `android-v*` tag and a plain `android-v*` tag push. Branch
   merges never publish to production. Do not change permissions or invoke
   either production path while the all-five-app testing hold remains.
