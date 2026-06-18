# FT8AF — iOS port

Native Swift + SwiftUI port of FT8AF. Reuses the **same pure-C `ft8_lib` DSP
core** the Android app (`ft8af/`) and the Tauri desktop port (`desktop/`) use —
no DSP is reimplemented. See `.claude/plans/i-want-to-add-expressive-bengio.md`
for the full design and phased roadmap.

## Status

**Phase 0 — C bridging + encode + golden tests** (merged). Proves the reused C
compiles under Apple clang and produces bit-identical FT8 frames:

- `FT8AFKit/` — a Swift package with:
  - `CFT8` — C module bridging `ft8_lib` + `ft8af_glue/gfsk.c`. The C is **not
    copied**; shim `.c` files (`Sources/CFT8/shim_*.c`) `#include` the real
    sources in place under `../../ft8af/app/src/main/cpp`, the same single
    source of truth the desktop `build.rs` references.
  - `FT8DSP` — `FT8Encoder` (pack77 / ft8_encode / GFSK synth / generateFT8) and
    `FT8Hash` (WSJT-X 22-bit callsign hash).
  - Tests: golden-vector encode + Costas, independent LDPC/CRC inverse
    cross-check, callsign-hash goldens, and C-struct memory-layout guards.

**Phase 1 — decode from a fixed buffer** (this branch). Adds the from-source RX
path: `FT8Decoder` (monitor lifecycle → `feedSlot` → `findSync` → `decodeAll`,
waterfall heatmap) and the per-decoder callsign `HashTable` + C hash-interface
callbacks, both ported from desktop `dsp/{decoder,hashtable}.rs`. Gated by an
in-memory **encode → decode round trip** (`FT8DecoderTests`): generate audio for
a message, feed it through the monitor, decode it back, and check the call/grid/
frequency. No audio device involved yet.

Later phases (AVAudioEngine capture/TX, QSO sequencing, rig control,
persistence, SwiftUI) add `FT8Audio`, `FT8Rig`, `FT8Engine`, `FT8Data`, `FT8UI`
targets here plus the `FT8AF.xcodeproj` app wrapper.

## Building / testing — requires macOS

The Swift toolchain does not run on Windows, so the package can only be built and
tested on a Mac (where this branch was authored). From `ios/FT8AFKit`:

```sh
swift build
swift test            # runs the golden gate + encode/decode round trip (no simulator)
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

The C bridging was smoke-compiled with LLVM clang on the Windows dev box: all
shims and the umbrella compile **except** `message.c`/`unpack.c`, which call
POSIX `stpcpy`. That is a Windows-libc gap only — Darwin's `<string.h>` (macOS +
iOS) declares `stpcpy`, so it builds on the real targets (the desktop port hit
the identical thing and needed a `stpcpy` shim **only** for its Windows host
build, never macOS). No action needed for iOS; do not "fix" it by editing the
vendored ft8_lib.

## Regenerating golden vectors

Never hand-edit the golden tables in `Tests/FT8DSPTests/GoldenVectors.swift`.
They are copied from the C golden test; regenerate via its `--emit` mode
(`ft8af/app/src/main/cpp/ft8af_glue/run_host_tests.ps1 -Regen`) and re-copy.
