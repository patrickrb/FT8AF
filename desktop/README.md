# FT8AF Desktop

A cross-platform (Windows / macOS / Linux) desktop port of the FT8AF Android app,
built with **Tauri** (Rust backend + React/TypeScript frontend). It reuses the
exact pure-C FT8 DSP core from the Android app (`ft8af/app/src/main/cpp`,
kgoba `ft8_lib` @ `6f528128`) — compiled from source, no JNI.

MVP scope: receive/decode FT8, transmit + auto-QSO sequencing, logbook with ADIF
export, rig control (**Hamlib** embedded library, **FLrig** XML-RPC, or direct
serial CAT — Yaesu / Kenwood / Icom CI-V), audio device selection, and a
waterfall. Deferred: map, POTA, QRZ photos, Bluetooth, PSKReporter upload.

## Layout

```
desktop/
  src/              React + TypeScript frontend (Vite)
    ipc.ts          typed command/event bridge to Rust
    App.tsx         decode list, waterfall, logbook, settings, TX/QSO panel
  src-tauri/        Rust backend
    build.rs        compiles the C ft8_lib core (cc crate) + Tauri context
    src/
      dsp/          FFI to ft8_lib (decoder, encode, callsign hash table)
      audio/        cpal capture/playback, 12 kHz resample, slot buffer
      engine.rs     the runtime: 15 s UTC cycle, decode, QSO, TX/PTT, events
      qso.rs        FT8 QSO auto-sequence state machine (pure, unit-tested)
      rig.rs        rig control backends: Hamlib (dyn-loaded), FLrig XML-RPC, serial CAT
      db.rs         SQLite (rusqlite) qso_log + config + ADIF export
      bands.rs      FT8 band plan
      main.rs       Tauri commands + event forwarding
```

## Prerequisites

- **Rust** (stable, MSVC toolchain on Windows): `winget install Rustlang.Rustup`
- **Node.js 18+** and npm
- **Windows only:** the C core uses C99 VLAs that MSVC's `cl.exe` rejects, so the
  build compiles the C with **clang-cl**. Install LLVM: `winget install LLVM.LLVM`
  (build.rs auto-detects `C:\Program Files\LLVM\bin\clang-cl.exe`, or set
  `CLANG_CL`). A POSIX `stpcpy` shim (`src-tauri/cbits/win_compat.h`) is
  force-included on Windows; the vendored `ft8_lib` is left unmodified.
- macOS/Linux use the default clang/gcc (VLAs + `stpcpy` already supported).

## Develop / run

```
cd desktop
npm install
npm run tauri dev      # starts Vite + builds the Rust app + opens the window
```

> **Windows Smart App Control:** freshly-built unsigned binaries can be blocked
> (`os error 4551`, "Application Control policy"). The GUI app generally runs, but
> SAC consistently blocks the unsigned **`cargo test`** binary because it does
> runtime `LoadLibrary` (for Hamlib via `libloading`). Sign the binaries, or test
> on a machine without Smart App Control. The app itself is unaffected.

### Rig control: Hamlib (bundled)

Hamlib is the default rig backend and is **bundled with the app on Windows** — the
LGPL Hamlib DLLs live in `src-tauri/hamlib/` and `build.rs` copies them next to
the built exe, so rig support works out of the box with no separate install.
Settings → Rig control → **Hamlib** shows a dropdown of every radio Hamlib
supports (~300+); pick yours, choose **Connection: Serial** (COM port + baud) or
**Network** (host:port), and Connect. Hamlib handles the model-specific CAT
protocol. (`Dummy` needs no hardware.)

**FlexRadio 6xxx:** two ways — (a) model **Kenwood TS-2000** + Serial, pointed at
your SmartSDR CAT virtual COM port (SmartSDR CAT emulates a TS-2000); or
(b) the native **FlexRadio 6xxx** model + Network, address `localhost:4992` (the
SmartSDR API). Note DAX is *audio* (set as the audio device), separate from CAT.

The backend dynamically loads the library at runtime (`libloading`), so there's
no build-time Hamlib dependency. To update Hamlib, replace the DLLs in
`src-tauri/hamlib/` (from a hamlib-w64 release). On Linux/macOS, install the
system `hamlib`/`libhamlib` package; if the library isn't found the rig list is
empty and you get a clean message rather than a crash.

> If you later enable Tauri bundling (`bundle.active`), add `hamlib/*.dll` to the
> bundle `resources` so they ship in the installer too.

## Build / test

```
cd desktop/src-tauri
cargo test            # DSP round-trip, QSO state machine, rig commands, DB/ADIF
cargo build --release # optimized binary (loads the bundled frontend in ../dist)
```

The frontend builds independently with `npm run build` (emits to `desktop/dist`).

## How it works

- **Decode:** audio is captured continuously (cpal), resampled to 12 kHz, and
  windowed into 15 s slots aligned to the UTC cycle. Each slot is fed to the C
  decoder; decodes stream to the UI as `decoded` events.
- **Transmit:** the QSO engine decides the next message; on our TX slot the
  engine encodes it (`generate_ft8`), keys PTT via the rig, and plays the GFSK
  waveform. Leading audio is clipped only when starting late
  (`ms_late = max(0, into_cycle - 2360)`) to preserve the Costas sync arrays.
- **Logging:** completed QSOs are written to SQLite and exportable as ADIF.
