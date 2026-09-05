#!/usr/bin/env bash
# Launcher for FT8AF on Linux, working around a real Hamlib version conflict
# (see rig.rs's load_hamlib(): a bare dlopen("libhamlib.so.4"), resolved
# against whatever the dynamic linker finds first).
#
# QMX (and other newer rigs) need a newer Hamlib than the distro package
# usually ships -- confirmed on this machine: the system's
# libhamlib.so.4 is Hamlib 4.5.4 (no QMX support at all), while
# /usr/local/lib/libhamlib.so.4 is a separately-built Hamlib 4.7.1 that does
# have it. Both share the same soname, so whichever the linker resolves
# first wins for any process that doesn't override the search path.
#
# We deliberately do NOT upgrade or replace the system Hamlib package --
# other software on this machine (CQRLOG) depends on it, and installing a
# newer one over it would break that. Instead this script sets
# LD_LIBRARY_PATH just for FT8AF's own process, so it preferentially finds
# the newer build at /usr/local/lib without touching anything system-wide.
# If no such build exists, this is a no-op and FT8AF falls back to whatever
# the system provides (same behavior as running the binary directly).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$SCRIPT_DIR/../src-tauri/target/release/ft8af"

if [ ! -x "$BIN" ]; then
	echo "error: $BIN not found or not executable -- build it first (npm run tauri build)" >&2
	exit 1
fi

if [ -f /usr/local/lib/libhamlib.so.4 ]; then
	export LD_LIBRARY_PATH="/usr/local/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

exec "$BIN" "$@"
