# Shared radio infrastructure audit and reference pattern

## Decision

FT8AF is the canonical reference for the first shared core: all five audited
repositories already use its Android-free FIR, rational resampler, UAC1 parser
and CAT liveness policies. These form a small, exercised compatibility boundary,
unlike the large recorder/view-model/global-state stack. SSTVAF has particularly
useful injected playback/PTT seams; ROTA has a service/StateFlow seam; neither is
yet a complete cross-app engine contract. This selection is about this extraction,
not a claim that FT8AF has the best implementation of every subsystem.

`ham-radio-core/` is a standalone Java library, also consumed as a normal Gradle
project by FT8AF. Adapters import it directly. Its tests moved with the source;
no JNI names, filter coefficients, waveform timing or USB scheduling changed.
See its README for ownership, buffering, rate, liveness and distribution contracts.

## Audit across the five projects

| Area | Observed reuse | Boundary for this rollout |
| --- | --- | --- |
| Audio/DSP | All five contain plain Java `FirDecimator` and `RationalResampler`, with separate native equivalents. | Share Java streaming filters unchanged. Retain native implementations and parity tests; do not merge mode codecs. |
| USB | All five contain `UacAudioFormats`, direct libusb + Android fallback adapters, and serial drivers. | Share raw-byte UAC1 decisions. Permissions, interface/FD ownership, cancellation, JNI/R8 and device selection stay local. |
| CAT | All five contain `CatLiveness` and substantial inherited rig/connector code. | Share liveness predicates. Keep polling/reconnect state, byte framing, rig encoders and PTT safety with each adapter. |
| Logging | ADIF helpers exist in all; database/export/service code mixes Android, globals and inherited FT8 report/branding assumptions. | No wholesale extraction. ROTA trip/Room/API log and live RTTY in-memory log differ materially; neither is interchangeable with FT8 QSL persistence. |
| UI | Related Compose themes/cards/sliders/status components recur; screens bind app state and resources. | Reuse the thin presentation/pure-decision pattern, not a universal screen or branded design system in this JVM module. |

### Project-specific integration surfaces

- **FT8AF:** `UsbAudioDevice` consumes the three audio/USB types;
  `TxUpsampler` consumes the rational filter; `MainViewModel` and
  `CatLivenessTracker` consume CAT decisions. Existing mode-specific test suites
  stay in the app; four portable test classes move into the core.
- **RTTYAF:** equivalent Java types under `com.k1af.rttyaf`; keep Baudot, AFC,
  modem, TX arbitration and key/unkey teardown local. Capture is 12 kHz; borrowed
  capture buffers must still be copied before asynchronous use.
- **SSTVAF:** equivalent types under legacy `com.k1af.ft8af`; preserve SSTV
  codec/image/gallery ownership and its `TransmitAudioSink`/`PttController`
  injection seams. Do not rename JNI packages while extracting Java policy.
- **ROTA:** equivalent types plus `TxUpsampler` and `CatLivenessTracker` consumers;
  preserve Hamlib, USB attachment identity, pending PTT-off handling, trip logging
  and foreground-service lifetime. Its network-rig selection is not supported
  merely because inherited networking classes exist.
- **JTTYAF:** repository still uses FT8AF build/packages/application identity at
  audit time. Include `TxUpsampler`/tracker consumers; preserve Hamlib transport,
  PTT-off debt, buffer ownership and existing JNI/library identity.

Further candidates include channel selection, framing, diagnostic snapshots,
and ADIF field encoding, but each needs its own contract comparison. In particular,
`InputAudioLevel` uses 3000 samples (250 ms only at 12 kHz), and USB retry policy's
5-second useful-session threshold derives from FT8 timing. Neither should become
generic by moving it unchanged under a neutral package name.

## Reference-app workflow

1. Audit real consumers and separate pure algorithms/policies from I/O/state.
2. Preserve behavior while extracting in FT8AF; move public-contract tests with it.
3. Run the standalone suite and app integration checks before distributing.
4. Transfer the exact versioned source/test/license/manifest payload to parallel
   project owners. Compare local implementations first; preserve additional
   consumer APIs/fixes rather than flattening forks blindly.
5. Each port changes Gradle wiring/imports, removes duplicate implementations,
   and runs core + consumer tests/build. Hardware claims require an actual device.
6. Report per-project results and explicit blockers; do not label an SDK failure
   as a passing app build. Ship through each repository's PR workflow only after
   the owner authorizes remote delivery.

No shared native mega-library, hosted artifact, infrastructure change or UI
redesign is needed for this first useful slice. Source snapshots are an explicit
bootstrap distribution strategy, not permission for five divergent core forks.

## FT8AF validation (2026-10-30)

The integration was rebased onto `origin/dev` (`6787f490`), rather than leaving
it on the orb's older local `main`. All four extracted implementations and test
bodies still match that baseline after removing package/import/comment changes.

With JDK 17 and an Android SDK configured, from the repository root:

```sh
bash ft8af/gradlew -p ham-radio-core clean test jar javadoc
bash ft8af/gradlew -p ft8af --no-daemon --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' \
  :ham-radio-core:test testDebugUnitTest assembleDebug
CC=gcc CXX=g++ bash ft8af/app/src/main/cpp/ft8af_glue/run_host_tests.sh
python3 -m unittest discover -s .github/scripts -p test_release_notes.py -q
```

Observed results:

- Standalone core: **44 tests passed**, JAR/Javadoc generated; Javadoc retains
  56 pre-existing missing-tag/comment warnings.
- Android integration: **BUILD SUCCESSFUL**, 44 core + 4,364 app tests, zero
  failures/errors/skips; debug APK built with all four native ABIs. APK inspection
  found the four `radio.core` descriptors and none of their old app-local names.
- Native host suite: **passed**; committed WAV corpus matched 462/473 truth
  messages (97.7%), meeting every committed decode floor; feed/Hamlib feed and
  grid-shape checks passed.
- Release-script suite: **31 tests passed**. Workflow YAML and shell syntax
  validated. Executing its path-selection expression includes core-only and
  app-only inputs, excludes docs/desktop/iOS-only and similarly prefixed paths.
- All 13 checksum-manifest entries and `git diff --check`: **passed**.

Android CI explicitly runs `:ham-radio-core:test`, includes the module in its
change detector and production-change scope/size signals, and uploads core test
reports on test failure. The Android-only test task does not run JVM-library
tests by itself. No CI workflow, release or deployment was triggered locally.

No Android device was attached. Install, connected instrumentation, actual
USB/CAT/PTT hardware and on-air behavior remain unverified. Lint/static-analysis
and release builds were not part of this extraction's validation. Existing
resource-format/deprecation/native format warnings were left unchanged.
