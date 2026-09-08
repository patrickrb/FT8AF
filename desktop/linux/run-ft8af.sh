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
# newer one over it would break that. Instead this script LD_PRELOADs that
# one library into FT8AF's own process: rig.rs's dlopen("libhamlib.so.4")
# then resolves to the already-loaded object, since the soname matches.
#
# LD_PRELOAD rather than prepending /usr/local/lib to LD_LIBRARY_PATH: the
# latter redirects *every* library FT8AF resolves, so a machine that also has
# a locally built libssl/libcurl/libstdc++ under /usr/local/lib would load
# those instead of the distro copies the binary was linked against -- version
# symbol errors or a crash at startup, from a script whose only job is to
# pick a Hamlib. If no newer build exists, this is a no-op and FT8AF falls
# back to whatever the system provides (same as running the binary directly).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV_BIN="$SCRIPT_DIR/../src-tauri/target/release/ft8af"

# Prefer the dev tree's build when this is run from a checkout, but fall back
# to an installed ft8af on PATH -- the .deb/AppImage CI produces puts it at
# /usr/bin/ft8af, and this script is meant to be copied out of the repo.
if [ -x "$DEV_BIN" ]; then
	BIN="$DEV_BIN"
elif BIN="$(command -v ft8af)"; then
	:
else
	echo "error: no ft8af binary found -- looked at $DEV_BIN and on PATH." >&2
	echo "       Build it with 'npm run tauri build' or install the package." >&2
	exit 1
fi

HAMLIB=/usr/local/lib/libhamlib.so.4
if [ -f "$HAMLIB" ]; then
	export LD_PRELOAD="$HAMLIB${LD_PRELOAD:+:$LD_PRELOAD}"
fi

exec "$BIN" "$@"
