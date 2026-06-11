#!/usr/bin/env bash
#
# Build and run the native FT8 golden-vector tests on a POSIX host (Linux/macOS),
# with no device/emulator. Companion to run_host_tests.ps1 (Windows); both build
# the same ft8cn_glue/test_golden_encode.c against the vendored ft8_lib encode
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

out="$(mktemp -d)/ft8_golden_test"

# -D_GNU_SOURCE: expose POSIX stpcpy + M_PI from glibc (harmless on macOS).
# Warnings from the vendored ft8_lib are non-fatal; the test's own exit code is
# the gate, so we deliberately do NOT pass -Werror.
"$CC" -std=c11 -O2 -D_GNU_SOURCE \
    -Wall -Wno-deprecated-non-prototype -Wno-unused-function \
    -I "$ft8" "${srcs[@]}" -lm -o "$out"

exec "$out" "$@"
