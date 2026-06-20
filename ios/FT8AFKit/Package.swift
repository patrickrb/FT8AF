// swift-tools-version: 5.9
import Foundation
import PackageDescription

// Resolve the ft8_lib and ft8af_glue C source directories relative to this
// Package.swift. SPM rejects .headerSearchPath() outside the package root, so
// we pass absolute -I flags via .unsafeFlags() instead (see ios/README.md).
// Standardize the URL so the `..` segments are collapsed: the compiler then
// receives a clean, canonical absolute path (stable module-cache keys and
// diagnostics) instead of one threaded through `../../`.
let packageDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let cppRoot = packageDir
    .appendingPathComponent("../../ft8af/app/src/main/cpp")
    .standardized
    .path

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
        .library(name: "FT8Engine", targets: ["FT8Engine"]),
        .library(name: "FT8Rig", targets: ["FT8Rig"]),
    ],
    targets: [
        // C bridge to ft8_lib. The library's internal `<ft8/...>`, `<fft/...>`,
        // `<common/...>` angle includes resolve via absolute -I flags pointing
        // at the ft8_lib / ft8af_glue roots (cppRoot, computed from #filePath
        // above) — SPM rejects .headerSearchPath() that escapes the package root.
        .target(
            name: "CFT8",
            cSettings: [
                // ft8_lib is third-party C; silence its warnings, keep our own clean.
                // -I flags replace .headerSearchPath() because SPM rejects search
                // paths that escape the package root (see ios/README.md).
                .unsafeFlags([
                    "-w",
                    "-I", cppRoot + "/ft8_lib",
                    "-I", cppRoot + "/ft8af_glue",
                ]),
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
            dependencies: ["FT8Audio", "FT8DSP"] // tests import FT8DSP for FT8.* constants
        ),
        // QSO auto-sequencer state machine + the QsoRecord it emits (port of
        // desktop qso.rs). Pure logic over DecodedMessage (FT8DSP); no I/O, so the
        // whole thing is host-testable. The live FT8Engine actor (audio + rig +
        // cycle loop) is added here later as device code.
        .target(
            name: "FT8Engine",
            dependencies: ["FT8DSP"]
        ),
        .testTarget(
            name: "FT8EngineTests",
            dependencies: ["FT8Engine", "FT8DSP"] // tests build DecodedMessage fixtures
        ),
        // CAT command-byte framing for Yaesu/Kenwood/Icom rigs (port of the
        // per-model Driver impls in desktop rig.rs). Pure bytes -- no transport,
        // no dependencies -- so it's fully host-testable. The BLE/network
        // transports that write these bytes are device code added later.
        .target(
            name: "FT8Rig"
        ),
        .testTarget(
            name: "FT8RigTests",
            dependencies: ["FT8Rig"]
        ),
    ]
)
