// swift-tools-version: 5.9
import PackageDescription

// FT8AFKit — the non-UI core of the native iOS port of FT8AF.
//
// All FT8 DSP comes from the SAME pure-C `ft8_lib` the Android app and the
// Tauri desktop port use (kgoba @ 6f528128 + ft8af_glue/gfsk.c). We do NOT
// duplicate that C: the `CFT8` target's shim .c files `#include` the real
// sources in place under ../../ft8af/app/src/main/cpp, exactly as the desktop
// build.rs references them. Single source of truth across all three frontends.
//
// Phase 0 ships CFT8 + FT8DSP (encode + hash) and the golden-vector tests that
// prove the reused C is bit-identical under Apple clang. Later phases add
// FT8Audio, FT8Rig, FT8Engine, FT8Data, FT8UI as separate targets here.
let package = Package(
    name: "FT8AFKit",
    platforms: [
        .iOS(.v16),
        .macOS(.v13), // so the pure-logic suites run via `swift test` on a Mac, no simulator
    ],
    products: [
        .library(name: "FT8DSP", targets: ["FT8DSP"]),
        .library(name: "FT8Audio", targets: ["FT8Audio"]),
    ],
    targets: [
        // C bridge to ft8_lib. Header-search path points at the ft8_lib root so
        // the library's internal `<ft8/...>`, `<fft/...>`, `<common/...>` angle
        // includes resolve. Path is relative to this target's source dir
        // (Sources/CFT8): four levels up reaches the repo root.
        .target(
            name: "CFT8",
            cSettings: [
                .headerSearchPath("../../../../ft8af/app/src/main/cpp/ft8_lib"),
                .headerSearchPath("../../../../ft8af/app/src/main/cpp/ft8af_glue"),
                // ft8_lib is third-party C; silence its warnings, keep our own clean.
                .unsafeFlags(["-w"]),
            ]
        ),
        // Safe Swift wrappers over CFT8 (mirror desktop dsp/{encode,decoder,hashtable}.rs).
        .target(
            name: "FT8DSP",
            dependencies: ["CFT8"]
        ),
        .testTarget(
            name: "FT8DSPTests",
            dependencies: ["FT8DSP", "CFT8"]
        ),
        // Platform-neutral audio/engine core: rolling slot buffer + UTC slot
        // timing + DT calibration (mirror desktop audio/slot.rs + the slot-timing
        // and calibrate_dt logic in engine.rs). No AVFoundation here, so it builds
        // and tests on the macOS host; the AVAudioEngine graph + resampler live in
        // a later, device-only target.
        .target(
            name: "FT8Audio",
            dependencies: ["FT8DSP"]
        ),
        .testTarget(
            name: "FT8AudioTests",
            dependencies: ["FT8Audio"]
        ),
    ]
)
