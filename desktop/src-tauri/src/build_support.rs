// Build-time architecture selection helpers, shared verbatim between `build.rs`
// (via `include!`) and the unit tests (via `mod build_support` in lib.rs). Pure
// string logic with no I/O, so `cargo test` exercises it directly.
//
// NOTE: keep this file free of inner attributes (`#![...]`, `//!` doc comments).
// `build.rs` pastes it in mid-file with `include!`, where inner attributes are a
// compile error.
//
// Two build decisions depend on the Cargo `TARGET` triple:
//   * which vendored Hamlib DLL set to copy next to the exe (32- vs 64-bit), and
//   * whether clang-cl must be told to emit 32-bit objects for the C DSP core.

/// The `hamlib/` sub-directory whose DLLs match `target`'s process architecture,
/// or `None` when no matching DLL set should be bundled.
///
/// 32-bit x86 (`i686`/`i586`/`i386`) → `"x86"`; 64-bit x86 (`x86_64`) → `"x64"`.
/// Non-Windows targets return `None` (no DLLs bundled on macOS/Linux), and so
/// does ARM64 Windows (`aarch64`): a native ARM64 process can't load the x64
/// DLLs, and no ARM64 Hamlib set is vendored, so we bundle nothing and rely on a
/// system Hamlib install (which `load_hamlib()` degrades to cleanly).
pub fn hamlib_arch_subdir(target: &str) -> Option<&'static str> {
    if !target.contains("windows") {
        return None;
    }
    if is_x86_32(target) {
        Some("x86")
    } else if target.starts_with("aarch64") {
        None
    } else {
        Some("x64")
    }
}

/// The extra `--target=<triple>` flag clang-cl needs so the C core compiles for
/// the same architecture as the Rust build. clang-cl defaults to the host arch,
/// which is wrong when cross-compiling x64→x86, so the triple is forced for
/// 32-bit x86 Windows targets. `None` means "leave clang-cl on its host
/// default" — the existing (correct) behavior for x86_64 and every non-Windows
/// target (this helper is only consulted from `build.rs`'s `windows-msvc` path,
/// and the explicit `windows` check keeps it matching that documented contract).
pub fn clang_cl_target_flag(target: &str) -> Option<String> {
    if target.contains("windows") && is_x86_32(target) {
        Some(format!("--target={target}"))
    } else {
        None
    }
}

/// True for the 32-bit x86 target triples (`i686-`, `i586-`, `i386-`).
fn is_x86_32(target: &str) -> bool {
    target.starts_with("i686") || target.starts_with("i586") || target.starts_with("i386")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn subdir_for_32bit_windows() {
        assert_eq!(hamlib_arch_subdir("i686-pc-windows-msvc"), Some("x86"));
        assert_eq!(hamlib_arch_subdir("i586-pc-windows-msvc"), Some("x86"));
    }

    #[test]
    fn subdir_for_64bit_windows() {
        assert_eq!(hamlib_arch_subdir("x86_64-pc-windows-msvc"), Some("x64"));
    }

    #[test]
    fn no_subdir_for_arm64_windows() {
        // ARM64 can't load the vendored x64 DLLs and no ARM64 set ships, so bundle
        // nothing and rely on a system Hamlib install.
        assert_eq!(hamlib_arch_subdir("aarch64-pc-windows-msvc"), None);
    }

    #[test]
    fn no_subdir_for_non_windows() {
        assert_eq!(hamlib_arch_subdir("x86_64-unknown-linux-gnu"), None);
        assert_eq!(hamlib_arch_subdir("x86_64-apple-darwin"), None);
        assert_eq!(hamlib_arch_subdir("aarch64-apple-darwin"), None);
    }

    #[test]
    fn clang_flag_only_for_32bit_windows() {
        assert_eq!(
            clang_cl_target_flag("i686-pc-windows-msvc").as_deref(),
            Some("--target=i686-pc-windows-msvc")
        );
        assert_eq!(clang_cl_target_flag("x86_64-pc-windows-msvc"), None);
        assert_eq!(clang_cl_target_flag("x86_64-unknown-linux-gnu"), None);
        // A 32-bit *non-Windows* target must not get the flag (matches the doc
        // contract; this helper only runs from the Windows clang-cl path).
        assert_eq!(clang_cl_target_flag("i686-unknown-linux-gnu"), None);
    }
}
