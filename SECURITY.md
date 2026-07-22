# Security Policy

FT8AF is amateur-radio software that talks directly to real hardware — it drives
radios over CAT/USB, moves audio through native code, and links to bundled
native libraries. We take security reports seriously and appreciate responsible
disclosure.

This policy covers **all** flavors of the app that live in this repository:

- **Android** — `ft8af/` (Jetpack Compose app + `libft8af.so` native DSP).
- **Desktop** — `desktop/` (Tauri: Rust backend + React/TypeScript frontend, for
  Windows, macOS, and Linux).
- **iOS** — `ios/` (Swift + SwiftUI port, `FT8AFKit` Swift package).

## Reporting a Vulnerability

**Please report vulnerabilities privately. Do not open a public GitHub issue, and
do not post in Discord.** Public disclosure before a fix is available puts users
at risk.

Preferred: use **GitHub private vulnerability reporting**.

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability** (under *Advisories*).
3. Fill in the private advisory form.

This opens a private channel between you and the maintainers, tracked as a draft
security advisory.

> **Note:** GitHub private vulnerability reporting must be enabled by the
> maintainers under **Settings → Security → Private vulnerability reporting**
> for the "Report a vulnerability" button to appear. If you don't see it, use
> the email fallback below.

Fallback: email **k1af@ft8af.app**. If you'd like to encrypt the report or
exchange a key first, say so in an initial message and we'll arrange it.

### What to include

To help us triage quickly, please include as much of the following as you can:

- The affected flavor (Android / desktop / iOS) and version, commit, or APK.
- The affected component or file path (see **Scope** below).
- A description of the issue and its impact.
- Steps to reproduce, a proof of concept, or a crashing input if you have one
  (e.g. a captured CAT exchange, a WAV/audio sample, or an ADIF/log payload).
- Any relevant device, rig model, OS, or toolchain details.

## Response

- We aim to **acknowledge** a report within **7 days**.
- We'll keep you updated on triage and remediation progress.
- We're happy to **credit** you in the advisory and release notes when a fix
  ships, unless you'd prefer to stay anonymous.

Please give us a reasonable window to release a fix before any public
disclosure. This is a volunteer, hobbyist project, so timelines are best-effort.

## Scope

Security-relevant areas of the codebase include, but are not limited to:

- **Native `ft8_lib` / JNI DSP path** — the vendored `ft8_lib` C core and the
  `ft8af_glue` JNI/FFI layer under `ft8af/app/src/main/cpp/`, which parse and
  synthesize FT8 waveforms and are reached from Android (JNI), desktop (Rust
  FFI), and iOS (Swift C interop). Memory-safety issues here (parsing decoded
  frames, buffer handling) affect every flavor.
- **CAT / audio & libusb** — USB CAT serial control and the direct-libusb audio
  path (`ft8af/app/src/main/cpp/usb_audio_capture.cpp` and related), including
  parsing of rig responses and handling of untrusted USB device descriptors.
- **Desktop Hamlib rig control** — `desktop/src-tauri/src/rig.rs` and the bundled
  Hamlib library, including CAT/serial parsing and any dynamically loaded
  Hamlib backends.
- **Tauri / Rust IPC** — the command/event bridge between the desktop frontend
  and the Rust backend (`desktop/src/ipc.ts`, `desktop/src-tauri/src/main.rs`),
  and any command that touches the filesystem, network, or serial ports.
- **Logging-service credentials** — storage and transmission of credentials and
  tokens for third-party logging integrations (Cloudlog/Wavelog, QRZ.com, PSK
  Reporter), and the data uploaded to them.

If you're unsure whether something is in scope, report it anyway and we'll help
figure it out.

### Out of scope

- Vulnerabilities in third-party services (Cloudlog/Wavelog, QRZ.com, PSK
  Reporter) themselves — report those to the respective service.
- Denial of service that requires physical access to the device or rig.
- Issues that require a maliciously modified build of the app.

## Upstream coordination

Some security-relevant code is vendored or bundled from upstream projects:

- **`ft8_lib`** — the DSP core is a vendored copy of
  [kgoba/ft8_lib](https://github.com/kgoba/ft8_lib), pinned to a specific commit
  (see `ft8af/app/src/main/cpp/ft8_lib/FT8_LIB_PIN.txt`). If a report concerns a
  bug in unmodified upstream code, we will coordinate disclosure with the
  upstream maintainers and update our pin once a fix is available.
- **Hamlib** — the desktop port bundles the [Hamlib](https://hamlib.github.io/)
  library for rig control (see `desktop/src-tauri/hamlib/`). Issues in Hamlib
  itself will be coordinated with the Hamlib project; we'll update the bundled
  version as fixes land upstream.

When a vulnerability originates upstream, please still report it to us so we can
track exposure across the Android, desktop, and iOS builds and pull in the fix.
