// Compile the pure-C FT8 DSP core (kgoba ft8_lib pinned at 6f528128) plus the
// FT8CN GFSK glue into a static `ft8core` lib that the Rust FFI layer links.
//
// We reference the C sources in place inside the Android tree (../../ft8af/...)
// rather than duplicating them, so the desktop port tracks the same DSP as the
// app. This mirrors the standalone build recipe proven by ft8af_glue/test_roundtrip.c.

use std::path::PathBuf;

// Arch-selection helpers (hamlib_arch_subdir / clang_cl_target_flag), shared
// verbatim with the library so their logic is unit-tested by `cargo test`.
// Included rather than `mod`'d because a build script is its own crate root.
include!("src/build_support.rs");

fn main() {
    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let target = std::env::var("TARGET").unwrap_or_default();
    // src-tauri -> desktop -> NEXT-FT8CN
    let cpp = manifest.join("../../ft8af/app/src/main/cpp");
    let ft8 = cpp.join("ft8_lib");
    let glue = cpp.join("ft8af_glue");

    let mut build = cc::Build::new();
    build.include(&ft8).include(&glue);

    // ft8_lib uses C99 variable-length arrays (decode.c, monitor.c). MSVC's cl.exe
    // rejects VLAs, so on the windows-msvc target compile with clang-cl instead:
    // it supports VLAs and still emits MSVC-ABI objects that link into the Rust
    // binary — and it's the same compiler family as the Android NDK, keeping the
    // DSP bit-identical. macOS/Linux use the default clang/gcc, which handle VLAs.
    if target.contains("windows-msvc") {
        if let Some(clang_cl) = find_clang_cl() {
            build.compiler(clang_cl);
        }
        // clang-cl defaults to the host architecture. When cross-compiling the
        // 64-bit host down to 32-bit x86 (i686), pass the target triple so the
        // `ft8core` objects are 32-bit and link into the i686 Rust binary; x64
        // keeps clang-cl on its host default (unchanged behavior). The
        // _USE_MATH_DEFINES / C99-VLA path below is arch-independent, so it
        // still compiles under x86.
        if let Some(flag) = clang_cl_target_flag(&target) {
            build.flag(&flag);
        }
        // Force-include the compat shim (provides stpcpy) into every C TU.
        build.include(manifest.join("cbits"));
        build.flag("/FIwin_compat.h");
    }

    // ft8_lib core (matches the file set in test_roundtrip.c's build line).
    for f in [
        "ft8/pack.c",
        "ft8/encode.c",
        "ft8/decode.c",
        "ft8/ldpc.c",
        "ft8/osd.c",
        "ft8/message.c",
        "ft8/text.c",
        "ft8/unpack.c",
        "ft8/crc.c",
        "ft8/constants.c",
        "common/monitor.c",
        "fft/kiss_fft.c",
        "fft/kiss_fftr.c",
    ] {
        build.file(ft8.join(f));
    }
    // FT8CN glue: GFSK waveform synthesis with the trailing `offset` param.
    build.file(glue.join("gfsk.c"));

    // MSVC needs _USE_MATH_DEFINES for M_PI; silence CRT string warnings.
    build.define("_USE_MATH_DEFINES", None);
    build.define("_CRT_SECURE_NO_WARNINGS", None);
    build.warnings(false);

    build.compile("ft8core");

    // On Windows, copy the vendored Hamlib DLLs next to the output exe so the
    // app loads rig support out of the box (no separate Hamlib install). The
    // exe's own directory is first in the DLL search order, so libhamlib-4.dll
    // and its dependencies resolve automatically.
    if target.contains("windows") {
        copy_hamlib_dlls(&manifest, &target);
    }

    println!("cargo:rerun-if-changed={}", cpp.display());
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=src/build_support.rs");
    println!("cargo:rerun-if-changed=hamlib");

    // Generate the Tauri build context (reads tauri.conf.json).
    tauri_build::build();
}

fn copy_hamlib_dlls(manifest: &std::path::Path, target: &str) {
    let hamlib = manifest.join("hamlib");
    if !hamlib.is_dir() {
        return;
    }
    // Pick the DLL set matching the target process architecture. The DLLs live
    // in arch subdirs (hamlib/x86, hamlib/x64) so the 32-bit build can't pick up
    // the win64 DLLs (which won't load into an i686 process) and vice-versa. Fall
    // back to the flat hamlib/ layout for older checkouts without the split.
    let src = match hamlib_arch_subdir(target) {
        Some(arch) => {
            let sub = hamlib.join(arch);
            if sub.is_dir() {
                sub
            } else {
                hamlib.clone()
            }
        }
        // Non-Windows: nothing to copy (Hamlib is a system package there).
        None => return,
    };
    // If the arch dir exists but ships no DLLs yet (e.g. the 32-bit set hasn't
    // been vendored), copy nothing — the app still builds and rig control
    // degrades cleanly to "Hamlib unavailable" via load_hamlib()'s fallback.
    //
    // OUT_DIR = target/<profile>/build/<pkg>/out  -> exe dir is 3 parents up.
    let out_dir = match std::env::var("OUT_DIR") {
        Ok(d) => PathBuf::from(d),
        Err(_) => return,
    };
    let exe_dir = match out_dir.ancestors().nth(3) {
        Some(p) => p.to_path_buf(),
        None => return,
    };
    if let Ok(entries) = std::fs::read_dir(&src) {
        for e in entries.flatten() {
            let p = e.path();
            if p.extension().and_then(|s| s.to_str()) == Some("dll") {
                if let Some(name) = p.file_name() {
                    let _ = std::fs::copy(&p, exe_dir.join(name));
                }
            }
        }
    }
}

/// Locate clang-cl: honor an explicit CLANG_CL env override, otherwise try PATH
/// and the default LLVM install location.
fn find_clang_cl() -> Option<std::ffi::OsString> {
    if let Ok(p) = std::env::var("CLANG_CL") {
        return Some(p.into());
    }
    let default = PathBuf::from(r"C:\Program Files\LLVM\bin\clang-cl.exe");
    if default.exists() {
        return Some(default.into_os_string());
    }
    // Fall back to PATH lookup (cc will resolve a bare name against PATH).
    Some("clang-cl.exe".into())
}

