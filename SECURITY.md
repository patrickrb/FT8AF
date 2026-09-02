# Security Policy

FT8AF is an amateur-radio application that drives radios over CAT, plays and
records audio, and uploads contact logs to third-party services (Cloudlog, QRZ).
We take security and privacy seriously and appreciate reports that help keep
operators and their stations safe.

## Supported Platforms

FT8AF ships in several flavors, all covered by this policy:

| Platform                | Location    | Notes                                              |
| ----------------------- | ----------- | -------------------------------------------------- |
| **Android**             | `ft8af/`    | Kotlin/Java app with native `ft8_lib`/JNI DSP core |
| **Desktop (Windows / macOS / Linux)** | `desktop/` | Tauri app (Rust backend + web UI), rig control via bundled Hamlib |
| **iOS**                 | `ios/`      | Swift app and `FT8AFKit`                            |

## Supported Versions

Security fixes are applied to the latest release only. Please make sure you can
reproduce an issue on the most recent [release](https://github.com/patrickrb/FT8AF/releases)
(or a current build of the `dev` branch) before reporting.

| Version            | Supported          |
| ------------------ | ------------------ |
| Latest release     | :white_check_mark: |
| Older releases     | :x:                |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
pull requests, or the Discord server.**

Instead, use GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/patrickrb/FT8AF/security) of this
   repository.
2. Click **Report a vulnerability** to open a private advisory.

This routes your report privately to the maintainers. If you are unable to use
GitHub's reporting flow, email [k1af@ft8af.app](mailto:k1af@ft8af.app) to
arrange a private channel.

### What to include

To help us triage quickly, please include as much of the following as you can:

- The affected platform (Android, desktop/Windows/macOS/Linux, or iOS) and
  component (app UI, native `ft8_lib`/JNI glue, Tauri/Rust backend, Hamlib rig
  control, build/CI workflows).
- Version or commit hash, plus device/OS and radio model if relevant.
- A description of the vulnerability and its potential impact.
- Step-by-step reproduction instructions, proof-of-concept, or logs.

### Our commitment

- We will acknowledge your report within **5 business days**.
- We will provide an assessment and expected timeline within **10 business days**.
- We will keep you informed as we work on a fix and will credit you in the
  release notes and advisory unless you prefer to remain anonymous.

## Scope

Areas of particular interest, across all platforms:

- Handling of untrusted RF/decoded input in the native FT8 DSP path
  (`ft8af/app/src/main/cpp/`) — memory-safety issues in parsing decoded frames.
- CAT / audio device handling: the Android USB CAT and direct-libusb path, and
  the desktop Hamlib-based rig control (`desktop/src-tauri/hamlib/`).
- The desktop Tauri/Rust backend and its exposed commands / IPC surface.
- Storage and transmission of credentials for logging services (Cloudlog, QRZ)
  on every platform.
- Any code that reads, writes, or uploads user data.

The vendored [ft8_lib](https://github.com/kgoba/ft8_lib) DSP core is pinned to an
upstream commit (see `ft8af/app/src/main/cpp/ft8_lib/FT8_LIB_PIN.txt`), and the
desktop build bundles [Hamlib](https://github.com/Hamlib/Hamlib). If a
vulnerability originates in one of these upstream projects, please also consider
reporting it there; we will coordinate on picking up the fix.

## Out of Scope

- Vulnerabilities in third-party services (QRZ, Cloudlog) themselves — report
  those to the respective service.
- Issues requiring a rooted device, physical access plus an unlocked bootloader,
  or a compromised host already under attacker control.
- Reports from automated scanners without a demonstrated, exploitable impact.

Thank you for helping keep FT8AF and the amateur-radio community safe. 73.
