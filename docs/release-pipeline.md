# Release pipeline

Two platforms (Android and desktop) build and release **independently** —
each has its own workflow, its own path filter, its own tag namespace, and its
own required status-check gate. A change to one platform never rebuilds the
others (except a change to the shared native C core under
`ft8af/app/src/main/cpp/**`, which both depend on). The retired iOS target and
its CI workflow have been removed; iOS/iPadOS is not a release target.

## Branch lifecycle

The source path stays **feature → dev → staging → main**. Both publishing
branches, `staging` and `main`, create **internal prereleases only**.

- **feature → dev** — every work item. CI runs (tests, build check) but nothing
  is published. Dev builds **never** reach the Releases page.
- **dev → staging** — bundle up many dev PRs and promote them together. Merging
  this PR (a push to `staging`) cuts a **dev / prerelease** build of every
  platform: prerelease GitHub Releases + Play **internal** track for Android.
- **staging → main** — creates another `android-dev.<run#>` /
  `desktop-dev.<run#>` GitHub **prerelease**, with `prerelease: true`. Android
  reuses the reachable staging candidate's semver and notes and publishes only
  to Play **internal**. It does not create any `android-v*` or `desktop-v*` tag.
- **Production is a separate deliberate action:** an `android-v*` tag push
  selects Play production and a full Android GitHub release; a `desktop-v*`
  tag push selects a full desktop GitHub release. A manual Android workflow
  run on an existing `android-v*` tag can also ship to production; it requires
  usable notes in that release's hidden markers and preserves its body. Manual
  runs on branches are build-only. None of these production actions is
  authorized until Patrick tests **FT8AF, RTTYAF, SSTVAF, ROTA and JTTYAF** and
  subsequently explicitly approves production. Do not create production tags
  during internal delivery.

### Safeguard landing order

Land the reviewed safeguard on `dev`, then use the normal `dev → staging`
and `staging → main` PRs, preserving the required source/review gates. Inspect
each resulting tree before promotion: it must already contain the guarded
Android and desktop workflows, including the first push to `main`. Push runs
use that merged tree; do not promote the older unsafe main-release workflow
first or bypass a source gate with a direct feature-to-main PR.

Source gates (enforced as required status checks):

- PRs to `staging` must come from `dev` (`Staging branch source gate`).
- PRs to `main` must come from `staging` (`Main branch source gate`).

## Tag / release namespaces

Per-platform prefixes keep the Releases page unambiguous:

| Platform | Production tag        | Dev (prerelease) tag      | Trigger of dev build |
|----------|-----------------------|---------------------------|----------------------|
| Android  | `android-v<x.y.z>`    | `android-dev.<run#>`      | push to `staging` / `main` |
| Desktop  | `desktop-v<x.y.z>`    | `desktop-dev.<run#>`      | push to `staging` / `main` |

- Production tags are deliberate; branch pushes never auto-bump or create them.
- Android versions are chosen by Claude (see below). Older Android tags are the
  two-part `android-v0.149` form; they normalise to `0.149.0` so numbering stays
  continuous. Android seeds from a legacy bare `v*` tag if no `android-v*`
  exists yet.

## Android versioning + release notes (AI-assisted)

Ported from Sorrel's `play-beta.yml`. On a push to `staging` the Android
`build` job:

1. Takes the latest `android-v*` tag as the baseline and collects everything
   from there to `HEAD`: merged PRs (number, branch, title), the commit log,
   and size signals (commit count, `ft8af/` diff stat, changed files by area).
2. If nothing under `ft8af/` changed (a desktop-only promotion) it
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

On the later push to `main` the job looks for the newest `android-dev.*` tag
that is an ancestor of `HEAD` and not already shipped, reads the version and
notes back out of those markers, and creates a new `android-dev.<run#>`
prerelease on Play internal, with the same base semver and notes. If no such
candidate exists (or it pre-dates the markers),
`main` uses a deterministic patch bump and PR-title notes, with no paid API call.
Retries also use that fallback rather than repeat paid generation. The tag lane
restores the existing release notes without Claude. No branch lane allocates
an `android-v*` tag.

`versionCode` is unchanged: still `GITHUB_RUN_NUMBER + 1000`.

The helper `.github/scripts/android-next-version.sh` does the bump arithmetic
and has a self-test (`--self-test`).

Run the offline selector truth tables with
`python3 -m unittest discover -s .github/scripts -p 'test_release_policy.py' -v`.
They execute the actual workflow shell across PR/dev/staging/main/tag refs and
unsupported events, verify prerelease/Play inputs, candidate reuse, empty app
changes, and desktop versions with and without production tag history. Both
platform detect jobs run them before any builds or publishing. The separate
`test_release_notes.py` suite stubs the paid API and proves first internal
attempt only, with deterministic promotion/retry fallbacks and saved-note reuse.

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

These cannot be done from a workflow file — do them in the repo settings:

1. **Create the `staging` branch** from `dev`:
   `git checkout dev && git pull && git checkout -b staging && git push -u origin staging`.
2. **Branch protection for `staging`** → Require status checks →
   add `Staging branch source gate / enforce-source-is-dev` (plus the platform
   gates `android-gate`, `desktop-gate` as desired). After the iOS-removal PR
   merges, retire the required `ios-gate` context (shown as `iOS CI / ios-gate`)
   from effective protection/rulesets targeting `dev`, `staging` and `main`,
   where configured. Keep every other required check and source gate. If the
   deleted check blocks merging the removal PR itself, coordinate its retirement
   with the maintainer; do not add a fake successful replacement.
3. **Branch protection for `main`** → Require status checks →
   add `Main branch source gate / enforce-source-is-staging`. If the old
   `enforce-source-is-dev` check was required on `main`, remove it — that gate
   now lives on `staging`.
4. **Play Console** → confirm the `PLAY_SERVICE_ACCOUNT_JSON` service account has
   release permission on the **production** track (it previously only needed
   internal). Two deliberate paths publish an AAB there: a manual workflow run
   on an existing `android-v*` tag and a plain `android-v*` tag push. Branch
   merges never publish to production. Do not change permissions or invoke
   either production path while the all-five-app testing hold remains.
