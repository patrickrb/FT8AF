# FT8AF — iOS port

Native Swift + SwiftUI port of FT8AF. Reuses the **same pure-C `ft8_lib` DSP
core** the Android app (`ft8af/`) and the Tauri desktop port (`desktop/`) use —
no DSP is reimplemented. See `.claude/plans/i-want-to-add-expressive-bengio.md`
for the full design and phased roadmap.

## Status

**Phase 0 — C bridging + encode + golden tests.** This is the make-or-break
phase: it proves the reused C compiles under Apple clang and produces
bit-identical FT8 frames. It ships:

- `FT8AFKit/` — a Swift package with:
  - `CFT8` — C module bridging `ft8_lib` + `ft8af_glue/gfsk.c`. The C is **not
    copied**; shim `.c` files (`Sources/CFT8/shim_*.c`) `#include` the real
    sources in place under `../../ft8af/app/src/main/cpp`, the same single
    source of truth the desktop `build.rs` references.
  - `FT8DSP` — `FT8Encoder` (pack77 / ft8_encode / GFSK synth / generateFT8) and
    `FT8Hash` (WSJT-X 22-bit callsign hash).
  - Tests: golden-vector encode + Costas, independent LDPC/CRC inverse
    cross-check, callsign-hash goldens, and C-struct memory-layout guards. The
    golden tables are copied verbatim from
    `ft8af/app/src/main/cpp/ft8af_glue/test_golden_encode.c`.

Later phases (decode, AVAudioEngine capture/TX, QSO sequencing, rig control,
persistence, SwiftUI) add `FT8Decoder`, `FT8Audio`, `FT8Rig`, `FT8Engine`,
`FT8Data`, `FT8UI` targets here plus the `FT8AF.xcodeproj` app wrapper.

## Building / testing — requires macOS

The Swift toolchain does not run on Windows, so the package can only be built and
tested on a Mac (where this branch was authored). From `ios/FT8AFKit`:

```sh
swift build
swift test            # runs the Phase 0 golden gate (no simulator needed)
```

`swift test` compiles `CFT8` (the vendored C) and runs `FT8DSPTests` on the host
Mac. Green here == the reused DSP is bit-identical under Apple clang.

### Header-search-path caveat (verify first on Mac)

`CFT8` needs the `ft8_lib` root on its header search path so the library's
internal `<ft8/...>`, `<fft/...>`, `<common/...>` angle includes resolve.
`Package.swift` sets this with `.headerSearchPath("../../../../ft8af/app/src/main/cpp/ft8_lib")`
(relative to `Sources/CFT8`). Some SwiftPM versions warn about a search path
that escapes the package root. If `swift build` rejects it, the fallback is to
switch those entries to `.unsafeFlags(["-I", "<path>"])`, or to symlink/copy the
`cpp` tree under `Sources/CFT8/vendor`. This is the first thing to confirm on the
Mac since everything downstream depends on the C module compiling.

A second thing to watch: the `CFT8.h` umbrella pulls in `ft8_lib` headers that
live outside the module, which `-fmodules` can flag as "include of non-modular
header." If that surfaces, add `-Wno-non-modular-include-in-module` (or
`-fmodules-allow-nonmodular-includes`) to the C target's flags. Both this and the
search-path item are pure wiring — they do not touch the DSP, whose correctness
the golden tests verify once the module compiles.

## Regenerating golden vectors

Never hand-edit the golden tables in `Tests/FT8DSPTests/GoldenVectors.swift`.
They are copied from the C golden test; regenerate via its `--emit` mode
(`ft8af/app/src/main/cpp/ft8af_glue/run_host_tests.ps1 -Regen`) and re-copy.
