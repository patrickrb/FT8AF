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

### Agent W — Waterfall parity

**Status: COMPLETE** — kit tests green (209/209), simulator build green.

**1. Configurable spectrum width, end-to-end (display-only; decoder untouched)**
- `FT8Audio/WaterfallAxis.swift`: fully parametric — every mapping (`fraction`, `clampedFraction`, `hz(forFraction:)`, `tunedTxHz`) now takes `displayMaxHz`. New `maxTxHz(displayMaxHz:)` implements the Android TX clamp `100...min(3000, width-100)` (per task directive "mirror Android"; note this lowers the old 200 Hz floor to Android's 100 — flag if the coordinator wants 200 kept). New `rulerTicks(displayMaxHz:)` + `RulerTick` mirror Android commit 647b12e8 (labels at true `hz/width` fractions, top tick below the far edge for non-500-multiples, degenerate width → single 0 tick).
- `FT8Audio/WaterfallRowBuilder.swift`: `maxHz` → `defaultMaxHz` (3500); `columns(sampleRate:maxHz:)` takes the width (defaulted, so existing callers/tests unchanged).
- `LiveEngine.runWaterfallLoop` reads `settings.spectrumWidthHz` on the main actor **every tick** (new `readSpectrumWidthHz` closure) → live application; column count recomputed per tick. On a width change the `applyWaterfall` closure clears `rows`/`rowTimestamps` (old-width history can't be drawn against the new axis) and updates `WaterfallState.displayMaxHz`.
- Views (`FrequencyRuler`, `SpectrumStrip`, `WaterfallCanvas`) map overlays/tap-to-tune against `Float(appState.settings.spectrumWidthHz)` so the setting applies instantly.

**2. UTC timestamps at FT8 period boundaries**
- New `FT8Audio/WaterfallTimestampGate.swift`: pure port of Android's `WaterfallTimestampGate` — `shouldDraw(utcMs:slotMs:)` once-per-slot gate (incl. mode-change re-baseline rule), `slotPeriod`, `slotStartMs`, and `utcLabel(forUtcMs:)` ("HH:mm:ss", day-independent, Euclidean-safe).
- Waterfall loop stamps the **period-start** label on the first row of each 15 s slot; `WaterfallState.rowTimestamps: [String?]` kept in lockstep with `rows` (append/trim/clear together).
- `WaterfallCanvas.drawPeriodTimestamps`: subtle full-width divider (`textFaint` 40 %, 0.5 pt) + HH:mm:ss monospaced 9 pt `textFaint` label at the left edge.

**3. Input level indicator**
- New `FT8Audio/AudioInputLevel.swift`: pure port of Android's `AudioInputLevel` classifier — identical thresholds (clip ≥0.985 linear, high ≥-3 dBFS peak, silent <-75 dBFS RMS, low <-45 dBFS RMS, -120 dBFS floor, NaN/∞ sanitized), `measure(_:)`, `fromPeakRms(peak:rms:)`, `meterFraction(_:)`.
- Waterfall loop meters the most recent ~250 ms window each tick → `WaterfallState.inputPeak/.inputRms` (plain Floats — AppState doesn't import FT8Audio).
- `WaterfallScreen`: new `InputLevelMeter` in the bottom info bar — "RX" label, 44 pt bar (RMS fill + peak tick) and status word; colors silent/low→`textFaint`, good→`statusConfirmed`, high→`statusWarn`, clipping→`statusBad`.

**Files touched** (all within ownership): `ios/FT8AFKit/Sources/FT8Audio/{WaterfallAxis,WaterfallRowBuilder,WaterfallTimestampGate*,AudioInputLevel*}.swift`, `ios/FT8AFKit/Tests/FT8AudioTests/{WaterfallAxisTests,WaterfallTimestampGateTests*,AudioInputLevelTests*}.swift` (* = new), `ios/FT8AF/FT8AF/Engine/LiveEngine.swift` (waterfall loop + closures + new `WaterfallUpdate` struct only), `ios/FT8AF/FT8AF/Screens/Waterfall/*` (all four), `AppState.swift` (WaterfallState body only: `rowTimestamps`, `displayMaxHz`, `inputPeak`, `inputRms`). `AudioCaptureService.swift` unchanged (metering lives in the waterfall loop).

**Tests**: `WaterfallAxisTests` (rewritten, 17 tests — width-parametric mapping, TX clamp per width, ruler ticks incl. 2750 non-multiple + degenerate cases), `WaterfallTimestampGateTests` (11 — once-per-slot over polled frames, reset, mode-change, label format, period-start-not-frame-time), `AudioInputLevelTests` (13 — all five statuses, threshold edges, NaN sanitizing, meter fraction). `swift test`: 209/209 pass.

**Coordinator notes**:
- Ran `xcodegen generate` to get a green app build — Agent D's new `LoggingSettings.swift`/`OnlineLogService.swift` existed on disk but weren't in the project yet (build failed on their `SettingsScreen` reference, not my files). Harmless for them; mentioning so nobody is surprised the pbxproj changed.
- TX tune floor changed 200→100 Hz to mirror Android's `100..width-100` clamp per the task brief; trivial to revert to 200 if desired (single constant `WaterfallAxis.minTxHz` + 2 test expectations).

## Session Metrics

Tasks total: 10 · Completed: 1

### Agent D — Logging integrations

**Status: COMPLETE** — kit tests 209/209 green (`swift test`), simulator build green (`xcodebuild` iPhone 16 Pro), `xcodegen generate` run after adding app files. Not committed (coordinator commits).

**What was built** (parity with Android `ThirdPartyService`, `PskReporterSender`, `ui/settings/LoggingSettings`):

Kit — pure, network-free protocol builders in `ios/FT8AFKit/Sources/FT8Engine/`:
- `CloudlogClient.swift` — `api/qso` POST request builder (JSON key order byte-identical to Android's JSONStringer; endpoint slash-less to dodge Wavelog/Nextlog 308s), `api/auth/<key>` test URL, whitespace-tolerant `<status>Valid</status>` + `<rights>rw</rights>` parser, 200/201 success rule.
- `QrzLogbookClient.swift` — logbook.qrz.com form bodies (INSERT/STATUS) with Java-URLEncoder-compatible encoding, `RESULT=` parser, OK/REPLACE=success for INSERT, OK-only for STATUS.
- `PskReporter.swift` — full IPFIX byte-layout port of PskReporterSender: 16-byte header, options template 0x50E2 (5 enterprise-30351 var fields), data template 0x50E3 (6 enterprise fields + standard flowStartSeconds 0x0096), receiver/sender data sets, var-string encoding, 1400-byte MTU splitting, templates on first 3 packets + hourly refresh; `PskDedup` (5-min per call|bandMHz window); `PskReporter.makeSpot` policy (skip self/`<...>`/free-text i3=0&n3=0, strip hash brackets, dial+audio-offset RF freq, ≥4-char locator). Time injected → fully deterministic tests.
- `OnlineLogAdif.swift` — `Adif.singleRecord()`: Android QSLRecordToADIF field order, no `<eoh>`/QSL flags, freq passed through as MHz.
- `OnlineLogSync.swift` — unsynced-record selection mirroring Android `unsyncedFilter`/`countUnsyncedQSOs`.
- `QsoRecord.swift` — added `syncedCloudlog`/`syncedQrz` Bools; custom `init(from:)` with `decodeIfPresent` defaults so pre-existing `qso_log.json` files still decode (proven by test); memberwise init defaults keep QsoEngine source-compatible.

Kit tests (45 new) in `Tests/FT8EngineTests/`: `CloudlogClientTests`, `QrzLogbookClientTests`, `PskReporterTests` (golden-byte template/record assertions, MTU split, template cadence, dedup, spot policy), `OnlineLogSyncTests` (incl. legacy-JSON backward-compat decode + single-record ADIF goldens).

App — `ios/FT8AF/FT8AF/`:
- `Engine/OnlineLogService.swift` — `@Observable @MainActor` singleton; URLSession transport (upload on QSO logged, retry-once, marks synced flags + persists; catch-up `syncAll` with `isSyncing/syncDone/syncTotal` progress; Cloudlog/QRZ test-connection); PSK Reporter NWConnection UDP to report.pskreporter.info:4739 with ~5-min batched flush.
- `Screens/Settings/LoggingSettings.swift` — new screen: Cloudlog (URL/API key/Station ID/enable/Test Connection with spinner + Pass/Fail + toast), QRZ Logbook (key/enable/Test), PSK Reporter toggle; persists via SettingsPersistence.
- `SettingsScreen.swift` — one nav row "Online Logging" (+ enabled-services summary) added to the existing Logging section.
- `Screens/Logbook/LogbookScreen.swift` — CL/QRZ sync chips on rows (signal cyan / confirmed green when synced, shown only when the service is enabled), toolbar cloud-upload button running catch-up sync with progress counter + completion toast.
- `Engine/LiveEngine.swift` — ONLY the two permitted touch points: decode-completion hands decodes to `OnlineLogService.shared.enqueuePskDecodes` (dial = band map + audio offset), QSO-completion calls `handleQsoLogged` after `broadcastUdpQso`.

**Coordinator notes:**
- AppState.swift untouched (pre-staged fields used as-is). QsoLogStore untouched — Codable back-compat handled inside QsoRecord.
- PSK spots queued while capture runs are flushed by the service's own 5-min timer even if the engine stops; `flushPsk` is also callable on demand if a stop-flush is ever wanted.
- QRZ REPLACE responses count as success (record already on QRZ), matching Android.

### Agent R — Decode UI parity

**Status: COMPLETE** — kit tests 256/256 green (`swift test`; 209 baseline + 47 new), simulator build green (`xcodebuild` iPhone 16 Pro), `xcodegen generate` re-run. Not committed (coordinator commits).

**What was built** (parity with Android `ui/decode/DecodeRow.kt`, `resolveQsoStatus`, `StatusPill.kt`, `SignalBar.kt`, `ui/settings/DecodeFilterSettings.kt`, `filterMessages` in `DecodeScreen.kt`):

Kit — pure, host-tested logic in `ios/FT8AFKit/Sources/FT8Engine/`:
- `DxccPrefix.swift` — compact prefix→DXCC-entity heuristic (~200 prefixes / ~130 entities incl. all task spot-checks W/K/N/A→USA, JA→Japan, DL→Germany, G/M/2E→England + GM/GW/GI regions, UA/R→Russia with 8/9/0-area Asiatic split, VK, PY, EA (+EA8 Canary AF), OH, ZL, VE…), longest-prefix match over `/`-separated segments (EA8/W1AW→Canary Is, W1AW/P→USA), continent codes NA/SA/EU/AF/AS/OC/AN.
- `DecodeAnnotator.swift` — `DecodeHighlight` enum, `HighlightToggles`, `LoggedQso`/`LogbookIndex` (O(1) worked calls / grid4s / bands-per-call / DXCC entities), `classifyDecode` with Android's priority PENDING > POTA > NEW DXCC > NEW GRID > NEW BAND > WORKED > CQ, each logbook category gated by its toggle (disabled → falls through). Helpers `isCQMessage`, `isDirectedToMe`, `isPotaCq`, `looksLikeParkRef`. RR73/short grids never count as new grids (reuses `gridToLatLon` validity).
- `DisplayFormatters.swift` — `relativeAge` (now/Ns/Nm/Nh/Nd buckets, clamps negative), `gridDistanceKm` (haversine over `gridToLatLon`), `kilometersToMiles`, `formatQsoDistance` (whole km/mi, empty when it rounds to 0), `gridDistanceText`.
- Tests (47 new): `DxccPrefixTests` (14), `DecodeAnnotatorTests` (21 — priority order, all four toggle fall-throughs, park-ref shape, grid validity, index normalization), `DisplayFormattersTests` (12 — age buckets/edges, FN31→JO31 ≈5900 km, unit conversion, zero suppression).

App — `ios/FT8AF/FT8AF/`:
- `Screens/Decode/DecodeRow.swift` — full Android-style rewrite: 3pt left accent bar (target pink > CQ amber), CALLING/CQ/TO YOU label chips (stack like Android), bold mono 15pt callsign (cyan to-me / pink target), grid, status pill right; message-text line; meta row = 5-segment SignalBar (Android thresholds −5/−12/−18, fill (snr+25)/30) + SNR dB + freq Hz + distance (km/mi per setting) + relative age; DXCC-entity location line with globe icon (USA/UK shorthand); backgrounds to-me signal 0.08/0.22, target TargetSoft/Border, CQ bgSurface/borderSubtle; third-party-QSO rows dimmed to 0.8; compact variant kept (denser paddings, 22pt bar, no msg/location lines). Status pill = 4pt-corner chip, dot + label, hue 0.12 bg / 0.28 border: pending amber, POTA green, NEW DXCC purple, NEW GRID yellow, NEW BAND/WORKED cyan, CQ amber.
- `Screens/Decode/DecodeScreen.swift` — base filter stage ANDs with chips like Android `filterMessages`: blocklist → showOnlyCQ → dxOnly (my continent from my call prefix; unknown continents excluded, matching Android) → continentFilter ≠ "All". Chips now share kit logic (`isCQMessage`, `isPotaCq`, New DXCC via `LogbookIndex.workedEntities`, Needed via worked calls, For Me via `isDirectedToMe`). Rows get `highlight` from `classifyDecode`, `isTarget` from `tx.targetCall`, live `now` from a 1s `TimelineView` (drives the age labels without per-row timers).
- `Screens/Settings/DecodeFilterSettings.swift` — "Phase 4" placeholder removed; real controls: Show-Only-CQ + DX-Only toggles, continent picker (All/NA/SA/EU/AF/AS/OC/AN), four highlight toggles (NewDxcc/NewGrid/NewBand/Worked) with category-colored dots, distance-unit picker (km/mi), Blocked Callsigns row shows the real count and links to `BlockedCallsignsSettings`. All persist via `SettingsPersistence` onChange.
- `AppState.swift` — DecodeMessage struct ONLY: added `var arrival: Date = Date()` (defaulted, so LiveEngine's existing constructor calls capture arrival at decode time — LiveEngine untouched per ownership).

**Coordinator notes:**
- NEW BAND uses the current `settings.band` at render time; a decode that survives a band change would be re-classified against the new band. Deriving per-message band would need LiveEngine to stamp it — deliberately avoided per ownership rules; cosmetic only.
- No iOS `highlightPota` setting exists in the pre-staged SettingsState, so POTA pills are always-on (Android gates them). Add the field later if wanted — annotator already isolates the category.
- Android's PENDING pill is textMuted; the task brief specified amber for pending — followed the brief.
- Ran `xcodegen generate`; pbxproj regenerated (kit files need no project entries; no new app-target files were added).

### Agent B — Engine/TX parity

**Status: COMPLETE** — kit tests green (298/298, `swift test`), simulator build green (`xcodebuild` iPhone 16 Pro), `xcodegen generate` run. Not committed (coordinator commits). Agents D/W's LiveEngine work (PSK hand-off, online-log hook, waterfall loop) preserved untouched; extended around it.

**1. Auto-sequence toggles wired**
- `autoCQAfterQSO` → `QsoEngine.autoReturnToCq` (set at engine start + every `syncSettingsToQso`). **Behavior change**: default OFF now means the sequencer *stops* after each completed QSO (it previously always returned to CQ); enable the setting for the old behavior.
- HUNT is now real behavior, not just a UI flag: while armed with no target locked, `autoAnswerIfHunting` answers the strongest decoded CQ (skips blocked callsigns, own call, hashed `<...>` calls, and already-worked stations from the logbook), sets `slotParity` to the opposite of the heard slot, and toasts "Answering X".
- `huntCallsCQ`: hunting with no CQ candidates and the engine idle → calls CQ itself (hunt+CQ hybrid; still interrupts to answer a CQ when one appears).
- `autoCallFollow`: while idle (not hunting-locked, engine inactive), a station calling *us* directly is auto-answered.
- `earlyDecode`: **inert on iOS** — the decode pipeline runs once at slot end (`runDecodeLoop` polls slot boundaries); a mid-slot early pass needs a decode-loop restructure that's out of scope. Setting remains persisted; noted here + nothing pretends to use it.

**2. TX watchdog + stop-after-N** — pure `TxSupervisor` in kit (`Sources/FT8Engine/TxSupervisor.swift`): watchdog = N minutes since last "progress" event (armed at CQ/answer/new-target/force-log; refreshed on QSO completion when auto-CQ is on and on hunt idle-listening slots, mirroring Android's `launchSupervision`; a *stuck* QSO deliberately ages it); stop-after-N = N consecutive transmissions of the same message with no reply (reply or message change resets). LiveEngine checks `stopReason` before every TX cycle → `stopTx()` + toast ("TX watchdog: stopped after N min" / "No reply after N attempts — TX stopped"). 0 = off for both.

**3. Late-start clip rule** — pure `TxTiming` in kit: `lateStartClipMs = max(0, msIntoCycle − slack)` (slack = `settings.lateStartToleranceMs`, default 2360), **never** modulo — tests explicitly guard the `% 15000` trap (700 ms in → 0 clipped; 3000 ms → 640; 14900 ms → 12540). Applied at actual playback time in `beginTxPlayback`; hopelessly-late TX (clip ≥ waveform) skips the cycle. `txDelayMs` honored as a pre-play delay (cancellable `txScheduleTask`). `pttDelayMs`: **inert on iOS** — there is no PTT line (AVAudioSession/VOX keying is the audio itself), so a keying settle delay has nothing to wait for; documented here, setting untouched.

**4. TUNE button** — `TuneTone` in kit: pure sine generator (raised-cosine 10 ms anti-click ramps, amplitude cap) + `chipLabel` ("TUNE 7s"). LiveEngine `toggleTune`/`startTune`/`stopTune`: buffer length = `settings.tuneTimeoutSec` so playback ending enforces the hard max-on even if the ticker dies; 1 Hz countdown into `tx.tuneRemainingSec`; stops on tap, timeout, CALL CQ, answer, or engine stop. TUNE pill in TxStrip info row: red (`statusBad`) + bold + countdown while active, dimmed/disabled while the sequencer is armed/transmitting (mutually exclusive with FT8 TX, as Android). Tune Timeout picker (10/15/30/60/120 s) added to TransmissionSettings' TX Safety section.

**5. Caller queue** — pure `CallerQueue` in kit: dedup by callsign (re-hear refreshes SNR/freq/time, keeps position), capacity 10 (Android `MAX_QUEUE_SIZE`), stale-prune after 60 s (~4 slots), `shouldEnqueue` policy (never self/target/blocked). LiveEngine enqueues stations calling me that aren't the target while the sequencer is active; auto-dequeues the next caller once the previous QSO fully wraps (incl. after the courtesy-73 slot, and even when auto-CQ is off — dequeue takes precedence over stopping, as Android). `ActiveQsoPanel` shows a wrapping QUEUE row (custom `Layout` flow); tapping a callsign = `forceLogAndMoveOn(nextCallsign:)` — logs the current QSO when it progressed far enough, then works that caller (replying with a report when their grid is known, mirroring Android's `checkFunOrderByExtraInfo + 1`).

**6. ActiveQsoPanel parity** — header states via pure `QsoPanelHeader` in kit (Hunting… / Calling CQ / QSOing with X / Waiting for X, target SNR in cyan; hunt+CQ hybrid correctly reads "Calling CQ" per the Android rule); LOG action (`QsoEngine.forceLog()` — logs only when reports were exchanged, à la Android `shouldForceLog`); ✕ = `QsoEngine.abandonToCq()` (back to CQ or idle per auto-CQ, nothing logged); TX selector chips CQ/GRID/RPT/R-RPT/RR73/73 wired to `engine.selectTxStage` → `QsoEngine.setStage` (active = amber accent + border, completed = cyan signal; pure `TxStageSelector` rules in kit). Conversation log + stage dots kept. Panel now also shows while HUNT is armed (so the Hunting state has somewhere to live) — one-line visibility condition in AppTabView alongside the `onToggleTune` callback wiring.

**7. TxStrip parity** — TUNE pill added to the info row (see #4); the mode pill was already a static non-interactive "FT8" chip — kept as-is per the brief (FT4/FT2 deferred). HUNT / CALL CQ / TX1-TX2 layout unchanged.

**Files** (all within ownership): kit — `Sources/FT8Engine/{TxSupervisor,TxTiming,CallerQueue,TuneTone,TxStageSelector}.swift` (new), `QsoEngine.swift` (added `forceLog`/`abandonToCq`, factored `makeRecord`); kit tests — `Tests/FT8EngineTests/{TxSupervisorTests,TxTimingTests,CallerQueueTests,TuneToneTests,TxStageSelectorTests}.swift` (new, 37 tests) + 6 new cases in `QsoEngineTests`; app — `Engine/LiveEngine.swift`, `Components/{ActiveQsoPanel,TxStrip}.swift`, `Navigation/AppTabView.swift` (callback + panel-visibility line), `Screens/Settings/TransmissionSettings.swift` (tune-timeout row), `AppState.swift` (TxState body only: `qsoStage`, `targetSnr`, `queuedCallers`, `isTuning`, `tuneRemainingSec`). `TxPlayerService.swift`/`SlotTimerBar.swift`/`TransmitGlow.swift` needed no changes.

**Coordinator notes:**
- Inert-by-platform: `earlyDecode` (slot-end decode pipeline), `pttDelayMs` (no PTT line on iOS). Both documented above; everything else in the TX/sequencer settings group now does something real.
- `autoCQAfterQSO` default-off behavior change called out in #1 — flag in the PR body.
- TUNE plays through the same `TxPlayerService`/AVAudioEngine speaker path as FT8 TX; on-device it keys VOX like any TX audio. Requires RX running (toast prompts if not).
