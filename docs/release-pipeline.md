# Release pipeline

Three platforms (Android, desktop, iOS) build and release **independently** —
each has its own workflow, its own path filter, its own tag namespace, and its
own required status-check gate. A change to one platform never rebuilds the
others (except a change to the shared native C core under
`ft8af/app/src/main/cpp/**`, which all three depend on).

## Branch lifecycle

```
feature/* ──PR──▶ dev ──PR──▶ staging ──PR──▶ main
                  │            │               │
                  │            │               └─ PRODUCTION build → Releases only (no AAB to Play)
                  │            │                  └─ manual run on the android-v* tag → Play production
                  │            └───────────────── DEV (prerelease) build → Releases + Play internal
                  └────────────────────────────── CI only, NO release
```

- **feature → dev** — every work item. CI runs (tests, build check) but nothing
  is published. Dev builds **never** reach the Releases page.
- **dev → staging** — bundle up many dev PRs and promote them together. Merging
  this PR (a push to `staging`) cuts a **dev / prerelease** build of every
  platform: prerelease GitHub Releases + Play **internal** track for Android.
- **staging → main** — promote the validated staging build. Merging this PR (a
  push to `main`) cuts the **production** build: full GitHub Releases with the
  auto-bumped `android-v<x.y.z>` / `desktop-v<x.y.z>` tags. It uploads **no app
  binary to Google Play** — no AAB reaches any track. (Store *listing* text is a
  separate pipeline: a `main` push touching `fastlane/metadata/android/**` still
  runs `play-listings.yml`, which does publish listing changes to Play. See
  [Store listings](#store-listings) below.)
- **shipping a `main`-cut release to Play production is manual.** In
  **Actions → Android CI & Release → Run workflow**, pick the `android-v<x.y.z>`
  tag the `main` merge created as the ref and run it. That run takes the same
  release lane an `android-v*` tag push takes and uploads the AAB to the Play
  **production** track. It reuses the release notes already on that tag's
  GitHub Release (read back through the hidden markers in its body) as Play's
  what's-new — and refuses to run if they are missing — and leaves the release
  body as the promotion wrote it. A merge to `main` on its own never puts a
  build in front of users. (Pushing a brand-new `android-v*` tag by hand is
  the other, older path: it also enters the tag lane and publishes to
  production directly, with the version string as the what's-new.)

  It has to be a manual run rather than a tag push: the `main` run creates the
  `android-v<x.y.z>` ref itself (the GitHub Releases API creates the tag), so
  `git push origin android-v<x.y.z>` is `Everything up-to-date` — it emits no
  push event and starts no workflow.

Source gates (enforced as required status checks):

- PRs to `staging` must come from `dev` (`Staging branch source gate`).
- PRs to `main` must come from `staging` (`Main branch source gate`).

## Tag / release namespaces

Per-platform prefixes keep the Releases page unambiguous:

| Platform | Production tag        | Dev (prerelease) tag      | Trigger of dev build |
|----------|-----------------------|---------------------------|----------------------|
| Android  | `android-v<x.y.z>`    | `android-dev.<run#>`      | push to `staging`    |
| Desktop  | `desktop-v<x.y.z>`    | `desktop-dev.<run#>`      | push to `staging`    |
| iOS      | _(no release yet)_    | _(no release yet)_        | —                    |

- Desktop production tags are auto-bumped on a push to `main` from the latest
  matching tag.
- Android versions are chosen by Claude (see below). Older Android tags are the
  two-part `android-v0.149` form; they normalise to `0.149.0` so numbering stays
  continuous. Android seeds from a legacy bare `v*` tag if no `android-v*`
  exists yet.
- iOS is CI-only: it builds the FT8AFKit test suite and an unsigned simulator
  build to prove it compiles. A distributable `.ipa` needs an Apple Developer
  cert + provisioning profile / TestFlight, which are not wired up yet.

## Android versioning + release notes (AI-assisted)

Ported from Sorrel's `play-beta.yml`. On a push to `staging` the Android
`build` job:

1. Takes the latest `android-v*` tag as the baseline and collects everything
   from there to `HEAD`: merged PRs (number, branch, title), the commit log,
   and size signals (commit count, `ft8af/` diff stat, changed files by area).
2. If nothing under `ft8af/` changed (an iOS- or desktop-only promotion) it
   builds but **cuts no release** — no empty versions.
3. Otherwise asks Claude (`claude-opus-5`, structured output) for the semver
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
notes back out of those markers, and releases `android-v<x.y.z>` with the same
notes — so when that tag is later shipped to Play, production ships exactly what
the internal testers ran. If no such candidate exists (or it pre-dates the markers) Claude
decides on `main` instead. An `android-v<x.y.z>` that already exists is stepped
by a patch until free.

`versionCode` is unchanged: still `GITHUB_RUN_NUMBER + 1000`.

The helper `.github/scripts/android-next-version.sh` does the bump arithmetic
and has a self-test (`--self-test`).

**Secret:** `ANTHROPIC_API_KEY` (repository secret). Without it, or if the API
call fails, the run annotates a warning, takes a **patch** bump, and uses the
PR titles as the notes — a release is never blocked on the AI step.

## Store listings

Play *store listing* text (title, descriptions, per-locale metadata) lives in
`fastlane/metadata/android/` and ships through its own workflow,
`play-listings.yml` — not through the Android release pipeline above. It
publishes on a push to `main` that touches that directory, and can also be run
manually (with a dry-run option). So a `main` merge that changes listing text
does reach Google Play, even though it uploads no app binary; the two are
deliberately independent, because listing copy and app builds ship on different
cadences.

## One-time setup on GitHub (manual)

These cannot be done from a workflow file — do them in the repo settings:

1. **Create the `staging` branch** from `dev`:
   `git checkout dev && git pull && git checkout -b staging && git push -u origin staging`.
2. **Branch protection for `staging`** → Require status checks →
   add `Staging branch source gate / enforce-source-is-dev` (plus the platform
   gates `android-gate`, `desktop-gate`, `ios-gate` as desired).
3. **Branch protection for `main`** → Require status checks →
   add `Main branch source gate / enforce-source-is-staging`. If the old
   `enforce-source-is-dev` check was required on `main`, remove it — that gate
   now lives on `staging`.
4. **Play Console** → confirm the `PLAY_SERVICE_ACCOUNT_JSON` service account has
   release permission on the **production** track (it previously only needed
   internal). Two paths publish an AAB there: the manual workflow run on an
   `android-v*` tag (the normal path for a release cut by a `main` merge) and a
   plain `android-v*` tag push. `main` merges do not.
