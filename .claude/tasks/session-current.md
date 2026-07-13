# Session — iOS parity with Android (features + style)

## Session Overview

**User Request**: "go ahead and get the ios app in parity in both style and features as the android app"
**Workflow Mode**: Development
**Success Criteria**: iOS app matches Android feature set and visual style for everything feasible without rig hardware; app builds for simulator; `swift test` green; every new code path unit-tested; PR to `dev`.
**Branch**: `feat/ios-parity` (worktree `/Users/patrickburns/Projects/FT8AF-ios-parity`)

## Strategic Analysis

**Complexity**: Complex, multi-phase.
**Research**: Full Android + iOS inventories completed (Explore agents). Key gaps:

- Settings persisted but inert on iOS: spectrum width, decode filters (show-only-CQ, DX-only), TX watchdog / stop-after-N, auto-sequence toggles, PTT/TX delay + late-start tolerance, TX power.
- Missing operating features: caller queue, ActiveQsoPanel TX-message selector chips + LOG/clear actions + header states, TUNE button, CQ options (free text / modifiers), waterfall UTC period timestamps, input-level indicator, DecodeRow status pills / distance / ago / location styling.
- Missing integrations: Cloudlog upload, QRZ logbook upload, PSKReporter sender, POTA real spot feed + activation QSO counting.
- Style: iOS uses SF fonts (Android: Inter + Geist Mono, bundled in `ft8af/app/src/main/res/font/`), app icon missing (placeholder Contents.json), color palette already matches Android dark palette hex-for-hex.

**Deferred (out of scope this PR, documented in PR body)**: rig CAT transport (no serial on iOS; BLE bridge is its own project — ALC/SWR/tune-via-ATU depend on it), FT4/FT2 mode switching, Hound/Field Day modes, GPS grid + time discipline, needed-DX local notifications, light theme, localization catalogs (16 languages), CarPlay, PSK Reporter map overlay, POTA OAuth self-spot/upload.

**Build facts**: XcodeGen (`ios/FT8AF/project.yml`, sources glob → run `xcodegen generate` after adding files). Baseline green: app builds (iPhone 16 Pro sim), 129 kit tests pass. Fonts require Info.plist `UIAppFonts` → use project.yml `info:` properties.

## Task Breakdown

### Phase 0: Shared plumbing (Central AI)
- [x] Worktree + session file
- [ ] Pre-stage `SettingsState` fields + persistence for all new settings (Cloudlog, QRZ key, PSKReporter, distance unit, etc.) so parallel agents don't collide on AppState.swift

### Batch 1 (parallel, disjoint files)
- [ ] **Agent D — Logging integrations**: Cloudlog + QRZ logbook upload + PSKReporter sender (pure request-builders in FT8AFKit/FT8Engine + tests; thin app service; LoggingSettings screen; sync chips + catch-up sync in Logbook)
- [ ] **Agent W — Waterfall parity**: spectrum width wired end-to-end, UTC timestamps at period boundaries, input-level indicator (kit geometry + tests; owns LiveEngine waterfall loop + Waterfall screen files)

### Batch 2 (parallel, disjoint files)
- [ ] **Agent B — Engine/TX parity**: watchdog, stop-after-N, auto-seq toggles wired, late-start clip rule `max(0, msIntoCycle - slack)`, TUNE carrier, caller queue, stage-selector plumbing; ActiveQsoPanel + TxStrip UI upgrades (owns LiveEngine, QsoEngine, Components/)
- [ ] **Agent R — Decode UI parity**: DecodeRow Android-style (accent bar, pills, SNR bar, distance, ago, location), filters wired (show-only-CQ, DX-only, continent), highlights vs logbook (new grid/band/worked, DXCC prefix heuristic) — extracted pure logic + tests (owns Screens/Decode, Util/)

### Batch 3 (parallel)
- [ ] **Agent E — POTA real data**: live spots from api.pota.app, activation QSO count wired to log, per-park ADIF export
- [ ] **Agent S — Style**: bundle Inter + Geist Mono, typography adoption (UI=Inter, data=Geist Mono), app icon from play_store_icon.png, project.yml info properties

### Phase 4: Verification & ship (Central AI)
- [ ] `swift test` (kit) green; xcodebuild simulator build green; app smoke-run in simulator
- [ ] Code review pass; commit; PR to `dev`

## Agent Work Sections

(appended by agents as they complete)

## Session Metrics

Tasks total: 10 · Completed: 1
