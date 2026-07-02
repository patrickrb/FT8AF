#!/usr/bin/env bash
#
# Build and run the native FT8 golden-vector tests on a POSIX host (Linux/macOS),
# with no device/emulator. Companion to run_host_tests.ps1 (Windows); both build
# the same ft8af_glue/test_golden_encode.c against the vendored ft8_lib encode
# path and exit 0 only when every golden check passes. This is what CI runs.
#
# Usage:
#   run_host_tests.sh            # build + run the tests (exit 0 == all pass)
#   run_host_tests.sh --emit     # re-emit golden literals from the current ft8_lib
#   CC=gcc run_host_tests.sh     # override the compiler (default: clang)
#
# Note on stpcpy/M_PI: ft8_lib's message.c uses POSIX stpcpy and (elsewhere)
# M_PI. host_compat.h shims those only for MSVC; on glibc/macOS we instead define
# _GNU_SOURCE so the C library exposes them natively. The shipped Android .so
# uses bionic, which provides both, and is built by Gradle/NDK — not this script.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cpp="$(dirname "$here")"
ft8="$cpp/ft8_lib"
CC="${CC:-clang}"

# ft8_lib sources needed by the encode + pack path (no decoder/monitor/fft).
srcs=(
    "$here/test_golden_encode.c"
    "$ft8/ft8/pack.c"
    "$ft8/ft8/encode.c"
    "$ft8/ft8/crc.c"
    "$ft8/ft8/constants.c"
    "$ft8/ft8/text.c"
    "$ft8/ft8/message.c"
)

tmp="$(mktemp -d)"
# Clean up the temp build dir on exit. We deliberately do NOT exec the test
# binaries below (which would replace the shell and skip this trap); set -e
# still propagates a failing test's exit status as the script's exit status.
trap 'rm -rf "$tmp"' EXIT
out="$tmp/ft8_golden_test"

# -D_GNU_SOURCE: expose POSIX stpcpy + M_PI from glibc (harmless on macOS).
# Warnings from the vendored ft8_lib are non-fatal; the test's own exit code is
# the gate, so we deliberately do NOT pass -Werror.
"$CC" -std=c11 -O2 -D_GNU_SOURCE \
    -Wall -Wno-deprecated-non-prototype -Wno-unused-function \
    -I "$ft8" "${srcs[@]}" -lm -o "$out"

# --emit (golden re-gen) only applies to the golden-vector binary.
if [ "${1:-}" = "--emit" ]; then
    "$out" "$@"
    exit "$?"
fi

"$out"

# dev-625 regression tests: ft8_snr() FT8 calibration + the waterfall display
# magnitude mapping (fft_display.c). Separate executable (own main()).
dev625_srcs=(
    "$here/test_dev625_fixes.c"
    "$ft8/ft8/pack.c"
    "$ft8/ft8/encode.c"
    "$ft8/ft8/crc.c"
    "$ft8/ft8/constants.c"
    "$ft8/ft8/text.c"
    "$ft8/ft8/message.c"
    "$ft8/ft8/decode.c"
    "$ft8/ft8/ldpc.c"
    "$ft8/ft8/unpack.c"
    "$here/fft_display.c"
)
out625="$tmp/ft8_dev625_test"
"$CC" -std=c11 -O2 -D_GNU_SOURCE \
    -Wall -Wno-deprecated-non-prototype -Wno-unused-function \
    -I "$ft8" "${dev625_srcs[@]}" -lm -o "$out625"

# Not exec'd, so the EXIT trap cleans up $tmp. set -e propagates a failure.
"$out625"

# FIR decimator tests (C++): anti-aliasing downsample that replaced the box filter
# in usb_audio_capture.cpp. Header-only, no ft8_lib deps. Compiled as C++.
CXX="${CXX:-clang++}"
fir_out="$tmp/ft8_fir_test"
"$CXX" -std=c++14 -O2 -Wall "$here/test_fir_decimator.cpp" -lm -o "$fir_out"
"$fir_out"

# Decode benchmark / regression harness: replays the committed FT8 WAV corpus
# through the app's exact decode pipeline (same monitor config, candidate
# search, LDPC settings, dedup, and subtract-and-redecode loop as
# FT8SignalListener + ft8_decode_jni.cpp) and scores against WSJT-X jt9 truth
# sidecars. Fails if any corpus file matches fewer truth messages than its
# committed floor (testdata/ft8/floors.txt) — the floors ratchet up as decoder
# improvements land. See decode_bench.c's header for flags and output format.
bench_srcs=(
    "$here/decode_bench.c"
    "$here/gfsk.c"
    "$here/ft8_subtract.c"
    "$ft8/ft8/pack.c"
    "$ft8/ft8/encode.c"
    "$ft8/ft8/crc.c"
    "$ft8/ft8/constants.c"
    "$ft8/ft8/text.c"
    "$ft8/ft8/message.c"
    "$ft8/ft8/decode.c"
    "$ft8/ft8/ldpc.c"
    "$ft8/ft8/unpack.c"
    "$ft8/common/monitor.c"
    "$ft8/common/wave.c"
    "$ft8/fft/kiss_fft.c"
    "$ft8/fft/kiss_fftr.c"
)
bench_out="$tmp/ft8_decode_bench"
"$CC" -std=c11 -O2 -D_GNU_SOURCE \
    -Wall -Wno-deprecated-non-prototype -Wno-unused-function \
    -I "$ft8" "${bench_srcs[@]}" -lm -o "$bench_out"

"$bench_out" --self-test
"$bench_out" --assert-floor "$here/testdata/ft8/floors.txt" "$here"/testdata/ft8/*.wav

# Subtraction unit tests: tone-accurate deep-decode subtraction properties
# (removal, neighbor preservation, masked co-channel recovery, bounded damage).
subtract_srcs=(
    "$here/test_subtract.c"
    "$here/gfsk.c"
    "$here/ft8_subtract.c"
    "$ft8/ft8/pack.c"
    "$ft8/ft8/encode.c"
    "$ft8/ft8/crc.c"
    "$ft8/ft8/constants.c"
    "$ft8/ft8/text.c"
    "$ft8/ft8/message.c"
    "$ft8/ft8/decode.c"
    "$ft8/ft8/ldpc.c"
    "$ft8/ft8/unpack.c"
    "$ft8/common/monitor.c"
    "$ft8/fft/kiss_fft.c"
    "$ft8/fft/kiss_fftr.c"
)
subtract_out="$tmp/ft8_subtract_test"
"$CC" -std=c11 -O2 -D_GNU_SOURCE \
    -Wall -Wno-deprecated-non-prototype -Wno-unused-function \
    -I "$ft8" "${subtract_srcs[@]}" -lm -o "$subtract_out"
"$subtract_out"
