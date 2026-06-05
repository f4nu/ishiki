#!/usr/bin/env bash
# Bump the version across the Pebble watchapp and the Android companion, then
# rebuild both artifacts.   Usage:  ./bump.sh 0.2.0
set -euo pipefail

VER="${1:-}"
if [[ ! "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: ./bump.sh X.Y.Z   (e.g. ./bump.sh 0.2.0)" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")" && pwd)"

# Android versionCode must increase monotonically; derive it from the semver:
#   major*10000 + minor*100 + patch   (0.2.0 -> 200, 1.0.0 -> 10000)
IFS=. read -r MA MI PA <<< "$VER"
CODE=$((10#$MA * 10000 + 10#$MI * 100 + 10#$PA))

echo "Bumping to $VER (Android versionCode $CODE)"

# --- set versions -----------------------------------------------------------
sed -i "s/\"version\": \"[^\"]*\"/\"version\": \"$VER\"/" \
  "$ROOT/pebble/package.json"

GRADLE="$ROOT/companion/app/build.gradle.kts"
sed -i "s/versionName = \"[^\"]*\"/versionName = \"$VER\"/" "$GRADLE"
sed -i "s/versionCode = [0-9]*/versionCode = $CODE/"        "$GRADLE"

# --- rebuild ----------------------------------------------------------------
export PATH="$HOME/.pebble-tool-venv/bin:$PATH"            # our pebble-tool venv
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

echo "=== building pebble (.pbw) ==="
( cd "$ROOT/pebble" && pebble build )

echo "=== building companion (.apk) ==="
( cd "$ROOT/companion" && ./gradlew assembleDebug )

echo
echo "Done: $VER"
echo "  pbw: pebble/build/pebble.pbw"
echo "  apk: companion/app/build/outputs/apk/debug/app-debug.apk"
echo "  (remember to add a CHANGELOG.md entry)"
