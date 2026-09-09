#!/usr/bin/env bash
#
# Cross-compile hamlib -> static libhamlib.a for the app's ABIs.
#
# Output goes to prebuilt/<abi>/libhamlib.a. These archives are NOT checked into
# git (see .gitignore) — they are produced on demand:
#   * CI (Linux) and local macOS builds run this automatically from CMake.
#   * Windows builds must run this once under WSL (the Windows NDK's .exe
#     toolchain can't drive an autotools build); CMake then reuses the result.
#
# Usage:
#   ./build_android.sh                 # build all four ABIs
#   ./build_android.sh arm64-v8a       # build a single ABI (used by CMake)
#
# The Android NDK is taken from ANDROID_NDK_HOME / ANDROID_NDK_ROOT / ANDROID_NDK
# (CMake passes CMAKE_ANDROID_NDK). A *host* toolchain matching this machine is
# required: Linux or macOS. GNU make is required.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TARBALL="$HERE/vendor/hamlib-4.7.2.tar.gz"
EXPECT_SHA="ae1fcf2dbc80ea0786ea8f047b09399c3f7737d1930442f61a031708ed33e88f"
API=23                         # matches app minSdk (see HAMLIB_PIN.txt)

# Host toolchain tag inside the NDK.
case "$(uname -s)" in
  Linux)  HOST_TAG=linux-x86_64 ;;
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *) echo "FATAL: unsupported build host $(uname -s); build under WSL/Linux or macOS"; exit 2 ;;
esac

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK:-}}}"
[ -n "$NDK" ] || { echo "FATAL: set ANDROID_NDK_HOME to an Android NDK (r25+)"; exit 2; }
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
[ -d "$TOOLCHAIN" ] || { echo "FATAL: no $HOST_TAG toolchain under $NDK"; exit 2; }
command -v make >/dev/null || { echo "FATAL: GNU make not found"; exit 2; }

# app ABI -> clang target triple
abi_triple() {
  case "$1" in
    arm64-v8a)   echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7a-linux-androideabi ;;
    x86)         echo i686-linux-android ;;
    x86_64)      echo x86_64-linux-android ;;
    *) echo "unknown ABI $1" >&2; return 1 ;;
  esac
}

ABIS="${1:-arm64-v8a armeabi-v7a x86 x86_64}"

echo "== verifying vendored tarball checksum =="
# macOS ships `shasum`, not `sha256sum`; support both.
if command -v sha256sum >/dev/null; then
  echo "$EXPECT_SHA  $TARBALL" | sha256sum -c - || { echo "FATAL: tarball checksum mismatch"; exit 3; }
elif command -v shasum >/dev/null; then
  echo "$EXPECT_SHA  $TARBALL" | shasum -a 256 -c - || { echo "FATAL: tarball checksum mismatch"; exit 3; }
else
  echo "FATAL: neither sha256sum nor shasum found"; exit 3
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for ABI in $ABIS; do
  TRIPLE="$(abi_triple "$ABI")"
  echo "==================== $ABI ($TRIPLE) ===================="
  BDIR="$WORK/$ABI"; mkdir -p "$BDIR"
  tar xzf "$TARBALL" -C "$BDIR" --strip-components=1

  export AR="$TOOLCHAIN/bin/llvm-ar"
  export CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
  export CXX="$TOOLCHAIN/bin/${TRIPLE}${API}-clang++"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  # -Wno-implicit-function-declaration: glob()/getifaddrs() are undeclared at
  # API 23; they live on dead code paths and are satisfied at app-link by
  # ft8af_glue/hamlib_compat.c. See HAMLIB_PIN.txt.
  export CFLAGS="-O2 -fPIC -Wno-implicit-function-declaration -Wl,-z,max-page-size=16384"
  export CXXFLAGS="$CFLAGS"

  ( cd "$BDIR"
    ./configure --host="$TRIPLE" \
        --without-libusb --enable-static --disable-shared \
        --without-cxx-binding --without-readline --disable-winradio \
        >/dev/null
    # Top-level make; the trailing CLI test binaries may fail to link (they want
    # libc++_shared / extra libs) but libhamlib.a is produced before them.
    make -k -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)" >/dev/null 2>&1 || true
  )

  OUT="$HERE/prebuilt/$ABI"; mkdir -p "$OUT"
  A="$BDIR/src/.libs/libhamlib.a"
  [ -f "$A" ] || { echo "FATAL: libhamlib.a not produced for $ABI"; exit 4; }
  cp "$A" "$OUT/libhamlib.a"
  echo "  -> $OUT/libhamlib.a  ($(du -h "$OUT/libhamlib.a" | cut -f1))"

  # Refresh the checked-in public headers from a full build only.
  if [ -z "${1:-}" ] && [ "$ABI" = "x86_64" ]; then
    echo "== refreshing include/hamlib =="
    rm -rf "$HERE/include/hamlib"; mkdir -p "$HERE/include"
    cp -r "$BDIR/include/hamlib" "$HERE/include/"
    # Drop autotools build cruft; rig.h does not include config.h.
    rm -f "$HERE/include/hamlib/config.h" "$HERE/include/hamlib/config.h.in" \
          "$HERE/include/hamlib/stamp-h1"
  fi
done

echo "== DONE: $ABIS =="
