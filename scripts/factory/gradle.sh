#!/usr/bin/env bash
# Runs the Android Gradle build the way AGENTS.md -> Build & Deploy says, as one
# allowlisted command, so the unattended factory loop doesn't stall on a
# permission prompt for a `cmd.exe //c` wrapper or an env-var prefix.
#
#   bash scripts/factory/gradle.sh testDebugUnitTest assembleDebug
#
# Run it from a checkout or worktree root; it builds that tree's ft8af/ project.
# The isolated Gradle home keeps the factory's daemons apart from interactive
# builds (a zombie daemon in the default home can hold locks until reboot).
#
# A fresh worktree lacks the gitignored hamlib static libs
# (ft8af/app/src/main/cpp/hamlib/prebuilt/, ~55 MB, built once under WSL) and
# fails configureCMake without them; they are copied in from the main checkout
# (FT8AF_MAIN_CHECKOUT) when missing. Never rebuild them here.
set -euo pipefail
top="$(git rev-parse --show-toplevel)"
prebuilt="ft8af/app/src/main/cpp/hamlib/prebuilt"
main="${FT8AF_MAIN_CHECKOUT:-/c/Users/burns/Projects/NEXT-FT8CN}"
if [ ! -f "$top/$prebuilt/arm64-v8a/libhamlib.a" ] && [ -d "$main/$prebuilt" ]; then
  mkdir -p "$top/$prebuilt"
  cp -r "$main/$prebuilt/." "$top/$prebuilt/"
  echo "gradle.sh: copied hamlib prebuilts from $main"
fi
cd "$top/ft8af"
export JAVA_HOME="${FT8AF_JAVA_HOME:-C:\\Program Files\\Android\\Android Studio\\jbr}"
export GRADLE_USER_HOME="${FT8AF_GRADLE_USER_HOME:-C:\\Users\\burns\\.gradle-ft8af}"
exec cmd.exe //c ".\\gradlew.bat $*"
