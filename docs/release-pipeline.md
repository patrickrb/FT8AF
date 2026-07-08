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
                  │            │               └─ PRODUCTION build → Releases + Play production
                  │            └───────────────── DEV (prerelease) build → Releases + Play internal
                  └────────────────────────────── CI only, NO release
```

- **feature → dev** — every work item. CI runs (tests, build check) but nothing
  is published. Dev builds **never** reach the Releases page.
- **dev → staging** — bundle up many dev PRs and promote them together. Merging
  this PR (a push to `staging`) cuts a **dev / prerelease** build of every
  platform: prerelease GitHub Releases + Play **internal** track for Android.
- **staging → main** — promote the validated staging build. Merging this PR (a
  push to `main`) cuts the **production** build: full GitHub Releases + Play
  **production** track for Android.

Source gates (enforced as required status checks):

- PRs to `staging` must come from `dev` (`Staging branch source gate`).
- PRs to `main` must come from `staging` (`Main branch source gate`).

## Tag / release namespaces

Per-platform prefixes keep the Releases page unambiguous:

| Platform | Production tag        | Dev (prerelease) tag      | Trigger of dev build |
|----------|-----------------------|---------------------------|----------------------|
| Android  | `android-v<x.y>`      | `android-dev.<run#>`      | push to `staging`    |
| Desktop  | `desktop-v<x.y.z>`    | `desktop-dev.<run#>`      | push to `staging`    |
| iOS      | _(no release yet)_    | _(no release yet)_        | —                    |

- Production tags are auto-bumped on a push to `main` from the latest matching
  tag. Android seeds from a legacy bare `v*` tag if no `android-v*` exists yet,
  so numbering stays continuous with pre-split releases.
- iOS is CI-only: it builds the FT8AFKit test suite and an unsigned simulator
  build to prove it compiles. A distributable `.ipa` needs an Apple Developer
  cert + provisioning profile / TestFlight, which are not wired up yet.

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
   internal). `main` merges now publish there.
