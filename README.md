# FT8AF

**FT8 on Android — modernized.**

🌐 **[ft8af.app](https://ft8af.app)** · 💬 **[Join the Discord](https://discord.gg/tz4spm5nWB)**

A fork of [FT8CN](https://github.com/N0BOY/FT8CN) that takes the excellent original and brings it forward: a Jetpack Compose UI, full English localization, dozens of bug fixes, and a pile of new operating features built for real on-the-air use.

Run FT8 natively on your Android phone or tablet, drive your radio over USB CAT, decode the band, and work the world from anywhere.

---

## What's New in FT8AF

### UI & UX
- **Jetpack Compose UI** with a Material 3 dark theme
- **Full English localization** (the original was Chinese-only)
- **Active QSO Monitor** — collapsible panel above the TX strip showing the current contact at a glance
- **Caller queue** so you stay on target during an active QSO instead of bouncing to whoever's loudest
- **CQ / Stop toggle** button right on the TX strip
- **TX1 / TX2 time slot toggle** for picking your transmit period
- **Configurable spectrum width** and continuous waterfall scrolling
- **UTC timestamps** drawn on the waterfall at FT8 period boundaries
- **TX volume control** wired to the hardware volume buttons

### Radio & Audio
- USB CAT control and USB audio reliability fixes (auto-connect race conditions, multi-port handling, serial control)
- Rig model, control mode, and audio device pickers in the new Compose Settings
- FT-891 bandwidth correctly set to 3000 Hz in DATA-USB mode
- TX marker frequency alignment fixes

### Logging
- **Cloudlog** configuration dialog and automatic log upload
- **QRZ** automatic log upload

### Stability
- 58+ bug fixes across two "bug bash" passes: NPE crashes, resource leaks, threading issues, Android lifecycle bugs, encoding errors, RTL support, lint errors for Android 12+

---

## Install

Grab the latest APK from the [Releases](https://github.com/patrickrb/FT8AF/releases) page, or build it yourself:

```bash
cd ft8cn
./gradlew installDebug
```

---

## Thanks

Massive thanks to **BG7YOZ**, the original author of FT8CN, and **N0BOY**, who hosts the original repository and did the early translation work. None of this exists without their work — this fork stands entirely on their shoulders.

---

## About this fork

Most of the changes in FT8AF were vibe coded on I-70 at 70 mph on the way to and from Hamvention. Laptop on the passenger seat, radio in the back, Claude in the loop. Some of the best debugging happens at highway speed.

Built by:
- **Patrick Burns — [K1AF](https://www.qrz.com/db/K1AF)**
- **Reid — [N0RC](https://www.qrz.com/db/N0RC)** (co-pilot, road-trip debugger, all-around enabler)

73.