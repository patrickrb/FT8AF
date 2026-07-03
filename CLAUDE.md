# FT8AF Project Instructions

## Development environments

Two checkouts are in use; the commands differ, so figure out which one you're on
first (`uname` / the primary working directory) and follow the matching path
throughout:

- **Windows** (`C:\Users\burns\Projects\NEXT-FT8CN`, driven from WSL) — the
  original build-and-deploy machine, with a Pixel 8 attached. Uses the
  `cmd.exe /c "gradlew.bat …"` wrapper.
- **macOS** (`/Users/reidcrowe/Documents/GitHub/FT8AF`) — builds and runs unit
  tests natively (`./gradlew …`, needs JDK 17) and has `adb` plus a device
  attached (a Moto G Stylus 5G, `ZY22G8KCH2`, usually alongside an emulator), so
  it can do the full `installDebug` + on-device verification loop too.

Both machines can build, test, install, and drive a real device. Check which
one you're on first (`uname` / the primary working directory) — the gradle
invocation differs (`gradlew.bat` wrapper vs. `./gradlew` with JDK 17), and the
attached phone differs (Pixel 8 on Windows, Moto G Stylus 5G on macOS). Where a
section below gives a command for one, the other's equivalent is called out
inline.

On macOS, `screencap` can come back all-black on the Moto during the splash
animation or when a hardware overlay is active; wait for the app to settle and
retry, and use the accessibility tree only as a fallback (`uiautomator dump`
often can't reach idle because the UI animates continuously).

## Workflow

**Every work item requires a pull request — no direct commits to `dev` or
`main`.** Even a one-line docs or config change goes through a feature branch and
a PR.

**All PRs target the `dev` branch.** After finishing the code for any work item,
open a pull request against `dev` (do the work on a feature branch, then
`gh pr create --base dev`). Don't merge straight to `main`.

**Use a git worktree for every separate line of work.** Don't switch branches in
the primary checkout (`C:\Users\burns\Projects\NEXT-FT8CN`) — branch-switching
there collides with anything else in flight (a running build, an `adb install`, a
different task). Instead spin up an isolated worktree per task:

```
git worktree add ../NEXT-FT8CN-<short-task-name> -b feat/<task>
```

Notes for a fresh worktree:

- The `ft8af/app/src/main/cpp/` native sources (`ft8_lib`, `ft8af_glue`) are
  **tracked** in git, so a fresh worktree builds with no manual copying.
- Build/install from inside the worktree's `ft8af` dir: Windows uses the wrapper
  (`cmd.exe /c "gradlew.bat installDebug"`); macOS uses `./gradlew` (see Build &
  Deploy for the JDK 17 requirement). Both can install to their attached device.

Remove the worktree when the branch is merged: `git worktree remove <path>`.

On macOS the primary checkout is not on the Windows path, so a feature branch in
place is fine when nothing else is in flight, but a worktree per task is still the
recommended default.

## Testing

**Every new code path requires a new test.** Any branch, helper, or behavior
you add or change must be covered by a unit test in the same PR — this is not
optional, even for small UI helpers.

Compose `@Composable` and `DrawScope` code can't be unit-tested directly, so
extract the decision/geometry logic into a plain top-level `internal` function
or class (e.g. `buildQsoLog`, `QsoPathProjection`) and test that. Keep the
Composable a thin wrapper that just calls the extracted logic.

Tests live in `ft8af/app/src/test/` (Kotlin under `.../kotlin`, Java under
`.../java`), use JUnit4 + Truth (`assertThat`), and add
`@RunWith(RobolectricTestRunner::class)` when the code under test touches
Android/Play-Services types (e.g. anything reaching `MaidenheadGrid`,
`GeneralVariables`, `LatLng`). Pure math/logic needs no runner.

Run from the `ft8af` dir.

**Windows:**

```
cmd.exe /c "gradlew.bat testDebugUnitTest"
# or a single class:
cmd.exe /c "gradlew.bat testDebugUnitTest --tests <fully.qualified.ClassName>"
```

**macOS** (needs JDK 17 — see Build & Deploy):

```
export JAVA_HOME=~/.local/jdks/jdk-17.0.19+10/Contents/Home
./gradlew testDebugUnitTest
# or a single class:
./gradlew testDebugUnitTest --tests <fully.qualified.ClassName>
```

## Build & Deploy

After making code changes, always build and install on the connected device.
Both machines have a device attached and can do this.

**Windows:** The WSL shell has no Linux JDK, so `./gradlew` fails with
`JAVA_HOME is not set`. Use the Windows wrapper instead — it picks up the Android
Studio JBR automatically:

```
cd ft8af && cmd.exe /c "gradlew.bat installDebug"
```

**macOS:** AGP 8.7.3 / Gradle 8.9 need **JDK 17**; the system JDK is 11, so builds
fail without pointing `JAVA_HOME` at a 17. Temurin 17 is installed user-locally
(no sudo — the Homebrew cask installer needs a sudo password) at
`~/.local/jdks/jdk-17.0.19+10/Contents/Home`. The `installDebug` task installs to
*every* attached device (including the emulator); to target only the phone, build
the APK and push it with an explicit serial:

```
cd ft8af && JAVA_HOME=~/.local/jdks/jdk-17.0.19+10/Contents/Home ./gradlew assembleDebug
adb -s ZY22G8KCH2 install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb` is on the PATH (`/opt/homebrew/bin/adb`). To drive the UI, `adb -s
ZY22G8KCH2 exec-out screencap -p > shot.png` and tap with `adb -s ZY22G8KCH2
shell input tap <x> <y>` (coordinates in the device's real pixel space, 1080×2460).

The phones: **Windows** has a Pixel 8 (`adb devices -l` model `Pixel_8`); **macOS**
has a Moto G Stylus 5G (`ZY22G8KCH2`). On both, an emulator is usually also
attached — Gradle's install step prints `TimeoutException`/`Unknown API Level`
warnings for the emulator and still installs on the phone; `Installed on 1 device.`
means the phone got the APK, so ignore the emulator noise.

When multiple devices are attached, target the phone explicitly with `-s` and its
serial (from `adb devices`). On Windows, adb lives at
`/mnt/c/Users/burns/AppData/Local/Android/Sdk/platform-tools/adb.exe`; on macOS
it's `/opt/homebrew/bin/adb`.

## Debug logs

The app writes a structured event log to
`/sdcard/Android/data/radio.ks3ckc.ft8af/files/debug.log` via `fileLog()` in
`ComposeMainActivity.kt` — CAT serial sends/recvs, USB attach events,
autoConnect attempts, band/frequency changes, etc. This is usually the most
useful source. Pull it with:

```
adb -s <phone-serial> pull /sdcard/Android/data/radio.ks3ckc.ft8af/files/debug.log /tmp/
```

For runtime detail not in `debug.log` (audio recording loop, system USB events,
crashes), use `adb logcat`. Useful tags: `FT8SignalListener`, `MicRecorder`,
`UsbAudioDevice`, `CableConnector`, `CableSerialPort`, `UsbHostManager`,
`UsbAlsaManager`. The app's `applicationId` is `radio.ks3ckc.ft8af` — pid-filter
with `--pid=$(adb shell pidof radio.ks3ckc.ft8af)` when you only want app-internal
lines.

## FT8 TX audio pipeline

How a `playFT8Signal` call becomes RF, with the gotchas that produce
audible-but-undecodable signals. Both of these were diagnosed the hard
way and re-introducing either makes TX silently broken — the rig keys,
audio is audible, ALC looks right, and zero spots appear on PSKReporter.

**1. The native library generates the entire waveform.** `GenerateFT8.generateFt8(msg,
freq, 12000)` returns a mono 12.64-second `float[]` at 12 kHz containing the full
FT8 message. The Costas sync arrays at symbols 0-6, 36-42, 72-78 are
embedded by `synth_gfsk` in the native lib — today that's the from-source
`libft8af.so` (`System.loadLibrary("ft8af")`, built from the vendored
`ft8_lib`; historically this was the retired closed prebuilt `libft8cn.so`).
The buffer is correct as generated — everything
downstream just has to *not break it*.

**2. `lateStartSkipMs` clips leading audio, but only if we'd overrun the cycle.**
FT8 audio is 12.64 s; cycle is 15 s; slack is 2.36 s. `msLate` (in
`FT8TransmitSignal.java`) must be computed as
`max(0, time_into_cycle_ms - 2360)` — **not** `time_into_cycle_ms %
15000`. The latter treats every ms past the cycle boundary as lateness,
so a normal on-time TX firing ~500-800 ms into the cycle chops that many
ms off the **start** of the buffer — exactly where the leading Costas
array lives. Receivers see audio but can't sync. Tell from log:
`playLength < samples` when the TX started <2.4 s into the cycle. Fixed
in PR #93.

**3. `libusb_set_iso_packet_lengths` must use the audio rate, not
`wMaxPacketSize`.** A USB Audio Class device plays back exactly the bytes
per frame the host hands it. For USB FS, that's
`(sampleRate * channels * bytesPerSample) / 1000` — e.g. 192 bytes/frame
at 48 kHz stereo 16-bit. The endpoint's `wMaxPacketSize` (~200 for
C-Media CM108-style chips) is the device's *max*, not the data rate.
Sending that much per frame makes the device clock samples ~4 % faster
than negotiated, shifting every FT8 tone up by the same ratio and
pushing the message off WSJT-X's 6.25 Hz grid. Tell from log:
`UsbAudioNative.nativeWrite` returns measurably faster than the audio
duration (12.14 s real time for 12.64 s of audio). Fixed in PR #94 in
`cpp/usb_audio_capture.cpp`. The Android-standard `AudioTrack` path is
unaffected because the kernel UAC driver does this math automatically;
the bug only bites the direct-libusb path used for car-dash kernels and
similar.
