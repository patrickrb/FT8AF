# Band Advisor

Band Advisor answers one question: **"What band should I work right now?"**
It combines VOACAP propagation predictions, live PSK Reporter observations
near the operator, and (optionally) the operator's own PSK Reporter reception
reports into a deterministic, explainable recommendation. There is no LLM or
generative model anywhere in the runtime path — a recommendation is a weighted
sum of named 0..1 inputs, and every score component is returned so the UI can
show exactly why a band won.

Everything ships behind feature flags and is **off by default in release
builds**. A failure of Band Advisor, VOACAP, PSK Reporter, remote
configuration, or alerts can never interfere with decoding, transmission,
logging, USB audio, or CAT control: every network call is time-boxed, every
failure maps to a typed UI state or a silently skipped background round, and
nothing advisor-related runs at all while its flag is off.

## Feature flags (`radio.ks3ckc.ft8af.flags`)

Three flags gate the feature set:

| Flag | Remote key | Gates |
|---|---|---|
| `BAND_ADVISOR` | `bandAdvisor` | Card, detail sheet, recommendation fetches, settings entry |
| `PERSONAL_PSK_ANALYTICS` | `personalPskAnalytics` | Callsign-specific PSK Reporter analytics (callsign is otherwise never sent) |
| `PROPAGATION_ALERTS` | `propagationAlerts` | Background alert checks, notifications, alert settings |

Resolution precedence (deterministic, highest first):

```
Debug developer override (debug builds only)
    ↓
Cached remote configuration (only while its own expiresAt is in the future)
    ↓
BuildConfig default (debug: true, release: false)
```

Implementation map:

- `FeatureFlag.kt` — flag enum, tri-state `FlagOverride`, `FlagValueSource`.
- `FeatureFlagRepository.kt` — the precedence logic (`DefaultFeatureFlagRepository`),
  fully injected (stores, clock, `isDebugBuild`) for tests.
- `RemoteFlagConfig.kt` — versioned payload model + validating parser. Invalid
  payloads are rejected whole; unknown flags ignored; missing flags fall back
  per-flag to the build default.
- `RemoteFlagConfigFetcher.kt` — fetches `https://ft8af.app/api/feature-config`
  (served by the band-advisor service, `GET /v1/feature-config`). Never blocks
  startup: `FeatureFlags.init` (Application.onCreate) only wires
  SharedPreferences; the refresh is kicked from `ComposeMainActivity` on a
  background coroutine and is rate-limited to one attempt per 6 hours.
- `FeatureFlags.kt` — process-wide singleton (the app's no-DI pattern).
- Debug controls: Settings → Advanced → *Developer: Feature flags*
  (`ui/settings/FeatureFlagScreen.kt`), gated on `BuildConfig.DEBUG`. Per flag:
  Default / Force on / Force off, plus the effective value and its source.
  Release builds never consult the override store — defense in depth on top of
  the screen simply not existing there.

> **Rollout controls, not access control.** A modified client can flip these
> flags. Any future paid feature must ALSO be validated server-side; the
> backend must refuse to serve data the account is not entitled to.

## Client architecture (`radio.ks3ckc.ft8af.bandadvisor`)

- `model/Models.kt` — framework-free domain models mirroring the versioned
  JSON contract (below), plus `Freshness` (FRESH / STALE after `validUntil` /
  EXPIRED after +45 min grace).
- `model/RecommendationParser.kt` — `org.json` parser that validates before
  anything reaches the UI or the tuning path (score/confidence ∈ [0,1],
  plausible frequency, valid grid, validUntil > generatedAt).
- `model/Validation.kt` — 4-char grid normalization (`normalizeAdvisorGrid`)
  and structural callsign check (`isPlausibleCallsign`) that gates personal
  analytics.
- `scoring/BandScorer.kt` — the client-side reference implementation of the
  deterministic scoring model (the production copy runs server-side in
  `server/band-advisor/app/scoring.py`; changes must be mirrored). Weights per
  goal, renormalization when inputs are missing, and a separate confidence
  function (observations, freshness, source availability, VOACAP-vs-observed
  agreement, profile completeness).
- `BandAdvisorRepository.kt` — the repository contract. Implementations never
  throw; failures are typed (`NO_GRID`, `OFFLINE`, `HTTP_ERROR`,
  `INVALID_RESPONSE`, `RATE_LIMITED`).
- `ApiBandAdvisorRepository.kt` — production: `GET /v1/recommendation`, 60 s
  client cooldown (force-refresh bypasses it, a 429/503 back-off doesn't),
  last-valid-payload cache in SharedPreferences reused as STALE/EXPIRED when
  offline.
- `FixtureBandAdvisorRepository.kt` — deterministic fixtures for offline UI
  development, built through the real scorer. Selected only when
  `BuildConfig.DEBUG` **and** the debug "Use fixture data" toggle are on
  (`BandAdvisor.repository`), so fabricated data cannot ship as live data.
- `personal/` — pure computation of personal PSK Reporter analytics
  (`computePersonalAnalytics`), small-sample suppression
  (`baselineComparison`: needs ≥10 current reports and ≥3 stored sessions),
  and the bounded on-device baseline history (`PersonalAnalyticsStore`,
  10 sessions/band, clearable from settings).
- `alerts/` — see below.

UI (`radio.ks3ckc.ft8af.ui.bandadvisor`):

- `BandAdvisorCard.kt` — compact card at the top of the Band & Mode sheet
  (injected via a null-able slot so the sheet stays decoupled).
- `BandAdvisorSheet.kt` — detail overlay: goal chips (Contact default / DX /
  Target / POTA), recommendation + dial + confidence, freshness and
  partial-source lines, expandable "Why this band" (evidence + score
  components), alternatives with the reason each ranked lower, and the
  personal panel.
- `BandAdvisorState.kt` / `PersonalPanelLoader.kt` — state holder + pure
  decision helpers. The personal panel reuses the existing
  `PskReporterClient` (its 5-minute cooldown and 429 back-off apply).
- `BandAdvisorTuning.kt` — one-tap tune. Reuses `selectBandIndex` (the same
  path as manual band picks: RigDialTarget protection, persistence, CAT push
  only in CAT/RTS/DTR modes). Refuses frequencies not in the band plan for
  the active mode rather than inventing dial entries; without CAT the button
  degrades to "Set radio to 14.074 MHz" and only updates app state. It never
  transmits and never schedules follow-up tuning — no unattended band hopping.

## Recommendation contract

Served by `GET /v1/recommendation?grid=EM28&goal=DX&mode=FT8[&callsign=K1AF][&targetRegion=EUROPE]`:

```json
{
  "generatedAt": "2026-09-27T12:00:00Z",
  "validUntil": "2026-09-27T12:15:00Z",
  "grid": "EM28", "callsign": "K1AF", "mode": "FT8", "goal": "DX",
  "recommendedBand": "20m", "recommendedFrequencyHz": 14074000,
  "score": 0.91, "confidence": 0.87,
  "summary": "Best combination of live activity and long-distance reliability.",
  "destinations": ["Europe", "South America"],
  "evidence": [{"type": "VOACAP", "message": "..."}],
  "scoreComponents": [{"name": "voacapReliability", "weight": 0.3, "value": 0.8, "contribution": 0.24}],
  "personalAnalytics": {"enabled": true, "reportsReceived": 23, "...": "..."},
  "alternatives": [{"band": "15m", "frequencyHz": 21074000, "score": 0.82, "reason": "..."}],
  "sources": {"voacapAvailable": true, "regionalPskReporterAvailable": true, "personalPskReporterAvailable": true}
}
```

Notes: `callsign`/`personalAnalytics` are omitted without a callsign param;
`scoreComponents[].weight` are the renormalized weights (sum = 1); upstream
outages produce HTTP 200 with `sources.*Available=false` and lower confidence,
never a 5xx. Score says "how good is this band"; confidence says "how much to
trust that claim" — they are computed separately.

## Scoring model

Base weights (`MAKE_CONTACT`): `0.30·voacapReliability +
0.25·nearbyObservedSuccess + 0.15·normalizedLiveActivity +
0.10·distancePotential + 0.10·activityTrend + 0.10·personalStationPerformance`.
DX shifts weight to distance potential and directional diversity; TARGET to
target-specific reliability/observed paths; POTA to dependable regional
coverage. Missing components are dropped and the rest renormalized to sum 1.

`normalizedLiveActivity` is activity **relative to that band-hour's own
baseline** — never raw spot volume, so 20 m can't win every recommendation
just by being busy.

## Propagation alerts (`bandadvisor/alerts`)

Opt-in, behind `PROPAGATION_ALERTS`, checked ~every 30 minutes by a
WorkManager periodic worker (network-constrained; WorkManager was added for
this — the app previously had no periodic-background mechanism).

- The phone does **no aggregation**: the worker downloads one small
  pre-scored document from `GET /v1/conditions?grid=…` and runs the pure
  policy in `PropagationAlertPolicy.kt`.
- Evidence bar: ≥10 observations, confidence ≥0.6, activity ≥1.5× the
  band-hour baseline, data ≤20 min old, and VOACAP agreement (or an
  explicitly-flagged unusual observed opening).
- Alert identity = `grid|band|targetRegion|90-min-bucket`; delivered
  identities persist for 48 h (`AlertHistoryStore`) so one opening never
  notifies twice. Cooldowns: ≥3 h between alerts for the same band, ≥30 min
  between any two alerts, at most one notification per check.
- Quiet hours (local, midnight-wrap aware), per-type opt-ins (general
  opening / watched band / watched region / personal improvement / unusual
  opening), watched band + region pickers — all in Settings → Band Advisor.
- `PropagationAlertScheduler.sync()` reconciles scheduled work on app start
  and after every settings/flag change: disabling the flag or the preference
  **cancels** pending work. The worker also re-checks the gates itself.
- Notifications use their own `propagation_alerts` channel; POST_NOTIFICATIONS
  is requested when the master toggle turns on (Android 13+) and re-checked
  before posting. Tapping opens the Band Advisor detail sheet.

## Privacy

- Recommendations send only the **4-character** grid square (~100×200 km) —
  6-char grids are truncated client-side (`normalizeAdvisorGrid`). GPS
  coordinates never leave the device and are never logged.
- The callsign is sent only while `PERSONAL_PSK_ANALYTICS` is on **and** the
  configured callsign passes structural validation; otherwise requests are
  anonymous.
- Personal analytics are reception reports (who *heard* you) — the UI says so
  explicitly and never implies completed contacts. Baseline comparisons are
  suppressed below the sample-size floor instead of shown as noise.
- Settings → Band Advisor → "Clear cached analytics" wipes the local
  recommendation cache and the personal baseline history.
- Server-side retention is documented in `server/band-advisor/README.md`
  (aggregates only; per-callsign cache ≤1 h; no long-term per-user storage).
- Local telemetry (`BandAdvisorTelemetry`) logs coarse event names to the
  on-device debug.log only; there is no remote analytics backend.

## Backend (`server/band-advisor/`)

A containerized FastAPI service (Python 3.11+) that runs
[voacapl](https://github.com/jawatson/voacapl) (invoked as an external GPL
tool — not linked or vendored) and centralizes PSK Reporter access so phones
never poll `retrieve.pskreporter.info` directly. Endpoints: `/healthz`,
`/v1/feature-config`, `/v1/recommendation`, `/v1/conditions`. See its README
for deck construction, the cache keying
(grid4 × month × UTC-hour × power × antenna × noise × region), PSK Reporter
etiquette (5-min per-parameter-set interval, gzip, `appcontact`, defusedxml),
deployment (Dockerfile builds voacapl from source), and the honest
not-yet-implemented list.

**FT8 SNR profile:** VOACAP's `REQ.SNR` is dB·Hz (1 Hz reference bandwidth).
The commonly quoted FT8 threshold of −21 dB is referenced to 2500 Hz, so the
conversion is −21 + 10·log₁₀(2500) ≈ **13 dB·Hz**, plus a configurable 3 dB
real-world margin (fading, QRM, imperfect audio chains) → default REQ.SNR 16.
Do not plan against the deeper AP/subtraction decode limits.

## Testing

Android unit tests (JUnit4 + Truth; Robolectric where Android types are
involved; MockWebServer for HTTP; injected clocks everywhere time matters —
no real network calls):

- `flags/` — precedence, build defaults, debug overrides, release ignoring
  overrides, remote-config parse/validation/expiry, fetcher rate-limiting,
  store round-trips.
- `bandadvisor/` — contract parsing (including the full example payload),
  score normalization, goal weights, confidence, freshness transitions,
  repository cache/cooldown/back-off/offline fallback, fixture determinism,
  grid/callsign validation, personal analytics math + small-sample
  suppression, baseline store bounds.
- `ui/bandadvisor`, `ui/settings` — UI decision helpers, tune-index lookup
  (never invents dials), panel mapping, flag-screen lines.
- `bandadvisor/alerts/` — thresholds, quiet hours (midnight wrap), identity
  bucketing, dedup, per-band/global cooldowns, history pruning, prefs
  round-trip, conditions parsing, and WorkManager schedule/cancel via
  `work-testing`.

Server: `cd server/band-advisor && python3 -m venv .venv && .venv/bin/pip
install -e '.[dev]' && .venv/bin/pytest` (190 tests, no network).

## Rollout

1. Ship with release defaults off (already the case).
2. Deploy the band-advisor service; point a staging build at it (debug builds
   have the flags on by default).
3. Enable `bandAdvisor` for a cohort via `/v1/feature-config` (the app caches
   the payload and honors its `expiresAt`).
4. `personalPskAnalytics` next, `propagationAlerts` last (it creates
   background work and notifications).
5. Rollback = flip the remote flag off; clients revert on their next config
   refresh, and the alert scheduler cancels its work on next app start / sync.

### Integration safeguards

Recommendation and conditions handlers run synchronous upstream work in the
FastAPI thread pool, keeping health checks responsive. Service locks protect
shared PSK rate-limit/baseline state and VOACAP cache population. Regional
observers share the raw PSK response for the upstream minimum interval, then
aggregate their own nearby statistics; each raw window trains the baseline once.

The Target goal includes a persisted concrete-region selector (Europe by
default). Recommendations echo `targetRegion`, which participates in client
cache matching. Background alert workers read the saved grid from `data.db`
without needing an Activity or FT8 engine to initialize process globals.
