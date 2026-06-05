#!/usr/bin/env bash
# Set the per-app versions (from ./versions) and rebuild both artifacts.
#
#   ./bump.sh                    apply versions from ./versions, rebuild both
#   ./bump.sh companion 0.3.0    set companion version in ./versions, then apply
#   ./bump.sh pebble 1.1.0       set pebble version in ./versions, then apply
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSIONS="$ROOT/versions"

is_semver() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; }

# Optionally update one component's version in the versions file.
if [ "$#" -eq 2 ]; then
  comp="$1"; ver="$2"
  case "$comp" in companion|pebble) ;; *)
    echo "unknown component: $comp (use companion|pebble)" >&2; exit 1 ;;
  esac
  is_semver "$ver" || { echo "bad version: $ver (want X.Y.Z)" >&2; exit 1; }
  if grep -qE "^$comp=" "$VERSIONS"; then
    sed -i "s/^$comp=.*/$comp=$ver/" "$VERSIONS"
  else
    echo "$comp=$ver" >> "$VERSIONS"
  fi
elif [ "$#" -ne 0 ]; then
  echo "usage: ./bump.sh [companion|pebble X.Y.Z]" >&2
  exit 1
fi

get() { grep -E "^$1=" "$VERSIONS" | head -1 | cut -d= -f2- | tr -d '[:space:]'; }
CV="$(get companion)"
PV="$(get pebble)"
is_semver "$CV" || { echo "companion version invalid in versions: '$CV'" >&2; exit 1; }
is_semver "$PV" || { echo "pebble version invalid in versions: '$PV'" >&2; exit 1; }

# Android versionCode from the companion semver: major*10000 + minor*100 + patch
IFS=. read -r MA MI PA <<< "$CV"
CODE=$((10#$MA * 10000 + 10#$MI * 100 + 10#$PA))

echo "companion = $CV (versionCode $CODE)"
echo "pebble    = $PV"

# --- apply versions ---------------------------------------------------------
GRADLE="$ROOT/companion/app/build.gradle.kts"
sed -i "s/versionName = \"[^\"]*\"/versionName = \"$CV\"/" "$GRADLE"
sed -i "s/versionCode = [0-9]*/versionCode = $CODE/"        "$GRADLE"
sed -i "s/\"version\": \"[^\"]*\"/\"version\": \"$PV\"/"    "$ROOT/pebble/package.json"

# --- rebuild ----------------------------------------------------------------
export PATH="$HOME/.pebble-tool-venv/bin:$PATH"            # our pebble-tool venv
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

echo "=== building pebble (.pbw) ==="
( cd "$ROOT/pebble" && pebble build )

echo "=== building companion (.apk) ==="
( cd "$ROOT/companion" && ./gradlew assembleDebug )

echo
echo "Done.  companion $CV   pebble $PV"
echo "  pbw: pebble/build/pebble.pbw"
echo "  apk: companion/app/build/outputs/apk/debug/app-debug.apk"
echo "  (update CHANGELOG.md if needed)"
